import { useEffect, useRef, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type {
  DeadlineExpiryState, DeadlineItem, DeadlineReminderType, ReminderSetting,
} from '../../lib/api';
import './equipment.css';

const PAGE_SIZE = 20;

const TYPE_LABEL: Record<DeadlineReminderType, string> = {
  WARRANTY: '质保',
  MAINTENANCE: '维保',
  EXPECTED_LIFE: '预计寿命',
};

const STATE_LABEL: Record<DeadlineExpiryState, string> = {
  MISSING_DATA: '待补资料',
  NORMAL: '正常',
  DUE_SOON: '即将到期',
  EXPIRED: '已超期',
};

/** 缺资料时把字段名翻成人话，直接显示字段名对使用者没有意义。 */
const FIELD_LABEL: Record<string, string> = {
  warrantyExpiresOn: '质保到期日',
  commissionedOn: '投用日',
  expectedLifeYears: '预计使用年限',
  contractId: '维保合同',
  endDate: '合同结束日期',
};

function stateClass(state: DeadlineExpiryState): string {
  if (state === 'EXPIRED') return 'is-danger';
  if (state === 'DUE_SOON') return 'is-warn';
  if (state === 'MISSING_DATA') return 'is-muted';
  return '';
}

/** 剩余天数只在有到期日时才有意义；到期当天说"今天到期"比"剩余 0 天"更直白。 */
function remainingText(row: DeadlineItem): string {
  if (row.remainingDays === null) return '未填日期';
  if (row.remainingDays === 0) return '今天到期';
  if (row.remainingDays > 0) return `剩余 ${row.remainingDays} 天`;
  return `已超期 ${Math.abs(row.remainingDays)} 天`;
}

function missingText(fields: string[]): string {
  return fields.map(field => FIELD_LABEL[field] ?? field).join('、');
}

function rowKey(row: DeadlineItem): string {
  return `${row.equipmentId}-${row.reminderType}-${row.contractId ?? 'none'}`;
}

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`;
}

function rememberedOperator(): string {
  try { return window.localStorage.getItem('eq-operator-name')?.trim() || '设备管理员'; }
  catch { return '设备管理员'; }
}

/**
 * 保存结果的影响面提示。
 *
 * <p>只说"保存成功"等于把后果藏起来：把维保提前量从 30 改成 90，可能一下让好几条记录
 * 从不提醒跳到即将到期。所以带上改前改后的条数，让这个数字的意义当场可见。</p>
 */
function dueSoonNotice(before: number | null, after: number | null): string {
  if (after === null) return '提醒设置已更新，期限列表已按新设置重新计算。';
  if (before === null) return `提醒设置已更新 · 当前 ${after} 条即将到期`;
  const delta = after - before;
  if (delta === 0) return `提醒设置已更新 · 当前 ${after} 条即将到期（数量未变）`;
  return `提醒设置已更新 · 当前 ${after} 条即将到期（${delta > 0 ? '+' : ''}${delta}）`;
}

/**
 * 期限提醒（S1-T01），并承载提醒设置（S1-T02 / T03）的入口。
 *
 * <p>跨设备的总览：一台设备的每个期限来源各占一行，所以同一台设备可能重复出现，
 * 这是契约规定的形状，不是重复数据。缺资料的设备也会列出——待补清单本身就是这个页面的价值。</p>
 *
 * <p>提醒设置放在这里而不是独立页面：它唯一的可见效果就是本列表的「即将到期」判定，
 * 改完立刻在同一屏看到结果，比放进一个没人会点的设置页有用。设置区做成抽屉内行内展开，
 * 不叠第二层弹层——抽屉已经监听 Esc，再叠一层会让用户在按 Esc 时连正在填的数字一起丢掉。</p>
 */
export function DeadlinePanel({ onClose }: { onClose: () => void }) {
  const [page, setPage] = useState(1);
  const [type, setType] = useState('');
  const [state, setState] = useState('');
  const [rows, setRows] = useState<DeadlineItem[] | null>(null);
  const [total, setTotal] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  const closeRef = useRef<HTMLButtonElement>(null);

  const [setting, setSetting] = useState<ReminderSetting | null>(null);
  const [settingOpen, setSettingOpen] = useState(false);
  const [draft, setDraft] = useState({warranty: '', maintenance: ''});
  const [saving, setSaving] = useState(false);
  const [settingError, setSettingError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => { closeRef.current?.focus(); }, []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      // 正在改设置时先收起设置区，别把没保存的数字连同抽屉一起关掉。
      if (settingOpen) { setSettingOpen(false); setSettingError(null); return; }
      onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, settingOpen]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    api.deadlines({
      page, pageSize: PAGE_SIZE,
      reminderType: type || undefined,
      expiryState: state || undefined,
    }, controller.signal)
      .then(result => {
        if (controller.signal.aborted) return;
        setRows(result.data.items);
        setTotal(result.data.total);
      })
      .catch((failure: unknown) => {
        if (controller.signal.aborted) return;
        setError(failure instanceof ApiError ? failure.message : '期限读取失败，请稍后重试。');
        setRows([]);
        setTotal(null);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [page, type, state, reloadToken]);

  const pageCount = total === null ? 1 : Math.max(1, Math.ceil(total / PAGE_SIZE));

  function applySetting(value: ReminderSetting) {
    setSetting(value);
    setDraft({
      warranty: String(value.warrantyLeadDays),
      maintenance: String(value.maintenanceLeadDays),
    });
  }

  async function openSetting() {
    setSettingError(null);
    setNotice(null);
    setSettingOpen(true);
    try {
      applySetting((await api.reminderSetting()).data);
    } catch (failure) {
      setSettingError(failure instanceof ApiError ? failure.message : '提醒设置读取失败，请稍后重试。');
    }
  }

  /**
   * 全量「即将到期」条数，用于改前改后对比。
   * 故意不带类型/状态筛选——影响面要按全量算，否则用户加了筛选就会看到错误的增减。
   */
  async function countDueSoon(): Promise<number | null> {
    try {
      const result = await api.deadlines({expiryState: 'DUE_SOON', page: 1, pageSize: 1});
      return result.data.total ?? 0;
    } catch {
      // 这个计数只用于给保存结果加一句影响说明，拿不到不该阻塞保存本身。
      return null;
    }
  }

  async function saveSetting() {
    if (!setting) return;
    const warranty = Number(draft.warranty);
    const maintenance = Number(draft.maintenance);
    if (draft.warranty.trim() === '' || draft.maintenance.trim() === ''
      || !Number.isInteger(warranty) || warranty < 0
      || !Number.isInteger(maintenance) || maintenance < 0) {
      setSettingError('两个提前天数都必须填非负整数，0 表示到期当天才提醒。');
      return;
    }
    setSaving(true);
    setSettingError(null);
    try {
      const before = await countDueSoon();
      const result = await api.updateReminderSetting(
        {warrantyLeadDays: warranty, maintenanceLeadDays: maintenance},
        {requestId: newRequestId(), operatorName: rememberedOperator(), expectedVersion: setting.version});
      const after = await countDueSoon();
      applySetting(result.data);
      setSettingOpen(false);
      setReloadToken(token => token + 1);
      setNotice(dueSoonNotice(before, after));
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === 'VERSION_CONFLICT') {
        setSettingError('这项设置刚被他人修改，已载入最新值，请确认后重新保存。');
        try { applySetting((await api.reminderSetting()).data); } catch { /* 读回失败就保留原输入 */ }
      } else {
        setSettingError(failure instanceof ApiError ? failure.message : '提醒设置保存失败，请稍后重试。');
      }
    } finally {
      setSaving(false);
    }
  }

  return <div className="eq-overlay" role="presentation"
    onClick={event => { if (event.target === event.currentTarget) onClose(); }}>
    <aside className="eq-drawer eq-dl-drawer" role="dialog" aria-modal="true"
      aria-labelledby="eq-deadline-title">
      <div className="eq-drawer-head">
        <span className="eq-code">S1-T01 · 质保 / 维保 / 预计寿命</span>
        <h2 id="eq-deadline-title">期限提醒</h2>
        <button ref={closeRef} type="button" className="eq-drawer-close" onClick={onClose}
          aria-label="关闭期限提醒">×</button>
        <div className="eq-dl-filters">
          <label className="eq-dl-filter">
            <span>期限类型</span>
            <select value={type} onChange={event => { setType(event.target.value); setPage(1); }}>
              <option value="">全部类型</option>
              <option value="WARRANTY">质保</option>
              <option value="MAINTENANCE">维保</option>
              <option value="EXPECTED_LIFE">预计寿命</option>
            </select>
          </label>
          <label className="eq-dl-filter">
            <span>计算状态</span>
            <select value={state} onChange={event => { setState(event.target.value); setPage(1); }}>
              <option value="">全部状态</option>
              <option value="EXPIRED">已超期</option>
              <option value="DUE_SOON">即将到期</option>
              <option value="NORMAL">正常</option>
              <option value="MISSING_DATA">待补资料</option>
            </select>
          </label>
          <span className="eq-dl-total">{total === null ? '正在统计…' : `共 ${total} 条`}</span>
          <button type="button" className="eq-dl-setting-toggle" aria-expanded={settingOpen}
            onClick={() => { if (settingOpen) { setSettingOpen(false); setSettingError(null); } else { void openSetting(); } }}>
            {settingOpen ? '收起设置' : '提醒设置'}
          </button>
        </div>

        {settingOpen && <div className="eq-dl-setting">
          <div className="eq-dl-setting-head">
            <strong>提前提醒天数</strong>
            <span>全系统生效 · 版本 v{setting?.version ?? '—'}</span>
          </div>
          <div className="eq-dl-setting-row">
            <label className="eq-dl-setting-field">
              <span>质保提前（天）</span>
              <input type="number" min={0} step={1} inputMode="numeric" value={draft.warranty}
                onChange={event => setDraft(current => ({...current, warranty: event.target.value}))} />
            </label>
            <label className="eq-dl-setting-field">
              <span>维保提前（天）</span>
              <input type="number" min={0} step={1} inputMode="numeric" value={draft.maintenance}
                onChange={event => setDraft(current => ({...current, maintenance: event.target.value}))} />
            </label>
            <button type="button" className="button button-small" onClick={() => void saveSetting()}
              disabled={saving || !setting}>{saving ? '保存中…' : '保存'}</button>
            <button type="button" className="button button-small" disabled={saving}
              onClick={() => { setSettingOpen(false); setSettingError(null); }}>取消</button>
          </div>
          <p className="eq-dl-setting-hint">
            只影响「即将到期」的判定：保存后本列表与合同页签按新天数重新计算，不改变设备管理状态，也不发送通知。
          </p>
          {settingError && <div className="eq-alert is-error">{settingError}</div>}
        </div>}
      </div>

      <div className="eq-drawer-body">
        {notice && <div className="eq-dl-notice" role="status">{notice}</div>}
        {loading && <div className="eq-loading"><span className="eq-spinner" />正在计算期限…</div>}
        {!loading && error && <div className="eq-alert is-error">{error}</div>}
        {!loading && !error && rows !== null && (rows.length === 0
          ? <p className="eq-param-empty">当前筛选下没有期限记录。换个类型或状态看看。</p>
          : <ul className="eq-dl-list">
            {rows.map(row => <li className="eq-dl-item" key={rowKey(row)}>
              <div className="eq-dl-top">
                <div className="eq-dl-equip">
                  <strong>{row.equipmentName}</strong>
                  <span className="eq-code">{row.equipmentCode}</span>
                </div>
                <div className="eq-dl-due">
                  <span className="eq-dl-date">{row.dueDate ?? '—'}</span>
                  <span className={`eq-badge is-type`}>{TYPE_LABEL[row.reminderType]}</span>
                  <span className={`eq-badge ${stateClass(row.expiryState)}`}>{STATE_LABEL[row.expiryState]}</span>
                </div>
              </div>
              <div className="eq-dl-meta">
                <span>{row.workshopName ?? '未分配车间'}</span>
                {row.responsiblePerson && <span>· {row.responsiblePerson}</span>}
                {row.contractNumber && <span>· 合同 {row.contractNumber}</span>}
                <span>· {remainingText(row)}</span>
              </div>
              {row.missingFields.length > 0 && <p className="eq-dl-missing">
                缺少{missingText(row.missingFields)}，补齐后自动重新计算。
              </p>}
            </li>)}
          </ul>)}
      </div>

      <div className="eq-drawer-foot">
        <span className="eq-spacer" />
        <button type="button" className="button button-small"
          onClick={() => setPage(previous => Math.max(1, previous - 1))}
          disabled={loading || page <= 1}>上一页</button>
        <span className="eq-dl-page">第 {page} / {pageCount} 页</span>
        <button type="button" className="button button-small"
          onClick={() => setPage(previous => Math.min(pageCount, previous + 1))}
          disabled={loading || page >= pageCount}>下一页</button>
      </div>
    </aside>
  </div>;
}
