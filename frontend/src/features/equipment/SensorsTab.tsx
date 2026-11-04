import { useEffect, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type { SensorDetail, SensorLatestReading, SensorMonitoringStatus, SensorSummary } from '../../lib/api';
import { SensorReadingsDialog } from './SensorReadingsDialog';
import { SensorFormDialog } from './SensorFormDialog';
import { ParameterFormDialog } from './ParameterFormDialog';
import './equipment.css';

/**
 * 设备详情里的"测点"页签（S1-P01/P02/P03/P04/P06/P09）。
 *
 * <p>列表与读数来自一期主数据和最新值快照，只读；新增与修改（P03/P04）写的是
 * {@code dim_sensor} 的约定主数据与 {@code p2_sensor_profile} 的扩展资料。</p>
 *
 * <p>监测状态分四态显示，不合并成一个"离线"：停用是配置决定，未知是从没采到，
 * 过期是采过但停了。三者该找的人完全不同。</p>
 */

const STATUS_LABEL: Record<SensorMonitoringStatus, string> = {
  ONLINE: '在线', STALE: '数据过期', UNKNOWN: '暂无数据', DISABLED: '已停用',
};

const STATUS_CLASS: Record<SensorMonitoringStatus, string> = {
  ONLINE: 'eq-badge', STALE: 'eq-badge is-warn', UNKNOWN: 'eq-badge is-muted', DISABLED: 'eq-badge is-muted',
};

/** 一期用代码表示来源，页面上给个可读说法；认不出来的原样显示，不猜。 */
function sourceLabel(source: string | null): string {
  if (!source) return '来源未填';
  if (source === 'DEV_DEMO_METADATA') return '模拟数据';
  if (source === 'PLC' || source === 'OPC_UA' || source === 'MODBUS') return '现场采集';
  return source;
}

function formatTime(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return value;
  return date.toLocaleString('zh-CN', {hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'});
}

/** 读数的显示：数值按参数精度补齐小数位，文本型直接显示文本。 */
function readingText(reading: SensorLatestReading | undefined, decimalPlaces: number): string {
  if (!reading) return '—';
  if (reading.valueText && !reading.value) return reading.valueText;
  if (!reading.value) return '待采集';
  const numeric = Number(reading.value);
  if (!Number.isFinite(numeric)) return reading.value;
  return numeric.toFixed(Math.min(Math.max(decimalPlaces, 0), 6));
}

export function SensorsTab({equipmentId, refreshToken}: {equipmentId: string; refreshToken: number}) {
  const [rows, setRows] = useState<SensorSummary[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [expanded, setExpanded] = useState<string | null>(null);
  const [detail, setDetail] = useState<SensorDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [readings, setReadings] = useState<Record<string, SensorLatestReading>>({});
  const [reading, setReading] = useState<{sensor: SensorSummary; parameterId: string; parameterName: string} | null>(null);
  const [form, setForm] = useState<{sensor: SensorDetail | null} | null>(null);
  const [parameterForm, setParameterForm] = useState<SensorDetail | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    api.sensors({equipmentId, pageSize: 100}, controller.signal)
      .then(result => { setRows(result.data.items); setError(null); })
      .catch((failure: unknown) => {
        if (failure instanceof Error && failure.name === 'AbortError') return;
        setError(failure instanceof ApiError ? failure.message : '测点列表读取失败。');
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [equipmentId, refreshToken, reloadKey]);

  // 切换设备时收起已展开的测点，否则会显示上一台设备的参数。
  useEffect(() => { setExpanded(null); setDetail(null); setReadings({}); }, [equipmentId]);

  async function loadSensorDetail(sensorId: string) {
    setDetailLoading(true);
    setDetailError(null);
    try {
      const result = await api.sensorDetail(sensorId);
      setDetail(result.data);
      // 参数逐个取最新值。参数数量受测点配置约束（通常个位数），并发请求比串行等待更合适。
      const pairs = await Promise.all(result.data.parameters.map(async parameter => {
        try {
          const latest = await api.sensorLatest(parameter.parameterId);
          return {id: parameter.parameterId, value: latest.data};
        } catch {
          // 单个参数取不到不影响整页，缺值状态由"待采集"兜住。
          return {id: parameter.parameterId, value: null};
        }
      }));
      const merged: Record<string, SensorLatestReading> = {};
      for (const pair of pairs) {
        if (pair.value) merged[pair.id] = pair.value;
      }
      setReadings(merged);
    } catch (failure: unknown) {
      setDetailError(failure instanceof ApiError ? failure.message : '测点详情读取失败。');
    } finally {
      setDetailLoading(false);
    }
  }

  async function toggle(sensor: SensorSummary) {
    if (expanded === sensor.sensorId) { setExpanded(null); setDetail(null); return; }
    setExpanded(sensor.sensorId);
    setDetail(null);
    await loadSensorDetail(sensor.sensorId);
  }

  const busy = loading && rows === null;

  return <>
    <div className="eq-section-bar">
      <h3 className="eq-section-title">测点</h3>
      <span className="eq-supplier-count">{rows ? `共 ${rows.length} 个` : '读取中'}</span>
      <button type="button" className="eq-primary-button" onClick={() => setForm({sensor: null})}>
        新增测点
      </button>
    </div>

    {error && <div className="eq-notice"><span>!</span><div>{error}</div></div>}
    {busy && <div className="eq-loading">正在读取测点…</div>}

    {!busy && rows !== null && (rows.length === 0
      ? <p className="eq-param-empty">这台设备还没有配置测点。可以用右上角「新增测点」登记身份，参数与采集映射随后再补。</p>
      : <ul className="eq-supplier-list">
        {rows.map(sensor => <li key={sensor.sensorId} className="eq-sensor-item">
          <button type="button" className="eq-sensor-head" aria-expanded={expanded === sensor.sensorId}
                  onClick={() => void toggle(sensor)}>
            <span className="eq-sensor-id">
              <strong>{sensor.sensorCode}</strong>
              <small>{sensor.name} · {sensor.sensorType ?? '类型未填'}</small>
            </span>
            <span className="eq-sensor-meta">
              {sensor.location ?? '位置未填'} · {sourceLabel(sensor.dataSource)}
            </span>
            <span className="eq-sensor-meta">{sensor.parameterCount} 个参数</span>
            <span className={STATUS_CLASS[sensor.monitoringStatus]}>{STATUS_LABEL[sensor.monitoringStatus]}</span>
            <span className="eq-sensor-fold" aria-hidden="true">{expanded === sensor.sensorId ? '收起' : '展开'}</span>
          </button>

          {expanded === sensor.sensorId && <div className="eq-sensor-body">
            {detailLoading && <p className="eq-form-hint">正在读取参数…</p>}
            {detailError && <p className="eq-form-error">{detailError}</p>}
            {detail && detail.sensorId === sensor.sensorId && <>
              <div className="eq-sensor-detail-bar">
                <p className="eq-form-hint">
                  {detail.purpose ? `用途：${detail.purpose}` : '用途未确认'}
                  {' · '}
                  {detail.configurationState === 'READY' ? '参数与来源已配齐' : '待配置：缺参数或缺采集来源'}
                  {' · '}
                  {detail.hasHistory ? '已有原始采集记录' : '暂无原始采集记录'}
                </p>
                <button type="button" className="eq-row-link" onClick={() => setParameterForm(detail)}>
                  新增参数
                </button>
                <button type="button" className="eq-row-link" onClick={() => setForm({sensor: detail})}>
                  修改
                </button>
              </div>
              {detail.parameters.length === 0
                ? <p className="eq-param-empty">这个测点还没有参数定义。</p>
                : <ul className="eq-sensor-params">
                  {detail.parameters.map(parameter => {
                    const latest = readings[parameter.parameterId];
                    return <li key={parameter.parameterId} className="eq-sensor-param">
                      <span className="eq-sensor-param-name">
                        <strong>{parameter.parameterCode}</strong>
                        <small>{parameter.name} · {parameter.dataType}{parameter.unitCode ? ` · ${parameter.unitCode}` : ' · 单位未配'}</small>
                      </span>
                      <span className="eq-sensor-value">
                        <b>{readingText(latest, parameter.decimalPlaces)}</b>
                        <small>
                          {latest ? `${STATUS_LABEL[latest.monitoringStatus]} · ${formatTime(latest.collectTime)}` : '读数未取到'}
                        </small>
                      </span>
                      <span className="eq-sensor-param-actions">
                        <button type="button" className="eq-row-link"
                                onClick={() => setReading({sensor, parameterId: parameter.parameterId, parameterName: `${parameter.parameterCode} ${parameter.name}`})}>
                          历史 / 规则
                        </button>
                      </span>
                    </li>;
                  })}
                </ul>}
            </>}
          </div>}
        </li>)}
      </ul>)}

    <p className="eq-form-hint">
      测点身份沿用一期主数据，读数取最新值快照。展开测点可新增参数并同时写入首版规则；
      参数规则修改与测点删除尚未开放，停用是当前唯一安全的退出方式。
    </p>

    {reading && <SensorReadingsDialog
      parameterId={reading.parameterId}
      title={`${reading.sensor.sensorCode} · ${reading.parameterName}`}
      unitCode={detail?.parameters.find(item => item.parameterId === reading.parameterId)?.unitCode ?? null}
      onClose={() => setReading(null)} />}

    {form && <SensorFormDialog
      sensor={form.sensor}
      equipmentId={equipmentId}
      onClose={() => setForm(null)}
      onSaved={() => { setForm(null); setExpanded(null); setDetail(null); setReloadKey(key => key + 1); }} />}

    {parameterForm && <ParameterFormDialog
      sensorId={parameterForm.sensorId}
      sensorCode={parameterForm.sensorCode}
      onClose={() => setParameterForm(null)}
      onSaved={() => {
        const sensorId = parameterForm.sensorId;
        setParameterForm(null);
        setReloadKey(key => key + 1);
        void loadSensorDetail(sensorId);
      }} />}
  </>;
}
