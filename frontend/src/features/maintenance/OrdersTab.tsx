import { useCallback, useEffect, useState } from 'react';
import { Icon } from '../../components/Icon';
import { MaintenanceApiError, repairOrderApi, toOffsetIso } from '../../lib/maintenanceApi';
import type { RepairOrderDetail, RepairOrderItem } from '../../lib/maintenanceApi';
import {
  acceptanceResultLabels, actionTypeLabels, availableActionLabels, formatTime,
  label, localInputValue, orderStatusLabels, reviewResultLabels, sourceTypeLabels,
} from './labels';
import { EquipmentSelect, Pager, useOperatorName } from './shared';
import type { RepairHandoff } from './shared';

type Feedback = {kind: 'ok' | 'error'; text: string} | null;

function statusTagClass(status: string): string {
  if (status === 'IN_REPAIR' || status === 'ACCEPTANCE_PENDING') return 'ops-tag-blue';
  if (status === 'COMPLETED') return 'ops-tag-green';
  if (status === 'REVIEW_PENDING' || status === 'RETURNED_FOR_INFO') return 'ops-tag-amber';
  return 'ops-tag-gray';
}

export function OrdersTab({handoff, onHandoffApplied}: {handoff?: RepairHandoff | null; onHandoffApplied?: () => void}) {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [items, setItems] = useState<RepairOrderItem[] | null>(null);
  const [total, setTotal] = useState(0);
  const [listError, setListError] = useState<string | null>(null);
  const [listLoading, setListLoading] = useState(true);

  const [createType, setCreateType] = useState('ALERT_EVENT');
  const [createSourceId, setCreateSourceId] = useState('');
  const [createEquipmentId, setCreateEquipmentId] = useState('');
  const [createOperator, setCreateOperator] = useOperatorName();
  const [createFeedback, setCreateFeedback] = useState<Feedback>(null);
  const [creating, setCreating] = useState(false);

  const [detail, setDetail] = useState<RepairOrderDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const [version, setVersion] = useState(0);
  const [operator, setOperator] = useOperatorName();
  const [saveDescription, setSaveDescription] = useState('');
  const [saveSupplement, setSaveSupplement] = useState('');
  const [saveReporter, setSaveReporter] = useState('');
  const [reviewResult, setReviewResult] = useState('APPROVE');
  const [reviewReason, setReviewReason] = useState('同意维修');
  const [repairDescription, setRepairDescription] = useState('更换热电偶并重新校准');
  const [acceptResult, setAcceptResult] = useState('PASS');
  const [acceptReason, setAcceptReason] = useState('');
  const [feedback, setFeedback] = useState<Feedback>(null);
  const [acting, setActing] = useState(false);

  // 从“异常事件 / 巡检”页签点“创建报修草稿”时带入来源，用户无需手抄编号
  useEffect(() => {
    if (!handoff) return;
    setCreateType(handoff.sourceType);
    setCreateSourceId(handoff.sourceId);
    setCreateEquipmentId(handoff.equipmentId);
    setCreateFeedback({kind: 'ok', text: `已带入来源：${handoff.summary}。确认后点「创建草稿」即可。`});
    onHandoffApplied?.();
  }, [handoff, onHandoffApplied]);

  const loadList = useCallback(async (targetPage = page, targetSize = pageSize) => {
    setListLoading(true);
    try {
      const data = await repairOrderApi.list({status: status || undefined}, targetPage, targetSize);
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
  }, [status, page, pageSize]);

  useEffect(() => { void loadList(); }, [loadList]);

  const openDetail = useCallback(async (repairOrderId: string) => {
    setDetailLoading(true);
    try {
      const data = await repairOrderApi.detail(repairOrderId);
      setDetail(data);
      setVersion(data.version);
      setSaveDescription(data.description ?? '');
      setSaveSupplement(data.supplement ?? '');
      setSaveReporter(data.reporterName ?? '');
      setFeedback(null);
    } catch (error) {
      setFeedback({kind: 'error', text: error instanceof MaintenanceApiError ? error.message : '读取详情失败，请重试。'});
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const refreshAfterAction = useCallback(async (repairOrderId: string, message: string) => {
    setFeedback({kind: 'ok', text: message});
    await Promise.all([loadList(), openDetail(repairOrderId)]);
  }, [loadList, openDetail]);

  const runAction = useCallback(async (kind: 'save' | 'submit' | 'review' | 'repair' | 'accept') => {
    if (!detail) return;
    const who = operator.trim();
    if (!who) { setFeedback({kind: 'error', text: '请填写经办人（项目暂无登录用户，需手工署名）。'}); return; }
    setActing(true);
    try {
      if (kind === 'save') {
        if (!saveDescription.trim()) { setFeedback({kind: 'error', text: '故障描述为必填。'}); return; }
        const data = await repairOrderApi.save(detail.repairOrderId, {
          operatorName: who, expectedVersion: version, reason: '补充报修资料',
          description: saveDescription.trim(), supplement: saveSupplement.trim(), reporterName: saveReporter.trim() || who,
        });
        await refreshAfterAction(detail.repairOrderId, `资料已保存，新版本 ${data.repairOrder.version}，状态「${label(orderStatusLabels, data.repairOrder.status)}」。`);
      } else if (kind === 'submit') {
        const data = await repairOrderApi.submit(detail.repairOrderId, {operatorName: who, expectedVersion: version});
        await refreshAfterAction(detail.repairOrderId, `工单已提交，进入「${label(orderStatusLabels, data.status)}」，新版本 ${data.version}。`);
      } else if (kind === 'review') {
        if (!reviewReason.trim()) { setFeedback({kind: 'error', text: '审核必须填写原因。'}); return; }
        const data = await repairOrderApi.review(detail.repairOrderId, {
          operatorName: who, expectedVersion: version, result: reviewResult, reason: reviewReason.trim(),
        });
        await refreshAfterAction(detail.repairOrderId, `审核完成（${label(reviewResultLabels, reviewResult)}），状态「${label(orderStatusLabels, data.status)}」，新版本 ${data.version}。`);
      } else if (kind === 'repair') {
        if (!repairDescription.trim()) { setFeedback({kind: 'error', text: '请填写维修内容。'}); return; }
        const data = await repairOrderApi.recordRepairResult(detail.repairOrderId, {
          operatorName: who, expectedVersion: version, performedAt: toOffsetIso(localInputValue()) ?? '', description: repairDescription.trim(),
        });
        await refreshAfterAction(detail.repairOrderId, `维修结果已登记，进入「${label(orderStatusLabels, data.status)}」，新版本 ${data.version}。`);
      } else {
        if (acceptResult === 'FAIL' && !acceptReason.trim()) { setFeedback({kind: 'error', text: '验收不通过必须填写原因。'}); return; }
        const data = await repairOrderApi.accept(detail.repairOrderId, {
          operatorName: who, expectedVersion: version, result: acceptResult,
          acceptedAt: toOffsetIso(localInputValue()) ?? '', reason: acceptReason.trim(),
        });
        await refreshAfterAction(detail.repairOrderId, `验收完成（${label(acceptanceResultLabels, acceptResult)}），状态「${label(orderStatusLabels, data.status)}」，新版本 ${data.version}。`);
      }
    } catch (error) {
      if (error instanceof MaintenanceApiError && error.code === 'VERSION_CONFLICT') {
        setFeedback({kind: 'error', text: `${error.message} 请重新点击该工单获取最新版本后再操作。`});
        await loadList();
      } else {
        setFeedback({kind: 'error', text: error instanceof MaintenanceApiError ? error.message : '操作失败，请重试。'});
      }
    } finally {
      setActing(false);
    }
  }, [detail, operator, version, saveDescription, saveSupplement, saveReporter, reviewResult, reviewReason,
    repairDescription, acceptResult, acceptReason, refreshAfterAction, loadList]);

  const submitCreate = useCallback(async () => {
    const sourceId = createSourceId.trim();
    const equipmentId = createEquipmentId.trim();
    if (!createOperator.trim()) { setCreateFeedback({kind: 'error', text: '请填写经办人。'}); return; }
    if ((createType === 'ALERT_EVENT' || createType === 'INSPECTION') && !sourceId) {
      setCreateFeedback({kind: 'error', text: '请填写来源编号，或到「异常事件提醒 / 巡检记录」页签点“创建报修草稿”自动带入。'});
      return;
    }
    if (createType === 'MANUAL' && !equipmentId) {
      setCreateFeedback({kind: 'error', text: '人工上报必须选择设备。'});
      return;
    }
    setCreating(true);
    try {
      const data = await repairOrderApi.create({
        sourceType: createType, operatorName: createOperator.trim(),
        sourceId: sourceId || undefined, equipmentId: createType === 'MANUAL' ? equipmentId : undefined,
      });
      setCreateFeedback({kind: 'ok', text: `已创建草稿 ${data.orderNo}（版本 ${data.version}），可在下方操作区继续完善并提交。`});
      setCreateSourceId('');
      await loadList();
      await openDetail(data.repairOrderId);
    } catch (error) {
      setCreateFeedback({kind: 'error', text: error instanceof MaintenanceApiError ? error.message : '建单失败，请重试。'});
    } finally {
      setCreating(false);
    }
  }, [createType, createSourceId, createEquipmentId, createOperator, loadList, openDetail]);

  const can = (action: string) => detail?.availableActions.includes(action) ?? false;

  return <>
    <section className="panel" aria-label="建单">
      <div className="panel-heading"><div><h2>创建报修草稿<span className="panel-count">S1-R01</span></h2><p>只有明确决定报修才建单；草稿不影响任何统计</p></div></div>
      <div className="ops-form ops-form-inline">
        <label className="select-field"><span>来源类型</span>
          <select value={createType} onChange={event => setCreateType(event.target.value)}>
            {Object.entries(sourceTypeLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
          </select></label>
        {createType === 'MANUAL'
          ? <EquipmentSelect label="设备" value={createEquipmentId} onChange={setCreateEquipmentId} />
          : <label className="select-field"><span>{sourceTypeLabels[createType]}编号（必填）</span>
              <input value={createSourceId} onChange={event => setCreateSourceId(event.target.value.replace(/[^0-9]/g, ''))} size={6} placeholder="可在来源页签一键带入" inputMode="numeric" /></label>}
        <label className="select-field"><span>经办人</span>
          <input value={createOperator} onChange={event => setCreateOperator(event.target.value)} size={8} placeholder="请填写姓名" /></label>
        <button className="button button-primary" type="button" onClick={() => void submitCreate()} disabled={creating}>{creating ? '建单中…' : '创建草稿'}</button>
      </div>
      {createFeedback && <p className={createFeedback.kind === 'ok' ? 'ops-feedback-ok' : 'ops-feedback-error'}>{createFeedback.text}</p>}
    </section>

    <section className="panel" aria-label="工单列表">
      <div className="panel-heading"><div><h2>维修工单<span className="panel-count">{items ? `${total} 张` : '等待读取'}</span></h2><p>草稿 → 提交 → 审核 → 维修 → 验收的完整状态机</p></div><span className="read-only-label"><Icon name="check" size={14} />S1-R02</span></div>
      <div className="filters">
        <label className="select-field"><span>工单状态</span>
          <select value={status} onChange={event => {setStatus(event.target.value); setPage(1);}}>
            <option value="">全部状态</option>
            {Object.entries(orderStatusLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
          </select></label>
        <button className="reset-filter" type="button" onClick={() => {setStatus(''); setPage(1);}}>清除筛选</button>
        <button className="button" type="button" onClick={() => void loadList(page, pageSize)} disabled={listLoading}>{listLoading ? '刷新中…' : '刷新'}</button>
      </div>
      {listError ? <div className="state-error" role="alert"><div className="state-icon"><Icon name="alert" size={24} /></div><div><h3>暂时无法读取工单</h3><p>{listError}</p></div><button className="button" type="button" onClick={() => void loadList()} disabled={listLoading}>重试读取</button></div>
        : listLoading && !items ? <div className="loading-state" role="status"><span className="spinner" /><span>正在读取维修工单…</span></div>
        : items && items.length === 0 ? <div className="empty-state" role="status"><div className="empty-icon"><Icon name="operations" size={30} /></div><h3>当前没有维修工单</h3><p>可在上方从异常事件、巡检或人工上报创建草稿。</p></div>
        : items && <div className="table-region" role="region" aria-label="维修工单表" tabIndex={0}><table>
          <thead><tr><th className="number-column">单号</th><th>来源</th><th>状态</th><th>设备</th><th>创建时间</th><th>首次提交</th><th>完成时间</th></tr></thead>
          <tbody>{items.map(item => <tr key={item.repairOrderId} className={detail?.repairOrderId === item.repairOrderId ? 'ops-selected' : ''} onClick={() => void openDetail(item.repairOrderId)}>
            <td className="row-number">{item.orderNo}</td>
            <td>{label(sourceTypeLabels, item.sourceType)}{item.sourceId ? ` #${item.sourceId}` : ''}</td>
            <td><span className={`ops-tag ${statusTagClass(item.status)}`}>{label(orderStatusLabels, item.status)}</span></td>
            <td>{item.equipmentName}</td>
            <td>{formatTime(item.createdAt)}</td>
            <td>{formatTime(item.firstSubmittedAt)}</td>
            <td>{formatTime(item.completedAt)}</td>
          </tr>)}</tbody>
        </table></div>}
      {items && items.length > 0 && <Pager page={page} pageSize={pageSize} total={total} unit="张"
        onPage={next => setPage(next)} onPageSize={size => {setPageSize(size); setPage(1);}} />}
      <div className="table-footer"><span role="status">点击行加载详情与流转操作</span><span><Icon name="clock" size={14} />{items ? `共 ${total} 张` : '等待读取'}</span></div>
    </section>

    {detail && <section className="panel" aria-label="工单详情">
      <div className="panel-heading"><div><h2>工单详情 {detail.orderNo}<span className="panel-count">{label(orderStatusLabels, detail.status)}</span></h2><p>来源快照在建单时冻结，后续来源变更不回写</p></div><span className="read-only-label"><Icon name="check" size={14} />S1-R03 / R09</span></div>
      <div className="ops-detail ops-detail-padded">
        <dl>
          <div><dt>状态</dt><dd><span className={`ops-tag ${statusTagClass(detail.status)}`}>{label(orderStatusLabels, detail.status)}</span></dd></div>
          <div><dt>设备</dt><dd>{detail.equipmentName}</dd></div>
          <div><dt>来源</dt><dd>{label(sourceTypeLabels, detail.sourceType)}{detail.sourceId ? ` #${detail.sourceId}` : ''}</dd></div>
          <div><dt>报修人</dt><dd>{detail.reporterName ?? <span className="missing-value">待补充</span>}</dd></div>
          <div><dt>故障描述</dt><dd>{detail.description ?? <span className="missing-value">待补充</span>}</dd></div>
          <div><dt>补充资料</dt><dd>{detail.supplement ?? <span className="missing-value">无</span>}</dd></div>
          <div><dt>创建 / 更新</dt><dd>{formatTime(detail.createdAt)} / {formatTime(detail.updatedAt)}</dd></div>
          <div><dt>首次提交 / 完成</dt><dd>{formatTime(detail.firstSubmittedAt)} / {formatTime(detail.completedAt)}</dd></div>
        </dl>
        <div className="ops-actions-available"><span>当前可用动作：</span>
          {detail.availableActions.length
            ? detail.availableActions.map(action => <span key={action} className="ops-tag ops-tag-blue">{label(availableActionLabels, action)}</span>)
            : <span className="missing-value">无（终态）</span>}
        </div>
        <details className="ops-json" open><summary>来源快照（sourceSnapshot，原始结构）</summary><pre>{JSON.stringify(detail.sourceSnapshot, null, 2)}</pre></details>
        {detail.actions.length > 0 && <div className="ops-history">
          <h3>动作历史（{detail.actions.length} 条）</h3>
          <table><thead><tr><th>动作</th><th>结果</th><th>状态流转</th><th>经办人</th><th>原因</th><th>内容</th><th>时间</th></tr></thead>
            <tbody>{detail.actions.map(action => <tr key={action.actionId}>
              <td>{label(actionTypeLabels, action.actionType)}</td>
              <td>{action.result ? label({...reviewResultLabels, ...acceptanceResultLabels}, action.result) : '—'}</td>
              <td>{action.previousStatus ? `${label(orderStatusLabels, action.previousStatus)} → ${label(orderStatusLabels, action.nextStatus)}` : `→ ${label(orderStatusLabels, action.nextStatus)}`}</td>
              <td>{action.operatorName}</td>
              <td className="ops-cell-text">{action.reason ?? '—'}</td>
              <td className="ops-cell-text">{action.description ?? '—'}</td>
              <td>{formatTime(action.occurredAt)}</td>
            </tr>)}</tbody></table>
        </div>}
      </div>
    </section>}

    {detail && <section className="panel" aria-label="工单流转操作">
      <div className="panel-heading"><div><h2>流转操作<span className="panel-count">S1-R04 ~ R08</span></h2><p>按钮按当前状态自动启用；并发冲突时返回 409 并提示刷新</p></div></div>
      <div className="ops-form">
        <div className="ops-form-row">
          <label className="select-field"><span>当前版本</span>
            <input value={version} onChange={event => setVersion(Number(event.target.value) || 0)} size={4} /></label>
          <label className="select-field"><span>经办人</span>
            <input value={operator} onChange={event => setOperator(event.target.value)} size={8} placeholder="请填写姓名" /></label>
        </div>
        <div className="ops-form-row">
          <label className="ops-text-field ops-grow"><span>故障描述（保存资料用）</span>
            <input value={saveDescription} onChange={event => setSaveDescription(event.target.value)} /></label>
          <label className="ops-text-field ops-grow"><span>补充资料</span>
            <input value={saveSupplement} onChange={event => setSaveSupplement(event.target.value)} /></label>
          <label className="select-field"><span>报修人</span>
            <input value={saveReporter} onChange={event => setSaveReporter(event.target.value)} size={8} placeholder="默认同经办人" /></label>
          <button className="button" type="button" onClick={() => void runAction('save')} disabled={acting || !can('SAVE')}>保存资料</button>
        </div>
        <div className="ops-form-row">
          <button className="button button-primary" type="button" onClick={() => void runAction('submit')} disabled={acting || !can('SUBMIT')}>提交审核</button>
          <label className="select-field"><span>审核结论</span>
            <select value={reviewResult} onChange={event => setReviewResult(event.target.value)}>
              {Object.entries(reviewResultLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
            </select></label>
          <label className="ops-text-field ops-grow"><span>审核原因</span>
            <input value={reviewReason} onChange={event => setReviewReason(event.target.value)} /></label>
          <button className="button" type="button" onClick={() => void runAction('review')} disabled={acting || !can('APPROVE') && !can('RETURN_FOR_INFO') && !can('NO_REPAIR') && !can('DUPLICATE')}>提交审核结论</button>
        </div>
        <div className="ops-form-row">
          <label className="ops-text-field ops-grow"><span>维修内容（登记维修结果用）</span>
            <input value={repairDescription} onChange={event => setRepairDescription(event.target.value)} /></label>
          <button className="button" type="button" onClick={() => void runAction('repair')} disabled={acting || !can('RECORD_REPAIR_RESULT')}>登记维修结果</button>
        </div>
        <div className="ops-form-row">
          <label className="select-field"><span>验收结论</span>
            <select value={acceptResult} onChange={event => setAcceptResult(event.target.value)}>
              {Object.entries(acceptanceResultLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
            </select></label>
          <label className="ops-text-field ops-grow"><span>验收原因（不通过时必填）</span>
            <input value={acceptReason} onChange={event => setAcceptReason(event.target.value)} /></label>
          <button className="button" type="button" onClick={() => void runAction('accept')} disabled={acting || !can('ACCEPT_PASS') && !can('ACCEPT_FAIL')}>提交验收</button>
        </div>
        {feedback && <p className={feedback.kind === 'ok' ? 'ops-feedback-ok' : 'ops-feedback-error'} role={feedback.kind === 'ok' ? 'status' : 'alert'}>{feedback.text}</p>}
      </div>
    </section>}

    {!detail && !detailLoading && <section className="panel"><div className="empty-state"><div className="empty-icon"><Icon name="operations" size={30} /></div><h3>尚未选择工单</h3><p>点击列表中的任意工单，查看详情与全部流转动作。</p></div></section>}
  </>;
}
