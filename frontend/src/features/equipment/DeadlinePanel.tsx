import { useEffect, useRef, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type { DeadlineExpiryState, DeadlineItem, DeadlineReminderType } from '../../lib/api';
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

/**
 * 期限提醒（S1-T01）。
 *
 * <p>跨设备的总览：一台设备的每个期限来源各占一行，所以同一台设备可能重复出现，
 * 这是契约规定的形状，不是重复数据。缺资料的设备也会列出——待补清单本身就是这个页面的价值。</p>
 */
export function DeadlinePanel({ onClose }: { onClose: () => void }) {
  const [page, setPage] = useState(1);
  const [type, setType] = useState('');
  const [state, setState] = useState('');
  const [rows, setRows] = useState<DeadlineItem[] | null>(null);
  const [total, setTotal] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => { closeRef.current?.focus(); }, []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

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
  }, [page, type, state]);

  const pageCount = total === null ? 1 : Math.max(1, Math.ceil(total / PAGE_SIZE));

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
        </div>
      </div>

      <div className="eq-drawer-body">
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
