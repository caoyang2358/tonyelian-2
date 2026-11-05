import { useCallback, useEffect, useState } from 'react';
import { api } from '../../lib/api';
import type { Equipment } from '../../lib/api';

/* ================= 经办人记忆 =================
 * 项目暂无登录体系，经办人无法由会话带入；至少记住上次填写的值，
 * 避免每次操作都重新输入（仅存本机 localStorage，不上传）。 */
const OPERATOR_KEY = 'copper.maintenance.operator';

export function useOperatorName(initial = ''): [string, (value: string) => void] {
  const [value, setValue] = useState(() => {
    try { return window.localStorage.getItem(OPERATOR_KEY) ?? initial; } catch { return initial; }
  });
  const update = useCallback((next: string) => {
    setValue(next);
    try { window.localStorage.setItem(OPERATOR_KEY, next); } catch { /* 隐私模式下忽略 */ }
  }, []);
  return [value, update];
}

/* ================= 设备选项 =================
 * 复用既有 /api/v1/equipment（设备管理页同一数据源），模块级缓存避免每个下拉重复请求。 */
let equipmentCache: Promise<Equipment[]> | null = null;

function loadEquipment(): Promise<Equipment[]> {
  equipmentCache ??= api.equipment()
    .then(result => result.data.filter(item => item.enabled))
    .catch(error => { equipmentCache = null; throw error; });
  return equipmentCache;
}

export function useEquipmentOptions(): {equipment: Equipment[]; state: 'loading' | 'ready' | 'error'} {
  const [equipment, setEquipment] = useState<Equipment[]>([]);
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  useEffect(() => {
    let alive = true;
    loadEquipment()
      .then(list => { if (alive) { setEquipment(list); setState('ready'); } })
      .catch(() => { if (alive) setState('error'); });
    return () => { alive = false; };
  }, []);
  return {equipment, state};
}

interface EquipmentSelectProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  /** 允许“全部/不限”，用于筛选场景 */
  allowEmpty?: boolean;
  emptyLabel?: string;
  width?: number;
}

/** 设备下拉：正常时按“设备名称（编码）”选择；读取失败时退化为编号输入框，保证功能不中断。 */
export function EquipmentSelect({label, value, onChange, allowEmpty = false, emptyLabel = '全部设备', width}: EquipmentSelectProps) {
  const {equipment, state} = useEquipmentOptions();
  if (state === 'loading') {
    return <label className="select-field"><span>{label}</span>
      <select disabled defaultValue=""><option value="">读取设备…</option></select></label>;
  }
  if (state === 'error') {
    return <label className="select-field"><span>{label}编号</span>
      <input value={value} onChange={event => onChange(event.target.value.replace(/[^0-9]/g, ''))}
        size={width ?? 6} placeholder="设备未读取，可填编号" inputMode="numeric" /></label>;
  }
  return <label className="select-field"><span>{label}</span>
    <select value={value} onChange={event => onChange(event.target.value)} style={width ? {maxWidth: `${width * 10}px`} : undefined}>
      {allowEmpty && <option value="">{emptyLabel}</option>}
      {!allowEmpty && <option value="" disabled>请选择设备</option>}
      {equipment.map(item => <option value={item.id} key={item.id}>{item.name}（{item.code}）</option>)}
    </select></label>;
}

/** 车间下拉：从设备档案里汇总去重，避免直接查车间主数据表。 */
export function useWorkshopOptions(): {id: string; name: string}[] {
  const {equipment} = useEquipmentOptions();
  const seen = new Map<string, string>();
  for (const item of equipment) {
    if (!seen.has(item.workshopId)) seen.set(item.workshopId, item.workshopName);
  }
  return [...seen.entries()].map(([id, name]) => ({id, name}));
}

/* ================= 分页器 ================= */

interface PagerProps {
  page: number;
  pageSize: number;
  total: number;
  unit: string;
  onPage: (page: number) => void;
  onPageSize: (size: number) => void;
}

export function Pager({page, pageSize, total, unit, onPage, onPageSize}: PagerProps) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const first = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const last = Math.min(page * pageSize, total);
  return <div className="ops-pager">
    <span className="ops-pager-range">第 {first}–{last} 条，共 {total} {unit}</span>
    <div className="ops-pager-controls">
      <label className="ops-pager-size">
        <span>每页</span>
        <select value={pageSize} onChange={event => onPageSize(Number(event.target.value))}>
          {[10, 20, 50, 100].map(size => <option value={size} key={size}>{size}</option>)}
        </select>
      </label>
      <button className="button" type="button" onClick={() => onPage(page - 1)} disabled={page <= 1}>上一页</button>
      <span className="ops-pager-num">{page} / {pages}</span>
      <button className="button" type="button" onClick={() => onPage(page + 1)} disabled={page >= pages}>下一页</button>
    </div>
  </div>;
}

/* ================= 跨页签报修交接 ================= */

export interface RepairHandoff {
  sourceType: 'ALERT_EVENT' | 'INSPECTION';
  sourceId: string;
  equipmentId: string;
  summary: string;
}
