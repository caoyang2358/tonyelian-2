import { useCallback, useEffect, useState } from 'react';
import { Icon } from '../../components/Icon';
import type { IconName } from '../../components/Icon';
import { api } from '../../lib/api';
import type { Equipment, EquipmentDetail, EquipmentPageResult, EquipmentQuery, Overview, ReferenceData } from '../../lib/api';
import { DeadlinePanel } from './DeadlinePanel';
import { EquipmentDetailDrawer } from './EquipmentDetailDrawer';
import { EquipmentFormDialog } from './EquipmentFormDialog';
import { isPlaceholderPerson } from './fieldLabels';
import './equipment.css';

const PAGE_SIZE = 20;

function readableTime(value: string | null) {
  const date = value ? new Date(value) : null;
  return date && Number.isFinite(date.getTime()) ? date.toLocaleTimeString('zh-CN', {hour12: false}) : '尚未读取';
}

function statusClass(status: string | null): string {
  if (status === 'IN_USE') return 'is-in-use';
  if (status === 'STOPPED') return 'is-stopped';
  if (status === 'SCRAPPED') return 'is-scrapped';
  return '';
}

function Metric({label, value, icon, detail}: {label: string; value: number | null; icon: IconName; detail: string}) {
  return <section className="metric"><div className="metric-label"><span>{label}</span><Icon name={icon} size={19} /></div><div className="metric-value">{value === null ? <span className="unknown">—</span> : value.toLocaleString('zh-CN')}<span className="metric-unit">{label === '生产设备' ? '台' : '个'}</span></div><p>{detail}</p></section>;
}

export interface EquipmentPageProps {
  overview: Overview | null;
  refreshToken: number;
}

export function EquipmentPage({overview, refreshToken}: EquipmentPageProps) {
  const [options, setOptions] = useState<ReferenceData | null>(null);
  const [query, setQuery] = useState<EquipmentQuery>({page: 1, pageSize: PAGE_SIZE});
  const [keyword, setKeyword] = useState('');
  const [result, setResult] = useState<EquipmentPageResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [timestamp, setTimestamp] = useState<string | null>(null);
  const [localVersion, setLocalVersion] = useState(0);
  const [openId, setOpenId] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [formTarget, setFormTarget] = useState<EquipmentDetail | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [deadlineOpen, setDeadlineOpen] = useState(false);

  // 表单下拉选项只取一次；失败不阻塞列表，只让下拉退化。
  useEffect(() => {
    const controller = new AbortController();
    api.referenceData(controller.signal).then(response => setOptions(response.data)).catch(() => setOptions(null));
    return () => controller.abort();
  }, []);

  // 关键字输入停顿后再请求，避免逐字打接口。
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setQuery(previous => previous.keyword === keyword ? previous : {...previous, keyword, page: 1});
    }, 300);
    return () => window.clearTimeout(timer);
  }, [keyword]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(null);
    api.equipment(query, controller.signal)
      .then(response => {
        if (controller.signal.aborted) return;
        setResult(response.data); setTimestamp(response.timestamp); setLoading(false);
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        setError(cause instanceof Error ? cause.message : '读取设备失败。'); setLoading(false);
      });
    return () => controller.abort();
  }, [query, localVersion, refreshToken]);

  const reload = useCallback(() => setLocalVersion(value => value + 1), []);

  // 删除成功后：关抽屉、刷新列表、给出可读结果，避免用户不知道数据已变。
  const handleDeleted = useCallback((detail: EquipmentDetail) => {
    setOpenId(null);
    setNotice(`已删除设备 ${detail.code} · ${detail.name}，变更记录仍可追溯。`);
    setLocalVersion(value => value + 1);
  }, []);
  const rows: Equipment[] = result?.items ?? [];
  const total = result?.total ?? rows.length;
  const pageSize = result?.pageSize ?? PAGE_SIZE;
  const currentPage = result?.page ?? 1;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));

  const patchQuery = (patch: Partial<EquipmentQuery>) => setQuery(previous => ({...previous, ...patch, page: 1}));
  const hasFilter = Boolean(keyword.trim() || query.workshopId || query.typeCode || query.managementStatus || query.enabled !== undefined);
  const clearFilters = () => { setKeyword(''); setQuery({page: 1, pageSize: PAGE_SIZE}); };

  const openCreate = () => { setFormTarget(null); setFormOpen(true); setNotice(null); };
  const openEdit = (detail: EquipmentDetail) => { setFormTarget(detail); setFormOpen(true); setNotice(null); };
  const closeForm = () => { setFormOpen(false); setFormTarget(null); };

  const handleSaved = (detail: EquipmentDetail, mode: 'create' | 'edit') => {
    closeForm();
    setOpenId(detail.id);
    setNotice(mode === 'create'
      ? `已新增 ${detail.name}，当前版本 v${detail.version}。`
      : `${detail.name} 已更正，当前版本 v${detail.version}。`);
    reload();
  };

  return <>
    <div className="metrics">
      <Metric label="生产设备" value={overview?.counts.productionEquipment ?? (result ? total : null)} icon="equipment" detail="设备基础信息" />
      <Metric label="生产车间" value={overview?.counts.workshops ?? null} icon="production" detail="按现有车间组织" />
      <Metric label="关联测点" value={overview?.counts.sensors ?? null} icon="network" detail="已有采集测点" />
    </div>

    <section className="panel equipment-panel" aria-labelledby="equipment-list-title" aria-busy={loading}>
      <div className="panel-heading">
        <div>
          <h2 id="equipment-list-title">生产设备档案<span className="panel-count">{result ? `${total} 台` : '等待读取'}</span></h2>
        </div>
        <div className="eq-head-actions">
          <button type="button" className="button" onClick={() => setDeadlineOpen(true)}>
            期限提醒
          </button>
          <button type="button" className="eq-primary-button" onClick={openCreate}>
            <span aria-hidden="true">＋</span> 新增设备
          </button>
        </div>
      </div>

      {notice && <div className="eq-alert is-warn" role="status" style={{margin: '0 0 12px'}}>
        {notice}
        <button type="button" className="eq-add-param" style={{marginLeft: 10}} onClick={() => setNotice(null)}>知道了</button>
      </div>}

      <div className="filters">
        <label className="search-field">
          <span className="visually-hidden">搜索设备名称或编码</span>
          <Icon name="search" size={18} />
          <input type="search" placeholder="搜索设备名称、编码" value={keyword} onChange={event => setKeyword(event.target.value)} />
        </label>
        <label className="select-field">
          <span>所属车间</span>
          <select value={query.workshopId ?? ''} onChange={event => patchQuery({workshopId: event.target.value || undefined})}>
            <option value="">全部车间</option>
            {(options?.workshops ?? []).map(item => <option value={item.id} key={item.id}>{item.name}</option>)}
          </select>
        </label>
        <label className="select-field">
          <span>设备类型</span>
          <select value={query.typeCode ?? ''} onChange={event => patchQuery({typeCode: event.target.value || undefined})}>
            <option value="">全部类型</option>
            {(options?.equipmentTypes ?? []).map(item => <option value={item.code} key={item.code}>{item.name}</option>)}
          </select>
        </label>
        <label className="select-field">
          <span>管理状态</span>
          <select value={query.managementStatus ?? ''} onChange={event => patchQuery({managementStatus: event.target.value || undefined})}>
            <option value="">全部状态</option>
            {(options?.equipmentManagementStatuses ?? []).map(item => <option value={item.code} key={item.code}>{item.name}</option>)}
          </select>
        </label>
        <label className="select-field">
          <span>启用状态</span>
          <select value={query.enabled === undefined ? '' : String(query.enabled)} onChange={event => patchQuery({enabled: event.target.value === '' ? undefined : event.target.value === 'true'})}>
            <option value="">全部</option>
            <option value="true">已启用</option>
            <option value="false">已停用</option>
          </select>
        </label>
        {hasFilter && <button className="reset-filter" type="button" onClick={clearFilters}>清除筛选</button>}
      </div>

      {error && <div className="state-error" role="alert">
        <div className="state-icon"><Icon name="alert" size={24} /></div>
        <div><h3>暂时无法读取设备</h3><p>{error}</p>{result && <p>下方保留上次读取结果，尚未刷新。</p>}</div>
        <button className="button" type="button" onClick={reload} disabled={loading}>重试读取</button>
      </div>}

      {loading && !result
        ? <div className="loading-state" role="status"><span className="spinner" /><span>正在读取设备档案…</span><div className="skeleton-rows" aria-hidden="true">{[1, 2, 3, 4].map(row => <div key={row}><i /><i /><i /><i /></div>)}</div></div>
        : result && total === 0 && !hasFilter
          ? <div className="empty-state" role="status"><div className="empty-icon"><Icon name="equipment" size={30} /></div><h3>当前没有生产设备</h3><p>设备接口已返回，本次查询未发现设备记录。</p><button type="button" className="button" onClick={reload}>重新读取</button></div>
          : result && rows.length === 0
            ? <div className="empty-state" role="status"><div className="empty-icon"><Icon name="search" size={28} /></div><h3>没有匹配的设备</h3><p>试试其他名称、编码或筛选条件。</p><button type="button" className="button" onClick={clearFilters}>清除筛选</button></div>
            : result && <>
              <div className="table-region" role="region" aria-label="生产设备表，可横向滚动" tabIndex={0}>
                <table>
                  <caption className="visually-hidden">设备档案列表，点击设备名称查看详情</caption>
                  <thead><tr>
                    <th scope="col" className="number-column">序号</th>
                    <th scope="col">设备名称 / 编码</th>
                    <th scope="col">所属车间</th>
                    <th scope="col">设备类型</th>
                    <th scope="col">管理状态</th>
                    <th scope="col">责任人</th>
                  </tr></thead>
                  <tbody>{rows.map((row, index) => <tr key={row.id}>
                    <td className="row-number">{String((currentPage - 1) * pageSize + index + 1).padStart(2, '0')}</td>
                    <td>
                      <button type="button" className="eq-row-link" onClick={() => setOpenId(row.id)}>
                        <span className="device-symbol"><Icon name="equipment" size={20} /></span>
                        <span>
                          <strong>{row.name}</strong>
                          <span className="device-code"><span className="eq-id-tag">设备编码</span>{row.code}</span>
                        </span>
                      </button>
                    </td>
                    <td><span className="workshop-marker" />{row.workshopName}</td>
                    <td>{row.typeName ?? <span className="missing-value">待补</span>}</td>
                    <td>
                      <span className="eq-status-cell">
                        {row.managementStatus
                          ? <span className={`eq-badge ${statusClass(row.managementStatus)}`}>
                            {options?.equipmentManagementStatuses.find(item => item.code === row.managementStatus)?.name ?? row.managementStatus}
                          </span>
                          : <span className="missing-value">待补</span>}
                        <span className="eq-subtle">系统{row.enabled ? '已启用' : '已停用'}</span>
                      </span>
                    </td>
                    <td>{isPlaceholderPerson(row.responsiblePerson)
                      ? <span className="missing-value">{row.responsiblePerson || '未指定'}</span>
                      : row.responsiblePerson}</td>
                  </tr>)}</tbody>
                </table>
              </div>
              <div className="table-footer">
                <span role="status">共 {total} 台，第 {currentPage} / {pageCount} 页</span>
                <span className="eq-pager">
                  <button type="button" className="button button-small" onClick={() => setQuery(previous => ({...previous, page: currentPage - 1}))} disabled={loading || currentPage <= 1}>上一页</button>
                  <button type="button" className="button button-small" onClick={() => setQuery(previous => ({...previous, page: currentPage + 1}))} disabled={loading || currentPage >= pageCount}>下一页</button>
                  <span className="eq-read-time"><Icon name="clock" size={14} />最近读取 {readableTime(timestamp)}</span>
                </span>
              </div>
            </>}

      <div className="panel-note"><span className="note-dot" /><p>点击设备名称可查看档案详情与变更记录；档案更正后会留下版本痕迹，多人同时编辑时后提交者会被要求重新读取。</p></div>
    </section>

    {openId && <EquipmentDetailDrawer
      key={`${openId}-${localVersion}`}
      equipmentId={openId}
      options={options}
      refreshToken={localVersion}
      onClose={() => setOpenId(null)}
      onEdit={openEdit}
      onDeleted={handleDeleted} />}

    {deadlineOpen && <DeadlinePanel onClose={() => setDeadlineOpen(false)} />}

    {formOpen && <EquipmentFormDialog
      key={formTarget?.id ?? 'create'}
      detail={formTarget}
      options={options}
      onClose={closeForm}
      onSaved={handleSaved} />}
  </>;
}
