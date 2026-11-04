import { useEffect, useRef, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type { ChangeLogItem, EquipmentDetail, ReferenceData } from '../../lib/api';
import { SupplierTab } from './SupplierTab';
import { ContractTab } from './ContractTab';
import { AttachmentTab } from './AttachmentTab';
import { SensorsTab } from './SensorsTab';
import './equipment.css';

/** 与新增/编辑弹窗共用同一个记住的操作人，避免两处显示不一致。 */
function rememberedOperator(): string {
  try { return window.localStorage.getItem('eq-operator-name')?.trim() || '设备管理员'; }
  catch { return '设备管理员'; }
}

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`;
}

const FIELD_LABELS: Record<string, string> = {
  'profile.typeCode': '设备类型',
  'profile.model': '型号',
  'profile.processId': '所属工序',
  'profile.installationLocation': '安装位置',
  'profile.manufacturer': '制造厂家',
  'profile.factorySerialNo': '出厂编号',
  'profile.purchasedOn': '购置日期',
  'profile.commissionedOn': '投用日期',
  'profile.expectedLifeYears': '预计使用年限',
  'profile.warrantyExpiresOn': '质保到期日',
  'profile.managementStatus': '管理状态',
  'profile.technicalParameters': '技术参数',
};

const OPERATION_LABELS: Record<string, string> = { CREATE: '新增', UPDATE: '更正', DELETE: '删除' };

function text(value: string | null | undefined): string {
  return value == null || value === '' ? '—' : value;
}

function formatTime(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return value;
  return date.toLocaleString('zh-CN', {hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'});
}

function statusClass(status: string | null): string {
  if (status === 'IN_USE') return 'is-in-use';
  if (status === 'STOPPED') return 'is-stopped';
  if (status === 'SCRAPPED') return 'is-scrapped';
  return '';
}

function Field({label, value, unknown}: {label: string; value: string | null; unknown?: boolean}) {
  return <div className="eq-field"><dt>{label}</dt><dd className={unknown ? 'eq-unknown' : undefined}>{text(value)}</dd></div>;
}

function ProfileTab({detail, options}: {detail: EquipmentDetail; options: ReferenceData | null}) {
  const profile = detail.profile;
  const typeName = profile.typeCode
    ? options?.equipmentTypes.find(item => item.code === profile.typeCode)?.name ?? profile.typeCode
    : null;
  const statusName = profile.managementStatus
    ? options?.equipmentManagementStatuses.find(item => item.code === profile.managementStatus)?.name ?? profile.managementStatus
    : null;
  const missing = detail.missingFields.map(field => FIELD_LABELS[field] ?? field);
  return <>
    {missing.length > 0 && <div className="eq-notice">
      <span>!</span>
      <div><strong>档案待补充</strong><br />{missing.join('、')} 尚未填写，补齐后才能进入完整业务流程。</div>
    </div>}
    {!detail.deletion.allowed && detail.deletion.blockers.length > 0 && <div className="eq-notice is-blocked">
      <span>×</span>
      <div><strong>不能删除，仍有业务关联</strong>
        <ul>{detail.deletion.blockers.map(blocker => <li key={blocker.type}>{blocker.message}</li>)}</ul>
        <div>如需停用，请改用编辑档案调整管理状态。</div>
      </div>
    </div>}

    <section className="eq-section">
      <h3 className="eq-section-title">设备身份</h3>
      <dl className="eq-grid">
        <Field label="所属车间" value={detail.workshopName} />
        <Field label="责任人" value={detail.responsiblePerson} unknown={!detail.responsiblePerson} />
        <Field label="启用状态" value={detail.enabled ? '已启用' : '已停用'} />
        <div className="eq-field">
          <dt>管理状态</dt>
          <dd>{statusName
            ? <span className={`eq-badge ${statusClass(profile.managementStatus)}`}>{statusName}</span>
            : <span className="eq-unknown">—</span>}</dd>
        </div>
      </dl>
    </section>

    <section className="eq-section">
      <h3 className="eq-section-title">档案信息</h3>
      <dl className="eq-grid">
        <Field label="设备类型" value={typeName} unknown={!typeName} />
        <Field label="型号" value={profile.model} unknown={!profile.model} />
        <Field label="安装位置" value={profile.installationLocation} unknown={!profile.installationLocation} />
        <Field label="制造厂家" value={profile.manufacturer} unknown={!profile.manufacturer} />
        <Field label="出厂编号" value={profile.factorySerialNo} unknown={!profile.factorySerialNo} />
        <Field label="预计使用年限" value={profile.expectedLifeYears == null ? null : `${profile.expectedLifeYears} 年`} unknown={profile.expectedLifeYears == null} />
        <Field label="购置日期" value={profile.purchasedOn} unknown={!profile.purchasedOn} />
        <Field label="投用日期" value={profile.commissionedOn} unknown={!profile.commissionedOn} />
        <Field label="质保到期日" value={profile.warrantyExpiresOn} unknown={!profile.warrantyExpiresOn} />
        <Field label="所属工序" value={profile.processId} unknown={!profile.processId} />
      </dl>
    </section>

    <section className="eq-section">
      <h3 className="eq-section-title">技术参数</h3>
      {profile.technicalParameters.length === 0
        ? <p className="eq-form-hint">尚未录入技术参数。</p>
        : <table className="eq-params">
          <thead><tr><th scope="col">参数</th><th scope="col">值</th><th scope="col">单位</th></tr></thead>
          <tbody>{profile.technicalParameters.map(item => <tr key={item.key}>
            <td>{item.label}<span className="eq-code" style={{display: 'block'}}>{item.key}</span></td>
            <td>{item.value === null ? '—' : typeof item.value === 'boolean' ? (item.value ? '是' : '否') : String(item.value)}</td>
            <td>{text(item.unitLabel)}</td>
          </tr>)}</tbody>
        </table>}
    </section>
  </>;
}

function ChangesTab({equipmentId, refreshToken}: {equipmentId: string; refreshToken: number}) {
  const [items, setItems] = useState<ChangeLogItem[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [more, setMore] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(null); setPage(1);
    api.equipmentChanges(equipmentId, 1, 20, controller.signal)
      .then(result => {
        if (controller.signal.aborted) return;
        setItems(result.data.items); setTotal(result.data.total ?? result.data.items.length); setLoading(false);
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        setError(cause instanceof Error ? cause.message : '变更记录读取失败。'); setLoading(false);
      });
    return () => controller.abort();
  }, [equipmentId, refreshToken]);

  const loadMore = () => {
    const next = page + 1;
    setMore(true);
    api.equipmentChanges(equipmentId, next, 20)
      .then(result => { setItems(previous => [...previous, ...result.data.items]); setPage(next); })
      .catch((cause: unknown) => setError(cause instanceof Error ? cause.message : '变更记录读取失败。'))
      .finally(() => setMore(false));
  };

  if (loading) return <div className="eq-loading"><span className="eq-spinner" />正在读取变更记录…</div>;
  if (error) return <div className="eq-alert is-error">{error}</div>;
  if (items.length === 0) return <p className="eq-form-hint">这台设备还没有变更记录。</p>;

  return <>
    <ul className="eq-timeline">{items.map(item => <li key={item.id} className={`is-${item.operation.toLowerCase()}`}>
      <div className="eq-timeline-head">
        <strong>{OPERATION_LABELS[item.operation] ?? item.operation}</strong>
        <span className="eq-badge">v{item.versionBefore ?? 0} → v{item.versionAfter ?? 0}</span>
      </div>
      <p className="eq-timeline-meta">{item.operatorName} · {formatTime(item.recordedAt)}</p>
      {item.reason && <p className="eq-timeline-reason">更正原因：{item.reason}</p>}
      {item.after != null && <details>
        <summary>查看变更后快照</summary>
        <pre>{JSON.stringify(item.after, null, 2)}</pre>
      </details>}
    </li>)}</ul>
    {total !== null && items.length < total && <button type="button" className="eq-add-param" onClick={loadMore} disabled={more}>
      {more ? '读取中…' : `加载更多（已显示 ${items.length} / ${total} 条）`}
    </button>}
  </>;
}

export interface DrawerProps {
  equipmentId: string;
  options: ReferenceData | null;
  refreshToken: number;
  onClose: () => void;
  onEdit: (detail: EquipmentDetail) => void;
  /** 删除成功后通知列表：关闭抽屉、刷新数据并提示结果。 */
  onDeleted: (detail: EquipmentDetail) => void;
}

export function EquipmentDetailDrawer({equipmentId, options, refreshToken, onClose, onEdit, onDeleted}: DrawerProps) {
  const [detail, setDetail] = useState<EquipmentDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<'profile' | 'sensors' | 'suppliers' | 'contracts' | 'attachments' | 'changes'>('profile');
  const [confirming, setConfirming] = useState(false);
  const [reason, setReason] = useState('');
  const [requestId, setRequestId] = useState(newRequestId);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deleteFields, setDeleteFields] = useState<Record<string, string>>({});
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(null);
    api.equipmentDetail(equipmentId, controller.signal)
      .then(result => {
        if (controller.signal.aborted) return;
        setDetail(result.data); setLoading(false);
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        setError(cause instanceof Error ? cause.message : '设备详情读取失败。'); setLoading(false);
      });
    return () => controller.abort();
  }, [equipmentId, refreshToken]);

  useEffect(() => { closeRef.current?.focus(); }, []);
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      // 确认框开着时先退回抽屉，避免误关后丢失已填的删除原因。
      if (confirming) { if (!deleting) setConfirming(false); return; }
      onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => { document.body.style.overflow = previous; document.removeEventListener('keydown', onKey); };
  }, [onClose, confirming, deleting]);

  const openConfirm = () => {
    setRequestId(newRequestId());
    setReason('');
    setDeleteError(null);
    setDeleteFields({});
    setConfirming(true);
  };

  const changeReason = (value: string) => {
    setReason(value);
    // 内容变了就换请求编号，避免被幂等表判成"同号不同内容"。
    setRequestId(newRequestId());
    setDeleteFields(previous => ({...previous, reason: ''}));
  };

  const submitDelete = () => {
    if (!detail) return;
    const trimmed = reason.trim();
    if (!trimmed) { setDeleteFields({reason: '删除原因不得为空。'}); return; }
    setDeleting(true); setDeleteError(null); setDeleteFields({});
    api.deleteEquipment(detail.id, {
      requestId, operatorName: rememberedOperator(),
      expectedVersion: detail.version, reason: trimmed,
    })
      .then(() => { setDeleting(false); setConfirming(false); onDeleted(detail); })
      .catch((cause: unknown) => {
        setDeleting(false);
        if (!(cause instanceof ApiError)) { setDeleteError('删除失败，请稍后重试。'); return; }
        if (cause.code === 'VERSION_CONFLICT') {
          setDeleteError('这台设备在你操作期间被他人修改过，请关闭详情后重新打开再试。');
        } else if (cause.code === 'RELATED_DATA_EXISTS') {
          setDeleteError(cause.message);
        } else if (Object.keys(cause.fields).length > 0) {
          setDeleteFields(cause.fields);
        } else {
          setDeleteError(cause.message);
        }
      });
  };

  return <div className="eq-overlay" role="presentation" onClick={event => { if (event.target === event.currentTarget) onClose(); }}>
    <aside className="eq-drawer" role="dialog" aria-modal="true" aria-labelledby="eq-drawer-title">
      <div className="eq-drawer-head">
        <span className="eq-code">{detail?.code ?? equipmentId}</span>
        <h2 id="eq-drawer-title">{detail?.name ?? '设备详情'}</h2>
        <button ref={closeRef} type="button" className="eq-drawer-close" onClick={onClose} aria-label="关闭详情">×</button>
        <div className="eq-tabs" role="tablist" aria-label="设备详情分栏">
          <button type="button" className="eq-tab" role="tab" aria-selected={tab === 'profile'} onClick={() => setTab('profile')}>档案信息</button>
          <button type="button" className="eq-tab" role="tab" aria-selected={tab === 'sensors'} onClick={() => setTab('sensors')}>测点</button>
          <button type="button" className="eq-tab" role="tab" aria-selected={tab === 'suppliers'} onClick={() => setTab('suppliers')}>供应商</button>
          <button type="button" className="eq-tab" role="tab" aria-selected={tab === 'contracts'} onClick={() => setTab('contracts')}>维保合同</button>
          <button type="button" className="eq-tab" role="tab" aria-selected={tab === 'attachments'} onClick={() => setTab('attachments')}>附件</button>
          <button type="button" className="eq-tab" role="tab" aria-selected={tab === 'changes'} onClick={() => setTab('changes')}>变更记录</button>
        </div>
      </div>

      <div className="eq-drawer-body">
        {loading && <div className="eq-loading"><span className="eq-spinner" />正在读取设备详情…</div>}
        {!loading && error && <div className="eq-alert is-error">{error}</div>}
        {!loading && !error && detail && (tab === 'profile'
          ? <ProfileTab detail={detail} options={options} />
          : tab === 'sensors'
            ? <SensorsTab equipmentId={equipmentId} refreshToken={refreshToken} />
            : tab === 'suppliers'
              ? <SupplierTab equipmentId={equipmentId} refreshToken={refreshToken} />
              : tab === 'contracts'
                ? <ContractTab equipmentId={equipmentId} refreshToken={refreshToken} />
                : tab === 'attachments'
                  ? <AttachmentTab equipmentId={equipmentId} refreshToken={refreshToken} />
                  : <ChangesTab equipmentId={equipmentId} refreshToken={refreshToken} />)}
      </div>

      {detail && <div className="eq-drawer-foot">
        <button type="button" className="button" onClick={() => onEdit(detail)}>编辑档案</button>
        <button type="button" className="button" onClick={openConfirm}
          disabled={!detail.deletion.allowed}
          title={detail.deletion.allowed
            ? '删除误建的重复档案'
            : detail.deletion.blockers.map(item => item.message).join('；')}>删除设备</button>
        <span className="eq-spacer" />
        <span className="eq-form-hint">当前版本 v{detail.version}</span>
      </div>}

      {confirming && detail && <div className="eq-confirm" role="presentation"
        onClick={event => { if (event.target === event.currentTarget && !deleting) setConfirming(false); }}>
        <div className="eq-confirm-card" role="dialog" aria-modal="true" aria-labelledby="eq-confirm-title">
          <h3 id="eq-confirm-title">确认删除这台设备？</h3>
          <p className="eq-confirm-text">
            <strong>{detail.code} · {detail.name}</strong><br />
            删除后设备不再出现在列表中；变更记录保留，仍可按原身份追溯。此操作不可撤销。
          </p>
          <label className="eq-form-field">
            <span>删除原因<em>*</em></span>
            <input value={reason} disabled={deleting} maxLength={1000}
              aria-invalid={Boolean(deleteFields.reason)}
              placeholder="例如：重复建账，与 SMELT-FUR-01 重复"
              onChange={event => changeReason(event.target.value)} />
            {deleteFields.reason
              ? <small className="eq-form-error">{deleteFields.reason}</small>
              : <small className="eq-form-hint">删除原因会写入变更记录，供日后追溯。</small>}
          </label>
          {deleteError && <div className="eq-alert is-error">{deleteError}</div>}
          <div className="eq-confirm-actions">
            <button type="button" className="button" onClick={() => setConfirming(false)} disabled={deleting}>取消</button>
            <button type="button" className="button is-danger" onClick={submitDelete} disabled={deleting}>
              {deleting ? '删除中…' : '确认删除'}
            </button>
          </div>
        </div>
      </div>}
    </aside>
  </div>;
}
