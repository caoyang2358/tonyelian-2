import {useCallback, useState} from 'react';
import {useResource} from '../../lib/useResource';
import {
  RECORD_TYPES, RECORD_TYPE_LABELS, fetchDayStatus, fetchRecordDetail, fetchRecords,
  newRequestId, postProduction,
  type RecordDetailPayload, type TimelineItem,
} from '../../lib/productionApi';
import {FormRow, SaveFeedback, workshopName} from './shared';
import {ApiError} from '../../lib/api';

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

  return <>
    <section className="panel" aria-label="实际记录查询">
      <div className="panel-heading"><div>
        <h2>实际记录<small className="panel-count">{records.data?.total ?? 0} 条</small></h2>
        <p>按真实业务日展示计划内外事实；点击行查看原始事实与更正历史。</p>
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
          {!records.loading && !records.error && (records.data?.items ?? []).map(item =>
            <TimelineRow key={`${item.recordType}-${item.recordId}`} item={item}
              expanded={expanded === `${item.recordType}-${item.recordId}`}
              onToggle={() => {
                const key = `${item.recordType}-${item.recordId}`;
                setExpanded(expanded === key ? null : key);
              }}/>)}
          {!records.loading && !records.error && !records.data?.items.length &&
            <tr><td colSpan={7}>所选条件下暂无实际记录。</td></tr>}
        </tbody>
      </table></div>
    </section>
    {unplannedOpen && <UnplannedPanel onClose={() => setUnplannedOpen(false)}
      onSaved={message => {setUnplannedOpen(false); setFeedback({ok: true, message}); records.reload();}}/>}
    {dayCheckOpen && <DayCheckPanel onClose={() => setDayCheckOpen(false)}
      defaultDate={actualTo}/>}
    {feedback && <SaveFeedback feedback={feedback}/>}
  </>;
}

function TimelineRow({item, expanded, onToggle}: {item: TimelineItem; expanded: boolean; onToggle: () => void}) {
  const quantity = item.recordType === 'ROD_HANDOVER'
    ? `${item.sentQuantityTon ?? '—'} 吨（实收 ${item.receivedQuantityTon ?? '待接收'}）`
    : item.recordType === 'NO_OUTPUT' ? '0.000 吨（明确零）'
      : item.recordType === 'EXECUTION' ? '起止片段'
        : `${item.quantityTon ?? '—'} 吨`;
  return <>
    <tr onClick={onToggle} style={{cursor: 'pointer'}} className={expanded ? 'ops-selected' : ''}>
      <td>{RECORD_TYPE_LABELS[item.recordType]}</td>
      <td>{item.actualDate}</td>
      <td>{item.recordedAt?.replace('T', ' ').slice(0, 16) ?? '—'}</td>
      <td>{workshopName(item.workshopId)}</td>
      <td>{item.voidedAt ? <span className="ops-tag ops-tag-red">已作废</span> : quantity}</td>
      <td><span className={`ops-tag ${item.source === 'UNPLANNED' ? 'ops-tag-amber' : 'ops-tag-blue'}`}>
        {item.source === 'UNPLANNED' ? '计划外' : '计划内'}</span>
        {item.productionGroupId && <small style={{marginLeft: 5, color: '#9aa294'}}>组 {item.productionGroupId}</small>}
      </td>
      <td>{item.operatorName}</td>
    </tr>
    {expanded && <tr><td colSpan={7} style={{height: 'auto'}}>
      <RecordDetailInline recordType={item.recordType} recordId={item.recordId}/>
    </td></tr>}
  </>;
}

function RecordDetailInline({recordType, recordId}: {recordType: string; recordId: string}) {
  const load = useCallback((signal: AbortSignal) => fetchRecordDetail(recordType, recordId, signal),
    [recordType, recordId]);
  const detail = useResource(load);
  if (detail.loading) return <p>读取中…</p>;
  if (detail.error || !detail.data) return <p>{detail.error ?? '读取失败。'}</p>;
  const data = detail.data;
  return <div style={{padding: '6px 4px 10px'}}>
    <dl className="ops-detail" style={{gridTemplateColumns: 'repeat(3, minmax(0,1fr))'}}>
      {Object.entries(data.current ?? {}).filter(([, value]) => value != null).slice(0, 12)
        .map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{String(value)}</dd></div>)}
    </dl>
    {data.movement && <p style={{fontSize: 11, color: '#5c6b57', margin: '4px 0'}}>
      库存流水 {data.movement.movementId} · {data.movement.movementType} ·
      {data.movement.quantityDeltaTon} 吨 · 结余 {data.movement.physicalAfterTon} 吨
    </p>}
    {data.allocations?.length ? <p style={{fontSize: 11, color: '#5c6b57', margin: '4px 0'}}>
      订单归属：{data.allocations.map(a => `${a.orderNo} ${a.quantityTon} 吨`).join('、')}
    </p> : null}
    {data.handoverState && <p style={{fontSize: 11, color: '#5c6b57', margin: '4px 0'}}>
      交接状态 {data.handoverState}{data.errorRate ? ` · 误差率 ${(Number(data.errorRate) * 100).toFixed(2)}%` : ''}
    </p>}
    {data.missingItems.length > 0 && <p style={{fontSize: 11, color: '#8a6a1f', margin: '4px 0'}}>
      待补：{data.missingItems.join('、')}
    </p>}
    <p style={{fontSize: 10, color: '#9aa294', margin: '8px 0 0'}}>
      更正历史 {data.corrections.length} 条 · 当前版本 v{data.version}
      {data.corrections.length > 0 && <>　最近：{data.corrections[0].operatorName} ·
        {data.corrections[0].reason ?? '（无原因记录）'}</>}
    </p>
    <HandoverReceiveInline recordId={recordId}
      pending={recordType === 'ROD_HANDOVER' && (data as RecordDetailPayload).handoverState === 'PENDING_RECEIPT'}/>
  </div>;
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
    try {
      await postProduction(`/api/v1/production/rod-handovers/${recordId}/corrections`, {
        requestId: newRequestId(), operatorName: String(data.get('operatorName') || '系统管理员'),
        expectedVersion: 0,
        receivedQuantityTon: String(data.get('receivedQuantityTon') ?? ''),
        receivedAt: `${data.get('receivedAt')}:00+08:00`,
        reason: String(data.get('reason') || '补录接收过磅记录'),
      });
      setMessage('接收已补录，同一交接的库存不再重复扣减。');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '补录失败。');
    } finally {
      setBusy(false);
    }
  }}>
    <div className="ops-form-row">
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>实收重量（吨）*</span>
        <input name="receivedQuantityTon" required
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
      </label>
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>接收时间 *</span>
        <input name="receivedAt" type="datetime-local" required
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
      </label>
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>经办人</span>
        <input name="operatorName" defaultValue="系统管理员"
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
      </label>
      <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
        <span>原因（超差放行必填）</span>
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

/** 计划外生产补录（S2-R17）：首笔建组，后续同组追加。 */
function UnplannedPanel({onClose, onSaved}: {onClose: () => void; onSaved: (message: string) => void}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return <section className="panel" aria-label="计划外生产补录">
    <div className="panel-heading"><div>
      <h2>计划外生产<small className="panel-count">S2-R17</small></h2>
      <p>登记已发生但无对应已确认任务的真实事实；首笔创建归集组，后续可从记录行继续追加。</p>
    </div>
      <button type="button" className="button" onClick={onClose}>收起</button>
    </div>
    {error && <p style={{padding: '0 23px 12px', color: '#a04a34', fontSize: 11}}>{error}</p>}
    <FormRow fields={[
      {name: 'workshopId', label: '车间', type: 'select', required: true,
        options: [['1', '车间1 · 铜杆'], ['2', '车间2 · 铜丝']]},
      {name: 'recordType', label: '首笔事实类型', type: 'select', required: true,
        options: [['PLATE_INPUT', '铜板投料（车间1）'], ['ROD_OUTPUT', '铜杆称重（车间1）'],
          ['ROD_HANDOVER', '领杆交接（车间2）'], ['WIRE_OUTPUT', '铜丝称重（车间2）'],
          ['EXECUTION', '实际起止']]},
      {name: 'materialId', label: '产出产品（物料编号）', required: true,
        placeholder: '车间1 填 102（Φ8 铜杆）；车间2 填铜丝规格编号'},
      {name: 'actualDate', label: '实际业务日', type: 'date', required: true},
      {name: 'inputQuantityTon', label: '数量（投料/称重/交出吨数；起止可空）', placeholder: '如 6.300'},
      {name: 'orderId', label: '适用订单编号（可空）'},
      {name: 'reason', label: '补录原因 *', required: true, placeholder: '如补录已发生且无对应任务的投料'},
    ]} hint="首笔事实的实际日即归集组首日；跨日事实各填自己的实际日。补录原因与经办人必填。"
      busy={busy} submitLabel="创建归集并保存首笔"
      onSubmit={async values => {
        setBusy(true); setError(null);
        const quantity = values.inputQuantityTon;
        const type = values.recordType;
        const fact: Record<string, unknown> = {actualDate: values.actualDate};
        if (type === 'EXECUTION') {
          fact.notes = values.reason;
        } else if (type === 'PLATE_INPUT') {
          fact.inputQuantityTon = quantity;
        } else if (type === 'ROD_OUTPUT') {
          fact.qualifiedQuantityTon = quantity;
        } else if (type === 'ROD_HANDOVER') {
          fact.sentQuantityTon = quantity;
          fact.sentAt = `${values.actualDate}T08:00:00+08:00`;
          if (values.orderId) fact.orderId = values.orderId;
        } else if (type === 'WIRE_OUTPUT') {
          fact.qualifiedQuantityTon = quantity;
          fact.allocations = values.orderId
            ? [{orderId: values.orderId, quantityTon: quantity}] : [];
        }
        try {
          await postProduction('/api/v1/production/unplanned-records', {
            recordType: type, workshopId: values.workshopId, materialId: values.materialId,
            anchorActualDate: values.actualDate, operatorName: '系统管理员',
            reason: values.reason, fact,
          });
          onSaved('计划外事实已登记，归集组已建立；后续事实请从实际记录进入同组补录。');
        } catch (requestError) {
          setError(requestError instanceof ApiError ? requestError.message : '补录失败。');
        } finally {
          setBusy(false);
        }
      }}/>
  </section>;
}

/** 当日录全核对（S2-R18/R19）。 */
function DayCheckPanel({onClose, defaultDate}: {onClose: () => void; defaultDate: string}) {
  const [workshopId, setWorkshopId] = useState('1');
  const [actualDate, setActualDate] = useState(defaultDate || today());
  const load = useCallback((signal: AbortSignal) => fetchDayStatus(workshopId, actualDate, signal),
    [workshopId, actualDate]);
  const status = useResource(load);
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<SaveFeedback | null>(null);
  const data = status.data;

  const confirm = useCallback(async () => {
    if (!data) return;
    setBusy(true); setFeedback(null);
    try {
      await postProduction(
        `/api/v1/production/workshops/${workshopId}/days/${actualDate}/record-completions`,
        {requestId: newRequestId(), operatorName: '系统管理员',
          expectedVersion: data.version, expectedSourceRevision: data.sourceRevision});
      setFeedback({ok: true, message: '当日记录已确认录全；后续补录或更正会使该确认失效。'});
      status.reload();
    } catch (error) {
      setFeedback({ok: false,
        message: error instanceof Error ? error.message : '确认失败。'});
    } finally {
      setBusy(false);
    }
  }, [data, workshopId, actualDate, status]);

  return <section className="panel" aria-label="核对当日记录">
    <div className="panel-heading"><div>
      <h2>核对当日记录<small className="panel-count">{workshopName(workshopId)} · {actualDate}</small></h2>
      <p>声明范围为当天已经发生的计划内外生产事实；成本缺项不阻塞，但影响产量分母的事实需先补齐。</p>
    </div>
      <button type="button" className="button" onClick={onClose}>收起</button>
    </div>
    <div className="filters" style={{paddingBottom: 12}}>
      <label className="select-field">车间
        <select value={workshopId} onChange={event => setWorkshopId(event.target.value)}>
          <option value="1">车间1 · 铜杆</option>
          <option value="2">车间2 · 铜丝</option>
        </select>
      </label>
      <label className="select-field">实际日期
        <input type="date" value={actualDate} onChange={event => setActualDate(event.target.value)}
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
      </label>
    </div>
    {status.loading && <p style={{padding: '0 23px 20px'}}>读取中…</p>}
    {status.error && <p style={{padding: '0 23px 20px'}}>{status.error}</p>}
    {data && <>
      <div className="ops-detail" style={{gridTemplateColumns: 'repeat(4, minmax(0,1fr))'}}>
        <div><dt>已知合格量</dt><dd><strong>{data.knownQualifiedQuantityTon ?? '0.000'} 吨</strong></dd></div>
        <div><dt>来源修订</dt><dd>r{data.sourceRevision}</dd></div>
        <div><dt>录全状态</dt><dd>{data.recordsComplete
          ? <span className="ops-tag ops-tag-green">已确认录全</span>
          : <span className="ops-tag ops-tag-amber">尚未录全</span>}</dd></div>
        <div><dt>最后确认</dt><dd>{data.confirmedBy
          ? `${data.confirmedBy} · ${data.confirmedAt?.replace('T', ' ').slice(0, 16) ?? ''}`
          : '未确认'}</dd></div>
      </div>
      {data.blockingItems.length > 0 && <div style={{padding: '10px 23px 4px'}}>
        {data.blockingItems.map(item =>
          <p key={item.code} style={{fontSize: 11, color: '#a04a34', margin: '4px 0'}}>
            ⚠ {item.message}</p>)}
      </div>}
      {data.missingItems.length > 0 && <div style={{padding: '4px 23px'}}>
        {data.missingItems.map(item =>
          <p key={item.code} style={{fontSize: 11, color: '#8a6a1f', margin: '4px 0'}}>
            待补：{item.message}</p>)}
      </div>}
      <div className="ops-form" style={{paddingTop: 10}}>
        <div className="ops-form-actions">
          <button type="button" className="button button-primary" disabled={busy
            || data.blockingItems.length > 0 || data.recordsComplete} onClick={confirm}>
            {busy ? '提交中…' : data.recordsComplete ? '已确认录全' : '确认当日记录已录全'}
          </button>
        </div>
        {feedback && <SaveFeedback feedback={feedback}/>}
      </div>
    </>}
  </section>;
}
