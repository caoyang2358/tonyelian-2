import {useCallback, useState, type ReactNode} from 'react';
import {useResource} from '../../lib/useResource';
import {
  RECORD_TYPES, RECORD_TYPE_LABELS, fetchDayStatus, fetchRecordDetail, fetchRecords,
  fetchTasks, newRequestId, postProduction, type TaskSummary,
  type RecordDetailPayload, type TimelineItem,
} from '../../lib/productionApi';
import {SaveFeedback, errorFeedback, workshopName} from './shared';
import {MATERIAL_LABELS} from './TasksTab';

const today = () => new Date().toLocaleDateString('sv-SE', {timeZone: 'Asia/Shanghai'});

export function RecordsTab() {
  const [actualFrom, setActualFrom] = useState(today());
  const [actualTo, setActualTo] = useState(today());
  const [workshopId, setWorkshopId] = useState('');
  const [recordType, setRecordType] = useState('');
  const [source, setSource] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [unplannedOpen, setUnplannedOpen] = useState(false);
  const [dayCheckOpen, setDayCheckOpen] = useState(false);
  const [feedback, setFeedback] = useState<SaveFeedback | null>(null);

  const load = useCallback((signal: AbortSignal) => fetchRecords({
    actualFrom, actualTo, workshopId: workshopId || undefined,
    recordType: recordType || undefined, source: source || undefined,
  }, signal), [actualFrom, actualTo, workshopId, recordType, source]);
  const records = useResource(load);

  // 开工停工记录不单独占一行：同一任务、同一实际日已有产出记录时，挂到那行下面当小字。
  const items = records.data?.items ?? [];
  const execByTaskDay = new Map<string, TimelineItem[]>();
  for (const item of items) {
    if (item.recordType !== 'EXECUTION' || !item.taskId) continue;
    const key = `${item.taskId}|${item.actualDate}`;
    execByTaskDay.set(key, [...(execByTaskDay.get(key) ?? []), item]);
  }
  const hasOutputOnSameDay = (item: TimelineItem) => items.some(other =>
    other.recordType !== 'EXECUTION' && other.taskId === item.taskId
    && other.actualDate === item.actualDate);
  const toggle = (key: string) => setExpanded(expanded === key ? null : key);
  const timelineRows: ReactNode[] = [];
  const attached = new Set<string>(); // 同一任务同一天只挂一次，避免多次称重时重复出现
  for (const item of items) {
    const key = `${item.recordType}-${item.recordId}`;
    if (item.recordType === 'EXECUTION' && item.taskId && hasOutputOnSameDay(item)) {
      continue; // 由产出记录行的“类型”格小字展示
    }
    let executions: TimelineItem[] = [];
    if (item.recordType !== 'EXECUTION' && item.taskId) {
      const taskDay = `${item.taskId}|${item.actualDate}`;
      if (!attached.has(taskDay)) {
        const subs = execByTaskDay.get(taskDay) ?? [];
        if (subs.length) {
          attached.add(taskDay);
          executions = subs;
        }
      }
    }
    timelineRows.push(<TimelineRow key={key} item={item} executions={executions}
      expandedKey={expanded} onExecutionToggle={toggle}
      expanded={expanded === key} onToggle={() => toggle(key)}
      onReload={() => {setExpanded(null); records.reload();}}/>);
  }

  return <>
    <section className="panel" aria-label="实际记录查询">
      <div className="panel-heading"><div>
        <h2>实际记录<small className="panel-count">{records.data?.total ?? 0} 条</small></h2>
      </div>
        <div style={{display: 'flex', gap: 8}}>
          <button type="button" className="button" onClick={() => {
            setUnplannedOpen(value => !value); setDayCheckOpen(false); setFeedback(null);
          }}>计划外生产</button>
          <button type="button" className="button" onClick={() => {
            setDayCheckOpen(value => !value); setUnplannedOpen(false); setFeedback(null);
          }}>核对当日记录</button>
        </div>
      </div>
      <div className="filters" style={{paddingBottom: 16}}>
        <label className="select-field">实际日期从
          <input type="date" value={actualFrom} onChange={event => setActualFrom(event.target.value)}
            style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
        </label>
        <label className="select-field">至
          <input type="date" value={actualTo} onChange={event => setActualTo(event.target.value)}
            style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
        </label>
        <label className="select-field">车间
          <select value={workshopId} onChange={event => setWorkshopId(event.target.value)}>
            <option value="">全部车间</option>
            <option value="1">车间1 · 铜杆</option>
            <option value="2">车间2 · 铜丝</option>
          </select>
        </label>
        <label className="select-field">类型
          <select value={recordType} onChange={event => setRecordType(event.target.value)}>
            <option value="">全部类型</option>
            {RECORD_TYPES.map(value => <option key={value} value={value}>{RECORD_TYPE_LABELS[value]}</option>)}
          </select>
        </label>
        <label className="select-field">来源
          <select value={source} onChange={event => setSource(event.target.value)}>
            <option value="">全部来源</option>
            <option value="PLANNED">计划内</option>
            <option value="UNPLANNED">计划外</option>
          </select>
        </label>
      </div>
      <div className="table-region"><table>
        <thead><tr>
          <th>类型</th><th>实际日</th><th>录入时间</th><th>车间</th><th>数量</th>
          <th>来源</th><th>经办人</th>
        </tr></thead>
        <tbody>
          {records.loading && <tr><td colSpan={7}>读取中…</td></tr>}
          {records.error && <tr><td colSpan={7}>{records.error}</td></tr>}
          {!records.loading && !records.error && timelineRows}
          {!records.loading && !records.error && !items.length &&
            <tr><td colSpan={7}>所选条件下暂无实际记录。</td></tr>}
        </tbody>
      </table></div>
    </section>
    {unplannedOpen && <UnplannedPanel onClose={() => setUnplannedOpen(false)}
      onSaved={message => {setUnplannedOpen(false); setFeedback({ok: true, message}); records.reload();}}/>}
    {dayCheckOpen && <DayCheckPanel onClose={() => setDayCheckOpen(false)}
      workshopId={workshopId} actualDate={actualTo}/>}
    {feedback && <SaveFeedback feedback={feedback}/>}
  </>;
}

/** 开工停工小字文本。 */
function executionText(execution: TimelineItem): string {
  const start = execution.startedAt?.slice(11, 16);
  const stop = execution.stoppedAt?.slice(11, 16);
  return start && stop ? `开工 ${start} → 停工 ${stop}`
    : start ? `开工 ${start} · 未停工`
      : stop ? `任务结束 ${stop}` : '未填时间';
}

function TimelineRow({item, executions = [], expandedKey, onExecutionToggle, expanded, onToggle, onReload}:
  {item: TimelineItem; executions?: TimelineItem[]; expandedKey?: string | null;
   onExecutionToggle?: (key: string) => void; expanded: boolean; onToggle: () => void;
   onReload?: () => void}) {
  const quantity: ReactNode = item.recordType === 'EXECUTION' ? <span className="missing-value">—</span>
    : item.recordType === 'ROD_HANDOVER'
      ? `${item.sentQuantityTon ?? '—'} 吨（实收 ${item.receivedQuantityTon ?? '待接收'}）`
      : item.recordType === 'NO_OUTPUT' ? '0.000 吨（明确零）'
        : `${item.quantityTon ?? '—'} 吨`;
  return <>
    <tr onClick={onToggle} style={{cursor: 'pointer'}} className={expanded ? 'ops-selected' : ''}>
      <td>{RECORD_TYPE_LABELS[item.recordType]}
        {executions.map(execution => <div key={execution.recordId}
          onClick={event => {event.stopPropagation();
            onExecutionToggle?.(`${execution.recordType}-${execution.recordId}`);}}
          style={{fontSize: 10, color: '#a0aa95', marginTop: 4, cursor: 'pointer',
            fontVariantNumeric: 'tabular-nums'}}>{executionText(execution)}</div>)}
      </td>
      <td>{item.actualDate}</td>
      <td>{item.recordedAt?.replace('T', ' ').slice(0, 16) ?? '—'}</td>
      <td>{workshopName(item.workshopId)}</td>
      <td>{item.voidedAt ? <span className="ops-tag ops-tag-red">已作废</span> : quantity}</td>
      <td><span className={`ops-tag ${item.source === 'UNPLANNED' ? 'ops-tag-amber' : 'ops-tag-blue'}`}>
        {item.source === 'UNPLANNED' ? '计划外' : '计划内'}</span></td>
      <td>{item.operatorName}</td>
    </tr>
    {expanded && <tr><td colSpan={7} style={{height: 'auto'}}>
      <RecordDetailInline recordType={item.recordType} recordId={item.recordId} onReload={onReload}/>
    </td></tr>}
    {executions.filter(execution => expandedKey === `${execution.recordType}-${execution.recordId}`)
      .map(execution => <tr key={`detail-${execution.recordId}`}><td colSpan={7} style={{height: 'auto'}}>
        <RecordDetailInline recordType={execution.recordType} recordId={execution.recordId}
          onReload={onReload}/>
      </td></tr>)}
  </>;
}

/** 记录详情字段的中文展示名。 */
const DETAIL_FIELD_LABELS: Record<string, string> = {
  actualDate: '发生日期', inputQuantityTon: '投料数量（吨）', actualQuantityTon: '投料数量（吨）',
  qualifiedQuantityTon: '合格产量（吨）',
  sentQuantityTon: '交出重量（吨）', receivedQuantityTon: '实收重量（吨）',
  sentAt: '交出时间', receivedAt: '接收时间', operatorName: '经办人',
  taskId: '任务编号', orderId: '订单编号', notes: '备注', reason: '原因',
  materialAmountYuan: '材料金额（元）', startedAt: '开工时间', stoppedAt: '停工时间',
  handoverState: '交接状态', errorRate: '误差率', confirmedBy: '确认人',
  workshopId: '车间', materialId: '产品', source: '来源', releaseReason: '放行原因',
};

/** 交接状态的中文展示名。 */
const HANDOVER_STATE_LABELS: Record<string, string> = {
  PENDING_RECEIPT: '待对方接收', CONFIRMED: '已确认接收', RELEASED: '超差放行',
};

/** 详情字段值的中文化（车间、产品等存的是编号）。 */
function detailValue(key: string, value: unknown): string {
  if (key === 'workshopId') return workshopName(String(value));
  if (key === 'materialId') {
    return MATERIAL_LABELS[String(value)] ?? String(value);
  }
  return String(value);
}

function RecordDetailInline({recordType, recordId, onReload}:
  {recordType: string; recordId: string; onReload?: () => void}) {
  const load = useCallback((signal: AbortSignal) => fetchRecordDetail(recordType, recordId, signal),
    [recordType, recordId]);
  const detail = useResource(load);
  if (detail.loading) return <p>读取中…</p>;
  if (detail.error || !detail.data) {
    // 记录被删/作废后列表还是旧的，点开就会 404；这种情况提示刷新，而不是甩一句"不存在"。
    const message = detail.error ?? '';
    if (message && !message.includes('不存在')) return <p>{message}</p>;
    return <p style={{padding: '6px 4px 10px', margin: 0}}>
      该记录已不存在，可能刚被删除或作废，列表已不是最新。
      {onReload && <button type="button" className="button"
        style={{marginLeft: 8, height: 26, padding: '0 10px', fontSize: 11}}
        onClick={onReload}>刷新列表</button>}
    </p>;
  }
  const data = detail.data;
  return <div style={{padding: '6px 4px 10px'}}>
    <dl className="ops-detail" style={{gridTemplateColumns: 'repeat(3, minmax(0,1fr))'}}>
      {Object.entries(data.current ?? {})
        .filter(([key, value]) => value != null && value !== '' && key !== 'recordId' && key !== 'requestId')
        .slice(0, 12)
        .map(([key, value]) => <div key={key}><dt>{DETAIL_FIELD_LABELS[key] ?? key}</dt>
          <dd>{detailValue(key, value)}</dd></div>)}
    </dl>
    {data.allocations?.length ? <p style={{fontSize: 11, color: '#5c6b57', margin: '4px 0'}}>
      订单归属：{data.allocations.map(a => `${a.orderNo} ${a.quantityTon} 吨`).join('、')}
    </p> : null}
    {data.handoverState && <p style={{fontSize: 11, color: '#5c6b57', margin: '4px 0'}}>
      交接状态：{HANDOVER_STATE_LABELS[data.handoverState] ?? data.handoverState}
      {data.errorRate ? ` · 误差率 ${(Number(data.errorRate) * 100).toFixed(2)}%` : ''}
    </p>}
    <p style={{fontSize: 10, color: '#9aa294', margin: '8px 0 0'}}>
      更正历史 {data.corrections.length} 条
      {data.corrections.length > 0 && <>　最近：{data.corrections[0].operatorName} ·
        {data.corrections[0].reason ?? '（无原因记录）'}</>}
    </p>
    <HandoverReceiveInline recordId={recordId}
      pending={recordType === 'ROD_HANDOVER' && (data as RecordDetailPayload).handoverState === 'PENDING_RECEIPT'}/>
    {recordType === 'PLATE_INPUT'
      && data.missingItems.some(text => text.includes('材料金额'))
      && <PlateAmountInline recordId={recordId} version={data.version}/>}
  </div>;
}

/** 投料记录缺材料金额时的补登表单（仅金额更正，不影响库存数量）。 */
function PlateAmountInline({recordId, version}: {recordId: string; version: number}) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<SaveFeedback | null>(null);
  return <form className="ops-form" style={{paddingTop: 10}} onSubmit={async event => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const amount = String(data.get('materialAmountYuan') ?? '').trim();
    const reason = String(data.get('reason') ?? '').trim();
    const operator = String(data.get('operatorName') ?? '').trim();
    if (!amount || !reason) {
      setMessage({ok: false, message: '请填写金额与原因。'});
      return;
    }
    setBusy(true); setMessage(null);
    try {
      await postProduction(`/api/v1/production/plate-inputs/${recordId}/corrections`, {
        requestId: newRequestId(), operatorName: operator || '系统管理员',
        expectedVersion: version, materialAmountYuan: amount, reason,
      });
      setMessage({ok: true, message: '材料金额已补登，成本核算不再缺项。'});
    } catch (error) {
      setMessage(errorFeedback(error));
    } finally {
      setBusy(false);
    }
  }}>
    <div className="ops-form-row">
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>材料金额（元）</span>
        <input name="materialAmountYuan" placeholder="如 28350.00"
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
      </label>
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>原因</span>
        <input name="reason" defaultValue="补登材料金额"
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px', minWidth: 160}}/>
      </label>
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>经办人</span>
        <input name="operatorName" placeholder="默认系统管理员"
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
      </label>
    </div>
    <div className="ops-form-actions">
      <button type="submit" className="button button-primary" disabled={busy}>
        {busy ? '提交中…' : '补登金额'}</button>
      {message && <SaveFeedback feedback={message}/>}
    </div>
  </form>;
}

/** 待接收交接的补录接收入口（S2-R11）。 */
function HandoverReceiveInline({recordId, pending}: {
  recordId: string; pending: boolean;
}) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  if (!pending) return null;
  return <form className="ops-form" style={{paddingTop: 10}} onSubmit={async event => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setBusy(true); setMessage(null);
    const reason = String(data.get('reason') ?? '').trim() || '补录接收过磅记录';
    try {
      await postProduction(`/api/v1/production/rod-handovers/${recordId}/corrections`, {
        requestId: newRequestId(), operatorName: String(data.get('operatorName') || '系统管理员'),
        expectedVersion: 0,
        receivedQuantityTon: String(data.get('receivedQuantityTon') ?? ''),
        receivedAt: `${data.get('receivedAt')}:00+08:00`,
        reason,
        // 补录实收也可能超差 5%：原因即放行依据，一并提交。
        releaseReason: reason,
        releaseConfirmed: true,
      });
      setMessage('接收已补录，同一交接不会再重复扣减库存。');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '补录失败。');
    } finally {
      setBusy(false);
    }
  }}>
    <div className="ops-form-row">
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>实收重量（吨）</span>
        <input name="receivedQuantityTon" required
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
      </label>
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>接收时间</span>
        <input name="receivedAt" type="datetime-local" required
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
      </label>
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>经办人</span>
        <input name="operatorName" defaultValue="系统管理员"
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
      </label>
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>原因（误差超 5% 时必填）</span>
        <input name="reason" placeholder="如补录过磅记录"
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px', minWidth: 200}}/>
      </label>
    </div>
    <div className="ops-form-actions">
      <button type="submit" className="button button-primary" disabled={busy}>
        {busy ? '提交中…' : '补录接收'}</button>
      {message && <small style={{color: '#5c6b57'}}>{message}</small>}
    </div>
  </form>;
}

/** 计划外生产补录：每次登记一条独立记录，供没有计划任务的生产补录使用。 */
function UnplannedPanel({onClose, onSaved}: {onClose: () => void; onSaved: (message: string) => void}) {
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<SaveFeedback | null>(null);
  // 产品自行选择，但只列出该车间的合法产出，保证不会选错。
  const [recordType, setRecordType] = useState('PLATE_INPUT');
  const [workshopId, setWorkshopId] = useState('1');
  const [materialId, setMaterialId] = useState('2');
  const typeOptions: [string, string][] = [
    ['PLATE_INPUT', '铜板投料（车间1）'], ['ROD_OUTPUT', '铜杆称重（车间1）'],
    ['ROD_HANDOVER', '接收铜杆（车间2 · 领取车间1交来的杆）'], ['WIRE_OUTPUT', '铜丝称重（车间2）'],
  ];
  const typeWorkshop: Record<string, '1' | '2'> = {
    PLATE_INPUT: '1', ROD_OUTPUT: '1', ROD_HANDOVER: '2', WIRE_OUTPUT: '2',
  };
  const productOptions: [string, string][] = workshopId === '1'
    ? [['2', '直径 8mm 铜杆']]
    : [['3', '直径 2.6mm 铜丝']];
  // 每个登记事项都填计划与实际，名称跟随事项；差额实时显示，和任务页口径一致。
  const [plannedTon, setPlannedTon] = useState('');
  const [actualTon, setActualTon] = useState('');
  const pairName = recordType === 'PLATE_INPUT' ? '投料量'
    : recordType === 'ROD_HANDOVER' ? '交出量' : '合格量';
  const variance = plannedTon.trim() && actualTon.trim()
    && Number.isFinite(Number(plannedTon)) && Number.isFinite(Number(actualTon))
    ? Number(actualTon) - Number(plannedTon) : null;

  return <section className="panel" aria-label="计划外生产补录">
    <div className="panel-heading"><div>
      <h2>计划外生产<small className="panel-count">补录</small></h2>
      <p>登记没有对应计划任务的实际生产。</p>
    </div>
      <button type="button" className="button" onClick={onClose}>收起</button>
    </div>
    {feedback && <div style={{padding: '0 23px 10px'}}><SaveFeedback feedback={feedback}/></div>}
    <form className="ops-form" style={{paddingTop: 8}} onSubmit={async event => {
      event.preventDefault();
      const data = new FormData(event.currentTarget);
      const actualDate = String(data.get('actualDate') ?? '').trim();
      const quantity = actualTon.trim();
      const amount = String(data.get('materialAmountYuan') ?? '').trim();
      const userReason = String(data.get('reason') ?? '').trim();
      const orderId = String(data.get('orderId') ?? '').trim();
      const pickedWorkshop = String(data.get('workshopId') ?? workshopId);
      if (!actualDate || !userReason || !quantity) {
        setFeedback({ok: false, message: '请填写日期、实际数量与原因。'});
        return;
      }
      // 计划值没有独立字段，随原因一起留痕，便于事后对照。
      const reason = plannedTon.trim()
        ? `${userReason}（计划 ${plannedTon.trim()} 吨，实际 ${quantity} 吨）` : userReason;
      setBusy(true); setFeedback(null);
      const fact: Record<string, unknown> = {actualDate};
      if (recordType === 'PLATE_INPUT') {
        fact.inputQuantityTon = quantity;
        if (amount) fact.materialAmountYuan = amount;
      } else if (recordType === 'ROD_OUTPUT') {
        fact.qualifiedQuantityTon = quantity;
      } else if (recordType === 'ROD_HANDOVER') {
        fact.sentQuantityTon = quantity;
        fact.sentAt = `${actualDate}T08:00:00+08:00`;
        if (orderId) fact.orderId = orderId;
      } else if (recordType === 'WIRE_OUTPUT') {
        fact.qualifiedQuantityTon = quantity;
        fact.allocations = orderId ? [{orderId, quantityTon: quantity}] : [];
      }
      try {
        await postProduction('/api/v1/production/unplanned-records', {
          requestId: newRequestId(),
          recordType, workshopId: pickedWorkshop, materialId,
          anchorActualDate: actualDate,
          operatorName: String(data.get('operatorName') ?? '').trim() || '系统管理员',
          reason, fact,
        });
        onSaved('登记成功。');
      } catch (requestError) {
        setFeedback(errorFeedback(requestError));
      } finally {
        setBusy(false);
      }
    }}>
      <div className="ops-form-row">
        <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
          <span>登记事项</span>
          <select value={recordType} onChange={event => {
            const next = event.target.value;
            setRecordType(next);
            setPlannedTon(''); setActualTon('');
            const ws = typeWorkshop[next];
            if (ws) {setWorkshopId(ws); setMaterialId(ws === '1' ? '2' : '3');}
          }}>
            {typeOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
          <span>产品</span>
          <select value={materialId} onChange={event => setMaterialId(event.target.value)}>
            {productOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
          <span>发生日期</span>
          <input name="actualDate" type="date" required defaultValue={today()}
            style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
        </label>
        <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
          <span>计划{pairName}（吨）</span>
          <input value={plannedTon} onChange={event => setPlannedTon(event.target.value)}
            placeholder="如 6.300"
            style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
        </label>
        <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
          <span>实际{pairName}（吨）</span>
          <input value={actualTon} onChange={event => setActualTon(event.target.value)} required
            placeholder="如 6.300"
            style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
        </label>
        {variance != null && <span className={variance < 0 ? 'ops-tag ops-tag-amber' : 'ops-tag ops-tag-green'}
          style={{height: 36, display: 'inline-flex', alignItems: 'center'}}>
          差额 {variance > 0 ? '+' : ''}{variance.toFixed(3)} 吨</span>}
        {recordType === 'PLATE_INPUT' && <label className="select-field"
          style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
          <span>材料金额（元，可空）</span>
          <input name="materialAmountYuan" placeholder="这批铜板的总金额"
            style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
        </label>}
        <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
          <span>关联订单（可空）</span>
          <input name="orderId"
            style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
        </label>
        <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
          <span>原因</span>
          <input name="reason" required placeholder="如补录当天实际发生的投料"
            style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px', minWidth: 180}}/>
        </label>
        <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
          <span>经办人</span>
          <input name="operatorName" placeholder="默认系统管理员"
            style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
        </label>
      </div>
      <div className="ops-form-actions">
        <button type="submit" className="button button-primary" disabled={busy}>
          {busy ? '保存中…' : '保存'}</button>
      </div>
    </form>
  </section>;
}

/** 当日录全核对（S2-R18/R19）。车间和日期直接取上方筛选，不再重复一套选择器。 */
function DayCheckPanel({onClose, workshopId, actualDate}:
  {onClose: () => void; workshopId: string; actualDate: string}) {
  // 上方筛选“全部车间”时两个车间各核一次，避免漏掉一边。
  const workshops = workshopId ? [workshopId] : ['1', '2'];
  return <section className="panel" aria-label="核对当日记录">
    <div className="panel-heading"><div>
      <h2>核对当日记录<small className="panel-count">
        {workshopId ? workshopName(workshopId) : '全部车间'} · {actualDate}</small></h2>
    </div>
      <button type="button" className="button" onClick={onClose}>收起</button>
    </div>
    {workshops.map(id => <DayCheckBlock key={id} workshopId={id} actualDate={actualDate}/>)}
  </section>;
}

/** 一个车间的当日核对：指标 + 当天记录清单 + 补称重 + 确认。 */
function DayCheckBlock({workshopId, actualDate}: {workshopId: string; actualDate: string}) {
  const load = useCallback((signal: AbortSignal) => fetchDayStatus(workshopId, actualDate, signal),
    [workshopId, actualDate]);
  const status = useResource(load);
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<SaveFeedback | null>(null);
  const data = status.data;

  // 当天该车间已登记的记录清单，让人看到系统收到了什么。
  const dayRecords = useResource(useCallback(
    (signal: AbortSignal) => fetchRecords(
      {actualFrom: actualDate, actualTo: actualDate, workshopId, page: 1}, signal),
    [actualDate, workshopId]));

  const confirm = useCallback(async () => {
    if (!data) return;
    setBusy(true); setFeedback(null);
    try {
      await postProduction(
        `/api/v1/production/workshops/${workshopId}/days/${actualDate}/record-completions`,
        {requestId: newRequestId(), operatorName: '系统管理员',
          expectedVersion: data.version, expectedSourceRevision: data.sourceRevision});
      setFeedback({ok: true, message: '已确认当日记录完整；之后补录或更正会使该确认自动失效。'});
      status.reload();
    } catch (error) {
      setFeedback({ok: false,
        message: error instanceof Error ? error.message : '确认失败。'});
    } finally {
      setBusy(false);
    }
  }, [data, workshopId, actualDate, status]);

  return <div style={{borderTop: '1px solid #eef1e9'}}>
    <p style={{padding: '10px 23px 0', margin: 0, fontSize: 12, color: '#354c39'}}>
      <strong>{workshopName(workshopId)}</strong></p>
    {status.loading && <p style={{padding: '0 23px 20px'}}>读取中…</p>}
    {status.error && <p style={{padding: '0 23px 20px'}}>{status.error}</p>}
    {data && <>
      <div className="ops-detail" style={{display: 'grid',
        gridTemplateColumns: 'repeat(4, minmax(0,1fr))', gap: '12px 26px', padding: '4px 23px 0'}}>
        <div><dt>当日合格产量</dt><dd><strong>{data.knownQualifiedQuantityTon ?? '0.000'} 吨</strong></dd></div>
        <div><dt>当日记录条数</dt><dd><strong>
          {(dayRecords.data?.items ?? []).filter(item => !item.voidedAt && item.workshopId === workshopId).length}
        </strong></dd></div>
        <div><dt>核对结果</dt><dd>{data.recordsComplete
          ? <span className="ops-tag ops-tag-green">已确认完整</span>
          : <span className="ops-tag ops-tag-amber">尚未确认完整</span>}</dd></div>
        <div><dt>确认人</dt><dd>{data.confirmedBy
          ? `${data.confirmedBy} · ${data.confirmedAt?.replace('T', ' ').slice(0, 16) ?? ''}`
          : '尚未确认'}</dd></div>
      </div>
      {dayRecords.loading && <p style={{padding: '10px 23px 0', fontSize: 11, color: '#5c6b57'}}>
        正在读取当天记录…</p>}
      {dayRecords.data && <div style={{padding: '10px 23px 0'}}>
        <p style={{fontSize: 12, color: '#354c39', margin: '0 0 6px'}}>
          <strong>当天已登记 {dayRecords.data.total} 条记录</strong>（计划内、计划外都算在内）
          {dayRecords.data.total === 0 && '——还没有任何投料、称重或交接。'}</p>
        {dayRecords.data.items.length > 0 && <ul style={{margin: 0, paddingLeft: 18, fontSize: 11}}>
          {dayRecords.data.items.map(item => {
            const quantity = item.quantityTon || item.sentQuantityTon || item.receivedQuantityTon;
            return <li key={`${item.recordType}-${item.recordId}`} style={{margin: '2px 0'}}>
              {item.actualDate} · {RECORD_TYPE_LABELS[item.recordType] ?? item.recordType}
              {quantity ? ` · ${quantity} 吨` : ''}
              {' '}<span className={`ops-tag ${item.source === 'UNPLANNED' ? 'ops-tag-amber' : 'ops-tag-blue'}`}>
                {item.source === 'UNPLANNED' ? '计划外' : '计划内'}</span>
              {item.voidedAt ? '（已作废）' : ''} · {item.operatorName}
            </li>;
          })}
        </ul>}
      </div>}
      <DayWeighInline workshopId={workshopId} actualDate={actualDate}
        onDone={() => {status.reload(); dayRecords.reload();}}/>
      {data.blockingItems.length > 0 && <div style={{padding: '10px 23px 4px'}}>
        {data.blockingItems.map(item =>
          <p key={item.code} style={{fontSize: 11, color: '#a04a34', margin: '4px 0'}}>
            ⚠ {item.message}</p>)}
      </div>}
      {data.blockingItems.some(item => item.code === 'ZERO_CONFIRMATION_MISSING')
        && <DayNoOutputInline workshopId={workshopId} actualDate={actualDate}
          onDone={() => {status.reload(); dayRecords.reload();}}/>}
      {data.missingItems.length > 0 && <div style={{padding: '4px 23px'}}>
        {data.missingItems.map(item =>
          <p key={item.code} style={{fontSize: 11, color: '#8a6a1f', margin: '4px 0'}}>
            {item.message}</p>)}
      </div>}
      <div className="ops-form" style={{paddingTop: 10}}>
        <div className="ops-form-actions">
          <button type="button" className="button button-primary" disabled={busy
            || data.blockingItems.length > 0 || data.recordsComplete} onClick={confirm}>
            {busy ? '提交中…' : data.recordsComplete ? '已确认完整' : '确认当日记录完整'}
          </button>
        </div>
        {feedback && <SaveFeedback feedback={feedback}/>}
      </div>
    </>}
  </div>;
}

/** 当天计划内任务的称重登记入口：直接登记实际合格量，不用再去任务页找。 */
function DayWeighInline({workshopId, actualDate, onDone}: {
  workshopId: string; actualDate: string; onDone: () => void;
}) {
  const load = useCallback((signal: AbortSignal) => fetchTasks(
    {date: actualDate, workshopId, page: 1}, signal), [actualDate, workshopId]);
  const tasks = useResource(load);
  // 已结束的任务不能再称重，明确零产出的也不用再称，其余任务当天都允许继续登记称重。
  const pending = (tasks.data?.items ?? [])
    .filter(task => task.weighingState !== 'CONFIRMED_ZERO' && task.executionState !== 'COMPLETED');
  if (tasks.loading || tasks.error) return null;
  if (!pending.length) {
    return <p style={{padding: '10px 23px 0', fontSize: 12, color: '#5c6b57', margin: 0}}>
      当天没有可继续称重的计划任务；有实际生产请用「计划外生产」补录称重。</p>;
  }
  return <div style={{padding: '10px 23px 0'}}>
    <p style={{fontSize: 12, color: '#354c39', margin: '0 0 4px'}}>
      <strong>计划内任务称重</strong>——当天有 {pending.length} 个任务可继续登记称重（已称过的也可再称）：</p>
    {pending.map(task => <DayWeighForm key={task.taskId} task={task} actualDate={actualDate}
      onDone={() => {onDone(); tasks.reload();}}/>)}
  </div>;
}

function DayWeighForm({task, actualDate, onDone}: {
  task: TaskSummary; actualDate: string; onDone: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<SaveFeedback | null>(null);
  const rodWorkshop = task.workshopId === '1';
  return <form className="ops-form" style={{paddingTop: 6}} onSubmit={async event => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const actualDate2 = String(data.get('actualDate') ?? '').trim();
    const quantity = String(data.get('qualifiedQuantityTon') ?? '').trim();
    const operator = String(data.get('operatorName') ?? '').trim();
    if (!actualDate2 || !quantity) {
      setMessage({ok: false, message: '请填写日期与合格产量。'});
      return;
    }
    setBusy(true); setMessage(null);
    try {
      let warning: string | undefined;
      if (rodWorkshop) {
        const result = await postProduction(`/api/v1/production/tasks/${task.taskId}/rod-outputs`, {
          requestId: newRequestId(), operatorName: operator || '系统管理员',
          actualDate: actualDate2, qualifiedQuantityTon: quantity, expectedVersion: task.version,
        }) as {overPlanWarning?: string} | undefined;
        warning = result?.overPlanWarning;
      } else {
        const result = await postProduction('/api/v1/production/wire-outputs', {
          requestId: newRequestId(), operatorName: operator || '系统管理员',
          taskIds: [task.taskId],
          expectedVersions: {tasks: [{id: task.taskId, version: task.version}]},
          materialId: task.materialId, actualDate: actualDate2,
          qualifiedQuantityTon: quantity, allocations: [],
        }) as {overPlanWarning?: string} | undefined;
        warning = result?.overPlanWarning;
      }
      setMessage({ok: true, message: warning ? `称重已登记。${warning}` : '称重已登记。'});
      onDone();
    } catch (error) {
      setMessage(errorFeedback(error));
    } finally {
      setBusy(false);
    }
  }}>
    <div className="ops-form-row" style={{alignItems: 'flex-end'}}>
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>任务</span>
        <input disabled value={`第 ${task.sequenceNo} 号 · 计划 ${task.plannedQualifiedTon ?? '—'} 吨`
          + (task.actualQualifiedTon != null ? ` · 已称 ${task.actualQualifiedTon} 吨` : '')}
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px',
            minWidth: 220, background: '#f4f6f1'}}/>
      </label>
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>发生日期</span>
        <input name="actualDate" type="date" required defaultValue={actualDate}
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
      </label>
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>本次合格产量（吨）</span>
        <input name="qualifiedQuantityTon" required placeholder="如 6.300"
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
      </label>
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>经办人</span>
        <input name="operatorName" placeholder="默认系统管理员"
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
      </label>
      <button type="submit" className="button button-primary" disabled={busy}
        style={{height: 36}}>{busy ? '提交中…' : '登记称重'}</button>
      {message && <SaveFeedback feedback={message}/>}
    </div>
  </form>;
}

/** 当天确实没有产出时的整日零产出确认（解除核对阻塞）。 */
function DayNoOutputInline({workshopId, actualDate, onDone}: {
  workshopId: string; actualDate: string; onDone: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<SaveFeedback | null>(null);
  return <form className="ops-form" style={{paddingTop: 4}} onSubmit={async event => {
    event.preventDefault();
    const formData = new FormData(event.currentTarget);
    const reason = String(formData.get('reason') ?? '').trim();
    const operator = String(formData.get('operatorName') ?? '').trim();
    if (!reason) {
      setMessage({ok: false, message: '请填写确认原因。'});
      return;
    }
    setBusy(true); setMessage(null);
    try {
      await postProduction('/api/v1/production/no-output-confirmations', {
        requestId: newRequestId(), operatorName: operator || '系统管理员',
        scope: 'WORKSHOP_DAY', workshopId, actualDate, reason,
      });
      setMessage({ok: true, message: '已确认当天无合格产出，阻塞已解除。'});
      onDone();
    } catch (error) {
      setMessage(errorFeedback(error));
    } finally {
      setBusy(false);
    }
  }}>
    <div className="ops-form-row">
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>确认原因</span>
        <input name="reason" required placeholder="如当天检修停工，无生产安排"
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px', minWidth: 200}}/>
      </label>
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>经办人</span>
        <input name="operatorName" placeholder="默认系统管理员"
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
      </label>
    </div>
    <div className="ops-form-actions">
      <button type="submit" className="button" disabled={busy}>
        {busy ? '提交中…' : '确认当天无合格产出'}</button>
      {message && <SaveFeedback feedback={message}/>}
    </div>
  </form>;
}
