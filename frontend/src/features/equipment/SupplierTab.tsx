import { useEffect, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type { SupplierDetail, SupplierSummary } from '../../lib/api';
import './equipment.css';

/**
 * 设备详情里的"供应商"页签（S1-S01/S02/S03/S04）。
 *
 * <p>供应商挂在设备上，所以这一页只做三件事：看当前设备关联了谁、
 * 新拉一个供应商进来、改联系方式或解绑。新增时自动带上当前设备，
 * 编辑时提交完整的 equipmentIds（整体替换语义），避免一次只改电话却把关联清空。</p>
 */

interface Draft {
  name: string;
  contactName: string;
  phone: string;
  remark: string;
}

const EMPTY: Draft = {name: '', contactName: '', phone: '', remark: ''};

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`;
}

function rememberedOperator(): string {
  try { return window.localStorage.getItem('eq-operator-name')?.trim() || '设备管理员'; }
  catch { return '设备管理员'; }
}

function text(value: string | null | undefined): string {
  return value == null || value === '' ? '—' : value;
}

export function SupplierTab({equipmentId, refreshToken}: {equipmentId: string; refreshToken: number}) {
  const [rows, setRows] = useState<SupplierSummary[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const [draft, setDraft] = useState<Draft | null>(null);
  const [editing, setEditing] = useState<SupplierDetail | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    api.suppliers({equipmentId, pageSize: 100}, controller.signal)
      .then(result => { setRows(result.data.items); setError(null); })
      .catch((failure: unknown) => {
        if (failure instanceof Error && failure.name === 'AbortError') return;
        setError(failure instanceof ApiError ? failure.message : '供应商列表读取失败。');
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [equipmentId, refreshToken, reloadKey]);

  function openCreate() {
    setEditing(null);
    setDraft(EMPTY);
    setFormError(null);
  }

  async function openEdit(row: SupplierSummary) {
    try {
      const result = await api.supplierDetail(row.supplierId);
      const detail = result.data;
      setEditing(detail);
      setDraft({
        name: detail.name,
        contactName: detail.contactName ?? '',
        phone: detail.phone ?? '',
        remark: detail.remark ?? '',
      });
      setFormError(null);
    } catch (failure: unknown) {
      setError(failure instanceof ApiError ? failure.message : '供应商详情读取失败。');
    }
  }

  async function save() {
    if (!draft) return;
    if (!draft.name.trim()) {
      setFormError('供应商名称不得为空。');
      return;
    }
    setSaving(true);
    try {
      const values = {
        name: draft.name.trim(),
        contactName: draft.contactName.trim() || null,
        phone: draft.phone.trim() || null,
        remark: draft.remark.trim() || null,
        // 新增时自动关联当前设备；编辑时保留原有全量关联，不因改电话而丢关系。
        equipmentIds: editing ? editing.equipmentIds : [equipmentId],
      };
      if (editing) {
        await api.updateSupplier(editing.supplierId, values, {
          requestId: newRequestId(),
          operatorName: rememberedOperator(),
          expectedVersion: editing.version,
        });
      } else {
        await api.createSupplier(values, {
          requestId: newRequestId(),
          operatorName: rememberedOperator(),
        });
      }
      setDraft(null);
      setEditing(null);
      setReloadKey(key => key + 1);
    } catch (failure: unknown) {
      if (failure instanceof ApiError && Object.keys(failure.fields).length > 0) {
        setFormError(Object.values(failure.fields)[0]);
      } else {
        setFormError(failure instanceof ApiError ? failure.message : '保存失败，请稍后重试。');
      }
    } finally {
      setSaving(false);
    }
  }

  async function unbind(row: SupplierSummary) {
    if (!window.confirm(`确定解除「${row.name}」与本设备的关联吗？供应商资料会保留。`)) return;
    try {
      const result = await api.supplierDetail(row.supplierId);
      const detail = result.data;
      await api.updateSupplier(row.supplierId, {
        name: detail.name,
        contactName: detail.contactName,
        phone: detail.phone,
        remark: detail.remark,
        equipmentIds: detail.equipmentIds.filter(item => item !== equipmentId),
      }, {
        requestId: newRequestId(),
        operatorName: rememberedOperator(),
        expectedVersion: detail.version,
      });
      setReloadKey(key => key + 1);
    } catch (failure: unknown) {
      setError(failure instanceof ApiError ? failure.message : '解绑失败，请刷新后重试。');
    }
  }

  const busy = loading && rows === null;

  return <>
    <div className="eq-section-bar">
      <h3 className="eq-section-title">关联供应商</h3>
      <button type="button" className="eq-primary-button" onClick={openCreate}>关联供应商</button>
    </div>

    {error && <div className="eq-notice"><span>!</span><div>{error}</div></div>}
    {busy && <div className="eq-loading">正在读取供应商…</div>}

    {!busy && rows !== null && (rows.length === 0
      ? <p className="eq-param-empty">该设备尚未关联供应商。备件与维保来源可在此登记。</p>
      : <ul className="eq-supplier-list">
        {rows.map(row => <li key={row.supplierId} className="eq-supplier-item">
          <div className="eq-supplier-main">
            <strong>{row.name}</strong>
            <small className="eq-supplier-count">共关联 {row.equipmentCount} 台设备</small>
          </div>
          <div className="eq-supplier-meta">
            {text(row.contactName)}<br />{text(row.phone)}
          </div>
          <div className="eq-supplier-actions">
            <button type="button" className="eq-row-link" onClick={() => openEdit(row)}>编辑</button>
            <button type="button" className="eq-row-link" onClick={() => unbind(row)}>解绑</button>
          </div>
        </li>)}
      </ul>)}
    <p className="eq-form-hint">供应商资料由设备管理共用；解绑只断开与本设备的关系，不删除供应商记录。</p>

    {draft && <div className="eq-overlay" role="presentation"
                 onMouseDown={event => { if (event.target === event.currentTarget && !saving) setDraft(null); }}>
      <div className="eq-dialog-wrap" role="dialog" aria-modal="true" aria-label={editing ? '编辑供应商' : '关联供应商'}>
        <div className="eq-dialog-head">
          <h2>{editing ? '编辑供应商' : '关联供应商'}</h2>
          <p>{editing ? `供应商 ID ${editing.supplierId} · 版本 ${editing.version}` : '保存后自动关联当前设备'}</p>
        </div>
        <div className="eq-dialog-body">
          <div className="eq-form-row is-full">
            <label className="eq-form-field">
              <span>供应商名称<em>*</em></span>
              <input value={draft.name} maxLength={150} autoFocus
                     onChange={event => setDraft({...draft, name: event.target.value})}
                     placeholder="如 华东机电设备有限公司" />
            </label>
          </div>
          <div className="eq-form-row">
            <label className="eq-form-field">
              <span>联系人</span>
              <input value={draft.contactName} maxLength={100}
                     onChange={event => setDraft({...draft, contactName: event.target.value})} />
            </label>
            <label className="eq-form-field">
              <span>电话</span>
              <input value={draft.phone} maxLength={50}
                     onChange={event => setDraft({...draft, phone: event.target.value})} />
            </label>
          </div>
          <div className="eq-form-row is-full">
            <label className="eq-form-field">
              <span>备注</span>
              <textarea value={draft.remark} maxLength={2000} rows={3}
                        onChange={event => setDraft({...draft, remark: event.target.value})} />
            </label>
          </div>
          {formError && <p className="eq-form-error">{formError}</p>}
        </div>
        <div className="eq-dialog-foot">
          <span className="eq-spacer" />
          <button type="button" className="button" onClick={() => setDraft(null)} disabled={saving}>取消</button>
          <button type="button" className="eq-primary-button" onClick={save} disabled={saving}>
            {saving ? '保存中…' : '保存'}
          </button>
        </div>
      </div>
    </div>}
  </>;
}
