import {useCallback, useState} from 'react';
import {useResource} from '../../lib/useResource';
import {ApiError} from '../../lib/api';
import {
  EXECUTION_STATE_LABELS, WEIGHING_STATE_LABELS,
  fetchTaskDetail, fetchTasks, newRequestId, postProduction,
  type TaskSummary,
} from '../../lib/productionApi';
import {FormRow, SaveFeedback, workshopName} from './shared';

const today = () => new Date().toLocaleDateString('sv-SE', {timeZone: 'Asia/Shanghai'});

export function TasksTab() {
  const [date, setDate] = useState(today());
  const [workshopId, setWorkshopId] = useState('');
  const [executionState, setExecutionState] = useState('');
  const [selected, setSelected] = useState<string | null>(null);

  const load = useCallback((signal: AbortSignal) => fetchTasks(
    {date, workshopId: workshopId || undefined, executionState: executionState || undefined}, signal),
  [date, workshopId, executionState]);
  const tasks = useResource(load);

  const list = tasks.data?.items ?? [];

  return <>
    <section className="panel" aria-label="生产任务查询">
      <div className="panel-heading"><div>
        <h2>生产任务<small className="panel-count">{date}</small></h2>
      </div></div>
      <div className="filters" style={{paddingBottom: 16}}>
        <label className="select-field">计划日期
          <input type="date" value={date} onChange={event => setDate(event.target.value)}
            style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
        </label>
        <label className="select-field">车间
          <select value={workshopId} onChange={event => setWorkshopId(event.target.value)}>
            <option value="">全部车间</option>
            <option value="1">车间1 · 铜杆</option>
            <option value="2">车间2 · 铜丝</option>
          </select>
        </label>
        <label className="select-field">执行状态
          <select value={executionState} onChange={event => setExecutionState(event.target.value)}>
            <option value="">全部状态</option>
            {Object.entries(EXECUTION_STATE_LABELS).map(([value, label]) =>
              <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        <button type="button" className="reset-filter" onClick={() => {
          setDate(today()); setWorkshopId(''); setExecutionState('');
        }}>清除筛选</button>
      </div>
      <div className="table-region"><table>
        <thead><tr>
          <th>车间</th><th>顺序</th><th>计划产品</th><th>关联订单</th>
          <th>计划合格量</th><th>实际合格量</th><th>差额</th><th>状态</th>
        </tr></thead>
        <tbody>
          {tasks.loading && <tr><td colSpan={8}>读取中…</td></tr>}
          {tasks.error && <tr><td colSpan={8}>{tasks.error}</td></tr>}
          {!tasks.loading && !tasks.error && list.map(task =>
            <tr key={task.taskId}
              className={selected === task.taskId ? 'ops-selected' : ''}
              onClick={() => setSelected(task.taskId)}
              style={{cursor: 'pointer'}}>
              <td>{workshopName(task.workshopId)}</td>
              <td>第 {task.sequenceNo} 号</td>
              <td>{MATERIAL_LABELS[task.materialId] ?? (task.workshopId === '1' ? '直径 8mm 铜杆' : '铜丝')}</td>
              <td>{(task.orderNos?.length ? task.orderNos : task.orderIds).join('、')
                || <span className="missing-value">无</span>}</td>
              <td>{task.plannedQualifiedTon} 吨</td>
              <td>{task.actualQualifiedTon == null
                ? <span className="missing-value">待统计</span> : `${task.actualQualifiedTon} 吨`}</td>
              <td>{task.varianceTon == null
                ? <span className="missing-value">待统计</span>
                : <span className={Number(task.varianceTon) < 0 ? 'ops-tag ops-tag-amber' : 'ops-tag ops-tag-green'}>
                  {task.varianceTon} 吨</span>}</td>
              <td><span className={`ops-tag ${stateTag(task)}`}>
                {EXECUTION_STATE_LABELS[task.executionState]} · {WEIGHING_STATE_LABELS[task.weighingState]}
              </span></td>
            </tr>)}
          {!tasks.loading && !tasks.error && !tasks.data?.items.length &&
            <tr><td colSpan={8}>该日期暂无生产任务；任务由「订单与计划」模块创建，无计划的生产请在「实际记录」页补录。</td></tr>}
        </tbody>
      </table></div>
    </section>
    {selected && <TaskDetailPanel taskId={selected}
      onClose={() => setSelected(null)} onChanged={() => tasks.reload()}/>}
  </>;
}

function stateTag(task: TaskSummary): string {
  if (task.weighingState === 'CONFIRMED_ZERO') return 'ops-tag-gray';
  if (task.executionState === 'COMPLETED') return 'ops-tag-green';
  if (task.weighingState === 'RECORDED') return 'ops-tag-blue';
  return 'ops-tag-amber';
}

export function TaskDetailPanel({taskId, onClose, onChanged}: {
  taskId: string; onClose: () => void; onChanged: () => void;
}) {
  const load = useCallback((signal: AbortSignal) => fetchTaskDetail(taskId, signal), [taskId]);
  const detail = useResource(load);
  const [openForm, setOpenForm] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<SaveFeedback | null>(null);
  const [busy, setBusy] = useState(false);

  const task = detail.data;
  const isRodWorkshop = task?.workshopId === '1';
  // 按用途分组：登记实际 / 任务收尾 / 可选作业时间，避免一排放一堆看不懂的按钮。
  const RECORD_ACTIONS = ['PLATE_INPUT', 'ROD_OUTPUT', 'ROD_HANDOVER', 'WIRE_OUTPUT'];
  const recordActions = (task?.availableActions ?? []).filter(action => RECORD_ACTIONS.includes(action));
  const closingActions = (task?.availableActions ?? [])
    .filter(action => action === 'TASK_COMPLETION' || action === 'NO_OUTPUT_CONFIRMATION');
  const canExecution = (task?.availableActions ?? []).includes('EXECUTION_RECORD');

  const submit = useCallback(async (path: string, body: Record<string, unknown>) => {
    setBusy(true); setFeedback(null);
    try {
      const payload = {...body};
      if (!payload.operatorName) payload.operatorName = '系统管理员';
      const result = await postProduction(path, {requestId: newRequestId(), ...payload}) as
        {overPlanWarning?: string} | undefined;
      setFeedback({ok: true,
        message: result?.overPlanWarning ? `保存成功。${result.overPlanWarning}` : '保存成功。'});
      detail.reload(); onChanged();
      setOpenForm(null);
    } catch (error) {
      const fields = error instanceof ApiError ? error.fields : {};
      const extra = error instanceof ApiError && error.detail
        && typeof error.detail.conflicts !== 'undefined' ? '（冲突详情见控制台/接口文档）' : '';
      setFeedback({ok: false, message: (error instanceof Error ? error.message : '保存失败。') + extra,
        fields});
    } finally {
      setBusy(false);
    }
  }, [detail, onChanged]);

  return <section className="panel" aria-label="任务详情">
    <div className="panel-heading"><div>
      <h2>任务详情<small className="panel-count">{task?.plannedDate}</small></h2>
    </div>
      <button type="button" className="button" onClick={onClose}>收起</button>
    </div>
    {detail.loading && <p style={{padding: '0 23px 20px'}}>读取中…</p>}
    {detail.error && <p style={{padding: '0 23px 20px'}}>{detail.error}</p>}
    {task && <>
      <div className="ops-detail ops-detail-padded" style={{display: 'grid',
        gridTemplateColumns: 'repeat(3, minmax(0,1fr))', gap: '12px 26px'}}>
        <div><dt>任务顺序</dt><dd>当日第 {task.sequenceNo} 号</dd></div>
        <div><dt>车间 / 产品</dt><dd>{workshopName(task.workshopId).split(' · ')[0]} ·
          {(task.materialName ?? '').startsWith('Φ')
            ? (task.materialName as string).replace(/^Φ([\d.]+)/, '直径 $1mm ')
            : (task.materialName ?? '—')}</dd></div>
        <div><dt>计划日期</dt><dd>{task.plannedDate}</dd></div>
        <div><dt>计划合格量</dt><dd><strong>{task.plannedQualifiedTon} 吨</strong></dd></div>
        <div><dt>实际合格量</dt><dd>{task.actualQualifiedTon == null
          ? <span className="missing-value">待统计</span>
          : <strong>{task.actualQualifiedTon} 吨</strong>}</dd></div>
        <div><dt>差额</dt><dd>{task.varianceTon == null ? '待统计' : `${task.varianceTon} 吨`}</dd></div>
        {isRodWorkshop ? <>
          <div><dt>计划投料</dt><dd>{task.plannedPlateTon == null
            ? <span className="missing-value">未安排投料</span>
            : `${task.plannedPlateTon} 吨`}</dd></div>
          <div><dt>已录投料</dt><dd>{task.plateInputSummaryTon ?? '无'} 吨（{task.plateInputCount} 笔）</dd></div>
          <div><dt>铜板库存</dt><dd>{task.inventory?.physicalTon ?? '—'} 吨</dd></div>
        </> : <>
          <div><dt>铜杆交接</dt><dd>{task.handoverCount ? `${task.handoverCount} 笔` : '尚无交接'}</dd></div>
        </>}
        <div><dt>本任务预留</dt><dd>{task.reservations.length
          ? task.reservations.map(r => `${r.reservedTon} 吨`).join('、')
          : <span className="missing-value">无</span>}</dd></div>
        <div><dt>关联订单</dt><dd>{task.orders.length
          ? task.orders.map(o => `${o.orderNo}（${o.customerName}${o.quantityTon ? ` · ${o.quantityTon} 吨` : ''}）`).join('、')
          : <span className="missing-value">无</span>}</dd></div>
        <div><dt>待补事项</dt><dd>{task.missingItems.length
          ? <span className="ops-tag ops-tag-amber">{task.missingItems.join('；')}</span>
          : '无'}</dd></div>
      </div>

      <div className="ops-form" style={{paddingTop: 6}}>
        {recordActions.length > 0 && <>
          <div className="action-row">
            <span className="action-row-title">登记生产实际</span>
            <div className="ops-form-actions" style={{margin: 0}}>
              {recordActions.map(action =>
                <button key={action} type="button"
                  className={`button ${openForm === action ? 'button-primary' : ''}`}
                  onClick={() => {setOpenForm(openForm === action ? null : action); setFeedback(null);}}>
                  {ACTION_LABELS[action] ?? action}
                </button>)}
            </div>
          </div>
        </>}
        {feedback && <SaveFeedback feedback={feedback}/>}
        {openForm === 'PLATE_INPUT' && isRodWorkshop && <FormRow fields={[
          {name: 'actualDate', label: '发生日期', type: 'date', required: true, defaultValue: today()},
          {name: 'inputQuantityTon', label: '本次铜板投入（吨）', required: true, placeholder: '如 6.300'},
          {name: 'materialAmountYuan', label: '材料金额（元，可留空）', placeholder: '可留空'},
          {name: 'operatorName', label: '经办人', placeholder: '默认系统管理员'},
        ]} busy={busy} onSubmit={values => submit(`/api/v1/production/tasks/${taskId}/plate-inputs`, {...values, expectedVersion: task.version})}/>}
        {openForm === 'ROD_OUTPUT' && isRodWorkshop && <FormRow fields={[
          {name: 'actualDate', label: '发生日期', type: 'date', required: true, defaultValue: today()},
          {name: 'qualifiedQuantityTon', label: '本次合格产量（吨）', required: true},
          {name: 'operatorName', label: '经办人', placeholder: '默认系统管理员'},
        ]} hint="按磅单填，可多次累加。"
          busy={busy} onSubmit={values => submit(`/api/v1/production/tasks/${taskId}/rod-outputs`, {...values, expectedVersion: task.version})}/>}
        {openForm === 'ROD_HANDOVER' && !isRodWorkshop && <FormRow fields={[
          {name: 'actualDate', label: '发生日期', type: 'date', required: true, defaultValue: today()},
          {name: 'sentQuantityTon', label: '车间1交来重量（吨）', required: true},
          {name: 'sentAt', label: '交来时间', type: 'datetime-local', required: true},
          {name: 'orderId', label: '关联订单（可空）'},
          {name: 'receivedQuantityTon', label: '本车间实收重量（吨，已收到才填）'},
          {name: 'receivedAt', label: '实收时间', type: 'datetime-local'},
          {name: 'releaseReason', label: '超差放行原因（误差超 5% 时填）'},
          {name: 'operatorName', label: '经办人', placeholder: '默认系统管理员'},
        ]} busy={busy}
          onSubmit={values => submit(`/api/v1/production/tasks/${taskId}/rod-handovers`, {
              ...values, expectedVersion: task.version,
              sentAt: toOffset(values.sentAt), receivedAt: toOffset(values.receivedAt),
              releaseReason: values.releaseReason || null,
              releaseConfirmed: Boolean(values.releaseReason),
            })}/>}
        {openForm === 'WIRE_OUTPUT' && !isRodWorkshop && <FormRow fields={[
          {name: 'materialId', label: '产品', type: 'select', required: true,
            options: [['3', '直径 2.6mm 铜丝']]},
          {name: 'actualDate', label: '发生日期', type: 'date', required: true, defaultValue: today()},
          {name: 'qualifiedQuantityTon', label: '本次合格产量（吨）', required: true},
          {name: 'stoppedAt', label: '停机时间', type: 'datetime-local'},
          {name: 'allocationsText', label: '订单归属（订单编号:吨数，逗号分隔，可空）',
            placeholder: '9001:3.000'},
          {name: 'operatorName', label: '经办人', placeholder: '默认系统管理员'},
        ]} hint="按磅单填，可多次累加。"
          busy={busy} onSubmit={values => submit('/api/v1/production/wire-outputs', {
            taskIds: [taskId], expectedVersions: {tasks: [{id: taskId, version: task.version}]},
            materialId: values.materialId, actualDate: values.actualDate,
            qualifiedQuantityTon: values.qualifiedQuantityTon,
            stoppedAt: toOffset(values.stoppedAt),
            allocations: parseAllocations(values.allocationsText),
            operatorName: values.operatorName,
          })}/>}

        {closingActions.length > 0 && <>
          <div className="action-row" style={{marginTop: 16}}>
            <span className="action-row-title">任务收尾</span>
            <div className="ops-form-actions" style={{margin: 0}}>
              {closingActions.includes('TASK_COMPLETION') && <button type="button"
                className={`button ${openForm === 'TASK_COMPLETION' ? 'button-primary' : ''}`}
                onClick={() => {setOpenForm(openForm === 'TASK_COMPLETION' ? null : 'TASK_COMPLETION');
                  setFeedback(null);}}>结束本任务</button>}
              {closingActions.includes('NO_OUTPUT_CONFIRMATION') && <button type="button"
                className={`button ${openForm === 'NO_OUTPUT_CONFIRMATION' ? 'button-primary' : ''}`}
                onClick={() => {setOpenForm(openForm === 'NO_OUTPUT_CONFIRMATION'
                  ? null : 'NO_OUTPUT_CONFIRMATION'); setFeedback(null);}}>今天没有产出</button>}
            </div>
          </div>
          {closingActions.includes('TASK_COMPLETION')
            && !closingActions.includes('NO_OUTPUT_CONFIRMATION')
            && <p className="detail-section-hint" style={{marginTop: 6}}>
              今天已有产量，不能再确认零产出。</p>}
          {openForm === 'TASK_COMPLETION' && <FormRow fields={[
            {name: 'actualDate', label: '发生日期', type: 'date', required: true, defaultValue: today()},
            {name: 'stoppedAt', label: '实际停止时间', type: 'datetime-local', required: true},
            {name: 'reason', label: '未称重就结束的原因（已称重可空）'},
            {name: 'operatorName', label: '经办人', placeholder: '默认系统管理员'},
          ]} hint="结束后不能再称重，产量没称完请先称重。" busy={busy}
            onSubmit={values => submit(`/api/v1/production/tasks/${taskId}/completion`, {
                ...values, expectedVersion: task.version, stoppedAt: toOffset(values.stoppedAt),
              })}/>}
          {openForm === 'NO_OUTPUT_CONFIRMATION' && <FormRow fields={[
            {name: 'actualDate', label: '发生日期', type: 'date', required: true, defaultValue: today()},
            {name: 'scope', label: '没产出的范围', type: 'select', required: true,
              options: [['TASK', '只有本任务没产出'], ['WORKSHOP_DAY', '整个车间当天都没产出']]},
            {name: 'reason', label: '原因', required: true, placeholder: '如设备检修未开机'},
            {name: 'operatorName', label: '经办人', placeholder: '默认系统管理员'},
          ]} hint="当天已经登记过合格产量时，不能确认零产出。" busy={busy}
            onSubmit={values => submit('/api/v1/production/no-output-confirmations', {
                ...values, taskId: values.scope === 'TASK' ? taskId : undefined,
                expectedVersion: values.scope === 'TASK' ? task.version : undefined,
                workshopId: task.workshopId,
              })}/>}
        </>}

        {canExecution && <>
          <p className="detail-section-hint" style={{margin: '10px 0 0'}}>
            <button type="button" className="link-button"
              onClick={() => {setOpenForm(openForm === 'EXECUTION_RECORD' ? null : 'EXECUTION_RECORD');
                setFeedback(null);}}>登记开工/停工时间</button>
            <span style={{marginLeft: 4}}>（不影响产量）</span>
          </p>
          {openForm === 'EXECUTION_RECORD' && <FormRow fields={[
            {name: 'actualDate', label: '发生日期', type: 'date', required: true, defaultValue: today()},
            {name: 'startedAt', label: '实际开始时间', type: 'datetime-local'},
            {name: 'stoppedAt', label: '实际停止时间', type: 'datetime-local'},
            {name: 'operatorName', label: '经办人', placeholder: '默认系统管理员'},
          ]} hint="填开始即开工，之后只补停止时间；一段没停不能开下一段。" busy={busy}
            onSubmit={values => submit(`/api/v1/production/tasks/${taskId}/execution-records`, {
                ...values, expectedVersion: task.version,
                startedAt: toOffset(values.startedAt), stoppedAt: toOffset(values.stoppedAt),
              })}/>}
        </>}
      </div>

      {task.executions.length > 0 && <div className="ops-form" style={{paddingTop: 0}}>
        <p className="detail-section-title" style={{marginTop: 16}}>开工/停工记录</p>
        <div className="table-region"><table style={{minWidth: 480}}>
          <thead><tr><th>实际日</th><th>起止</th><th>经办人</th></tr></thead>
          <tbody>
            {task.executions.map(execution =>
              <tr key={execution.recordId}>
                <td>{execution.actualDate}</td>
                <td>{trimTime(execution.startedAt) ?? '未登记'} ~ {trimTime(execution.stoppedAt) ?? '未登记'}</td>
                <td>{execution.operatorName}</td>
              </tr>)}
          </tbody>
        </table></div>
      </div>}
    </>}
  </section>;
}

const ACTION_LABELS: Record<string, string> = {
  EXECUTION_RECORD: '登记开工/停工时间', PLATE_INPUT: '记录铜板投入', ROD_OUTPUT: '铜杆称重',
  ROD_HANDOVER: '记录铜杆交接', WIRE_OUTPUT: '铜丝称重', NO_OUTPUT_CONFIRMATION: '确认今天没有产出',
  TASK_COMPLETION: '结束本任务',
};

export const MATERIAL_LABELS: Record<string, string> = {
  '1': '铜板', '2': '直径 8mm 铜杆', '3': '直径 2.6mm 铜丝',
};

function parseAllocations(text: string | undefined): {orderId: string; quantityTon: string}[] {
  if (!text?.trim()) return [];
  return text.split(/[,，]/).map(chunk => chunk.trim()).filter(Boolean).map(chunk => {
    const [orderId, quantityTon] = chunk.split(':');
    return {orderId: String(orderId ?? '').trim(), quantityTon: String(quantityTon ?? '').trim()};
  }).filter(item => item.orderId && item.quantityTon);
}

function toOffset(value: string | undefined): string | null {
  if (!value) return null;
  // datetime-local 无时区标记：按业务时区 Asia/Shanghai 提交。
  return `${value}:00+08:00`;
}

function trimTime(value: string | null): string | null {
  return value ? value.replace('T', ' ').slice(0, 16) : null;
}

