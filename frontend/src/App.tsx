import { useEffect, useState } from 'react';
import { api } from './lib/api';
import type { Health, Overview } from './lib/api';
import { useResource } from './lib/useResource';
import type { Resource } from './lib/useResource';

import { Icon } from './components/Icon';
import type { Page } from './components/Icon';
import { PendingPage } from './components/PendingPage';
import { EquipmentPage } from './features/equipment/EquipmentPage';
import { MaintenancePage } from './features/maintenance/MaintenancePage';
import { KnowledgePage } from './features/knowledge/KnowledgePage';
import { NetworkPage } from './features/network/NetworkPage';

const navigation: {id: Page; label: string; system: 1 | 2; description: string; features: string[]}[] = [
  {id: 'equipment', label: '设备管理', system: 1, description: '查看生产设备与基础信息', features: []},
  {id: 'operations', label: '运维管理', system: 1, description: '异常提醒、人工巡检与维修处理', features: ['异常事件与人工处理', '巡检记录与报修草稿', '维修、审核及验收']},
  {id: 'knowledge', label: '运维知识库', system: 1, description: '设备知识、手册与人工排查步骤', features: ['按设备和类型查阅', '人工维护的故障排查步骤', '已验收维修案例整理']},
  {id: 'network', label: '设备组网', system: 1, description: '车间工艺、设备与测点的二维示意', features: ['二维工艺与设备关系', '测点信息及档案关联', '示意位置与连线维护']},
  {id: 'planning', label: '订单与计划', system: 2, description: '订单、库存、产能与日生产计划', features: ['订单与物料需求', '生产草稿与日计划', '库存分配和交付记录']},
  {id: 'production', label: '生产记录', system: 2, description: '真实投入、领用、交接与合格产量', features: ['车间一投料与铜杆称重', '车间二领料及交接', '每日合格铜丝与订单归属']},
  {id: 'statistics', label: '生产与能源统计', system: 2, description: '生产总量、能源消耗与订单核算', features: ['分车间生产与能源总量', '能源价格与日期适用', '订单成本及估算盈亏']},
];

const pageComponents = {
  operations: MaintenancePage,
  knowledge: KnowledgePage,
  network: NetworkPage,
  planning: PendingPage,
  production: PendingPage,
  statistics: PendingPage,
};

function ConnectionDetails({live, ready, overview, retry}: {live: Resource<Health>; ready: Resource<Health>; overview: Resource<Overview>; retry: () => void}) {
  const db = overview.data?.database;
  return <details className="connection-details"><summary><Icon name="database" size={15} />连接状态与环境<span className="details-hint">查看</span></summary><div className="connection-content"><dl><div><dt>应用服务</dt><dd>{live.loading ? '检测中' : live.data?.status === 'UP' && !live.error ? '运行正常' : '暂不可用'}</dd></div><div><dt>数据库连接</dt><dd>{ready.loading ? '检测中' : ready.data?.database?.status === 'UP' && !ready.error ? '连接正常' : '尚未就绪'}</dd></div><div><dt>环境</dt><dd>{overview.data?.environment || '未返回'}</dd></div><div><dt>数据库</dt><dd>{db?.schemaName || '未返回'}{db?.serverVersion ? ` · ${db.serverVersion}` : ''}</dd></div><div><dt>表数量</dt><dd>一期 {db?.phase1TableCount ?? '—'} / 二期 {db?.phase2TableCount ?? '—'}</dd></div></dl>{(live.error || ready.error || overview.error) && <p className="connection-error">{live.error || ready.error || overview.error}</p>}<button type="button" className="button button-small" onClick={retry}>重新检测</button></div></details>;
}

export default function App() {
  const [page, setPage] = useState<Page>('equipment');
  const equipment = useResource(api.equipment), overview = useResource(api.overview), live = useResource(api.live), ready = useResource(api.ready);
  const backToEquipment = () => setPage('equipment');
  const active = navigation.find(item => item.id === page)!;
  const PageComponent = page === 'equipment' ? null : pageComponents[page];
  useEffect(() => { document.title = `${active.label} · 铜冶炼二期`; }, [active.label]);
  const retry = () => { live.reload(); ready.reload(); overview.reload(); equipment.reload(); };
  const connected = ready.data?.database?.status === 'UP' && !ready.error;
  const checking = ready.loading && !ready.data;
  return <><a className="skip-link" href="#main-content">跳到主要内容</a><div className="app-shell"><aside className="sidebar"><a className="brand" href="#main-content" onClick={() => setPage('equipment')} aria-label="铜冶炼二期，返回设备管理"><span className="brand-mark"><span /><span /><span /></span><span className="brand-copy">铜冶炼二期<small>COPPER OPERATIONS</small></span></a><div className="workspace-caption">管理工作台<span>PHASE 02</span></div><nav aria-label="主导航">{([1, 2] as const).map(system => <div className="navigation-group" key={system}><p className="navigation-label">系统{system === 1 ? '一' : '二'}<span>{system === 1 ? '区域能源管理' : '全生命周期管控'}</span></p><div className="navigation-items">{navigation.filter(item => item.system === system).map(item => <button type="button" className={`navigation-item ${page === item.id ? 'active' : ''}`} key={item.id} aria-current={page === item.id ? 'page' : undefined} onClick={() => setPage(item.id)}><Icon name={item.id} size={19} /><span>{item.label}</span>{item.id !== 'equipment' && item.id !== 'operations' && item.id !== 'network' && <span className="nav-pending">待开发</span>}{page === item.id && <span className="nav-active-dot" />}</button>)}</div></div>)}</nav><div className="sidebar-bottom"><div className="sidebar-lines" aria-hidden="true"><i /><i /><i /><i /></div><span>设备 · 生产 · 能源</span><p>信息连接，协同生产</p></div></aside>
    <div className="workspace"><header className="topbar"><div className="breadcrumb"><span>管理平台</span><Icon name="arrow" size={14} /><strong>{active.label}</strong></div><div className="topbar-right"><span className="initialization-tag">工程初始化</span><span className="header-divider" /><span className={`status-pill ${checking ? 'loading' : connected ? 'up' : 'down'}`}><span className="status-dot" />{checking ? '检测连接中' : connected ? '数据连接正常' : '等待数据连接'}</span></div></header>
      <main id="main-content" tabIndex={-1}><div className="page-heading"><div><div className="eyebrow"><span />{active.system === 1 ? '铜冶炼区域能源管理系统' : '全生命周期管控与决策支持系统'}</div><h1>{active.label}</h1><p>{page === 'equipment' ? '统一查看生产设备，建立清晰的设备信息底账。' : active.description}</p></div>{page === 'equipment' && <button className="button refresh-button" type="button" onClick={retry} disabled={equipment.loading}><Icon name="refresh" size={17} /><span>{equipment.loading ? '读取中…' : '刷新数据'}</span></button>}</div>
      {page === 'equipment' ? <EquipmentPage resource={equipment} overview={overview.data} /> :
        page === 'operations' ? <MaintenancePage /> :
        PageComponent && <PageComponent item={{...active, id: page}} back={backToEquipment} />}
      <ConnectionDetails live={live} ready={ready} overview={overview} retry={retry} /></main><footer className="footer"><span>铜冶炼二期管理平台</span><span>独立二期工程<span className="footer-dot">·</span>初始化接入范围</span></footer></div></div></>;
}
