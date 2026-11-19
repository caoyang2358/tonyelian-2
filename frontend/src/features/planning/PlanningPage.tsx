import {useCallback, useEffect, useMemo, useState} from 'react';
import type {FormEvent, ReactNode} from 'react';
import type {Page} from '../../components/Icon';
import {Icon} from '../../components/Icon';
import {ApiError} from '../../lib/api';
import {planningApi} from './planningApi';
import type {Capacity, CapacityDay, Inventory, Material, Order, PlanTask} from './planningApi';
import './planning.css';
import './planning-plan.css';

type View = 'orders' | 'inventory' | 'capacity' | 'calendar';
const localDate = (offset = 0) => { const d = new Date(); d.setDate(d.getDate() + offset); return d.toISOString().slice(0, 10); };
const uuid = () => crypto.randomUUID();
const materialLabel = (m: Material) => m.type === 'COPPER_PLATE' ? '铜板' : m.type === 'COPPER_ROD' ? 'Φ8 铜杆' : `成品铜丝 ${m.specification}`;
const resultLabel = (value: string) => ({PENDING: '待处理', COMPLETED: '已完成', PARTIAL: '不足量结束', CANCELLED: '已取消'}[value] || value);

export function PlanningPage({item}: {item: {id: Exclude<Page, 'equipment'>; label: string}}) {
  const [view, setView] = useState<View>('orders');
  const [materials, setMaterials] = useState<Material[]>([]);
  const [orders, setOrders] = useState<Order[]>([]);
  const [inventory, setInventory] = useState<Inventory[]>([]);
  const [capacities, setCapacities] = useState<Capacity[]>([]);
  const [days, setDays] = useState<CapacityDay[]>([]);
  const [tasks, setTasks] = useState<PlanTask[]>([]);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [orderModal, setOrderModal] = useState(false);
  const [materialModal, setMaterialModal] = useState(false);
  const [detail, setDetail] = useState<Order | null>(null);
  const [planOrder, setPlanOrder] = useState<Order | null>(null);
  const [keyword, setKeyword] = useState('');

  const load = useCallback(async () => {
    setBusy(true); setError('');
    try {
      const [m, o, i, c, d, t] = await Promise.all([
        planningApi.materials(), planningApi.orders({pageSize: '100'}), planningApi.inventory(),
        planningApi.capacities(), planningApi.capacityDays(localDate(), localDate(30)),
        planningApi.planTasks(localDate(), localDate(60)),
      ]);
      setMaterials(m.data.items); setOrders(o.data.items); setInventory(i.data.items);
      setCapacities(c.data); setDays(d.data.items); setTasks(t.data.items);
    } catch (e) { setError(e instanceof Error ? e.message : '业务数据读取失败。'); }
    finally { setBusy(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const filtered = useMemo(() => orders.filter(o => !keyword.trim()
    || `${o.orderNo} ${o.customerName} ${o.wireSpecification}`.toLowerCase().includes(keyword.trim().toLowerCase())), [orders, keyword]);
  const wire = materials.filter(m => m.type === 'FINISHED_WIRE' && m.enabled);

  const submitOrder = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault(); const fd = new FormData(e.currentTarget);
    try {
      await planningApi.createOrder({requestId: uuid(), orderNo: fd.get('orderNo'), customerName: fd.get('customerName'), wireMaterialId: fd.get('wireMaterialId'), requiredQuantityTon: fd.get('requiredQuantityTon'), dueDate: fd.get('dueDate'), manualPlannedRodTon: fd.get('manualPlannedRodTon') || null, contractAmountYuan: fd.get('contractAmountYuan') || null, amountConfirmed: fd.get('amountConfirmed') === 'on', operatorName: fd.get('operatorName')});
      setOrderModal(false); setNotice('订单已建立；下一步可在订单行编制正式计划。'); await load();
    } catch (x) { setError(x instanceof ApiError ? x.message : '订单保存失败。'); }
  };
  const submitMaterial = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault(); const fd = new FormData(e.currentTarget);
    try {
      await planningApi.createMaterial({requestId: uuid(), code: fd.get('code'), name: fd.get('name'), specification: fd.get('specification'), operatorName: fd.get('operatorName')});
      setMaterialModal(false); setNotice('铜丝规格已新增，实物库存仍为 0。'); await load();
    } catch (x) { setError(x instanceof Error ? x.message : '规格保存失败。'); }
  };
  const submitPlan = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault(); if (!planOrder) return; const fd = new FormData(e.currentTarget);
    try {
      const result = await planningApi.confirmPlan({requestId: uuid(), orderId: planOrder.orderId, expectedOrderVersion: planOrder.version, manualPlannedRodTon: fd.get('manualPlannedRodTon'), rodStartDate: fd.get('rodStartDate'), wireStartDate: fd.get('wireStartDate'), reason: fd.get('reason'), operatorName: fd.get('operatorName')});
      const warnings = result.data.warnings.length ? ` ${result.data.warnings.join(' ')}` : '';
      setPlanOrder(null); setView('calendar'); setNotice(`计划 ${result.data.planId} 已生效，共生成 ${result.data.taskIds.length} 项任务，预计 ${result.data.completionDate} 完成。${warnings}`); await load();
    } catch (x) { setError(x instanceof Error ? x.message : '计划确认失败。'); }
  };

  const orderRows = filtered.map(o => { const planned = Number(o.plannedQuantityTon) > 0; return <tr key={o.orderId}><td><strong>{o.orderNo}</strong><small>{o.customerName}</small></td><td>{o.wireSpecification}</td><td>{o.requiredQuantityTon} t</td><td>{o.dueDate}</td><td><div className="pl-statuses"><span>{planned ? `已排 ${o.plannedQuantityTon} t` : resultLabel(o.productionResult)}</span><span>{resultLabel(o.deliveryResult)}</span><span>{resultLabel(o.accountingState)}</span></div></td><td><div className="pl-row-actions"><button className="pl-plan-button" disabled={planned} onClick={() => setPlanOrder(o)}>{planned ? '计划已生效' : '编制计划'}</button><button className="pl-link" onClick={() => setDetail(o)}>查看</button></div></td></tr>; });
  const inventoryRows = inventory.map(i => <tr key={i.materialId}><td><strong>{i.name}</strong><small>{i.code}</small></td><td>{materialLabel({...i, unit: 't', enabled: true} as Material)}</td><td>{i.physicalQuantityTon} t</td><td>{i.reservedQuantityTon} t</td><td><strong className={Number(i.availableQuantityTon) < 0 ? 'negative' : ''}>{i.availableQuantityTon} t</strong></td></tr>);
  const capacityRows = days.map(d => <tr key={`${d.workshopId}-${d.date}`}><td>{d.date}</td><td><strong>{d.workshopName}</strong></td><td>{d.normalCapacityTon} t</td><td>{d.overrideCapacityTon === null ? '—' : `${d.overrideCapacityTon} t`}</td><td>{d.plannedQuantityTon} t</td><td><strong className={Number(d.remainingCapacityTon) < 0 ? 'negative' : ''}>{d.remainingCapacityTon} t</strong></td></tr>);
  const taskRows = tasks.map(t => <tr key={t.taskId}><td><strong>{t.productionDate}</strong><small>当日第 {t.sequenceNo} 项</small></td><td>{t.workshopName}</td><td>{t.materialName} {t.specification}</td><td>{t.orderNumbers ? <><strong>{t.orderNumbers}</strong><small>{t.customerNames}</small></> : <small>未关联订单</small>}</td><td>{t.plannedQualifiedTon} t</td><td><span className="pl-tag">{t.status}</span></td></tr>);

  return <section className="pl-shell"><header className="pl-head"><div><span className="section-kicker">{item.label}</span><h2>{{orders: '订单管理', inventory: '物料与库存', capacity: '产能日历', calendar: '生效日计划'}[view]}</h2><p>订单需求、现货余额与两车间日能力使用同一套真实业务数据。</p></div><div className="pl-tabs" role="tablist">{([['orders','订单'],['inventory','库存'],['capacity','产能'],['calendar','日计划']] as [View,string][]).map(([id,label]) => <button key={id} className={view === id ? 'active' : ''} onClick={() => setView(id)} role="tab" aria-selected={view === id}>{label}</button>)}</div></header>
    {error && <div className="pl-alert error"><span>{error}</span><button onClick={() => {setError(''); void load();}}>重试</button></div>}{notice && <div className="pl-alert success"><span>{notice}</span><button onClick={() => setNotice('')}>知道了</button></div>}
    {busy ? <div className="pl-loading">正在读取订单与计划数据…</div> : view === 'orders' ? <><div className="pl-summary"><Summary icon="clipboard" label="有效订单" value={`${orders.length} 单`}/><Summary icon="calendar" label="最近交期" value={orders[0]?.dueDate || '暂无'}/><Summary icon="inventory" label="可选铜丝规格" value={`${wire.length} 种`}/></div><div className="pl-toolbar"><label className="pl-search"><Icon name="search" size={16}/><input value={keyword} onChange={e => setKeyword(e.target.value)} placeholder="搜索订单号、客户或规格"/></label><button className="button" onClick={() => setOrderModal(true)} disabled={!wire.length}>＋ 新建订单</button></div><Table empty="暂无订单；先建立铜丝规格，再录入客户订单。" heads={['订单号 / 客户','规格','要求量','交期','生产 / 交付 / 核算','操作']} rows={orderRows}/></>
      : view === 'inventory' ? <><div className="pl-toolbar"><div><strong>物料定义与库存余额</strong><p>可用量 = 实物量 − 有效预留；未来计划产出不计入现货。</p></div><button className="button" onClick={() => setMaterialModal(true)}>＋ 新增铜丝规格</button></div><Table empty="暂无物料。" heads={['物料','类型 / 规格','实物库存','有效预留','当前可用']} rows={inventoryRows}/></>
      : view === 'capacity' ? <><div className="pl-summary">{capacities.map(c => <Summary key={c.workshopId} icon="factory" label={`${c.workshopName}普通产能`} value={`${c.maxDailyOutputTon} t/日`}/>)}</div><Table empty="尚未配置车间产能。" heads={['日期','车间','普通产能','特殊产能','已排产','剩余能力']} rows={capacityRows}/></>
      : <><div className="pl-note"><Icon name="calendar" size={21}/><div><strong>这里只展示已经确认生效的日计划</strong><p>任务确认后会同步出现在“生产记录”模块，实际投料和称重在生产记录中登记。</p></div></div><Table empty="未来 60 天暂无已确认的生产任务。" heads={['日期 / 顺序','车间','产品规格','关联订单 / 客户','计划合格量','任务状态']} rows={taskRows}/></>}
    {orderModal && <Modal title="新建客户订单" onClose={() => setOrderModal(false)}><form className="pl-form" onSubmit={submitOrder}><Field label="订单编号" name="orderNo" required/><Field label="客户名称" name="customerName" required/><label>铜丝规格<select name="wireMaterialId" required defaultValue=""><option value="" disabled>请选择</option>{wire.map(m => <option key={m.materialId} value={m.materialId}>{m.specification} · {m.name}</option>)}</select></label><Field label="要求数量（吨）" name="requiredQuantityTon" type="number" step="0.001" min="0.001" required/><Field label="交付日期" name="dueDate" type="date" required/><Field label="人工计划用杆总需求（吨，可后补）" name="manualPlannedRodTon" type="number" step="0.001" min="0"/><Field label="合同金额（元，可后补）" name="contractAmountYuan" type="number" step="0.01" min="0"/><label className="pl-check"><input type="checkbox" name="amountConfirmed"/> 金额已经人工确认</label><Field label="经办人" name="operatorName" required/><FormActions cancel={() => setOrderModal(false)} submit="保存订单"/></form></Modal>}
    {materialModal && <Modal title="新增成品铜丝规格" onClose={() => setMaterialModal(false)}><form className="pl-form" onSubmit={submitMaterial}><Field label="物料编码" name="code" placeholder="如 WIRE-PHI1.82" required/><Field label="物料名称" name="name" placeholder="如 Φ1.82 成品铜丝" required/><Field label="规格" name="specification" placeholder="如 Φ1.82" required/><Field label="经办人" name="operatorName" required/><p className="pl-form-note">新增规格只建立物料定义，不会自动生成期初库存。</p><FormActions cancel={() => setMaterialModal(false)} submit="保存规格"/></form></Modal>}
    {planOrder && <Modal title={`编制计划 · ${planOrder.orderNo}`} onClose={() => setPlanOrder(null)}><form className="pl-form" onSubmit={submitPlan}><div className="pl-plan-order"><strong>{planOrder.customerName}</strong><span>{planOrder.wireSpecification} · {planOrder.requiredQuantityTon} t · 交期 {planOrder.dueDate}</span></div><Field label="人工确认计划用杆总需求（吨）" name="manualPlannedRodTon" type="number" step="0.001" min="0.001" defaultValue={planOrder.manualPlannedRodTon || planOrder.requiredQuantityTon} required/><Field label="车间1开始日期" name="rodStartDate" type="date" min={localDate()} defaultValue={localDate(1)} required/><Field label="车间2开始日期" name="wireStartDate" type="date" min={localDate()} defaultValue={localDate(2)} required/><Field label="排产原因" name="reason" defaultValue="订单首次排产" required/><Field label="经办人" name="operatorName" required/><p className="pl-form-note">系统按两车间每日剩余产能拆分任务。若车间二日期早于供杆完成日，将自动顺延并明确提示；铜板量按人工确认的用杆总需求统一计算一次。</p><FormActions cancel={() => setPlanOrder(null)} submit="确认生成正式计划"/></form></Modal>}
    {detail && <Modal title={`订单 ${detail.orderNo}`} onClose={() => setDetail(null)}><dl className="pl-detail"><div><dt>客户</dt><dd>{detail.customerName}</dd></div><div><dt>铜丝规格</dt><dd>{detail.wireSpecification}</dd></div><div><dt>要求数量</dt><dd>{detail.requiredQuantityTon} t</dd></div><div><dt>交付日期</dt><dd>{detail.dueDate}</dd></div><div><dt>人工计划用杆总需求</dt><dd>{detail.manualPlannedRodTon ? `${detail.manualPlannedRodTon} t` : '待补充'}</dd></div><div><dt>合同金额</dt><dd>{detail.contractAmountYuan ? `¥ ${detail.contractAmountYuan}` : '待补充'}</dd></div><div><dt>生产结果</dt><dd>{resultLabel(detail.productionResult)}</dd></div><div><dt>交付结果</dt><dd>{resultLabel(detail.deliveryResult)}</dd></div></dl></Modal>}
  </section>;
}

function Summary({icon, label, value}: {icon: string; label: string; value: string}) { return <div className="pl-summary-card"><span><Icon name={icon as never} size={19}/></span><div><small>{label}</small><strong>{value}</strong></div></div>; }
function Table({heads, rows, empty}: {heads: string[]; rows: ReactNode[]; empty: string}) { return <div className="pl-table-wrap"><table className="pl-table"><thead><tr>{heads.map(h => <th key={h}>{h}</th>)}</tr></thead><tbody>{rows.length ? rows : <tr><td className="pl-empty" colSpan={heads.length}>{empty}</td></tr>}</tbody></table></div>; }
function Modal({title, onClose, children}: {title: string; onClose: () => void; children: ReactNode}) { return <div className="pl-modal-backdrop" onMouseDown={e => {if (e.currentTarget === e.target) onClose();}}><section className="pl-modal" role="dialog" aria-modal="true"><header><h3>{title}</h3><button onClick={onClose} aria-label="关闭">×</button></header>{children}</section></div>; }
function Field({label, name, ...props}: {label: string; name: string; [key: string]: unknown}) { return <label>{label}<input name={name} {...props}/></label>; }
function FormActions({cancel, submit}: {cancel: () => void; submit: string}) { return <div className="pl-form-actions"><button type="button" onClick={cancel}>取消</button><button className="button" type="submit">{submit}</button></div>; }
