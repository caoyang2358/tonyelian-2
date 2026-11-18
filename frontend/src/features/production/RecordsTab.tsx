import {useCallback, useState} from 'react';
import {useResource} from '../../lib/useResource';
import {
  RECORD_TYPES, RECORD_TYPE_LABELS, fetchDayStatus, fetchRecordDetail, fetchRecords,
  newRequestId, postProduction,
  type RecordDetailPayload, type TimelineItem,
} from '../../lib/productionApi';
import {SaveFeedback, errorFeedback, workshopName} from './shared';

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
        <p>按实际日期查看生产记录，点击行查看明细与更正历史。</p>
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

/** 记录详情字段的中文展示名。 */
const DETAIL_FIELD_LABELS: Record<string, string> = {
  actualDate: '发生日期', actualQuantityTon: '投料数量（吨）', qualifiedQuantityTon: '合格产量（吨）',
  sentQuantityTon: '交出重量（吨）', receivedQuantityTon: '实收重量（吨）',
  sentAt: '交出时间', receivedAt: '接收时间', operatorName: '经办人',
  taskId: '任务编号', orderId: '订单编号', notes: '备注', reason: '原因',
  materialAmountYuan: '材料金额（元）', startedAt: '开始时间', stoppedAt: '停止时间',
  handoverState: '交接状态', errorRate: '误差率', confirmedBy: '确认人',
  workshopId: '车间', materialId: '产品', source: '来源',
};

/** 库存流水类型的中文展示名。 */
const MOVEMENT_LABELS: Record<string, string> = {
  PRODUCTION_ISSUE: '生产投料出库', QUALIFIED_OUTPUT: '合格产出入库', ROD_OUTPUT: '铜杆入库',
  WIRE_OUTPUT: '铜丝入库', CORRECTION: '更正调整', OPENING: '期初库存',
};

function RecordDetailInline({recordType, recordId}: {recordType: string; recordId: string}) {
  const load = useCallback((signal: AbortSignal) => fetchRecordDetail(recordType, recordId, signal),
    [recordType, recordId]);
  const detail = useResource(load);
  if (detail.loading) return <p>读取中…</p>;
  if (detail.error || !detail.data) return <p>{detail.error ?? '读取失败。'}</p>;
  const data = detail.data;
  return <div style={{padding: '6px 4px 10px'}}>
    <dl className="ops-detail" style={{gridTemplateColumns: 'repeat(3, minmax(0,1fr))'}}>
      {Object.entries(data.current ?? {})
        .filter(([key, value]) => value != null && value !== '' && key !== 'recordId' && key !== 'requestId')
        .slice(0, 12)
        .map(([key, value]) => <div key={key}><dt>{DETAIL_FIELD_LABELS[key] ?? key}</dt>
          <dd>{String(value)}</dd></div>)}
    </dl>
    {data.movement && <p style={{fontSize: 11, color: '#5c6b57', margin: '4px 0'}}>
      库存变动：{MOVEMENT_LABELS[data.movement.movementType] ?? data.movement.movementType}
      {' '}{data.movement.quantityDeltaTon} 吨 · 结余 {data.movement.physicalAfterTon} 吨
    </p>}
    {data.allocations?.length ? <p style={{fontSize: 11, color: '#5c6b57', margin: '4px 0'}}>
      订单归属：{data.allocations.map(a => `${a.orderNo} ${a.quantityTon} 吨`).join('、')}
    </p> : null}
    {data.handoverState && <p style={{fontSize: 11, color: '#5c6b57', margin: '4px 0'}}>
      交接状态 {data.handoverState}{data.errorRate ? ` · 误差率 ${(Number(data.errorRate) * 100).toFixed(2)}%` : ''}
    </p>}
    {data.missingItems.length > 0 && <p style={{fontSize: 11, color: '#8a6a1f', margin: '4px 0'}}>
      {data.missingItems.join('；')}
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

/** 计划外生产补录（S2-R17）：首笔建组，后续同组追加。 */
function UnplannedPanel({onClose, onSaved}: {onClose: () => void; onSaved: (message: string) => void}) {
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<SaveFeedback | null>(null);
  // 事项决定车间与产品（后端要求：车间1 组产品固定铜杆、车间2 固定铜丝），选错不了。
  const [recordType, setRecordType] = useState('PLATE_INPUT');
  const [workshopId, setWorkshopId] = useState('1');
  const typeOptions: [string, string][] = [
    ['PLATE_INPUT', '铜板投料（车间1）'], ['ROD_OUTPUT', '铜杆称重（车间1）'],
    ['ROD_HANDOVER', '领杆交接（车间2）'], ['WIRE_OUTPUT', '铜丝称重（车间2）'],
    ['EXECUTION', '实际起止（选车间）'],
  ];
  const typeWorkshop: Record<string, '1' | '2' | null> = {
    PLATE_INPUT: '1', ROD_OUTPUT: '1', ROD_HANDOVER: '2', WIRE_OUTPUT: '2', EXECUTION: null,
  };
  const productName = workshopId === '1' ? '直径 8mm 铜杆' : '直径 2.6mm 铜丝';
  const quantityLabel = recordType === 'PLATE_INPUT' ? '投入铜板数量（吨）'
    : recordType === 'ROD_HANDOVER' ? '交出重量（吨）'
      : recordType === 'EXECUTION' ? '数量（吨，可空）' : '合格产量（吨）';

  return <section className="panel" aria-label="计划外生产补录">
    <div className="panel-heading"><div>
      <h2>计划外生产<small className="panel-count">补录</small></h2>
      <p>登记没有对应计划任务的实际生产；首次保存自动建立补录组，后续可在同组继续登记。</p>
    </div>
      <button type="button" className="button" onClick={onClose}>收起</button>
    </div>
    {feedback && <div style={{padding: '0 23px 10px'}}><SaveFeedback feedback={feedback}/></div>}
    <form className="ops-form" style={{paddingTop: 8}} onSubmit={async event => {
      event.preventDefault();
      const data = new FormData(event.currentTarget);
      const actualDate = String(data.get('actualDate') ?? '').trim();
      const quantity = String(data.get('inputQuantityTon') ?? '').trim();
      const reason = String(data.get('reason') ?? '').trim();
      const orderId = String(data.get('orderId') ?? '').trim();
      const pickedWorkshop = String(data.get('workshopId') ?? workshopId);
      if (!actualDate || !reason || (recordType !== 'EXECUTION' && !quantity)) {
        setFeedback({ok: false, message: '请填写日期、数量与原因。'});
        return;
      }
      setBusy(true); setFeedback(null);
      const fact: Record<string, unknown> = {actualDate};
      if (recordType === 'EXECUTION') {
        fact.notes = reason;
      } else if (recordType === 'PLATE_INPUT') {
        fact.inputQuantityTon = quantity;
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
          recordType, workshopId: pickedWorkshop, materialId: pickedWorkshop === '1' ? '2' : '3',
          anchorActualDate: actualDate, operatorName: '系统管理员', reason, fact,
        });
        onSaved('已登记并建立补录组；同组后续登记请在实际记录中继续追加。');
      } catch (requestError) {
        setFeedback(errorFeedback(requestError));
      } finally {
        setBusy(false);
      }
    }}>
      <p className="ops-form-context">首次保存自动建立补录组；跨天的生产分多次登记。</p>
      <div className="ops-form-row">
        <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
          <span>登记事项</span>
          <select value={recordType} onChange={event => {
            const next = event.target.value;
            setRecordType(next);
            const ws = typeWorkshop[next];
            if (ws) setWorkshopId(ws);
          }}>
            {typeOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        {typeWorkshop[recordType] === null && <label className="select-field"
          style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
          <span>车间</span>
          <select name="workshopId" value={workshopId}
            onChange={event => setWorkshopId(event.target.value)}>
            <option value="1">车间1 · 铜杆</option>
            <option value="2">车间2 · 铜丝</option>
          </select>
        </label>}
        <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
          <span>产品（自动确定）</span>
          <input value={productName} disabled
            style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px',
              background: '#f4f6f1', color: '#5c6b57'}}/>
        </label>
        <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
          <span>发生日期</span>
          <input name="actualDate" type="date" required defaultValue={today()}
            style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
        </label>
        <label className="select-field" style={{flexDirection: 'column', alignItems: 'flex-start', gap: 4}}>
          <span>{quantityLabel}</span>
          <input name="inputQuantityTon" placeholder="如 6.300"
            style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
        </label>
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
      </div>
      <div className="ops-form-actions">
        <button type="submit" className="button button-primary" disabled={busy}>
          {busy ? '保存中…' : '保存首笔登记'}</button>
      </div>
    </form>
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
      setFeedback({ok: true, message: '已确认当日记录完整；之后补录或更正会使该确认自动失效。'});
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
      <p>核对当天该车间的生产记录是否登记完整。成本信息缺失不影响确认；产量记录未补齐前不能确认。</p>
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
      <label className="select-field">发生日期
        <input type="date" value={actualDate} onChange={event => setActualDate(event.target.value)}
          style={{height: 36, border: '1px solid #e1e7dc', borderRadius: 5, padding: '0 11px'}}/>
      </label>
    </div>
    {status.loading && <p style={{padding: '0 23px 20px'}}>读取中…</p>}
    {status.error && <p style={{padding: '0 23px 20px'}}>{status.error}</p>}
    {data && <>
      <div className="ops-detail" style={{gridTemplateColumns: 'repeat(4, minmax(0,1fr))'}}>
        <div><dt>当日合格产量</dt><dd><strong>{data.knownQualifiedQuantityTon ?? '0.000'} 吨</strong></dd></div>
        <div><dt>数据版本</dt><dd>v{data.sourceRevision}</dd></div>
        <div><dt>核对结果</dt><dd>{data.recordsComplete
          ? <span className="ops-tag ops-tag-green">已确认完整</span>
          : <span className="ops-tag ops-tag-amber">尚未确认完整</span>}</dd></div>
        <div><dt>确认人</dt><dd>{data.confirmedBy
          ? `${data.confirmedBy} · ${data.confirmedAt?.replace('T', ' ').slice(0, 16) ?? ''}`
          : '尚未确认'}</dd></div>
      </div>
      {data.blockingItems.length > 0 && <div style={{padding: '10px 23px 4px'}}>
        {data.blockingItems.map(item =>
          <p key={item.code} style={{fontSize: 11, color: '#a04a34', margin: '4px 0'}}>
            ⚠ {item.message}</p>)}
      </div>}
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
  </section>;
}
