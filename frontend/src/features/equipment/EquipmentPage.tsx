import { useMemo, useState } from 'react';
import { Icon } from '../../components/Icon';
import type { IconName } from '../../components/Icon';
import type { Equipment, Overview } from '../../lib/api';
import type { Resource } from '../../lib/useResource';

function readableTime(value: string | null) {
  const date = value ? new Date(value) : null;
  return date && Number.isFinite(date.getTime()) ? date.toLocaleTimeString('zh-CN', {hour12: false}) : '尚未读取';
}
function Metric({label, value, icon, detail}: {label: string; value: number | null; icon: IconName; detail: string}) {
  return <section className="metric"><div className="metric-label"><span>{label}</span><Icon name={icon} size={19} /></div><div className="metric-value">{value === null ? <span className="unknown">—</span> : value.toLocaleString('zh-CN')}<span className="metric-unit">{label === '生产设备' ? '台' : '个'}</span></div><p>{detail}</p></section>;
}
function EquipmentList({resource}: {resource: Resource<Equipment[]>}) {
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

export function EquipmentPage({resource, overview}: {resource: Resource<Equipment[]>; overview: Overview | null}) {
  return <>
    <div className="metrics">
      <Metric label="生产设备" value={overview?.counts.productionEquipment ?? resource.data?.length ?? null} icon="equipment" detail="设备基础信息" />
      <Metric label="生产车间" value={overview?.counts.workshops ?? null} icon="production" detail="按现有车间组织" />
      <Metric label="关联测点" value={overview?.counts.sensors ?? null} icon="network" detail="已有采集测点" />
    </div>
    <EquipmentList resource={resource} />
  </>;
}
