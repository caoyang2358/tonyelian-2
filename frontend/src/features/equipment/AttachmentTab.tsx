import { useEffect, useRef, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type { AttachmentFileKind, AttachmentItem } from '../../lib/api';
import './equipment.css';

/**
 * 设备详情里的"附件"页签（S1-F01—F05）。
 *
 * <p>附件归属靠 ownerType + ownerId 表达，所以这一页只关心"本设备的附件"；
 * 同一套接口也被合同、知识条目和维修单复用，不为某个模块单独开一套上传入口。</p>
 *
 * <p>页面上没有删除：契约 F01—F05 没有删除接口，误传的文件用"替换"覆盖内容，
 * 附件 ID 与既有引用（例如知识页引用的手册）保持不变。</p>
 */

const MAX_FILE_SIZE = 20 * 1024 * 1024;

/** 设备页只放档案配图、手册和合同文档；报修照片属于工单，不在设备档案里出现。 */
const KINDS: {value: AttachmentFileKind; label: string}[] = [
  {value: 'IMAGE', label: '设备配图'},
  {value: 'MANUAL', label: '设备手册'},
  {value: 'DOCUMENT', label: '文档资料'},
];

const KIND_LABELS: Record<AttachmentFileKind, string> = {
  IMAGE: '设备配图', MANUAL: '设备手册', DOCUMENT: '文档资料', PHOTO: '报修照片',
};

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`;
}

function rememberedOperator(): string {
  try { return window.localStorage.getItem('eq-operator-name')?.trim() || '设备管理员'; }
  catch { return '设备管理员'; }
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

function formatTime(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return value;
  return date.toLocaleString('zh-CN', {hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'});
}

/** 上传前的本地检查只挡明显的错误，类型与大小仍以服务端为准，避免两套规则各说各话。 */
function localError(file: File): string | null {
  if (file.size === 0) return '这个文件是空的，请重新选择。';
  if (file.size > MAX_FILE_SIZE) return `文件大小为 ${formatSize(file.size)}，超过 20 MB 上限。`;
  return null;
}

export function AttachmentTab({equipmentId, refreshToken}: {equipmentId: string; refreshToken: number}) {
  const [rows, setRows] = useState<AttachmentItem[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const [kind, setKind] = useState<AttachmentFileKind>('IMAGE');
  const [uploading, setUploading] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const uploadRef = useRef<HTMLInputElement>(null);

  const [replacing, setReplacing] = useState<AttachmentItem | null>(null);
  const [replaceFile, setReplaceFile] = useState<File | null>(null);
  const [replaceReason, setReplaceReason] = useState('');
  const [replaceError, setReplaceError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    api.attachments({ownerType: 'EQUIPMENT', ownerId: equipmentId, pageSize: 100}, controller.signal)
      .then(result => { setRows(result.data.items); setError(null); })
      .catch((failure: unknown) => {
        if (failure instanceof Error && failure.name === 'AbortError') return;
        setError(failure instanceof ApiError ? failure.message : '附件列表读取失败。');
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [equipmentId, refreshToken, reloadKey]);

  async function upload(file: File) {
    const invalid = localError(file);
    if (invalid) { setFormError(invalid); return; }
    setUploading(true); setFormError(null);
    try {
      await api.uploadAttachment(file, {
        requestId: newRequestId(), operatorName: rememberedOperator(),
        ownerType: 'EQUIPMENT', ownerId: equipmentId, fileKind: kind,
      });
      setReloadKey(key => key + 1);
    } catch (failure: unknown) {
      if (failure instanceof ApiError && Object.keys(failure.fields).length > 0) {
        setFormError(Object.values(failure.fields)[0]);
      } else {
        setFormError(failure instanceof ApiError ? failure.message : '上传失败，请稍后重试。');
      }
    } finally {
      setUploading(false);
    }
  }

  function pick(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    // 清空 value：否则再次选同一个文件时不会触发 change，用户会以为点击没反应。
    event.target.value = '';
    if (file) void upload(file);
  }

  function openReplace(row: AttachmentItem) {
    setReplacing(row);
    setReplaceFile(null);
    setReplaceReason('');
    setReplaceError(null);
  }

  async function submitReplace() {
    if (!replacing) return;
    if (!replaceFile) { setReplaceError('请选择要替换成的新文件。'); return; }
    const invalid = localError(replaceFile);
    if (invalid) { setReplaceError(invalid); return; }
    if (!replaceReason.trim()) { setReplaceError('替换原因不得为空，它会写入变更记录。'); return; }

    setSaving(true); setReplaceError(null);
    try {
      await api.replaceAttachmentContent(replacing.attachmentId, replaceFile, {
        requestId: newRequestId(), operatorName: rememberedOperator(),
        expectedVersion: replacing.version, reason: replaceReason.trim(),
      });
      setReplacing(null);
      setReloadKey(key => key + 1);
    } catch (failure: unknown) {
      if (failure instanceof ApiError && failure.code === 'VERSION_CONFLICT') {
        setReplaceError('这份附件在你操作期间已被他人替换，请关闭后重新打开再试。');
      } else if (failure instanceof ApiError && Object.keys(failure.fields).length > 0) {
        setReplaceError(Object.values(failure.fields)[0]);
      } else {
        setReplaceError(failure instanceof ApiError ? failure.message : '替换失败，请稍后重试。');
      }
    } finally {
      setSaving(false);
    }
  }

  const busy = loading && rows === null;

  return <>
    <div className="eq-section-bar">
      <h3 className="eq-section-title">附件</h3>
      <div className="eq-att-tools">
        <select className="eq-att-kind" value={kind} disabled={uploading} aria-label="附件类别"
                onChange={event => { setKind(event.target.value as AttachmentFileKind); setFormError(null); }}>
          {KINDS.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}
        </select>
        <button type="button" className="eq-primary-button" disabled={uploading}
                onClick={() => uploadRef.current?.click()}>
          {uploading ? '上传中…' : '上传附件'}
        </button>
        <input ref={uploadRef} type="file" hidden onChange={pick} />
      </div>
    </div>

    {formError && <p className="eq-form-error">{formError}</p>}
    {error && <div className="eq-notice"><span>!</span><div>{error}</div></div>}
    {busy && <div className="eq-loading">正在读取附件…</div>}

    {!busy && rows !== null && (rows.length === 0
      ? <p className="eq-param-empty">这台设备还没有附件。可以上传设备配图、手册或合同类文档。</p>
      : <ul className="eq-supplier-list">
        {rows.map(row => <li key={row.attachmentId} className="eq-supplier-item">
          <div className="eq-supplier-main">
            <strong className="eq-att-name" title={row.originalName}>{row.originalName}</strong>
            <small className="eq-supplier-count">
              {KIND_LABELS[row.fileKind]} · {formatSize(row.size)} · v{row.version}
            </small>
            <small className="eq-supplier-count">更新于 {formatTime(row.updatedAt)}</small>
          </div>
          <div className="eq-supplier-actions">
            <a className="eq-row-link" href={api.attachmentUrl(row.attachmentId)} target="_blank" rel="noreferrer">查看</a>
            <a className="eq-row-link" href={api.attachmentUrl(row.attachmentId, true)}>下载</a>
            <button type="button" className="eq-row-link" onClick={() => openReplace(row)}>替换</button>
          </div>
        </li>)}
      </ul>)}
    <p className="eq-form-hint">
      支持图片、PDF、Word、Excel 与纯文本，单个文件不超过 20 MB。附件不提供删除：传错了请用"替换"更新内容，
      已被知识页引用的手册会继续指向同一个附件 ID。
    </p>

    {replacing && <div className="eq-overlay" role="presentation"
                      onMouseDown={event => { if (event.target === event.currentTarget && !saving) setReplacing(null); }}>
      <div className="eq-dialog-wrap" role="dialog" aria-modal="true" aria-label="替换附件内容">
        <div className="eq-dialog-head">
          <h2>替换附件内容</h2>
          <p>{replacing.originalName} · 当前版本 v{replacing.version}</p>
        </div>
        <div className="eq-dialog-body">
          <div className="eq-form-row is-full">
            <label className="eq-form-field">
              <span>新文件<em>*</em></span>
              <input type="file" onChange={event => { setReplaceFile(event.target.files?.[0] ?? null); setReplaceError(null); }} />
              {replaceFile
                ? <small className="eq-form-hint">{replaceFile.name} · {formatSize(replaceFile.size)}</small>
                : <small className="eq-form-hint">附件 ID 与引用关系保持不变，只更新内容与版本。</small>}
            </label>
          </div>
          <div className="eq-form-row is-full">
            <label className="eq-form-field">
              <span>替换原因<em>*</em></span>
              <input value={replaceReason} maxLength={1000} disabled={saving}
                     placeholder="例如：厂家补发 2026 版手册"
                     onChange={event => { setReplaceReason(event.target.value); setReplaceError(null); }} />
              <small className="eq-form-hint">替换原因会写入变更记录，供日后追溯。</small>
            </label>
          </div>
          {replaceError && <p className="eq-form-error">{replaceError}</p>}
        </div>
        <div className="eq-dialog-foot">
          <span className="eq-spacer" />
          <button type="button" className="button" onClick={() => setReplacing(null)} disabled={saving}>取消</button>
          <button type="button" className="eq-primary-button" onClick={submitReplace} disabled={saving}>
            {saving ? '替换中…' : '确认替换'}
          </button>
        </div>
      </div>
    </div>}
  </>;
}
