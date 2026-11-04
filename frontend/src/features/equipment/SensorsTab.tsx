import { useEffect, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type {
  SensorDetail, SensorLatestReading, SensorMonitoringStatus, SensorParameterEditable, SensorSummary,
} from '../../lib/api';
import { formatReadingValue } from '../../lib/reading';
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

/** 与新增/编辑弹窗共用同一个记住的操作人，避免两处显示不一致。 */
function rememberedOperator(): string {
  try { return window.localStorage.getItem('eq-operator-name')?.trim() || '设备管理员'; }
  catch { return '设备管理员'; }
}

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`;
}

/**
 * 读数的显示：数值按参数精度补齐小数位，文本型直接显示文本。
 *
 * <p>精度统一由 {@link formatReadingValue} 处理，历史读数弹层用的是同一套规则，
 * 否则同一个值在参数行和历史列表会出现两种写法。</p>
 */
function readingText(reading: SensorLatestReading | undefined, decimalPlaces: number): string {
  if (!reading) return '—';
  if (reading.valueText && !reading.value) return reading.valueText;
  if (!reading.value) return '待采集';
  return formatReadingValue(reading.value, decimalPlaces) ?? reading.value;
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
  const [reading, setReading] = useState<
    {sensor: SensorSummary; parameterId: string; parameterName: string; unitCode: string | null; decimalPlaces: number} | null
  >(null);
  const [form, setForm] = useState<{sensor: SensorDetail | null} | null>(null);
  const [parameterForm, setParameterForm] = useState<
    {sensor: SensorDetail; parameter: SensorParameterEditable | null} | null>(null);
  const [parameterNotice, setParameterNotice] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<SensorDetail | null>(null);
  const [deleteReason, setDeleteReason] = useState('');
  const [deleteRequestId, setDeleteRequestId] = useState(newRequestId);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deleteFields, setDeleteFields] = useState<Record<string, string>>({});
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

  const openDelete = (sensor: SensorDetail) => {
    setDeleteTarget(sensor);
    setDeleteReason('');
    setDeleteRequestId(newRequestId());
    setDeleteError(null);
    setDeleteFields({});
  };

  const changeDeleteReason = (value: string) => {
    setDeleteReason(value);
    // 内容变了就换请求编号，避免被幂等表判成"同号不同内容"。
    setDeleteRequestId(newRequestId());
    setDeleteFields(previous => ({...previous, reason: ''}));
  };

  const submitDelete = () => {
    if (!deleteTarget) return;
    const trimmed = deleteReason.trim();
    if (!trimmed) { setDeleteFields({reason: '删除原因不得为空。'}); return; }
    setDeleteBusy(true); setDeleteError(null); setDeleteFields({});
    api.deleteSensor(deleteTarget.sensorId, {
      requestId: deleteRequestId, operatorName: rememberedOperator(),
      expectedVersion: deleteTarget.version, reason: trimmed,
    })
      .then(() => {
        setDeleteBusy(false);
        setDeleteTarget(null);
        // 行已经不存在了，展开状态与详情缓存都要一起清掉。
        setExpanded(null);
        setDetail(null);
        setReloadKey(key => key + 1);
      })
      .catch((cause: unknown) => {
        setDeleteBusy(false);
        if (!(cause instanceof ApiError)) { setDeleteError('删除失败，请稍后重试。'); return; }
        if (cause.code === 'VERSION_CONFLICT') {
          setDeleteError('这个测点在你操作期间被他人修改过，请收起后重新展开再试。');
        } else if (cause.code === 'RELATED_DATA_EXISTS') {
          setDeleteError(cause.message);
        } else if (Object.keys(cause.fields).length > 0) {
          setDeleteFields(cause.fields);
        } else {
          setDeleteError(cause.message);
        }
      });
  };

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
                <button type="button" className="eq-row-link"
                        onClick={() => setParameterForm({sensor: detail, parameter: null})}>
                  新增参数
                </button>
                <button type="button" className="eq-row-link" onClick={() => setForm({sensor: detail})}>
                  修改
                </button>
                <button type="button" className="eq-row-link" onClick={() => openDelete(detail)}
                        disabled={!detail.deletion.allowed}
                        title={detail.deletion.allowed
                          ? '删除误建的测点，会一并清理参数与组网节点'
                          : detail.deletion.blockers.map(item => item.message).join('；')}>
                  删除
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
                                onClick={() => setParameterForm({sensor: detail, parameter})}>
                          修改
                        </button>
                        <button type="button" className="eq-row-link"
                                onClick={() => setReading({
                                  sensor, parameterId: parameter.parameterId,
                                  parameterName: `${parameter.parameterCode} ${parameter.name}`,
                                  unitCode: parameter.unitCode, decimalPlaces: parameter.decimalPlaces,
                                })}>
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

    {parameterNotice && <p className="eq-form-hint">上次保存：{parameterNotice}</p>}

    <p className="eq-form-hint">
      测点身份沿用一期主数据，读数取最新值快照。展开测点可新增参数并写入首版规则；
      参数行上的「修改」用于调整名称、单位、精度、采样周期与规则——判断依据变了会追加一版新规则，
      只改展示字段则只推进配置版本。删除只用于纠正误建，有采集记录或统计数据的测点会被拒绝，
      那时请改为停用。
    </p>

    {reading && <SensorReadingsDialog
      parameterId={reading.parameterId}
      title={`${reading.sensor.sensorCode} · ${reading.parameterName}`}
      unitCode={reading.unitCode}
      decimalPlaces={reading.decimalPlaces}
      onClose={() => setReading(null)} />}

    {form && <SensorFormDialog
      sensor={form.sensor}
      equipmentId={equipmentId}
      onClose={() => setForm(null)}
      onSaved={() => { setForm(null); setExpanded(null); setDetail(null); setReloadKey(key => key + 1); }} />}

    {parameterForm && <ParameterFormDialog
      sensorId={parameterForm.sensor.sensorId}
      sensorCode={parameterForm.sensor.sensorCode}
      parameter={parameterForm.parameter}
      onClose={() => setParameterForm(null)}
      onSaved={result => {
        const sensorId = parameterForm.sensor.sensorId;
        setParameterForm(null);
        // 改没改规则、有没有结束事件，由服务端的生效说明来讲，前端不复述自己的猜测。
        setParameterNotice(result?.effectNote ?? null);
        setReloadKey(key => key + 1);
        void loadSensorDetail(sensorId);
      }} />}

    {deleteTarget && <div className="eq-confirm" role="presentation"
      onClick={event => { if (event.target === event.currentTarget && !deleteBusy) setDeleteTarget(null); }}>
      <div className="eq-confirm-card" role="dialog" aria-modal="true" aria-labelledby="eq-sensor-delete-title">
        <h3 id="eq-sensor-delete-title">确认删除这个测点？</h3>
        <p className="eq-confirm-text">
          <strong>{deleteTarget.sensorCode} · {deleteTarget.name}</strong><br />
          测点本身、{deleteTarget.parameterCount} 个参数及其组网节点会一并删除，变更记录保留。
          已有采集记录的编码不会再被复用，此操作不可撤销。
        </p>
        <label className="eq-form-field">
          <span>删除原因<em>*</em></span>
          <input value={deleteReason} disabled={deleteBusy} maxLength={1000}
            aria-invalid={Boolean(deleteFields.reason)}
            placeholder="例如：误建，与 TEMP-FURNACE-01 重复"
            onChange={event => changeDeleteReason(event.target.value)} />
          {deleteFields.reason
            ? <small className="eq-form-error">{deleteFields.reason}</small>
            : <small className="eq-form-hint">删除原因会写入变更记录，供日后追溯。</small>}
        </label>
        {deleteError && <div className="eq-alert is-error">{deleteError}</div>}
        <div className="eq-confirm-actions">
          <button type="button" className="button" onClick={() => setDeleteTarget(null)} disabled={deleteBusy}>取消</button>
          <button type="button" className="button is-danger" onClick={() => void submitDelete()} disabled={deleteBusy}>
            {deleteBusy ? '删除中…' : '确认删除'}
          </button>
        </div>
      </div>
    </div>}
  </>;
}
