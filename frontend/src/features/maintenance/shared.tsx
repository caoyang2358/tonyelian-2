import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../../lib/api';
import type { AttachmentItem, Equipment } from '../../lib/api';
import { newRequestId } from '../../lib/maintenanceApi';
import { label } from './labels';

/* ================= 状态标签 =================
 * 事件、巡检、工单三张表共用一套配色：进行中蓝、已完结绿、待办黄、异常红、其余灰。
 * 放在 shared 里，避免各处重复定义导致同一状态两种颜色。 */
export function stateTagClass(state: string): string {
  if (state === 'ONGOING' || state === 'IN_REPAIR') return 'ops-tag-blue';
  if (state === 'RECOVERED' || state === 'ACKNOWLEDGED' || state === 'COMPLETED') return 'ops-tag-green';
  if (state === 'PENDING' || state === 'DEFER_REPAIR' || state === 'RETURNED_FOR_INFO' || state === 'REVIEW_PENDING') return 'ops-tag-amber';
  if (state === 'ABNORMAL') return 'ops-tag-red';
  return 'ops-tag-gray';
}

export function Tag({value, map}: {value: string; map: Record<string, string>}) {
  return <span className={`ops-tag ${stateTagClass(value)}`}>{label(map, value)}</span>;
}

/* ================= 口径/术语提示 =================
 * 标题旁的小问号：悬停或键盘聚焦才展开说明，避免页面上铺满解释性文字。 */
export function Tip({text}: {text: string | undefined}) {
  if (!text) return null;
  return <span className="ops-tip" tabIndex={0} role="note" aria-label={text} data-tip={text}>?</span>;
}

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
  equipmentCache ??= api.equipment({})
    .then(result => result.data.items.filter(item => item.enabled))
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
  /** MANUAL 用于设备没有测点、无法登记事件时直接建单，此时 sourceId 为空。 */
  sourceType: 'ALERT_EVENT' | 'INSPECTION' | 'MANUAL';
  sourceId: string;
  equipmentId: string;
  summary: string;
}

/* ================= 现场照片 =================
 * 照片走共用附件接口（ownerType + ownerId），不单独建一套表：
 * 巡检登记、巡检更正、报修建单、维修结果共用同一份上传逻辑，避免四套代码四种错法。 */

export type PhotoOwner = 'REPAIR_ORDER' | 'INSPECTION';

function messageOf(error: unknown): string {
  if (error && typeof error === 'object' && 'message' in error && typeof (error as {message: unknown}).message === 'string') {
    return (error as {message: string}).message;
  }
  return '上传失败，请重试。';
}

/** 逐张上传：单张失败不影响其余照片，失败的按文件名回传，便于用户只补那一张。 */
export async function uploadPhotos(ownerType: PhotoOwner, ownerId: string, operatorName: string,
                                   files: File[]): Promise<{uploaded: number; uploadedIds: string[]; failed: string[]}> {
  const failed: string[] = [];
  const uploadedIds: string[] = [];
  for (const file of files) {
    try {
      const detail = await api.uploadAttachment(file, {
        requestId: newRequestId(), operatorName, ownerType, ownerId, fileKind: 'PHOTO',
      });
      uploadedIds.push(detail.data.attachmentId);
    } catch (error) {
      failed.push(`${file.name}：${messageOf(error)}`);
    }
  }
  return {uploaded: uploadedIds.length, uploadedIds, failed};
}

/** 已上传照片的缩略图墙；operatorName 有值时同时提供"继续上传"。 */
export function PhotoSection({ownerType, ownerId, operatorName, refreshKey = 0, emptyHint, onLoaded}: {
  ownerType: PhotoOwner; ownerId: string; operatorName?: string; refreshKey?: number; emptyHint?: string;
  /** 把当前照片 ID 交给父组件：保存资料时要带回去，否则会被判为"删除了全部照片"。 */
  onLoaded?: (attachmentIds: string[]) => void;
}) {
  const [items, setItems] = useState<AttachmentItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [version, setVersion] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let alive = true;
    setError(null);
    api.attachments({ownerType, ownerId, pageSize: 100})
      .then(result => {
        if (!alive) return;
        setItems(result.data.items);
        onLoaded?.(result.data.items.map(item => item.attachmentId));
      })
      .catch(err => { if (alive) { setItems([]); setError(messageOf(err)); } });
    return () => { alive = false; };
  }, [ownerType, ownerId, refreshKey, version, onLoaded]);

  const pick = useCallback(async (event: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    event.target.value = '';
    if (!files.length || !operatorName?.trim()) return;
    setUploading(true);
    try {
      const result = await uploadPhotos(ownerType, ownerId, operatorName.trim(), files);
      setError(result.failed.length ? `部分照片未上传成功：${result.failed.join('；')}` : null);
      setVersion(value => value + 1);
    } finally {
      setUploading(false);
    }
  }, [ownerType, ownerId, operatorName]);

  return <div className="ops-photos">
    <div className="ops-photos-head">
      <span>现场照片{items ? `（${items.length} 张）` : ''}</span>
      {operatorName?.trim()
        ? <><button className="button" type="button" disabled={uploading} onClick={() => inputRef.current?.click()}>
            {uploading ? '上传中…' : '上传照片'}
          </button>
          <input ref={inputRef} type="file" accept="image/jpeg,image/png,image/webp,image/gif,image/bmp"
            multiple hidden onChange={event => void pick(event)} />
        </>
        : <span className="ops-photos-tip">先填写经办人后可上传</span>}
    </div>
    {error && <p className="ops-feedback-error">{error}</p>}
    {items === null ? <p className="missing-value">正在读取照片…</p>
      : items.length === 0 ? <p className="missing-value">{emptyHint ?? '暂无现场照片（选填，不强制）。'}</p>
      : <div className="ops-photo-grid">{items.map(item => <a className="ops-photo" key={item.attachmentId}
          href={api.attachmentUrl(item.attachmentId)} target="_blank" rel="noreferrer" title={`${item.originalName} · ${Math.max(1, Math.round(item.size / 1024))} KB`}>
          <img src={api.attachmentUrl(item.attachmentId)} alt={item.originalName} loading="lazy" />
          <span>{item.originalName}</span>
        </a>)}</div>}
  </div>;
}

/** 建单/登记"之前"选好的本地照片：此时业务对象还没有编号，先拿在手里，保存成功后立刻上传。 */
export function PhotoPicker({files, onChange, disabled}: {
  files: File[]; onChange: (files: File[]) => void; disabled?: boolean;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [previews, setPreviews] = useState<string[]>([]);

  useEffect(() => {
    const urls = files.map(file => URL.createObjectURL(file));
    setPreviews(urls);
    return () => urls.forEach(url => URL.revokeObjectURL(url));
  }, [files]);

  return <div className="ops-photos">
    <div className="ops-photos-head">
      <span>现场照片（选填，可多选）</span>
      <button className="button" type="button" disabled={disabled} onClick={() => inputRef.current?.click()}>选择照片</button>
      {files.length > 0 && <button className="reset-filter" type="button" disabled={disabled}
        onClick={() => onChange([])}>清空</button>}
      <input ref={inputRef} type="file" accept="image/jpeg,image/png,image/webp,image/gif,image/bmp"
        multiple hidden onChange={event => {
          const picked = Array.from(event.target.files ?? []);
          event.target.value = '';
          if (picked.length) onChange([...files, ...picked]);
        }} />
    </div>
    {files.length === 0
      ? <p className="missing-value">尚未选择照片；不选也能提交，功能具备即可。</p>
      : <div className="ops-photo-grid">{files.map((file, index) => <div className="ops-photo" key={`${file.name}-${index}`}>
          {previews[index] && <img src={previews[index]} alt={file.name} />}
          <span>{file.name}</span>
        </div>)}</div>}
  </div>;
}
