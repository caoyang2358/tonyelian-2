import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError, ENERGY_SCOPE_LABELS, ENERGY_TYPES, api } from '../../lib/api';
import type {
  EnergyPricePageResult, EnergyPriceSummary, EnergyType, WorkshopOption,
} from '../../lib/api';
import { formatPrice, localToday, priceError } from './energyPriceFormat';
import './energy.css';

/**
 * S2-E04—E06 能源价格维护。
 *
 * <p>这一页要回答两个问题，不能混在一起：</p>
 * <ul>
 *   <li>「录了哪些价」——列表，按生效日倒序，可翻修订历史；</li>
 *   <li>「某天该用哪个价」——选定车间与用能日后的匹配结果，专用优先、无则回退通用。</li>
 * </ul>
 *
 * <p>录错价不改行：走「更正原价格」，原值原样留在历史里。价格是核算依据，
 * 改掉它会让已经算过的账无法复算。</p>
 */

const OPERATOR_STORAGE_KEY = 'en-operator-name';

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, char => {
    const random = Math.floor(Math.random() * 16);
    return (char === 'x' ? random : (random & 0x3) | 0x8).toString(16);
  });
}

function rememberedOperator(): string {
  try { return window.localStorage.getItem(OPERATOR_STORAGE_KEY) || ''; }
  catch { return ''; }
}

function rememberOperator(name: string) {
  try { window.localStorage.setItem(OPERATOR_STORAGE_KEY, name); } catch { /* 隐私模式下忽略 */ }
}

interface Filters {
  energyType: string;
  scope: string;
  effectiveFrom: string;
  effectiveTo: string;
  includeHistory: boolean;
}

const EMPTY_FILTERS: Filters = {
  energyType: '', scope: '', effectiveFrom: '', effectiveTo: '', includeHistory: false,
};

export function EnergyPricePanel() {
  const [filters, setFilters] = useState<Filters>(EMPTY_FILTERS);
  const [page, setPage] = useState(1);
  const [matchWorkshop, setMatchWorkshop] = useState('');
  const [matchDate, setMatchDate] = useState('');
  const [data, setData] = useState<EnergyPricePageResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [workshops, setWorkshops] = useState<WorkshopOption[]>([]);
  const [dialog, setDialog] = useState<{mode: 'create'} | {mode: 'correct'; row: EnergyPriceSummary} | null>(null);

  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    api.referenceData()
      .then(result => setWorkshops(result.data.workshops.filter(item => item.enabled)))
      .catch(() => setWorkshops([]));
  }, []);

  const reload = useCallback((nextPage = page) => {
    controller.current?.abort();
    const current = new AbortController();
    controller.current = current;
    setLoading(true);
    setError(null);
    api.energyPrices({
      energyType: filters.energyType || undefined,
      scope: filters.scope || undefined,
      effectiveFrom: filters.effectiveFrom || undefined,
      effectiveTo: filters.effectiveTo || undefined,
      includeHistory: filters.includeHistory || undefined,
      // 匹配要能源、车间、用能日三者齐备。能源复用列表筛选的那个值——接口的 energyType
      // 同时作用于列表与匹配，分开设两个下拉必然出现"列表看电、匹配算水"的矛盾。
      actualDate: matchDate && matchWorkshop && filters.energyType ? matchDate : undefined,
      workshopId: matchDate && matchWorkshop && filters.energyType ? matchWorkshop : undefined,
      page: nextPage,
      pageSize: 20,
    }, current.signal)
      .then(result => { if (!current.signal.aborted) setData(result.data); })
      .catch((cause: unknown) => {
        if (cause instanceof Error && cause.name === 'AbortError') return;
        setError(cause instanceof ApiError ? cause.message : '读取能源价格失败，请稍后重试。');
      })
      .finally(() => { if (!current.signal.aborted) setLoading(false); });
  }, [filters, matchDate, matchWorkshop, page]);

  useEffect(() => { reload(page); }, [reload, page]);

  useEffect(() => () => controller.current?.abort(), []);

  const matched = data?.matchedPrice ?? null;
  const totalPages = data ? Math.max(1, Math.ceil(Number(data.total ?? 0) / Number(data.pageSize || 20))) : 1;
  const workshopName = useMemo(
    () => (id: string | null) => {
      if (!id) return '全部车间';
      return workshops.find(item => item.id === id)?.name ?? `车间 ${id}`;
    },
    [workshops]);

  const handleSaved = (message: string) => {
    setDialog(null);
    setNotice(message);
    setPage(1);
    reload(1);
  };

  return (
    <div className="en-price">
      <div className="en-toolbar">
        <label className="en-field">
          <span>能源类型</span>
          <select value={filters.energyType}
            onChange={event => { setFilters({...filters, energyType: event.target.value}); setPage(1); }}>
            <option value="">全部能源</option>
            {ENERGY_TYPES.map(item => <option key={item.code} value={item.code}>{item.label}</option>)}
          </select>
        </label>
        <label className="en-field">
          <span>价格范围</span>
          <select value={filters.scope}
            onChange={event => { setFilters({...filters, scope: event.target.value}); setPage(1); }}>
            <option value="">全部范围</option>
            <option value="GENERAL">通用</option>
            <option value="WORKSHOP">车间专用</option>
          </select>
        </label>
        <label className="en-field">
          <span>生效日起</span>
          <input type="date" value={filters.effectiveFrom}
            onChange={event => { setFilters({...filters, effectiveFrom: event.target.value}); setPage(1); }} />
        </label>
        <label className="en-field">
          <span>生效日止</span>
          <input type="date" value={filters.effectiveTo}
            onChange={event => { setFilters({...filters, effectiveTo: event.target.value}); setPage(1); }} />
        </label>
        <label className="en-checkbox">
          <input type="checkbox" checked={filters.includeHistory}
            onChange={event => { setFilters({...filters, includeHistory: event.target.checked}); setPage(1); }} />
          <span>含已更正的历史价</span>
        </label>
        <button type="button" className="button button-primary en-add" onClick={() => setDialog({mode: 'create'})}>
          新增价格
        </button>
      </div>

      <div className="en-match">
        <p className="en-match-title">按实际用能日查适用价</p>
        <div className="en-match-controls">
          <label className="en-field">
            <span>车间</span>
            <select value={matchWorkshop} onChange={event => setMatchWorkshop(event.target.value)}>
              <option value="">选择车间</option>
              {workshops.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select>
          </label>
          <label className="en-field">
            <span>实际用能日</span>
            <input type="date" value={matchDate} max={localToday()}
              onChange={event => setMatchDate(event.target.value)} />
          </label>
          {matchWorkshop && matchDate && (
            <button type="button" className="button button-small"
              onClick={() => { setMatchWorkshop(''); setMatchDate(''); }}>
              清除
            </button>
          )}
        </div>
        <div className="en-match-result">
          {!filters.energyType
            ? <span className="en-muted">
                先在上方「能源类型」选定一种能源——价格按能源分别维护，不选能源就无法匹配到具体单价。
              </span>
            : !matchWorkshop || !matchDate
              ? <span className="en-muted">选择车间与日期后显示当天适用的价格。</span>
              : loading
                ? <span className="en-muted">查询中…</span>
                : matched
                  ? <span>
                      适用价 <strong>{formatPrice(matched.unitPriceYuanPerUnit)}</strong> 元/{matched.unitCode}
                      <span className="en-match-meta">
                        {ENERGY_SCOPE_LABELS[matched.scope]} · 自 {matched.effectiveDate} 生效 · 价格 ID {matched.priceId}
                      </span>
                    </span>
                  : <span className="en-missing">该日无适用价格。用量会保留，单价与费用为空，请先补录价格。</span>}
        </div>
        {matchWorkshop && matchDate && filters.energyType && (
          <p className="en-match-hint">
            接口的车间参数同时筛选列表，所以下方只列出该车间的价；点「清除」恢复全部车间。
          </p>
        )}
      </div>

      {notice && <p className="en-notice" role="status">{notice}
        <button type="button" className="en-link" onClick={() => setNotice(null)}>知道了</button>
      </p>}
      {error && <p className="en-error" role="alert">{error}</p>}

      <div className="en-table-wrap">
        <table className="en-table">
          <thead>
            <tr>
              <th scope="col">能源</th>
              <th scope="col">范围</th>
              <th scope="col">生效日</th>
              <th scope="col">单价</th>
              <th scope="col">状态</th>
              <th scope="col">经办人</th>
              <th scope="col">说明</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            {loading && !data && (
              <tr><td colSpan={8} className="en-empty">正在读取…</td></tr>
            )}
            {!loading && data && data.items.length === 0 && (
              <tr><td colSpan={8} className="en-empty">
                还没有录入任何价格。先建通用价，再按车间细化——没有价格时，用能记录会停在待核算。
              </td></tr>
            )}
            {data?.items.map(row => (
              <tr key={row.priceId} className={row.current ? undefined : 'en-row-history'}>
                <td>{ENERGY_TYPES.find(item => item.code === row.energyType)?.label ?? row.energyType}</td>
                <td>
                  {ENERGY_SCOPE_LABELS[row.scope]}
                  <span className="en-scope-detail">{workshopName(row.workshopId)}</span>
                </td>
                <td className="en-mono">{row.effectiveDate}</td>
                <td className="en-mono" title={`${row.unitPriceYuanPerUnit} 元/${row.unitCode}`}>
                  {formatPrice(row.unitPriceYuanPerUnit)}
                  <span className="en-unit">元/{row.unitCode}</span>
                </td>
                <td>
                  {row.current
                    ? <span className="en-badge en-badge-current">当前有效</span>
                    : <span className="en-badge">已被更正</span>}
                </td>
                <td>{row.operatorName ?? '—'}</td>
                <td className="en-reason" title={row.reason ?? undefined}>{row.reason ?? '—'}</td>
                <td>
                  {row.current && (
                    <button type="button" className="en-link"
                      onClick={() => setDialog({mode: 'correct', row})}>
                      更正原价格
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="en-pager">
        <span>共 {data?.total ?? 0} 条</span>
        <div>
          <button type="button" className="button button-small" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</button>
          <span className="en-pager-index">{page} / {totalPages}</span>
          <button type="button" className="button button-small" disabled={page >= totalPages} onClick={() => setPage(page + 1)}>下一页</button>
        </div>
      </div>

      {dialog?.mode === 'create' && (
        <PriceDialog workshops={workshops} onClose={() => setDialog(null)} onSaved={handleSaved} />
      )}
      {dialog?.mode === 'correct' && (
        <PriceDialog workshops={workshops} origin={dialog.row}
          onClose={() => setDialog(null)} onSaved={handleSaved} />
      )}
    </div>
  );
}

interface PriceDialogProps {
  workshops: WorkshopOption[];
  origin?: EnergyPriceSummary;
  onClose: () => void;
  onSaved: (message: string) => void;
}

/**
 * 新增与更正共用一个弹窗：两者字段相同，差别只在"从空白开始"还是"从原价带出"，
 * 以及提交走哪个接口。分成两个组件会把同一套校验写两遍。
 */
function PriceDialog({workshops, origin, onClose, onSaved}: PriceDialogProps) {
  const correcting = origin !== undefined;
  const [energyType, setEnergyType] = useState<EnergyType>(origin?.energyType ?? 'ELECTRICITY');
  const [workshopId, setWorkshopId] = useState<string>(origin?.workshopId ?? '');
  const [effectiveDate, setEffectiveDate] = useState(origin?.effectiveDate ?? localToday());
  const [unitPrice, setUnitPrice] = useState(origin?.unitPriceYuanPerUnit ?? '');
  const [reason, setReason] = useState('');
  const [operatorName, setOperatorName] = useState(rememberedOperator());
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const unit = ENERGY_TYPES.find(item => item.code === energyType)?.unit ?? '';

  const submit = () => {
    const errors: Record<string, string> = {};
    const priceProblem = priceError(unitPrice);
    if (priceProblem) errors.unitPriceYuanPerUnit = priceProblem;
    if (!effectiveDate) errors.effectiveDate = '生效日为必填项。';
    if (!operatorName.trim()) errors.operatorName = '经办人为必填项。';
    if (correcting && !reason.trim()) errors.reason = '更正原因不得为空，它会进入变更记录。';
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setSaving(true);
    setFailure(null);
    rememberOperator(operatorName.trim());
    const requestId = newRequestId();

    const done = (message: string) => { setSaving(false); onSaved(message); };
    const fail = (cause: unknown) => {
      setSaving(false);
      setFailure(cause instanceof ApiError ? cause.message : '保存失败，请稍后重试。');
    };

    if (correcting) {
      api.correctEnergyPrice(origin.priceId, {
        energyType,
        // 空字符串即"改为通用范围"，必须显式传 null，漏传会被当成"范围不变"。
        workshopId: workshopId || null,
        effectiveDate,
        unitCode: unit,
        unitPriceYuanPerUnit: unitPrice.trim(),
      }, {requestId, operatorName: operatorName.trim(), expectedVersion: origin.version, reason: reason.trim()})
        .then(result => {
          const scope = result.data.affectedScope;
          const range = scope.to ? `${scope.from} 至 ${scope.to}` : `${scope.from} 起`;
          done(`已更正：原价格 ${result.data.previousPriceId} 退出当前有效，新价格 ${result.data.priceId} 自 ${range} 生效；`
            + `核算状态 ${result.data.status === 'PENDING' ? '待补算' : result.data.status}。`);
        })
        .catch(fail);
    } else {
      api.createEnergyPrice({
        energyType,
        workshopId: workshopId || null,
        effectiveDate,
        unitCode: unit,
        unitPriceYuanPerUnit: unitPrice.trim(),
      }, {requestId, operatorName: operatorName.trim()})
        .then(result => {
          const scope = result.data.affectedScope;
          const range = scope.to ? `${scope.from} 至 ${scope.to}` : `${scope.from} 起`;
          done(`已新增价格 ${result.data.priceId}，自 ${range} 生效。已核定的历史核算保留原价格依据。`);
        })
        .catch(fail);
    }
  };

  return (
    <div className="en-overlay" role="dialog" aria-modal="true" aria-label={correcting ? '更正原价格' : '新增价格'}>
      <div className="en-dialog">
        <header>
          <h3>{correcting ? '更正原价格' : '新增价格'}</h3>
          <button type="button" className="en-close" aria-label="关闭" onClick={onClose}>×</button>
        </header>

        {correcting && (
          <div className="en-origin">
            <p>原价格 ID {origin.priceId}：{formatPrice(origin.unitPriceYuanPerUnit)} 元/{origin.unitCode}，
              自 {origin.effectiveDate} 生效，{ENERGY_SCOPE_LABELS[origin.scope]}。</p>
            <p className="en-muted">保存后原价格保留在历史里，只退出「当前有效」，不会被删除。</p>
          </div>
        )}

        <div className="en-form">
          <label className="en-field">
            <span>能源</span>
            <select value={energyType} onChange={event => setEnergyType(event.target.value as EnergyType)}>
              {ENERGY_TYPES.map(item => <option key={item.code} value={item.code}>{item.label}</option>)}
            </select>
          </label>
          <label className="en-field">
            <span>范围</span>
            <select value={workshopId} onChange={event => setWorkshopId(event.target.value)}>
              <option value="">通用（全部车间）</option>
              {workshops.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select>
          </label>
          <label className="en-field">
            <span>生效日</span>
            <input type="date" value={effectiveDate} onChange={event => setEffectiveDate(event.target.value)} />
            {fieldErrors.effectiveDate && <em className="en-field-error">{fieldErrors.effectiveDate}</em>}
          </label>
          <label className="en-field">
            <span>单价（元/{unit}）</span>
            <input inputMode="decimal" value={unitPrice} placeholder="例如 0.8"
              onChange={event => setUnitPrice(event.target.value)} />
            {fieldErrors.unitPriceYuanPerUnit
              ? <em className="en-field-error">{fieldErrors.unitPriceYuanPerUnit}</em>
              : <em className="en-hint">允许填 0（自产或免费）；单位随能源固定，不需要手填。</em>}
          </label>
          <label className="en-field en-field-wide">
            <span>经办人</span>
            <input value={operatorName} placeholder="填写操作人姓名"
              onChange={event => setOperatorName(event.target.value)} />
            {fieldErrors.operatorName && <em className="en-field-error">{fieldErrors.operatorName}</em>}
          </label>
          {correcting && (
            <label className="en-field en-field-wide">
              <span>更正原因</span>
              <textarea rows={2} value={reason} placeholder="说明为什么要改，例如：核对约定价格后更正原录入值"
                onChange={event => setReason(event.target.value)} />
              {fieldErrors.reason && <em className="en-field-error">{fieldErrors.reason}</em>}
            </label>
          )}
        </div>

        <p className="en-dialog-note">
          {correcting
            ? '更正会生成一条新价格，并让原价格退出当前有效；受影响范围的核算需要重新补算。'
            : '按实际用能日生效；已核定的历史核算保留原价格依据，不会被这次新增改写。'}
        </p>

        {failure && <p className="en-error" role="alert">{failure}</p>}

        <footer>
          <button type="button" className="button" onClick={onClose} disabled={saving}>取消</button>
          <button type="button" className="button button-primary" onClick={submit} disabled={saving}>
            {saving ? '保存中…' : correcting ? '确认更正' : '保存'}
          </button>
        </footer>
      </div>
    </div>
  );
}
