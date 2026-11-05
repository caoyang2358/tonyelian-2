import { useEffect, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type { ContractDetail, ContractSummary } from '../../lib/api';
import './equipment.css';

/**
 * 设备详情里的"维保合同"页签（S1-C01—C04）。
 *
 * <p>合同是设备级的覆盖关系，所以这一页展示"覆盖本设备的合同"：
 * 新增时自动把本设备加进覆盖集合，编辑时提交完整的 equipmentIds（整体替换语义），
 * 解绑只把本设备从集合里摘掉，不会连累同一份合同覆盖的其他设备。</p>
 *
 * <p>期限状态由服务端按业务日期现算，页面只做展示，不自己拿本地时间减一遍——
 * 否则浏览器时区一变，剩余天数就和列表页对不上。</p>
 */

interface Draft {
  serviceOrganization: string;
  contractNumber: string;
  startDate: string;
  endDate: string;
  remark: string;
}

const EMPTY: Draft = {serviceOrganization: '', contractNumber: '', startDate: '', endDate: '', remark: ''};

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`;
}

function rememberedOperator(): string {
  try { return window.localStorage.getItem('eq-operator-name')?.trim() || '设备管理员'; }
  catch { return '设备管理员'; }
}

function period(row: ContractSummary | ContractDetail): string {
  if (!row.startDate && !row.endDate) return '起止日期待补';
  return `${row.startDate ?? '未填'} 至 ${row.endDate ?? '未填'}`;
}

/** 期限徽标：缺日期就说缺日期，不用"未到期"掩盖。 */
function ExpiryBadge({row}: {row: ContractSummary}) {
  if (row.expiryState === 'UNKNOWN') {
    return <span className="eq-badge is-muted">未填结束日期</span>;
  }
  if (row.expiryState === 'EXPIRED') {
    return <span className="eq-badge is-danger">已超期 {Math.abs(row.remainingDays ?? 0)} 天</span>;
  }
  if (row.expiryState === 'EXPIRING_SOON') {
    return <span className="eq-badge is-warn">剩余 {row.remainingDays} 天</span>;
  }
  return <span className="eq-badge">剩余 {row.remainingDays} 天</span>;
}

export function ContractTab({equipmentId, refreshToken}: {equipmentId: string; refreshToken: number}) {
  const [rows, setRows] = useState<ContractSummary[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const [draft, setDraft] = useState<Draft | null>(null);
  const [editing, setEditing] = useState<ContractDetail | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    api.contracts({equipmentId, pageSize: 100}, controller.signal)
      .then(result => { setRows(result.data.items); setError(null); })
      .catch((failure: unknown) => {
        if (failure instanceof Error && failure.name === 'AbortError') return;
        setError(failure instanceof ApiError ? failure.message : '合同列表读取失败。');
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [equipmentId, refreshToken, reloadKey]);

  function openCreate() {
    setEditing(null);
    setDraft(EMPTY);
    setFormError(null);
  }

  async function openEdit(row: ContractSummary) {
    try {
      const result = await api.contractDetail(row.contractId);
      const detail = result.data;
      setEditing(detail);
      setDraft({
        serviceOrganization: detail.serviceOrganization,
        contractNumber: detail.contractNumber ?? '',
        startDate: detail.startDate ?? '',
        endDate: detail.endDate ?? '',
        remark: detail.remark ?? '',
      });
      setFormError(null);
    } catch (failure: unknown) {
      setError(failure instanceof ApiError ? failure.message : '合同详情读取失败。');
    }
  }

  async function save() {
    if (!draft) return;
    if (!draft.serviceOrganization.trim()) {
      setFormError('服务单位名称不得为空。');
      return;
    }
    if (draft.startDate && draft.endDate && draft.startDate > draft.endDate) {
      setFormError('结束日期不得早于开始日期。');
      return;
    }
    setSaving(true);
    try {
      const values = {
        serviceOrganization: draft.serviceOrganization.trim(),
        contractNumber: draft.contractNumber.trim() || null,
        // 空串按"未填写"提交 null，契约允许先建档后补日期。
        startDate: draft.startDate || null,
        endDate: draft.endDate || null,
        remark: draft.remark.trim() || null,
        // 新增时自动覆盖当前设备；编辑时保留原有全集，不因改备注而丢掉其他设备。
        equipmentIds: editing ? editing.equipmentIds : [equipmentId],
      };
      if (editing) {
        await api.updateContract(editing.contractId, values, {
          requestId: newRequestId(),
          operatorName: rememberedOperator(),
          expectedVersion: editing.version,
        });
      } else {
        await api.createContract(values, {
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

  async function unbind(row: ContractSummary) {
    if (!window.confirm(`确定把本设备从「${row.serviceOrganization}」这份合同中移除吗？合同与其他设备的覆盖关系会保留。`)) return;
    try {
      const result = await api.contractDetail(row.contractId);
      const detail = result.data;
      await api.updateContract(row.contractId, {
        serviceOrganization: detail.serviceOrganization,
        contractNumber: detail.contractNumber,
        startDate: detail.startDate,
        endDate: detail.endDate,
        remark: detail.remark,
        equipmentIds: detail.equipmentIds.filter(item => item !== equipmentId),
      }, {
        requestId: newRequestId(),
        operatorName: rememberedOperator(),
        expectedVersion: detail.version,
      });
      setReloadKey(key => key + 1);
    } catch (failure: unknown) {
      setError(failure instanceof ApiError ? failure.message : '解除覆盖关系失败，请刷新后重试。');
    }
  }

  const busy = loading && rows === null;

  return <>
    <div className="eq-section-bar">
      <h3 className="eq-section-title">维保合同</h3>
      <button type="button" className="eq-primary-button" onClick={openCreate}>新建合同</button>
    </div>

    {error && <div className="eq-notice"><span>!</span><div>{error}</div></div>}
    {busy && <div className="eq-loading">正在读取合同…</div>}

    {!busy && rows !== null && (rows.length === 0
      ? <p className="eq-param-empty">该设备尚未纳入任何维保合同。可在此新建，或在服务单位名下补一份。</p>
      : <ul className="eq-supplier-list">
        {rows.map(row => <li key={row.contractId} className="eq-supplier-item">
          <div className="eq-supplier-main">
            <strong>{row.serviceOrganization}</strong>
            <small className="eq-supplier-count">
              {row.contractNumber ? `${row.contractNumber} · ` : ''}覆盖 {row.equipmentCount} 台设备
            </small>
            <small className="eq-supplier-count">{period(row)}</small>
            <span style={{marginTop: 4, display: 'inline-block'}}><ExpiryBadge row={row} /></span>
          </div>
          <div className="eq-supplier-actions">
            <button type="button" className="eq-row-link" onClick={() => openEdit(row)}>编辑</button>
            <button type="button" className="eq-row-link" onClick={() => unbind(row)}>移出本设备</button>
          </div>
        </li>)}
      </ul>)}
    <p className="eq-form-hint">合同到期只做提醒，不会自动关闭设备或工单；移出本设备不会删除合同与其他设备的覆盖关系。</p>

    {draft && <div className="eq-overlay" role="presentation"
                 onMouseDown={event => { if (event.target === event.currentTarget && !saving) setDraft(null); }}>
      <div className="eq-dialog-wrap" role="dialog" aria-modal="true" aria-label={editing ? '编辑合同' : '新建合同'}>
        <div className="eq-dialog-head">
          <h2>{editing ? '编辑合同' : '新建合同'}</h2>
          <p>{editing ? `合同 ID ${editing.contractId} · 版本 ${editing.version}` : '保存后自动覆盖当前设备'}</p>
        </div>
        <div className="eq-dialog-body">
          <div className="eq-form-row is-full">
            <label className="eq-form-field">
              <span>服务单位<em>*</em></span>
              <input value={draft.serviceOrganization} maxLength={150} autoFocus
                     onChange={event => setDraft({...draft, serviceOrganization: event.target.value})}
                     placeholder="如 华东维保服务中心" />
            </label>
          </div>
          <div className="eq-form-row is-full">
            <label className="eq-form-field">
              <span>合同编号</span>
              <input value={draft.contractNumber} maxLength={100}
                     onChange={event => setDraft({...draft, contractNumber: event.target.value})} />
            </label>
          </div>
          <div className="eq-form-row">
            <label className="eq-form-field">
              <span>开始日期</span>
              <input type="date" value={draft.startDate}
                     onChange={event => setDraft({...draft, startDate: event.target.value})} />
            </label>
            <label className="eq-form-field">
              <span>结束日期</span>
              <input type="date" value={draft.endDate}
                     onChange={event => setDraft({...draft, endDate: event.target.value})} />
            </label>
          </div>
          <p className="eq-form-hint">起止日期可以先留空，之后再补；填了结束日期才会参与到期提醒。</p>
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
