import { useEffect, useRef, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type { AttachmentFileKind, AttachmentItem } from '../../lib/api';
import {
  CONTRACT_ACCEPT, MAX_FILE_SIZE_TEXT, acceptOf, formatSize, localFileError, supportHintOf,
} from './attachmentLimits';
import './equipment.css';

/**
 * 维保合同卡片里的"合同文件"区（S1-F03 / F04，ownerType=MAINTENANCE_CONTRACT）。
 *
 * <p>与设备"附件"页签共用同一套附件接口，区别只在归属：合同文件挂在 contract_id 上，
 * 换设备、加设备都不会把它们带进别的设备档案；同一份合同的扫描件也只有一份，
 * 不会因为合同覆盖三台设备就在三处各存一遍。</p>
 *
 * <p>类别由文件本身决定，不让用户选：图片走 IMAGE、其余走 DOCUMENT。合同扫描件
 * 多数是手机拍的图，只开 DOCUMENT（PDF/Word/文本）会把最常见的上传方式挡在门外。</p>
 */

/** 合同语境下的类别名，和"设备配图 / 设备手册"分开说，避免两张页签的文案混在一起。 */
const KIND_LABELS: Record<AttachmentFileKind, string> = {
  IMAGE: '扫描图片', MANUAL: '合同附件', DOCUMENT: '合同文档', PHOTO: '现场照片',
};

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`;
}

function rememberedOperator(): string {
  try { return window.localStorage.getItem('eq-operator-name')?.trim() || '设备管理员'; }
  catch { return '设备管理员'; }
}

function formatTime(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return value;
  return date.toLocaleString('zh-CN', {hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'});
}

/**
 * 合同文件不让用户选类别，按文件本身归类。
 *
 * <p>先看 MIME，再看扩展名兜底：浏览器偶尔不给 MIME（或只给 application/octet-stream），
 * 此时一律归 DOCUMENT，会让 png 扫描件撞上文档白名单被服务端拒收。</p>
 */
function kindFor(file: File): AttachmentFileKind {
  if (file.type.startsWith('image/')) return 'IMAGE';
  return /\.(jpe?g|png|webp|gif|bmp)$/i.test(file.name) ? 'IMAGE' : 'DOCUMENT';
}

export function ContractFiles({contractId, readOnly = false}: {contractId: string; readOnly?: boolean}) {
  const [rows, setRows] = useState<AttachmentItem[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const [uploading, setUploading] = useState(false);
  const uploadRef = useRef<HTMLInputElement>(null);

  const [replacing, setReplacing] = useState<AttachmentItem | null>(null);
  const [replaceFile, setReplaceFile] = useState<File | null>(null);
  const [replaceReason, setReplaceReason] = useState('');
  const [replaceError, setReplaceError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    api.attachments({ownerType: 'MAINTENANCE_CONTRACT', ownerId: contractId, pageSize: 100}, controller.signal)
      .then(result => { setRows(result.data.items); setError(null); })
      .catch((failure: unknown) => {
        if (failure instanceof Error && failure.name === 'AbortError') return;
        setError(failure instanceof ApiError ? failure.message : '合同文件读取失败。');
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [contractId, reloadKey]);

  async function upload(file: File) {
    const invalid = localFileError(file);
    if (invalid) { setFormError(invalid); return; }
    setUploading(true);
    setFormError(null);
    try {
      await api.uploadAttachment(file, {
        requestId: newRequestId(), operatorName: rememberedOperator(),
        ownerType: 'MAINTENANCE_CONTRACT', ownerId: contractId, fileKind: kindFor(file),
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
    // 清空 value，否则再选同一个文件不会触发 change，用户会以为点击没反应。
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
    const invalid = localFileError(replaceFile);
    if (invalid) { setReplaceError(invalid); return; }
    if (!replaceReason.trim()) { setReplaceError('替换原因不得为空，它会写入变更记录。'); return; }

    setSaving(true);
    setReplaceError(null);
    try {
      await api.replaceAttachmentContent(replacing.attachmentId, replaceFile, {
        requestId: newRequestId(), operatorName: rememberedOperator(),
        expectedVersion: replacing.version, reason: replaceReason.trim(),
      });
      setReplacing(null);
      setReloadKey(key => key + 1);
    } catch (failure: unknown) {
      if (failure instanceof ApiError && failure.code === 'VERSION_CONFLICT') {
        setReplaceError('这份文件在你操作期间已被他人替换，请关闭后重新打开再试。');
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
    <div className="eq-contract-files">
      <div className="eq-contract-files-head">
        <span className="eq-contract-files-title">
          合同文件{rows && rows.length > 0 ? <em> · {rows.length}</em> : null}
        </span>
        {!readOnly && <>
          <button type="button" className="eq-row-link eq-contract-upload" disabled={uploading}
                  onClick={() => uploadRef.current?.click()}>
            {uploading ? '上传中…' : '+ 上传'}
          </button>
          <input ref={uploadRef} type="file" hidden accept={CONTRACT_ACCEPT} onChange={pick} />
        </>}
      </div>

      {formError && <p className="eq-form-error">{formError}</p>}
      {error && <div className="eq-notice"><span>!</span><div>{error}</div></div>}
      {busy && <div className="eq-loading">正在读取合同文件…</div>}

      {!busy && rows !== null && (rows.length === 0
        ? <p className="eq-contract-empty">
            尚未上传合同文件。<br />
            {`支持 PDF、Word、Excel 与图片扫描件，单个不超过 ${MAX_FILE_SIZE_TEXT}。`}
          </p>
        : <ul className="eq-contract-file-list">
          {rows.map(row => <li key={row.attachmentId} className="eq-contract-file">
            <div className="eq-contract-file-main">
              <a className="eq-contract-file-name" href={api.attachmentUrl(row.attachmentId)}
                 target="_blank" rel="noreferrer" title={row.originalName}>{row.originalName}</a>
              <small className="eq-contract-file-meta">
                {KIND_LABELS[row.fileKind]} · {formatSize(row.size)} · v{row.version} · {formatTime(row.updatedAt)}
              </small>
            </div>
            <div className="eq-contract-file-actions">
              <a className="eq-row-link" href={api.attachmentUrl(row.attachmentId)} target="_blank" rel="noreferrer">查看</a>
              <a className="eq-row-link" href={api.attachmentUrl(row.attachmentId, true)}>下载</a>
              {!readOnly && <button type="button" className="eq-row-link" onClick={() => openReplace(row)}>替换</button>}
            </div>
          </li>)}
        </ul>)}
    </div>

    {replacing && <div className="eq-overlay" role="presentation"
                      onMouseDown={event => { if (event.target === event.currentTarget && !saving) setReplacing(null); }}>
      <div className="eq-dialog-wrap" role="dialog" aria-modal="true" aria-label="替换合同文件">
        <div className="eq-dialog-head">
          <h2>替换合同文件</h2>
          <p>{replacing.originalName} · 当前版本 v{replacing.version}</p>
        </div>
        <div className="eq-dialog-body">
          <div className="eq-form-row is-full">
            <label className="eq-form-field">
              <span>新文件<em>*</em></span>
              <input type="file" accept={acceptOf(replacing.fileKind)}
                     onChange={event => { setReplaceFile(event.target.files?.[0] ?? null); setReplaceError(null); }} />
              {replaceFile
                ? <small className="eq-form-hint">{replaceFile.name} · {formatSize(replaceFile.size)}</small>
                : <small className="eq-form-hint">
                    {`只能换成同一类别的文件，${supportHintOf([replacing.fileKind])}合同与设备的关联不变，只更新这份文件的内容与版本。`}
                  </small>}
            </label>
          </div>
          <div className="eq-form-row is-full">
            <label className="eq-form-field">
              <span>替换原因<em>*</em></span>
              <input value={replaceReason} maxLength={1000} disabled={saving}
                     placeholder="例如：换签后的正式盖章版"
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
