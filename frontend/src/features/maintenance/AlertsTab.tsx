import { useCallback, useEffect, useState } from 'react';
import { Icon } from '../../components/Icon';
import { MaintenanceApiError, maintenanceApi, newRequestId } from '../../lib/maintenanceApi';
import type { AlertEventDetail, AlertEventItem, HandlingRecordItem, RuleSnapshot } from '../../lib/maintenanceApi';
import { endReasonLabels, eventStateLabels, formatTime, handlingStateLabels, label, orderStatusLabels, triggerModeLabels } from './labels';
import { EquipmentSelect, Pager, useOperatorName } from './shared';
import type { RepairHandoff } from './shared';

type Feedback = {kind: 'ok' | 'error'; text: string} | null;

function stateTagClass(state: string): string {
  if (state === 'ONGOING' || state === 'IN_REPAIR') return 'ops-tag-blue';
  if (state === 'RECOVERED' || state === 'ACKNOWLEDGED' || state === 'COMPLETED') return 'ops-tag-green';
  if (state === 'PENDING' || state === 'DEFER_REPAIR' || state === 'RETURNED_FOR_INFO' || state === 'REVIEW_PENDING') return 'ops-tag-amber';
  if (state === 'ABNORMAL') return 'ops-tag-red';
  return 'ops-tag-gray';
}
function Tag({value, map}: {value: string; map: Record<string, string>}) {
  return <span className={`ops-tag ${stateTagClass(value)}`}>{label(map, value)}</span>;
}

/** 把结构化规则快照翻成业务能读的句子，避免直接抛 [0, 80] 这种数学区间。 */
function ruleText(snapshot: RuleSnapshot): string {
  const unit = snapshot.unitCode ?? '';
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

export function AlertsTab({onRepairRequest}: {onRepairRequest?: (handoff: RepairHandoff) => void}) {
  const [eventState, setEventState] = useState('');
  const [handlingState, setHandlingState] = useState('');
  const [equipmentId, setEquipmentId] = useState('');
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

  const loadList = useCallback(async (targetPage = page, targetSize = pageSize) => {
    setListLoading(true);
    try {
      const data = await maintenanceApi.alertEvents({
        eventState: eventState || undefined,
        handlingState: handlingState || undefined,
        equipmentId: equipmentId || undefined,
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
  }, [eventState, handlingState, equipmentId, page, pageSize]);

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
      setFeedback({kind: 'ok', text: `处理记录已保存（记录 ${result.handlingRecordId}），事件处理状态更新为「${label(handlingStateLabels, result.handlingState)}」，当前版本 ${result.version}。`});
      setReason('');
      await Promise.all([loadList(), openDetail(detail.eventId)]);
      void loadHistory(detail.eventId);
    } catch (error) {
      if (error instanceof MaintenanceApiError && error.code === 'VERSION_CONFLICT') {
        setFeedback({kind: 'error', text: `${error.message} 列表中的版本号已刷新，请重新选择该事件后再试。`});
        await loadList();
      } else {
        setFeedback({kind: 'error', text: error instanceof MaintenanceApiError ? error.message : '提交失败，请重试。'});
      }
    } finally {
      setSubmitting(false);
    }
  }, [detail, reason, operator, action, loadList, openDetail, loadHistory]);

  const resetFilters = () => { setEventState(''); setHandlingState(''); setEquipmentId(''); setPage(1); };

  return <>
    <section className="panel" aria-label="异常事件列表">
      <div className="panel-heading"><div><h2>异常事件提醒<span className="panel-count">{items ? `${total} 条` : '等待读取'}</span></h2><p>持续异常过程只产生一条事件；状态由恢复与结束事实推导</p></div><span className="read-only-label"><Icon name="check" size={14} />S1-A01</span></div>
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
        <button className="reset-filter" type="button" onClick={resetFilters}>清除筛选</button>
        <button className="button" type="button" onClick={() => void loadList(page, pageSize)} disabled={listLoading}>{listLoading ? '刷新中…' : '刷新'}</button>
      </div>
      {listError ? <div className="state-error" role="alert"><div className="state-icon"><Icon name="alert" size={24} /></div><div><h3>暂时无法读取异常事件</h3><p>{listError}</p></div><button className="button" type="button" onClick={() => void loadList()} disabled={listLoading}>重试读取</button></div>
        : listLoading && !items ? <div className="loading-state" role="status"><span className="spinner" /><span>正在读取异常事件…</span></div>
        : items && items.length === 0 ? <div className="empty-state" role="status"><div className="empty-icon"><Icon name="operations" size={30} /></div><h3>当前没有异常事件</h3><p>该筛选条件下未查询到事件记录。</p></div>
        : items && <div className="table-region" role="region" aria-label="异常事件表" tabIndex={0}><table>
          <thead><tr><th className="number-column">编号</th><th>设备</th><th>测点 / 参数</th><th>触发时间</th><th>事件状态</th><th>处理状态</th><th>关联工单</th></tr></thead>
          <tbody>{items.map(item => <tr key={item.eventId} className={detail?.eventId === item.eventId ? 'ops-selected' : ''} onClick={() => void openDetail(item.eventId)}>
            <td className="row-number">{item.eventId}</td>
            <td>{item.equipmentName}</td>
            <td>{item.sensorCode} / {item.parameterCode}</td>
            <td>{formatTime(item.triggeredAt)}</td>
            <td><Tag value={item.eventState} map={eventStateLabels} /></td>
            <td><Tag value={item.handlingState} map={handlingStateLabels} /></td>
            <td>{item.repairOrderIds.length ? item.repairOrderIds.join('、') : <span className="missing-value">无</span>}</td>
          </tr>)}</tbody>
        </table></div>}
      {items && items.length > 0 && <Pager page={page} pageSize={pageSize} total={total} unit="条"
        onPage={next => setPage(next)} onPageSize={size => {setPageSize(size); setPage(1);}} />}
      <div className="table-footer"><span role="status">点击行查看详情并发起人工处理</span><span><Icon name="clock" size={14} />{items ? `共 ${total} 条` : '等待读取'}</span></div>
    </section>

    <div className="ops-two-column">
      <section className="panel" aria-label="事件详情">
        <div className="panel-heading"><div><h2>事件详情<span className="panel-count">S1-A02</span></h2><p>触发证据与规则快照</p></div></div>
        {detailLoading ? <div className="loading-state"><span className="spinner" /><span>正在读取详情…</span></div>
          : detailError ? <div className="state-error" role="alert"><div className="state-icon"><Icon name="alert" size={24} /></div><div><h3>无法读取事件详情</h3><p>{detailError}</p></div></div>
          : detail ? <div className="ops-detail">
            <div className="ops-detail-row"><dt>状态</dt><dd><Tag value={detail.eventState} map={eventStateLabels} /><Tag value={detail.handlingState} map={handlingStateLabels} /></dd></div>
            <dl>
              <div><dt>设备</dt><dd>{detail.equipmentName}</dd></div>
              <div><dt>测点 / 参数</dt><dd>{detail.sensorCode} / {detail.parameterCode}</dd></div>
              <div><dt>触发值</dt><dd><strong>{detail.triggerValue ?? '—'} {detail.unitCode ?? ''}</strong></dd></div>
              <div><dt>开始 / 触发</dt><dd>{formatTime(detail.startedAt)} / {formatTime(detail.triggeredAt)}</dd></div>
              <div><dt>恢复 / 结束</dt><dd>{formatTime(detail.recoveredAt)} / {formatTime(detail.endedAt)}</dd></div>
              <div><dt>结束原因</dt><dd>{label(endReasonLabels, detail.endReason)}</dd></div>
              {detail.ruleSnapshot && <div><dt>命中规则（版本 {detail.ruleSnapshot.ruleVersion}）</dt><dd>{ruleText(detail.ruleSnapshot)}</dd></div>}
              {detail.ruleSnapshot?.reason && <div><dt>规则依据</dt><dd>{detail.ruleSnapshot.reason}</dd></div>}
              <div><dt>关联工单</dt><dd>{detail.repairOrders.length
                ? detail.repairOrders.map(order => `${order.orderNo}（${label(orderStatusLabels, order.status)}）`).join('、')
                : <span className="missing-value">尚未报修</span>}</dd></div>
            </dl>
            <div className="ops-form-actions">
              {onRepairRequest && <button className="button button-primary" type="button"
                onClick={() => onRepairRequest({
                  sourceType: 'ALERT_EVENT', sourceId: detail.eventId, equipmentId: detail.equipmentId,
                  summary: `事件 #${detail.eventId}｜${detail.equipmentName}｜${detail.triggerValue ?? '—'}${detail.unitCode ?? ''}｜${formatTime(detail.triggeredAt)}`,
                })}>
                以此事件创建报修草稿
              </button>}
              <button className="button" type="button" onClick={() => void loadHistory(detail.eventId)}>查看处理历史</button>
            </div>
            <details className="ops-json"><summary>读数来源引用（sourceReference，原始结构）</summary><pre>{JSON.stringify(detail.sourceReference, null, 2)}</pre></details>
          </div>
          : <div className="empty-state"><div className="empty-icon"><Icon name="operations" size={30} /></div><h3>尚未选择事件</h3><p>在上方列表点击任意一行查看触发证据。</p></div>}
      </section>

      <section className="panel" aria-label="人工处理">
        <div className="panel-heading"><div><h2>人工处理<span className="panel-count">S1-A03 / A04</span></h2><p>已知悉或暂不报修；处理记录只追加、不覆盖</p></div></div>
        {detail ? <div className="ops-form">
          <p className="ops-form-context">事件 <strong>#{detail.eventId}</strong> · 当前版本 <strong>{detail.version}</strong></p>
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
        : <div className="empty-state"><div className="empty-icon"><Icon name="check" size={28} /></div><h3>选择事件后可处理</h3><p>人工处理会携带事件版本号，并发冲突时后端返回 409。</p></div>}
      </section>
    </div>
  </>;
}
