import { useCallback, useEffect, useState } from 'react';
import { Icon } from '../../components/Icon';
import { api } from '../../lib/api';
import type { SensorParameterSummary, SensorSummary } from '../../lib/api';
import { MaintenanceApiError, maintenanceApi, newRequestId, toOffsetIso } from '../../lib/maintenanceApi';
import type {
  AlertEventDetail, AlertEventItem, AlertEventSort, HandlingRecordItem, RuleSnapshot,
} from '../../lib/maintenanceApi';
import {
  endReasonLabels, eventSourceLabels, eventStateLabels, formatReading, formatTime, formatUnit,
  handlingStateLabels, label, localInputValue, orderStatusLabels, parameterText, priorityLabels,
  triggerModeLabels,
} from './labels';
import { EquipmentSelect, Pager, Tag, Tip, useOperatorName } from './shared';
import type { RepairHandoff } from './shared';

type Feedback = {kind: 'ok' | 'error'; text: string} | null;

/** 优先级单独配色：紧急必须是红的，一般的不能再抢眼。 */
function priorityTagClass(priority: string): string {
  if (priority === 'URGENT') return 'ops-tag-red';
  if (priority === 'HIGH') return 'ops-tag-amber';
  if (priority === 'NORMAL') return 'ops-tag-blue';
  return 'ops-tag-gray';
}

/** 把结构化规则快照翻成业务能读的句子，避免直接抛 [0, 80] 这种数学区间。 */
function ruleText(snapshot: RuleSnapshot): string {
  const unit = formatUnit(snapshot.unitCode);
  const parts: string[] = [];
  const {normalLower, normalUpper, alarmLower, alarmUpper} = snapshot;
  if (normalLower !== null && normalUpper !== null) parts.push(`正常范围 ${normalLower}~${normalUpper}${unit}`);
  else if (normalUpper !== null) parts.push(`上限 ${normalUpper}${unit} 内属正常`);
  else if (normalLower !== null) parts.push(`下限 ${normalLower}${unit} 以上属正常`);
  if (alarmUpper !== null) parts.push(`超过 ${alarmUpper}${unit} 报警`);
  if (alarmLower !== null) parts.push(`低于 ${alarmLower}${unit} 报警`);
  if (snapshot.triggerMode) parts.push(`${label(triggerModeLabels, snapshot.triggerMode)}生效`);
  if (snapshot.durationSeconds) parts.push(`需持续 ${snapshot.durationSeconds} 秒`);
  return parts.length ? `${parts.join('，')}。` : '规则快照无阈值字段。';
}

/** 阈值单列一行，和"触发数值"左右对照，看到事件时不用自己比对区间。 */
function thresholdText(snapshot: RuleSnapshot | null, unitCode: string | null): string {
  if (!snapshot) return '规则快照缺失';
  const unit = formatUnit(snapshot.unitCode ?? unitCode);
  const bounds: string[] = [];
  if (snapshot.alarmUpper !== null) bounds.push(`上限 ${snapshot.alarmUpper}${unit}`);
  if (snapshot.alarmLower !== null) bounds.push(`下限 ${snapshot.alarmLower}${unit}`);
  return bounds.length ? `${bounds.join('，')}（超出即报警）` : '该规则未设置报警阈值';
}

/** PT1M → 1 分钟。ISO-8601 时长直接显示给业务人员没有意义。 */
function durationText(value: unknown): string {
  if (typeof value !== 'string') return '—';
  const match = /^PT(\d+)([SMH])$/.exec(value);
  if (!match) return value;
  const unit = match[2] === 'S' ? '秒' : match[2] === 'M' ? '分钟' : '小时';
  return `${match[1]} ${unit}`;
}

const referenceKeyLabels: Record<string, string> = {
  type: '采集方式', window: '统计窗口', sampleCount: '参与计算采样点数',
  operator: '补录人', reason: '补录原因',
};
const referenceTypeLabels: Record<string, string> = {
  REALTIME: '实时采集', HISTORY: '历史回放', MANUAL: '人工补录',
};

/** 来源码单独取一次：列表用 sourceType，详情只有 sourceReference，两处要给出同一个结论。 */
function sourceTypeOf(reference: unknown): string | null {
  if (!reference || typeof reference !== 'object' || Array.isArray(reference)) return null;
  const type = (reference as Record<string, unknown>).type;
  return typeof type === 'string' ? type : null;
}

/** 来源引用由后端原样保存，前端翻成人话；未知字段原样保留，不丢信息。 */
function referenceText(value: unknown): string {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return '—';
  const parts: string[] = [];
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    const name = referenceKeyLabels[key] ?? key;
    const text = key === 'type' ? label(referenceTypeLabels, String(raw), String(raw))
      : key === 'window' ? durationText(raw) : String(raw);
    parts.push(`${name} ${text}`);
  }
  return parts.length ? parts.join('，') : '—';
}

export function AlertsTab({onRepairRequest}: {onRepairRequest?: (handoff: RepairHandoff) => void}) {
  const [eventState, setEventState] = useState('');
  const [handlingState, setHandlingState] = useState('');
  const [equipmentId, setEquipmentId] = useState('');
  const [sort, setSort] = useState<AlertEventSort>('TIME');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [items, setItems] = useState<AlertEventItem[] | null>(null);
  const [total, setTotal] = useState(0);
  const [listError, setListError] = useState<string | null>(null);
  const [listLoading, setListLoading] = useState(true);

  const [detail, setDetail] = useState<AlertEventDetail | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const [operator, setOperator] = useOperatorName();
  const [action, setAction] = useState('ACKNOWLEDGED');
  const [reason, setReason] = useState('');
  const [feedback, setFeedback] = useState<Feedback>(null);
  const [submitting, setSubmitting] = useState(false);

  const [history, setHistory] = useState<HandlingRecordItem[] | null>(null);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [historyFor, setHistoryFor] = useState<string | null>(null);

  /* ---- 人工补录（S1-A05）：允许人补，但来源固定标 MANUAL，列表标签区分 ---- */
  const [manualOpen, setManualOpen] = useState(false);
  const [manualEquipmentId, setManualEquipmentId] = useState('');
  const [manualSensorId, setManualSensorId] = useState('');
  const [manualParameterId, setManualParameterId] = useState('');
  const [manualValue, setManualValue] = useState('');
  const [manualStartedAt, setManualStartedAt] = useState(() => localInputValue(-10));
  const [manualReason, setManualReason] = useState('');
  const [manualSensors, setManualSensors] = useState<SensorSummary[]>([]);
  const [manualSensorsLoading, setManualSensorsLoading] = useState(false);
  const [manualParameters, setManualParameters] = useState<SensorParameterSummary[]>([]);
  const [manualSubmitting, setManualSubmitting] = useState(false);
  const [manualFeedback, setManualFeedback] = useState<Feedback>(null);

  useEffect(() => {
    setManualSensorId(''); setManualParameterId(''); setManualParameters([]);
    if (!manualOpen || !manualEquipmentId) { setManualSensors([]); setManualSensorsLoading(false); return; }
    let alive = true;
    setManualSensorsLoading(true);
    void api.sensors({equipmentId: manualEquipmentId, enabled: true, pageSize: 100})
      .then(result => { if (alive) setManualSensors(result.data.items); })
      .catch(() => { if (alive) setManualSensors([]); })
      .finally(() => { if (alive) setManualSensorsLoading(false); });
    return () => { alive = false; };
  }, [manualOpen, manualEquipmentId]);

  useEffect(() => {
    setManualParameterId('');
    if (!manualSensorId) { setManualParameters([]); return; }
    let alive = true;
    void api.sensorParameters(manualSensorId, 1, 50)
      .then(result => {
        if (!alive) return;
        const rows = result.data.items;
        setManualParameters(rows);
        // 一个测点通常只有一个参数，省掉一次点击
        if (rows.length === 1) setManualParameterId(rows[0].parameterId);
      })
      .catch(() => { if (alive) setManualParameters([]); });
    return () => { alive = false; };
  }, [manualSensorId]);

  const manualParameter = manualParameters.find(item => item.parameterId === manualParameterId) ?? null;
  /** 单位跟着参数走，取参数定义里的单位；没有参数就没有单位可言。 */
  const manualUnit = manualParameter ? formatUnit(manualParameter.unitCode) : '';
  /** 设备选好了、也查完了、确实一个测点都没有——这时补录走不通，得另给出路。 */
  const manualNoSensor = Boolean(manualEquipmentId) && !manualSensorsLoading && manualSensors.length === 0;

  const loadList = useCallback(async (targetPage = page, targetSize = pageSize) => {
    setListLoading(true);
    try {
      const data = await maintenanceApi.alertEvents({
        eventState: eventState || undefined,
        handlingState: handlingState || undefined,
        equipmentId: equipmentId || undefined,
        sort,
      }, targetPage, targetSize);
      // 页码越界（例如筛掉后当前页空了）时回到最后一页，避免空白列表
      if (data.items.length === 0 && targetPage > 1 && data.total > 0) {
        const lastPage = Math.max(1, Math.ceil(data.total / targetSize));
        if (lastPage !== targetPage) { setPage(lastPage); return; }
      }
      setItems(data.items);
      setTotal(data.total);
      setListError(null);
    } catch (error) {
      setListError(error instanceof MaintenanceApiError ? error.message : '读取失败，请重试。');
    } finally {
      setListLoading(false);
    }
  }, [eventState, handlingState, equipmentId, sort, page, pageSize]);

  useEffect(() => { void loadList(); }, [loadList]);

  const openDetail = useCallback(async (eventId: string) => {
    setDetailLoading(true);
    setDetailError(null);
    try {
      setDetail(await maintenanceApi.alertEvent(eventId));
      setHistory(null);
      setHistoryFor(null);
    } catch (error) {
      setDetail(null);
      setDetailError(error instanceof MaintenanceApiError ? error.message : '读取失败，请重试。');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const loadHistory = useCallback(async (eventId: string) => {
    try {
      const data = await maintenanceApi.handlingRecords(eventId, 1, 50);
      setHistory(data.items);
      setHistoryTotal(data.total);
      setHistoryFor(eventId);
    } catch (error) {
      setFeedback({kind: 'error', text: error instanceof MaintenanceApiError ? error.message : '读取失败，请重试。'});
    }
  }, []);

  const submitHandling = useCallback(async () => {
    if (!detail) return;
    if (!operator.trim()) { setFeedback({kind: 'error', text: '请填写经办人（项目暂无登录用户，需手工署名）。'}); return; }
    if (!reason.trim()) { setFeedback({kind: 'error', text: '请填写处理原因（接口要求必填）。'}); return; }
    setSubmitting(true);
    try {
      const result = await maintenanceApi.addHandlingRecord(detail.eventId, {
        requestId: newRequestId(),
        operatorName: operator.trim(),
        expectedVersion: detail.version,
        action,
        reason: reason.trim(),
      });
      setFeedback({kind: 'ok', text: `处理记录已保存（记录 ${result.handlingRecordId}），处理状态更新为「${label(handlingStateLabels, result.handlingState)}」。`});
      setReason('');
      await Promise.all([loadList(), openDetail(detail.eventId)]);
      void loadHistory(detail.eventId);
    } catch (error) {
      if (error instanceof MaintenanceApiError && error.code === 'VERSION_CONFLICT') {
        setFeedback({kind: 'error', text: `${error.message} 这条事件刚被别人处理过，请重新选择后再试。`});
        await loadList();
      } else {
        setFeedback({kind: 'error', text: error instanceof MaintenanceApiError ? error.message : '提交失败，请重试。'});
      }
    } finally {
      setSubmitting(false);
    }
  }, [detail, reason, operator, action, loadList, openDetail, loadHistory]);

  const submitManual = useCallback(async () => {
    if (!manualSensorId || !manualParameterId) { setManualFeedback({kind: 'error', text: '请先选择测点与参数。'}); return; }
    if (!manualValue.trim()) { setManualFeedback({kind: 'error', text: '请填写现场看到的超限数值。'}); return; }
    if (!operator.trim()) { setManualFeedback({kind: 'error', text: '请填写补录人（项目暂无登录用户，需手工署名）。'}); return; }
    if (!manualReason.trim()) { setManualFeedback({kind: 'error', text: '请填写补录原因：说明这条异常为什么不是采集产生的。'}); return; }
    const startedAt = toOffsetIso(manualStartedAt);
    if (!startedAt) { setManualFeedback({kind: 'error', text: '异常起始时间格式不正确。'}); return; }

    setManualSubmitting(true);
    try {
      const created = await maintenanceApi.createAlertEvent({
        requestId: newRequestId(),
        sensorId: manualSensorId,
        parameterId: manualParameterId,
        triggerValue: manualValue.trim(),
        startedAt,
        reason: manualReason.trim(),
        operatorName: operator.trim(),
      });
      setManualFeedback({kind: 'ok', text: `已补录事件 #${created.eventId}，来源记为「人工补录」，可直接在下方处理或报修。`});
      setManualValue('');
      setManualReason('');
      await loadList(1);
      await openDetail(created.eventId);
    } catch (error) {
      // 同一参数只能有一条未结束事件：后端 409 的话把原话展示出来，别让用户反复试
      setManualFeedback({kind: 'error', text: error instanceof MaintenanceApiError ? error.message : '补录失败，请重试。'});
    } finally {
      setManualSubmitting(false);
    }
  }, [manualSensorId, manualParameterId, manualValue, manualStartedAt, manualReason, operator, loadList, openDetail]);

  const resetFilters = () => { setEventState(''); setHandlingState(''); setEquipmentId(''); setSort('TIME'); setPage(1); };

  return <>
    {manualOpen && <section className="panel" aria-label="人工补录异常事件">
      <div className="panel-heading"><div><h2>人工补录异常事件<span className="panel-count">来源记为人工补录</span></h2><p>采集缺失、设备离线或现场口头报警时手工补录</p></div></div>
      <div className="ops-form">
        <div className="ops-form-row">
          <EquipmentSelect label="设备" value={manualEquipmentId} onChange={setManualEquipmentId} />
          <label className="select-field"><span>测点</span>
            <select value={manualSensorId} onChange={event => setManualSensorId(event.target.value)} disabled={!manualEquipmentId}>
              <option value="">{manualEquipmentId ? (manualSensorsLoading ? '读取中…' : (manualSensors.length ? '请选择测点' : '该设备未配置测点')) : '先选设备'}</option>
              {manualSensors.map(sensor => <option value={sensor.sensorId} key={sensor.sensorId}>{sensor.sensorCode} {sensor.name}</option>)}
            </select></label>
          <label className="select-field"><span>参数</span>
            <select value={manualParameterId} onChange={event => setManualParameterId(event.target.value)} disabled={!manualSensorId}>
              <option value="">{manualSensorId ? '请选择参数' : '先选测点'}</option>
              {manualParameters.map(parameter => <option value={parameter.parameterId} key={parameter.parameterId}>{parameter.name}</option>)}
            </select></label>
        </div>
        {manualNoSensor && <p className="ops-feedback-hint" role="status">
          该设备还没有配置测点，补录不了参数异常——事件必须挂在具体参数上。
          {onRepairRequest
            ? <>如果是设备整体故障、不是某个参数超限，<button type="button" className="link-button"
                onClick={() => onRepairRequest({sourceType: 'MANUAL', sourceId: '', equipmentId: manualEquipmentId, summary: '人工上报 · 设备无测点'})}>直接建维修工单</button>，建单不需要测点。</>
            : <>设备整体故障请到「维修工单」页签人工上报，建单不需要测点。</>}
        </p>}
        <div className="ops-form-row">
          <label className="select-field" title="单位取自参数定义，不需要手动填写">
            <span>超限数值{manualUnit ? `（${manualUnit}）` : ''}</span>
            <input value={manualValue} onChange={event => setManualValue(event.target.value)} inputMode="decimal" size={12}
              placeholder={manualUnit ? `例如 97.2${manualUnit}` : '例如 97.2'} /></label>
          <label className="select-field"><span>异常起始时间</span>
            <input type="datetime-local" value={manualStartedAt} onChange={event => setManualStartedAt(event.target.value)} max={localInputValue()} /></label>
          <label className="select-field"><span>补录人</span>
            <input value={operator} onChange={event => setOperator(event.target.value)} size={8} placeholder="请填写姓名" /></label>
        </div>
        <label className="ops-text-field"><span>补录原因（必填）</span>
          <textarea rows={2} value={manualReason} onChange={event => setManualReason(event.target.value)}
            placeholder="例如：采集器离线，当班巡检用便携表测得 97.2℃，已口头通知班组" /></label>
        <div className="ops-form-actions">
          <button className="button button-primary" type="button" onClick={() => void submitManual()} disabled={manualSubmitting}>{manualSubmitting ? '提交中…' : '保存补录'}</button>
          <button className="button" type="button" onClick={() => {setManualOpen(false); setManualFeedback(null);}} disabled={manualSubmitting}>收起</button>
        </div>
        {manualFeedback && <p className={manualFeedback.kind === 'ok' ? 'ops-feedback-ok' : 'ops-feedback-error'} role={manualFeedback.kind === 'ok' ? 'status' : 'alert'}>{manualFeedback.text}</p>}
      </div>
    </section>}

    <section className="panel" aria-label="异常事件列表">
      <div className="panel-heading"><div><h2>异常事件提醒<span className="panel-count">{items ? `${total} 条` : '等待读取'}</span></h2><p>一次持续异常只记一条</p></div></div>
      <div className="filters">
        <label className="select-field"><span>事件状态</span>
          <select value={eventState} onChange={event => {setEventState(event.target.value); setPage(1);}}>
            <option value="">全部状态</option>
            {Object.entries(eventStateLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
          </select></label>
        <label className="select-field"><span>处理状态</span>
          <select value={handlingState} onChange={event => {setHandlingState(event.target.value); setPage(1);}}>
            <option value="">全部状态</option>
            {Object.entries(handlingStateLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
          </select></label>
        <EquipmentSelect label="设备" value={equipmentId} onChange={next => {setEquipmentId(next); setPage(1);}} allowEmpty emptyLabel="全部设备" />
        <label className="select-field"><span>排序</span>
          <select value={sort} onChange={event => {setSort(event.target.value as AlertEventSort); setPage(1);}}>
            <option value="TIME">按触发时间</option>
            <option value="PRIORITY">按处理优先级</option>
          </select></label>
        <button className="reset-filter" type="button" onClick={resetFilters}>清除筛选</button>
        <button className="button" type="button" onClick={() => {setManualOpen(open => !open); setManualFeedback(null);}}>{manualOpen ? '收起补录' : '人工补录事件'}</button>
        <button className="button" type="button" onClick={() => void loadList(page, pageSize)} disabled={listLoading}>{listLoading ? '刷新中…' : '刷新'}</button>
      </div>
      {listError ? <div className="state-error" role="alert"><div className="state-icon"><Icon name="alert" size={24} /></div><div><h3>暂时无法读取异常事件</h3><p>{listError}</p></div><button className="button" type="button" onClick={() => void loadList()} disabled={listLoading}>重试读取</button></div>
        : listLoading && !items ? <div className="loading-state" role="status"><span className="spinner" /><span>正在读取异常事件…</span></div>
        : items && items.length === 0 ? <div className="empty-state" role="status"><div className="empty-icon"><Icon name="operations" size={30} /></div><h3>当前没有异常事件</h3><p>该筛选条件下未查询到事件记录。</p></div>
        : items && <div className="table-region" role="region" aria-label="异常事件表" tabIndex={0}><table>
          <thead><tr><th className="number-column">编号</th><th>优先级</th><th>设备</th><th>测点 / 参数</th><th>超限数值</th><th>异常起始时间</th><th>来源</th><th>事件状态</th><th>处理状态</th><th>工单进度</th></tr></thead>
          <tbody>{items.map(item => <tr key={item.eventId} className={detail?.eventId === item.eventId ? 'ops-selected' : ''} onClick={() => void openDetail(item.eventId)}>
            <td className="row-number">{item.eventId}</td>
            <td><span className={`ops-tag ${priorityTagClass(item.priority)}`}>{label(priorityLabels, item.priority)}</span></td>
            <td>{item.equipmentName}</td>
            <td>{item.sensorCode} / {parameterText(item.parameterCode, item.parameterName)}</td>
            <td>{item.triggerValue != null
              ? <strong className="ops-value-alert">{formatReading(item.triggerValue)} {formatUnit(item.unitCode)}</strong>
              : <span className="missing-value">—</span>}</td>
            <td>{formatTime(item.startedAt)}</td>
            <td><span className={`ops-tag ${item.sourceType === 'MANUAL' ? 'ops-tag-amber' : 'ops-tag-gray'}`}>{label(eventSourceLabels, item.sourceType, '—')}</span></td>
            <td><Tag value={item.eventState} map={eventStateLabels} /></td>
            <td><Tag value={item.handlingState} map={handlingStateLabels} /></td>
            <td>{item.repairOrderStatuses?.length
              ? <><Tag value={item.repairOrderStatuses[0]} map={orderStatusLabels} />
                  {item.repairOrderStatuses.length > 1 && <span className="missing-value"> 等{item.repairOrderStatuses.length}张</span>}</>
              : <span className="missing-value">未报修</span>}</td>
          </tr>)}</tbody>
        </table></div>}
      {items && items.length > 0 && <Pager page={page} pageSize={pageSize} total={total} unit="条"
        onPage={next => setPage(next)} onPageSize={size => {setPageSize(size); setPage(1);}} />}
      <div className="table-footer"><span role="status">点击行查看详情并发起人工处理</span><span><Icon name="clock" size={14} />{items ? `共 ${total} 条` : '等待读取'}</span></div>
    </section>

    <div className="ops-two-column">
      <section className="panel" aria-label="事件详情">
        <div className="panel-heading"><div><h2>事件详情</h2></div></div>
        {detailLoading ? <div className="loading-state"><span className="spinner" /><span>正在读取详情…</span></div>
          : detailError ? <div className="state-error" role="alert"><div className="state-icon"><Icon name="alert" size={24} /></div><div><h3>无法读取事件详情</h3><p>{detailError}</p></div></div>
          : detail ? <div className="ops-detail">
            <div className="ops-detail-row"><dt>当前状态</dt><dd>
              <span className={`ops-tag ${priorityTagClass(detail.priority)}`}>优先级 {label(priorityLabels, detail.priority)}</span>
              <Tag value={detail.eventState} map={eventStateLabels} />
              <Tag value={detail.handlingState} map={handlingStateLabels} />
            </dd></div>
            <dl>
              <div><dt>设备</dt><dd><strong>{detail.equipmentName}</strong></dd></div>
              <div><dt>测点</dt><dd>{detail.sensorCode}</dd></div>
              <div><dt>参数</dt><dd>{parameterText(detail.parameterCode, detail.parameterName)}</dd></div>
              <div><dt>触发数值</dt><dd><strong className="ops-value-alert">{formatReading(detail.triggerValue)} {formatUnit(detail.unitCode)}</strong></dd></div>
              <div><dt>报警阈值</dt><dd>{thresholdText(detail.ruleSnapshot, detail.unitCode)}</dd></div>
              <div><dt>触发时间</dt><dd>{formatTime(detail.triggeredAt)}</dd></div>
              <div><dt>开始 / 恢复 / 结束</dt><dd>{formatTime(detail.startedAt)} / {formatTime(detail.recoveredAt)} / {formatTime(detail.endedAt)}</dd></div>
              <div><dt>结束原因</dt><dd>{label(endReasonLabels, detail.endReason)}</dd></div>
              <div><dt>命中规则<Tip text={detail.ruleSnapshot
                ? `触发时用的是第 ${detail.ruleSnapshot.ruleVersion} 版阈值规则；之后规则怎么改都不影响本事件的判断依据。`
                : undefined} /></dt><dd>{detail.ruleSnapshot ? ruleText(detail.ruleSnapshot) : '规则快照缺失'}</dd></div>
              {detail.ruleSnapshot?.reason && <div><dt>规则调整依据</dt><dd>{detail.ruleSnapshot.reason}</dd></div>}
              <div><dt>事件来源</dt><dd>
                <span className={`ops-tag ${sourceTypeOf(detail.sourceReference) === 'MANUAL' ? 'ops-tag-amber' : 'ops-tag-gray'}`}>
                  {label(eventSourceLabels, sourceTypeOf(detail.sourceReference), '—')}
                </span>
              </dd></div>
              <div><dt>读数来源</dt><dd>{referenceText(detail.sourceReference)}</dd></div>
              <div><dt>关联工单</dt><dd>{detail.repairOrders.length
                ? detail.repairOrders.map(order => `${order.orderNo}（${label(orderStatusLabels, order.status)}）`).join('、')
                : <span className="missing-value">尚未报修</span>}</dd></div>
            </dl>
            <div className="ops-form-actions">
              {onRepairRequest && <button className="button button-primary" type="button"
                onClick={() => onRepairRequest({
                  sourceType: 'ALERT_EVENT', sourceId: detail.eventId, equipmentId: detail.equipmentId,
                  summary: `事件 #${detail.eventId}｜${detail.equipmentName}｜${formatReading(detail.triggerValue)}${formatUnit(detail.unitCode)}｜${formatTime(detail.triggeredAt)}`,
                })}>
                就此事件报修
              </button>}
              <button className="button" type="button" onClick={() => void loadHistory(detail.eventId)}>查看处理历史</button>
            </div>
          </div>
          : <div className="empty-state"><div className="empty-icon"><Icon name="operations" size={30} /></div><h3>尚未选择事件</h3><p>在上方列表点击任意一行查看触发证据。</p></div>}
      </section>

      <section className="panel" aria-label="人工处理">
        <div className="panel-heading"><div><h2>人工处理</h2><p>对告警表态，不改设备与工单</p></div></div>
        {detail ? <div className="ops-form">
          <p className="ops-form-context">事件 <strong>#{detail.eventId}</strong> · {detail.equipmentName}</p>
          {detail.repairOrders.some(order => order.status !== 'COMPLETED' && order.status !== 'CLOSED') &&
            <p className="ops-feedback-hint">该事件已报修，工单正在流程中。已经有人接手，这里不登记也可以；
              只有"判断不需要修"或"确认已阅"时才用这里留痕。</p>}
          <div className="ops-form-row">
            <label className="select-field"><span>处理动作</span>
              <select value={action} onChange={event => setAction(event.target.value)}>
                <option value="ACKNOWLEDGED">已知悉</option>
                <option value="DEFER_REPAIR">暂不报修</option>
              </select></label>
            <label className="select-field"><span>经办人</span>
              <input value={operator} onChange={event => setOperator(event.target.value)} size={8} placeholder="请填写姓名" /></label>
          </div>
          <label className="ops-text-field"><span>处理原因（必填）</span>
            <textarea rows={3} value={reason} onChange={event => setReason(event.target.value)} placeholder="例如：已通知当班班组现场确认" /></label>
          <div className="ops-form-actions">
            <button className="button button-primary" type="button" onClick={() => void submitHandling()} disabled={submitting}>{submitting ? '提交中…' : '提交处理记录'}</button>
          </div>
          {feedback && <p className={feedback.kind === 'ok' ? 'ops-feedback-ok' : 'ops-feedback-error'} role={feedback.kind === 'ok' ? 'status' : 'alert'}>{feedback.text}</p>}
          {historyFor === detail.eventId && history && <div className="ops-history">
            <h3>处理历史（{history.length} 条{historyTotal > history.length ? `，仅显示前 ${history.length} 条` : ''}）</h3>
            {history.length === 0 ? <p className="missing-value">该事件还没有人工处理记录。</p> : <table><thead><tr><th>动作</th><th>原因</th><th>经办人</th><th>时间</th></tr></thead>
              <tbody>{history.map(record => <tr key={record.handlingRecordId}><td><Tag value={record.action} map={handlingStateLabels} /></td><td>{record.reason}</td><td>{record.operatorName}</td><td>{formatTime(record.recordedAt)}</td></tr>)}</tbody></table>}
          </div>}
        </div>
        : <div className="empty-state"><div className="empty-icon"><Icon name="check" size={28} /></div><h3>选择事件后可处理</h3><p>在这里登记"已阅"或"暂不报修"；需要维修请用左侧「就此事件报修」。</p></div>}
      </section>
    </div>
  </>;
}
