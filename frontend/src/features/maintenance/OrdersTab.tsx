import { useCallback, useEffect, useState } from 'react';
import { Icon } from '../../components/Icon';
import { MaintenanceApiError, repairOrderApi, toOffsetIso } from '../../lib/maintenanceApi';
import type { RepairActionItem, RepairOrderDetail, RepairOrderItem } from '../../lib/maintenanceApi';
import {
  acceptanceResultLabels, actionTypeLabels, faultCategoryLabels, formatTime,
  label, localInputValue, orderStatusLabels, reviewResultLabels, sourceTypeLabels,
} from './labels';
import { EquipmentSelect, Pager, PhotoPicker, PhotoSection, uploadPhotos, useOperatorName } from './shared';
import type { RepairHandoff } from './shared';

type Feedback = {kind: 'ok' | 'error'; text: string} | null;

function statusTagClass(status: string): string {
  if (status === 'IN_REPAIR' || status === 'ACCEPTANCE_PENDING') return 'ops-tag-blue';
  if (status === 'COMPLETED' || status === 'CLOSED') return 'ops-tag-green';
  if (status === 'REVIEW_PENDING' || status === 'RETURNED_FOR_INFO') return 'ops-tag-amber';
  return 'ops-tag-gray';
}

/* ---------------- 四步流程条 ----------------
 * 文档只有状态机，没有"派工"这个动作码：审核通过（APPROVE）即进入维修中，
 * 因此"审核派工"把两者合并成一步，派工对象记在审核意见里。 */
const flowSteps = [
  {title: '报修提交', owner: '报修人', detail: '登记设备、故障类别与现象，提交后进入待审核'},
  {title: '审核派工', owner: '设备主管', detail: '确认是否维修并指定维修人；也可退回补充、判无需维修'},
  {title: '维修处理', owner: '维修工', detail: '登记维修时间与处理内容，附现场照片'},
  {title: '验收关闭', owner: '验收人', detail: '验收通过即完成；不通过退回维修'},
] as const;

/** 状态 → 当前所处环节下标；终态返回 4（四步全部完成）。 */
function stepIndex(status: string): number {
  if (status === 'REVIEW_PENDING') return 1;
  if (status === 'IN_REPAIR') return 2;
  if (status === 'ACCEPTANCE_PENDING') return 3;
  if (status === 'COMPLETED' || status === 'CLOSED') return 4;
  return 0;
}

/** 每个环节的实际处理人：取该环节最后一次动作的经办人与时间。 */
function handlerOf(actions: RepairActionItem[], actionTypes: string[]): RepairActionItem | null {
  for (const action of actions) {
    if (actionTypes.includes(action.actionType)) return action;
  }
  return null;
}

const snapshotLabels: Record<string, string> = {
  eventId: '异常事件编号', inspectionId: '巡检编号',
  equipmentName: '设备', sensorCode: '测点', parameterCode: '参数', parameterName: '参数名称',
  triggerValue: '触发数值', unitCode: '单位', triggeredAt: '触发时间', startedAt: '开始时间',
  recoveredAt: '恢复时间', endedAt: '结束时间', endReason: '结束原因',
  inspectedAt: '巡检时间', inspectorName: '巡检人', content: '巡检内容', description: '情况说明',
  result: '巡检结果', eventVersion: '事件版本', inspectionVersion: '巡检版本',
};
const snapshotOrder = [
  'eventId', 'inspectionId', 'equipmentName', 'sensorCode', 'parameterCode', 'triggerValue', 'unitCode',
  'triggeredAt', 'startedAt', 'recoveredAt', 'endedAt', 'endReason',
  'inspectedAt', 'inspectorName', 'result', 'content', 'description',
];

/** 来源快照翻成业务字段；不再把 JSON 原样抛给使用者。 */
function snapshotRows(snapshot: unknown): {label: string; text: string}[] {
  if (!snapshot || typeof snapshot !== 'object' || Array.isArray(snapshot)) return [];
  const source = snapshot as Record<string, unknown>;
  const rows: {label: string; text: string}[] = [];
  for (const key of snapshotOrder) {
    const raw = source[key];
    if (raw === undefined || raw === null || raw === '') continue;
    const name = snapshotLabels[key] ?? key;
    const text = key === 'result'
      ? label({NORMAL: '正常', ABNORMAL: '异常'}, String(raw))
      : /At$/.test(key) ? formatTime(String(raw)) : String(raw);
    rows.push({label: name, text});
  }
  return rows;
}

export function OrdersTab({handoff, onHandoffApplied}: {handoff?: RepairHandoff | null; onHandoffApplied?: () => void}) {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [items, setItems] = useState<RepairOrderItem[] | null>(null);
  const [total, setTotal] = useState(0);
  const [listError, setListError] = useState<string | null>(null);
  const [listLoading, setListLoading] = useState(true);

  const [operator, setOperator] = useOperatorName();
  const [createType, setCreateType] = useState('ALERT_EVENT');
  const [createSourceId, setCreateSourceId] = useState('');
  const [createEquipmentId, setCreateEquipmentId] = useState('');
  const [createFault, setCreateFault] = useState('');
  const [createDescription, setCreateDescription] = useState('');
  const [createReporter, setCreateReporter] = useState('');
  const [createPhotos, setCreatePhotos] = useState<File[]>([]);
  const [createFeedback, setCreateFeedback] = useState<Feedback>(null);
  const [creating, setCreating] = useState(false);

  const [detail, setDetail] = useState<RepairOrderDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const [version, setVersion] = useState(0);
  const [saveFault, setSaveFault] = useState('');
  const [saveDescription, setSaveDescription] = useState('');
  const [saveSupplement, setSaveSupplement] = useState('');
  const [saveReporter, setSaveReporter] = useState('');
  const [photoIds, setPhotoIds] = useState<string[]>([]);
  const [reviewResult, setReviewResult] = useState('APPROVE');
  const [reviewHandler, setReviewHandler] = useState('');
  const [reviewReason, setReviewReason] = useState('');
  const [repairDescription, setRepairDescription] = useState('');
  const [acceptResult, setAcceptResult] = useState('PASS');
  const [acceptReason, setAcceptReason] = useState('');
  const [feedback, setFeedback] = useState<Feedback>(null);
  const [acting, setActing] = useState(false);

  // 从"异常事件 / 巡检"页签点"报修"时带入来源，用户无需手抄编号
  useEffect(() => {
    if (!handoff) return;
    setCreateType(handoff.sourceType);
    setCreateSourceId(handoff.sourceId);
    setCreateEquipmentId(handoff.equipmentId);
    setCreateFeedback({kind: 'ok', text: `已带入来源：${handoff.summary}。确认故障类别与现象后点「提交报修工单」。`});
    onHandoffApplied?.();
  }, [handoff, onHandoffApplied]);

  // 报修人默认跟经办人一致，改了经办人时同步跟进（用户手动改过就不再覆盖）
  useEffect(() => {
    setCreateReporter(current => current || operator);
  }, [operator]);

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
      setSaveFault(data.faultCategory ?? '');
      setSaveDescription(data.description ?? '');
      setSaveSupplement(data.supplement ?? '');
      setSaveReporter(data.reporterName ?? '');
      setPhotoIds(data.photoAttachmentIds);
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
          description: saveDescription.trim(), supplement: saveSupplement.trim(),
          reporterName: saveReporter.trim() || who,
          faultCategory: saveFault || null, photoAttachmentIds: photoIds,
        });
        await refreshAfterAction(detail.repairOrderId, `报修资料已保存，新版本 ${data.repairOrder.version}，状态「${label(orderStatusLabels, data.repairOrder.status)}」。`);
      } else if (kind === 'submit') {
        if (!saveDescription.trim()) { setFeedback({kind: 'error', text: '正式提交前必须填写故障描述。'}); return; }
        const data = await repairOrderApi.submit(detail.repairOrderId, {operatorName: who, expectedVersion: version});
        await refreshAfterAction(detail.repairOrderId, `已提交，进入「${label(orderStatusLabels, data.status)}」，新版本 ${data.version}。`);
      } else if (kind === 'review') {
        if (!reviewReason.trim()) { setFeedback({kind: 'error', text: '审核必须填写意见。'}); return; }
        if (reviewResult === 'APPROVE' && !reviewHandler.trim()) { setFeedback({kind: 'error', text: '通过审核时必须指定维修人（派工对象）。'}); return; }
        const reason = reviewResult === 'APPROVE' && reviewHandler.trim()
          ? `派工给 ${reviewHandler.trim()}：${reviewReason.trim()}` : reviewReason.trim();
        const data = await repairOrderApi.review(detail.repairOrderId, {
          operatorName: who, expectedVersion: version, result: reviewResult, reason,
        });
        await refreshAfterAction(detail.repairOrderId, `${label(reviewResultLabels, reviewResult)}已提交，状态「${label(orderStatusLabels, data.status)}」，新版本 ${data.version}。`);
      } else if (kind === 'repair') {
        if (!repairDescription.trim()) { setFeedback({kind: 'error', text: '请填写维修内容。'}); return; }
        const data = await repairOrderApi.recordRepairResult(detail.repairOrderId, {
          operatorName: who, expectedVersion: version, performedAt: toOffsetIso(localInputValue()) ?? '',
          description: repairDescription.trim(), attachmentIds: photoIds,
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
  }, [detail, operator, version, saveFault, saveDescription, saveSupplement, saveReporter, photoIds,
    reviewResult, reviewHandler, reviewReason, repairDescription, acceptResult, acceptReason,
    refreshAfterAction, loadList]);

  const submitCreate = useCallback(async () => {
    const sourceId = createSourceId.trim();
    const equipmentId = createEquipmentId.trim();
    const who = operator.trim();
    if (!who) { setCreateFeedback({kind: 'error', text: '请填写报修人/经办人。'}); return; }
    if ((createType === 'ALERT_EVENT' || createType === 'INSPECTION') && !sourceId) {
      setCreateFeedback({kind: 'error', text: '请填写来源编号，或到「异常事件提醒 / 巡检记录」页签点「报修」自动带入。'});
      return;
    }
    if (createType === 'MANUAL' && !equipmentId) {
      setCreateFeedback({kind: 'error', text: '人工上报必须选择设备。'}); return;
    }
    if (!createFault) { setCreateFeedback({kind: 'error', text: '请选择故障类别。'}); return; }
    if (!createDescription.trim()) { setCreateFeedback({kind: 'error', text: '请填写故障现象，便于维修人判断。'}); return; }
    setCreating(true);
    try {
      const reporter = createReporter.trim() || who;
      const created = await repairOrderApi.create({
        sourceType: createType, operatorName: who,
        sourceId: sourceId || undefined, equipmentId: createType === 'MANUAL' ? equipmentId : undefined,
        faultCategory: createFault, description: createDescription.trim(), reporterName: reporter,
      });
      // 照片要挂在工单上，只能等工单有编号后再传；传完把 ID 写进工单照片位（保存资料接口）。
      let photoNote = '';
      if (createPhotos.length) {
        const result = await uploadPhotos('REPAIR_ORDER', created.repairOrderId, who, createPhotos);
        if (result.uploadedIds.length) {
          await repairOrderApi.save(created.repairOrderId, {
            operatorName: who, expectedVersion: created.version, reason: '报修时上传现场照片',
            description: createDescription.trim(), supplement: '', reporterName: reporter,
            faultCategory: createFault, photoAttachmentIds: result.uploadedIds,
          }).catch(() => null);
        }
        photoNote = result.uploaded
          ? `，已上传 ${result.uploaded} 张现场照片`
          : '';
        if (result.failed.length) {
          photoNote += `；有 ${result.failed.length} 张未上传成功（${result.failed.join('；')}），可在下方详情里补传`;
        }
      }
      setCreateFeedback({
        kind: createPhotos.length && photoNote.includes('未上传成功') ? 'error' : 'ok',
        text: `报修工单 ${created.orderNo} 已创建（状态「${label(orderStatusLabels, created.status)}」）${photoNote}。未提交前不计入统计。`,
      });
      setCreateSourceId(''); setCreateDescription(''); setCreateFault(''); setCreatePhotos([]);
      await loadList();
      await openDetail(created.repairOrderId);
    } catch (error) {
      setCreateFeedback({kind: 'error', text: error instanceof MaintenanceApiError ? error.message : '建单失败，请重试。'});
    } finally {
      setCreating(false);
    }
  }, [createType, createSourceId, createEquipmentId, createFault, createDescription, createReporter,
    createPhotos, operator, loadList, openDetail]);

  const current = detail ? stepIndex(detail.status) : 0;
  const handlers = detail ? [
    detail.reporterName ?? handlerOf(detail.actions, ['SUBMIT'])?.operatorName ?? null,
    handlerOf(detail.actions, ['REVIEW'])?.operatorName ?? null,
    handlerOf(detail.actions, ['REPAIR_RESULT'])?.operatorName ?? null,
    handlerOf(detail.actions, ['ACCEPTANCE'])?.operatorName ?? null,
  ] : [null, null, null, null];
  const handlerTimes = detail ? [
    handlerOf(detail.actions, ['SUBMIT'])?.occurredAt ?? null,
    handlerOf(detail.actions, ['REVIEW'])?.occurredAt ?? null,
    handlerOf(detail.actions, ['REPAIR_RESULT'])?.occurredAt ?? null,
    handlerOf(detail.actions, ['ACCEPTANCE'])?.occurredAt ?? null,
  ] : [null, null, null, null];

  const can = (action: string) => detail?.availableActions.includes(action) ?? false;
  const editable = detail ? detail.availableActions.includes('SAVE') : false;
  const rows = detail ? snapshotRows(detail.sourceSnapshot) : [];

  return <>
    <section className="panel" aria-label="新建报修工单">
      <div className="panel-heading"><div><h2>新建报修工单</h2><p>选设备、填现象、归类故障即可；未提交的工单不计入任何统计</p></div></div>
      <div className="ops-form">
        <div className="ops-form-row">
          <label className="select-field"><span>来源</span>
            <select value={createType} onChange={event => setCreateType(event.target.value)}>
              {Object.entries(sourceTypeLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
            </select></label>
          {createType === 'MANUAL'
            ? <EquipmentSelect label="设备" value={createEquipmentId} onChange={setCreateEquipmentId} />
            : <label className="select-field"><span>{sourceTypeLabels[createType]}编号（必填）</span>
                <input value={createSourceId} onChange={event => setCreateSourceId(event.target.value.replace(/[^0-9]/g, ''))} size={6} placeholder="可在来源页签一键带入" inputMode="numeric" /></label>}
          <label className="select-field"><span>故障类别</span>
            <select value={createFault} onChange={event => setCreateFault(event.target.value)}>
              <option value="">请选择</option>
              {Object.entries(faultCategoryLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
            </select></label>
          <label className="select-field"><span>报修人</span>
            <input value={createReporter} onChange={event => setCreateReporter(event.target.value)} size={8} placeholder="默认同经办人" /></label>
        </div>
        <label className="ops-text-field"><span>故障现象（必填）</span>
          <textarea rows={2} value={createDescription} onChange={event => setCreateDescription(event.target.value)}
            placeholder="例如：1#熔炼炉热电偶读数持续偏高，伴随报警" /></label>
        <PhotoPicker files={createPhotos} onChange={setCreatePhotos} disabled={creating} />
        <div className="ops-form-actions">
          <label className="select-field"><span>经办人</span>
            <input value={operator} onChange={event => setOperator(event.target.value)} size={8} placeholder="请填写姓名" /></label>
          <button className="button button-primary" type="button" onClick={() => void submitCreate()} disabled={creating}>
            {creating ? '提交中…' : '提交报修工单'}
          </button>
        </div>
        {createFeedback && <p className={createFeedback.kind === 'ok' ? 'ops-feedback-ok' : 'ops-feedback-error'}>{createFeedback.text}</p>}
      </div>
    </section>

    <section className="panel" aria-label="工单列表">
      <div className="panel-heading"><div><h2>维修工单<span className="panel-count">{items ? `${total} 张` : '等待读取'}</span></h2><p>报修提交 → 审核派工 → 维修处理 → 验收关闭</p></div></div>
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
        : items && items.length === 0 ? <div className="empty-state" role="status"><div className="empty-icon"><Icon name="operations" size={30} /></div><h3>当前没有维修工单</h3><p>可在上方从异常事件、巡检或人工上报新建报修工单。</p></div>
        : items && <div className="table-region" role="region" aria-label="维修工单表" tabIndex={0}><table>
          <thead><tr><th className="number-column">单号</th><th>设备</th><th>来源</th><th>故障类别</th><th>状态</th><th>报修时间</th><th>提交时间</th><th>完成时间</th></tr></thead>
          <tbody>{items.map(item => <tr key={item.repairOrderId} className={detail?.repairOrderId === item.repairOrderId ? 'ops-selected' : ''} onClick={() => void openDetail(item.repairOrderId)}>
            <td className="row-number">{item.orderNo}</td>
            <td>{item.equipmentName}</td>
            <td>{label(sourceTypeLabels, item.sourceType)}</td>
            <td>{item.faultCategory ? label(faultCategoryLabels, item.faultCategory) : <span className="missing-value">未分类</span>}</td>
            <td><span className={`ops-tag ${statusTagClass(item.status)}`}>{label(orderStatusLabels, item.status)}</span></td>
            <td>{formatTime(item.createdAt)}</td>
            <td>{formatTime(item.firstSubmittedAt)}</td>
            <td>{formatTime(item.completedAt)}</td>
          </tr>)}</tbody>
        </table></div>}
      {items && items.length > 0 && <Pager page={page} pageSize={pageSize} total={total} unit="张"
        onPage={next => setPage(next)} onPageSize={size => {setPageSize(size); setPage(1);}} />}
      <div className="table-footer"><span role="status">点击行加载详情与当前环节的操作</span><span><Icon name="clock" size={14} />{items ? `共 ${total} 张` : '等待读取'}</span></div>
    </section>

    {detailLoading && !detail && <section className="panel"><div className="loading-state"><span className="spinner" /><span>正在读取工单详情…</span></div></section>}

    {detail && <section className="panel" aria-label="工单详情">
      <div className="panel-heading"><div><h2>工单 {detail.orderNo}<span className="panel-count">{label(orderStatusLabels, detail.status)}</span></h2><p>{detail.equipmentName} · {label(sourceTypeLabels, detail.sourceType)}来源 · 报修人 {detail.reporterName ?? '待补充'}</p></div></div>
      <div className="ops-detail ops-detail-padded">
        <ol className="ops-flow">
          {flowSteps.map((step, index) => {
            const state = current === 4 || index < current ? 'done' : index === current ? 'active' : 'todo';
            return <li className={`ops-flow-step ${state}`} key={step.title}>
              <span className="ops-flow-index">{index + 1}</span>
              <div className="ops-flow-body">
                <strong>{step.title}<small>· {step.owner}</small></strong>
                <span className="ops-flow-hint">{step.detail}</span>
                <span className="ops-flow-who">{handlers[index]
                  ? `处理人 ${handlers[index]}${handlerTimes[index] ? ` · ${formatTime(handlerTimes[index])}` : ''}`
                  : state === 'active' ? '当前环节，等待处理' : '未开始'}</span>
              </div>
            </li>;
          })}
        </ol>
        <dl>
          <div><dt>设备</dt><dd><strong>{detail.equipmentName}</strong></dd></div>
          <div><dt>故障类别</dt><dd>{detail.faultCategory ? label(faultCategoryLabels, detail.faultCategory) : <span className="missing-value">未分类</span>}</dd></div>
          <div><dt>故障现象</dt><dd>{detail.description ?? <span className="missing-value">待补充</span>}</dd></div>
          <div><dt>补充资料</dt><dd>{detail.supplement ?? <span className="missing-value">无</span>}</dd></div>
          <div><dt>报修人</dt><dd>{detail.reporterName ?? <span className="missing-value">待补充</span>}</dd></div>
          <div><dt>报修时间</dt><dd>{formatTime(detail.createdAt)}</dd></div>
          <div><dt>首次提交 / 完成</dt><dd>{formatTime(detail.firstSubmittedAt)} / {formatTime(detail.completedAt)}</dd></div>
          <div><dt>最近更新</dt><dd>{formatTime(detail.updatedAt)}</dd></div>
        </dl>
        {rows.length > 0 && <>
          <h3 className="ops-section-title">报修依据（建单时冻结，来源后续变更不回写）</h3>
          <dl className="ops-snapshot">{rows.map(row => <div key={row.label}><dt>{row.label}</dt><dd>{row.text}</dd></div>)}</dl>
        </>}
        <PhotoSection ownerType="REPAIR_ORDER" ownerId={detail.repairOrderId}
          operatorName={operator} onLoaded={setPhotoIds}
          emptyHint="该工单还没有现场照片，可在此补传。" />
        {detail.actions.length > 0 && <div className="ops-history">
          <h3>流转记录（{detail.actions.length} 条）</h3>
          <table><thead><tr><th>动作</th><th>结果</th><th>状态流转</th><th>经办人</th><th>说明</th><th>内容</th><th>时间</th></tr></thead>
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

    {detail && <section className="panel" aria-label="当前环节操作">
      <div className="panel-heading"><div><h2>{current >= 4 ? '流程已结束' : `当前环节：${flowSteps[current].title}`}</h2><p>只显示当前状态下可做的操作；并发冲突时后端返回 409 并提示刷新</p></div></div>
      <div className="ops-form">
        <div className="ops-form-row">
          <label className="select-field"><span>经办人</span>
            <input value={operator} onChange={event => setOperator(event.target.value)} size={8} placeholder="请填写姓名" /></label>
          <span className="ops-form-context ops-inline-context">当前版本 <strong>{version}</strong></span>
        </div>

        {editable && <>
          <div className="ops-form-row">
            <label className="select-field"><span>故障类别</span>
              <select value={saveFault} onChange={event => setSaveFault(event.target.value)}>
                <option value="">未分类</option>
                {Object.entries(faultCategoryLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
              </select></label>
            <label className="ops-text-field ops-grow"><span>故障现象（必填）</span>
              <input value={saveDescription} onChange={event => setSaveDescription(event.target.value)} /></label>
            <label className="select-field"><span>报修人</span>
              <input value={saveReporter} onChange={event => setSaveReporter(event.target.value)} size={8} placeholder="默认同经办人" /></label>
          </div>
          <label className="ops-text-field"><span>补充资料（选填）</span>
            <textarea rows={2} value={saveSupplement} onChange={event => setSaveSupplement(event.target.value)} placeholder="例如：现场已断电挂牌，待停机窗口维修" /></label>
          <div className="ops-form-actions">
            <button className="button" type="button" onClick={() => void runAction('save')} disabled={acting || !can('SAVE')}>保存修改</button>
            <button className="button button-primary" type="button" onClick={() => void runAction('submit')} disabled={acting || !can('SUBMIT')}>提交报修</button>
          </div>
        </>}

        {can('APPROVE') && <>
          <div className="ops-form-row">
            <label className="select-field"><span>审核结论</span>
              <select value={reviewResult} onChange={event => setReviewResult(event.target.value)}>
                {Object.entries(reviewResultLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
              </select></label>
            <label className="select-field"><span>派工维修人</span>
              <input value={reviewHandler} onChange={event => setReviewHandler(event.target.value)} size={8} placeholder="通过时必填" /></label>
            <label className="ops-text-field ops-grow"><span>审核意见（必填）</span>
              <input value={reviewReason} onChange={event => setReviewReason(event.target.value)} placeholder="例如：属电气故障，安排电仪班处理" /></label>
          </div>
          <div className="ops-form-actions">
            <button className="button button-primary" type="button" onClick={() => void runAction('review')} disabled={acting || !can('APPROVE')}>
              提交审核结论
            </button>
            <span className="ops-form-context ops-inline-context">通过后进入「维修中」，派工对象记入审核说明</span>
          </div>
        </>}

        {can('RECORD_REPAIR_RESULT') && <>
          <label className="ops-text-field"><span>维修内容（必填）</span>
            <textarea rows={2} value={repairDescription} onChange={event => setRepairDescription(event.target.value)}
              placeholder="例如：更换热电偶并重新校准，复测温度回到正常范围" /></label>
          <div className="ops-form-actions">
            <button className="button button-primary" type="button" onClick={() => void runAction('repair')} disabled={acting || !can('RECORD_REPAIR_RESULT')}>登记维修结果</button>
            <span className="ops-form-context ops-inline-context">维修照片在上方"现场照片"里上传，随本次维修一并留档</span>
          </div>
        </>}

        {can('ACCEPT_PASS') && <>
          <div className="ops-form-row">
            <label className="select-field"><span>验收结论</span>
              <select value={acceptResult} onChange={event => setAcceptResult(event.target.value)}>
                {Object.entries(acceptanceResultLabels).map(([value, text]) => <option value={value} key={value}>{text}</option>)}
              </select></label>
            <label className="ops-text-field ops-grow"><span>验收意见（不通过时必填）</span>
              <input value={acceptReason} onChange={event => setAcceptReason(event.target.value)} /></label>
          </div>
          <div className="ops-form-actions">
            <button className="button button-primary" type="button" onClick={() => void runAction('accept')} disabled={acting || !can('ACCEPT_PASS')}>提交验收</button>
          </div>
        </>}

        {!editable && !can('APPROVE') && !can('RECORD_REPAIR_RESULT') && !can('ACCEPT_PASS') &&
          <p className="ops-note-block">该工单已结束（{label(orderStatusLabels, detail.status)}），不再接受新的流转操作。</p>}

        {feedback && <p className={feedback.kind === 'ok' ? 'ops-feedback-ok' : 'ops-feedback-error'} role={feedback.kind === 'ok' ? 'status' : 'alert'}>{feedback.text}</p>}
      </div>
    </section>}

    {!detail && !detailLoading && <section className="panel"><div className="empty-state"><div className="empty-icon"><Icon name="operations" size={30} /></div><h3>尚未选择工单</h3><p>点击列表中的任意工单，查看流程进度与当前环节的操作。</p></div></section>}
  </>;
}
