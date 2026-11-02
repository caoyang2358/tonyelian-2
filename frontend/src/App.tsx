import { useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { api } from './lib/api';
import type { Equipment, Health, Overview } from './lib/api';
import { useResource } from './lib/useResource';
import type { Resource } from './lib/useResource';

type Page = 'equipment' | 'operations' | 'knowledge' | 'network' | 'planning' | 'production' | 'statistics';
type IconName = Page | 'arrow' | 'refresh' | 'search' | 'check' | 'alert' | 'database' | 'clock';
const navigation: {id: Page; label: string; system: 1 | 2; description: string; features: string[]}[] = [
  {id: 'equipment', label: '设备管理', system: 1, description: '查看生产设备与基础信息', features: []},
  {id: 'operations', label: '运维管理', system: 1, description: '异常提醒、人工巡检与维修处理', features: ['异常事件与人工处理', '巡检记录与报修草稿', '维修、审核及验收']},
  {id: 'knowledge', label: '运维知识库', system: 1, description: '设备知识、手册与人工排查步骤', features: ['按设备和类型查阅', '人工维护的故障排查步骤', '已验收维修案例整理']},
  {id: 'network', label: '设备组网', system: 1, description: '车间工艺、设备与测点的二维示意', features: ['二维工艺与设备关系', '测点信息及档案关联', '示意位置与连线维护']},
  {id: 'planning', label: '订单与计划', system: 2, description: '订单、库存、产能与日生产计划', features: ['订单与物料需求', '生产草稿与日计划', '库存分配和交付记录']},
  {id: 'production', label: '生产记录', system: 2, description: '真实投入、领用、交接与合格产量', features: ['车间一投料与铜杆称重', '车间二领料及交接', '每日合格铜丝与订单归属']},
  {id: 'statistics', label: '生产与能源统计', system: 2, description: '生产总量、能源消耗与订单核算', features: ['分车间生产与能源总量', '能源价格与日期适用', '订单成本及估算盈亏']},
];

function Icon({name, size = 20}: {name: IconName; size?: number}) {
  const paths: Record<IconName, ReactNode> = {
    equipment: <><rect x="3" y="5" width="18" height="15" rx="3" /><path d="M7 5V2m10 3V2M7 10h10M7 15h5" /><circle cx="17" cy="15" r="1" /></>,
    operations: <><path d="m14 4 6 6M5 20l6-6m-6-2 7 7m2-15 3-2 5 5-2 3-6 6-5-5zM3 17l4 4" /></>,
    knowledge: <><path d="M12 5v16M3 4c4-1 6 0 9 2 3-2 5-3 9-2v15c-4-1-6 0-9 2-3-2-5-3-9-2z" /></>,
    network: <><rect x="8" y="2" width="8" height="6" rx="1" /><rect x="2" y="16" width="7" height="6" rx="1" /><rect x="15" y="16" width="7" height="6" rx="1" /><path d="M12 8v4H5v4m7-4h7v4" /></>,
    planning: <><rect x="3" y="4" width="18" height="18" rx="3" /><path d="M7 2v4m10-4v4M3 10h18m-14 4h3m4 0h3m-10 4h3" /></>,
    production: <><path d="M3 21V10l6 3V7l6 4V3h6v18zM3 21h18M7 17h1m4 0h1m4 0h1" /></>,
    statistics: <><path d="M3 3v18h18M7 16v-5m5 5V6m5 10V9" /></>,
    arrow: <path d="m9 5 7 7-7 7" />,
    refresh: <><path d="M20 7a8 8 0 1 0 0 10M20 3v5h-5" /></>,
    search: <><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 5 5" /></>,
    check: <path d="m5 12 4 4L19 6" />,
    alert: <><path d="m12 3 10 18H2zM12 9v5m0 3v.1" /></>,
    database: <><ellipse cx="12" cy="5" rx="9" ry="3" /><path d="M3 5v7c0 4 18 4 18 0V5M3 12v7c0 4 18 4 18 0v-7" /></>,
    clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>;
}

function readableTime(value: string | null) {
  const date = value ? new Date(value) : null;
  return date && Number.isFinite(date.getTime()) ? date.toLocaleTimeString('zh-CN', {hour12: false}) : '尚未读取';
}
function Metric({label, value, icon, detail}: {label: string; value: number | null; icon: IconName; detail: string}) {
  return <section className="metric"><div className="metric-label"><span>{label}</span><Icon name={icon} size={19} /></div><div className="metric-value">{value === null ? <span className="unknown">—</span> : value.toLocaleString('zh-CN')}<span className="metric-unit">{label === '生产设备' ? '台' : '个'}</span></div><p>{detail}</p></section>;
}
function ConnectionDetails({live, ready, overview, retry}: {live: Resource<Health>; ready: Resource<Health>; overview: Resource<Overview>; retry: () => void}) {
  const db = overview.data?.database;
  return <details className="connection-details"><summary><Icon name="database" size={15} />连接状态与环境<span className="details-hint">查看</span></summary><div className="connection-content"><dl><div><dt>应用服务</dt><dd>{live.loading ? '检测中' : live.data?.status === 'UP' && !live.error ? '运行正常' : '暂不可用'}</dd></div><div><dt>数据库连接</dt><dd>{ready.loading ? '检测中' : ready.data?.database?.status === 'UP' && !ready.error ? '连接正常' : '尚未就绪'}</dd></div><div><dt>环境</dt><dd>{overview.data?.environment || '未返回'}</dd></div><div><dt>数据库</dt><dd>{db?.schemaName || '未返回'}{db?.serverVersion ? ` · ${db.serverVersion}` : ''}</dd></div><div><dt>表数量</dt><dd>一期 {db?.phase1TableCount ?? '—'} / 二期 {db?.phase2TableCount ?? '—'}</dd></div></dl>{(live.error || ready.error || overview.error) && <p className="connection-error">{live.error || ready.error || overview.error}</p>}<button type="button" className="button button-small" onClick={retry}>重新检测</button></div></details>;
}

function EquipmentPage({resource}: {resource: Resource<Equipment[]>}) {
  const [query, setQuery] = useState(''), [workshop, setWorkshop] = useState('all'), [enabled, setEnabled] = useState('all');
  const rows = useMemo(() => resource.data ?? [], [resource.data]);
  const workshops = useMemo(() => [...new Map(rows.map(row => [row.workshopId, row.workshopName])).entries()], [rows]);
  const filtered = useMemo(() => {
    const word = query.trim().toLocaleLowerCase();
    return rows.filter(row => (!word || `${row.name} ${row.code}`.toLocaleLowerCase().includes(word)) && (workshop === 'all' || row.workshopId === workshop) && (enabled === 'all' || row.enabled === (enabled === 'enabled')));
  }, [rows, query, workshop, enabled]);
  const hasFilter = query !== '' || workshop !== 'all' || enabled !== 'all';
  const clear = () => { setQuery(''); setWorkshop('all'); setEnabled('all'); };
  return <section className="panel equipment-panel" aria-labelledby="equipment-list-title" aria-busy={resource.loading}>
    <div className="panel-heading"><div><h2 id="equipment-list-title">生产设备档案<span className="panel-count">{resource.data ? `${rows.length} 台` : '等待读取'}</span></h2><p>沿用一期设备编码与车间信息</p></div><span className="read-only-label"><Icon name="check" size={14} />只读接入</span></div>
    <div className="filters"><label className="search-field"><span className="visually-hidden">搜索设备名称或编码</span><Icon name="search" size={18} /><input type="search" placeholder="搜索设备名称、编码" value={query} onChange={event => setQuery(event.target.value)} disabled={!resource.data} /></label><label className="select-field"><span>所属车间</span><select value={workshop} onChange={event => setWorkshop(event.target.value)} disabled={!resource.data}><option value="all">全部车间</option>{workshops.map(([id, name]) => <option value={id} key={id}>{name}</option>)}</select></label><label className="select-field"><span>启用状态</span><select value={enabled} onChange={event => setEnabled(event.target.value)} disabled={!resource.data}><option value="all">全部状态</option><option value="enabled">已启用</option><option value="disabled">已停用</option></select></label>{hasFilter && <button className="reset-filter" type="button" onClick={clear}>清除筛选</button>}</div>
    {resource.error && <div className="state-error" role="alert"><div className="state-icon"><Icon name="alert" size={24} /></div><div><h3>暂时无法读取设备</h3><p>{resource.error}</p>{resource.data && <p>下方保留上次读取结果，尚未刷新。</p>}</div><button className="button" type="button" onClick={resource.reload} disabled={resource.loading}>重试读取</button></div>}
    {resource.loading && !resource.data ? <div className="loading-state" role="status"><span className="spinner" /><span>正在读取设备档案…</span><div className="skeleton-rows" aria-hidden="true">{[1, 2, 3, 4].map(row => <div key={row}><i /><i /><i /><i /></div>)}</div></div> : resource.data && rows.length === 0 ? <div className="empty-state" role="status"><div className="empty-icon"><Icon name="equipment" size={30} /></div><h3>当前没有生产设备</h3><p>设备接口已返回，本次查询未发现设备记录。</p><button type="button" className="button" onClick={resource.reload}>重新读取</button></div> : resource.data && filtered.length === 0 ? <div className="empty-state" role="status"><div className="empty-icon"><Icon name="search" size={28} /></div><h3>没有匹配的设备</h3><p>试试其他名称、编码或筛选条件。</p><button type="button" className="button" onClick={clear}>清除筛选</button></div> : resource.data ? <><div className="table-region" role="region" aria-label="生产设备表，可横向滚动" tabIndex={0}><table><caption className="visually-hidden">一期生产设备只读列表</caption><thead><tr><th scope="col" className="number-column">序号</th><th scope="col">设备名称 / 编码</th><th scope="col">所属车间</th><th scope="col">责任人</th><th scope="col">启用状态</th></tr></thead><tbody>{filtered.map((row, index) => <tr key={row.id}><td className="row-number">{String(index + 1).padStart(2, '0')}</td><td><div className="device-cell"><span className="device-symbol"><Icon name="equipment" size={20} /></span><div><strong>{row.name}</strong><span className="device-code">{row.code}</span></div></div></td><td><span className="workshop-marker" />{row.workshopName}</td><td>{row.responsiblePerson || <span className="missing-value">待补充</span>}</td><td><span className={`equipment-status ${row.enabled ? 'enabled' : ''}`}><span />{row.enabled ? '已启用' : '已停用'}</span></td></tr>)}</tbody></table></div><div className="table-footer"><span role="status">共 {rows.length} 台，当前显示 {filtered.length} 台</span><span><Icon name="clock" size={14} />最近读取 {readableTime(resource.timestamp)}</span></div></> : null}
    <div className="panel-note"><span className="note-dot" /><p>当前页面提供设备基础信息查询；档案编辑、测点维护与运维记录将在后续开发中接入。</p></div>
  </section>;
}
function PendingPage({page, back}: {page: Exclude<Page, 'equipment'>; back: () => void}) {
  const item = navigation.find(row => row.id === page)!;
  return <section className="panel pending-panel"><div className="pending-art" aria-hidden="true"><div className="pending-orbit" /><div className="pending-glyph"><Icon name={page} size={36} /></div><span className="pending-spark" /></div><span className="section-kicker">功能建设计划</span><h2>{item.label}待开发</h2><p className="pending-description">{item.description}。本次工程初始化尚未实现这一业务模块。</p><div className="pending-features">{item.features.map((feature, index) => <div key={feature}><span>{String(index + 1).padStart(2, '0')}</span>{feature}</div>)}</div><button className="button button-primary" type="button" onClick={back}>查看已接入的设备<Icon name="arrow" size={16} /></button><p className="pending-note">当前可用：设备基础信息只读查询</p></section>;
}

export default function App() {
  const [page, setPage] = useState<Page>('equipment');
  const equipment = useResource(api.equipment), overview = useResource(api.overview), live = useResource(api.live), ready = useResource(api.ready);
  const active = navigation.find(item => item.id === page)!;
  useEffect(() => { document.title = `${active.label} · 铜冶炼二期`; }, [active.label]);
  const retry = () => { live.reload(); ready.reload(); overview.reload(); equipment.reload(); };
  const connected = ready.data?.database?.status === 'UP' && !ready.error;
  const checking = ready.loading && !ready.data;
  return <><a className="skip-link" href="#main-content">跳到主要内容</a><div className="app-shell"><aside className="sidebar"><a className="brand" href="#main-content" onClick={() => setPage('equipment')} aria-label="铜冶炼二期，返回设备管理"><span className="brand-mark"><span /><span /><span /></span><span className="brand-copy">铜冶炼二期<small>COPPER OPERATIONS</small></span></a><div className="workspace-caption">管理工作台<span>PHASE 02</span></div><nav aria-label="主导航">{([1, 2] as const).map(system => <div className="navigation-group" key={system}><p className="navigation-label">系统{system === 1 ? '一' : '二'}<span>{system === 1 ? '区域能源管理' : '全生命周期管控'}</span></p><div className="navigation-items">{navigation.filter(item => item.system === system).map(item => <button type="button" className={`navigation-item ${page === item.id ? 'active' : ''}`} key={item.id} aria-current={page === item.id ? 'page' : undefined} onClick={() => setPage(item.id)}><Icon name={item.id} size={19} /><span>{item.label}</span>{item.id !== 'equipment' && <span className="nav-pending">待开发</span>}{page === item.id && <span className="nav-active-dot" />}</button>)}</div></div>)}</nav><div className="sidebar-bottom"><div className="sidebar-lines" aria-hidden="true"><i /><i /><i /><i /></div><span>设备 · 生产 · 能源</span><p>信息连接，协同生产</p></div></aside>
    <div className="workspace"><header className="topbar"><div className="breadcrumb"><span>管理平台</span><Icon name="arrow" size={14} /><strong>{active.label}</strong></div><div className="topbar-right"><span className="initialization-tag">工程初始化</span><span className="header-divider" /><span className={`status-pill ${checking ? 'loading' : connected ? 'up' : 'down'}`}><span className="status-dot" />{checking ? '检测连接中' : connected ? '数据连接正常' : '等待数据连接'}</span></div></header>
      <main id="main-content" tabIndex={-1}><div className="page-heading"><div><div className="eyebrow"><span />{active.system === 1 ? '铜冶炼区域能源管理系统' : '全生命周期管控与决策支持系统'}</div><h1>{active.label}</h1><p>{page === 'equipment' ? '统一查看生产设备，建立清晰的设备信息底账。' : active.description}</p></div>{page === 'equipment' && <button className="button refresh-button" type="button" onClick={retry} disabled={equipment.loading}><Icon name="refresh" size={17} /><span>{equipment.loading ? '读取中…' : '刷新数据'}</span></button>}</div>
      {page === 'equipment' ? <><div className="metrics"><Metric label="生产设备" value={overview.data?.counts.productionEquipment ?? (equipment.data ? equipment.data.length : null)} icon="equipment" detail="设备基础信息" /><Metric label="生产车间" value={overview.data?.counts.workshops ?? null} icon="production" detail="按现有车间组织" /><Metric label="关联测点" value={overview.data?.counts.sensors ?? null} icon="network" detail="已有采集测点" /></div><EquipmentPage resource={equipment} /></> : <PendingPage page={page} back={() => setPage('equipment')} />}
      <ConnectionDetails live={live} ready={ready} overview={overview} retry={retry} /></main><footer className="footer"><span>铜冶炼二期管理平台</span><span>独立二期工程<span className="footer-dot">·</span>初始化接入范围</span></footer></div></div></>;
}
