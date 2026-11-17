import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, ENERGY_TYPES, api } from '../../lib/api';
import type {
  EnergyCompleteness, EnergyDailyConsumption, EnergyDailyConsumptionPage,
  EnergyQuantity, EnergyTotalsResult, WorkshopOption,
} from '../../lib/api';
import { localToday } from './energyPriceFormat';
import { EnergyDayDetail } from './EnergyDayDetail';
import './energy.css';

/**
 * 「总量统计」视图：S2-E01 四项总量 + S2-E02 日来源明细。
 *
 * <p>页面规格把两者放在同一视图、同一筛选下：先看区间总量，再往下看每一天的来源。
 * 分开成两个页签会逼用户来回切同一个日期范围，还容易看错。</p>
 *
 * <p>界面只有一条硬规则：不完整就说不完整。PARTIAL 显示「已知 ×× 数据未齐」，
 * 完整值留空；MISSING 显示「暂无有效计量」，绝不用 0 占位——把缺测显示成零，
 * 后面算出来的能耗成本和实际能差一个数量级。</p>
 */

const COMPLETENESS_LABELS: Record<EnergyCompleteness, string> = {
  COMPLETE: '完整',
  PARTIAL: '部分已知',
  MISSING: '无可用来源',
};

const QUALITY_OPTIONS: {code: string; label: string}[] = [
  {code: 'GOOD', label: 'GOOD'},
  {code: 'PARTIAL', label: 'PARTIAL'},
  {code: 'BAD', label: 'BAD'},
  {code: 'NO_DATA', label: 'NO_DATA'},
];

const PAGE_SIZE = 20;

/** 车间一的产出是铜杆、车间二是铜丝，规格要求分别标明，不能笼统叫「合格产量」。 */
function outputLabel(workshopName: string | null): string {
  if (!workshopName) return '合格产量';
  if (workshopName.includes('杆')) return '合格铜杆';
  if (workshopName.includes('丝')) return '合格铜丝';
  return '合格产量';
}

export function EnergyTotalsPanel() {
  // 规格要求默认当天；区间由用户自己放宽。
  const [from, setFrom] = useState(localToday());
  const [to, setTo] = useState(localToday());
  const [workshopId, setWorkshopId] = useState('');
  const [energyType, setEnergyType] = useState('');
  const [completeness, setCompleteness] = useState('');
  const [qualityStatus, setQualityStatus] = useState('');
  const [page, setPage] = useState(1);

  const [totals, setTotals] = useState<EnergyTotalsResult | null>(null);
  const [daily, setDaily] = useState<EnergyDailyConsumptionPage | null>(null);
  const [loadingTotals, setLoadingTotals] = useState(true);
  const [loadingDaily, setLoadingDaily] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [workshops, setWorkshops] = useState<WorkshopOption[]>([]);
  // 点某一天进详情：分摊天然是「一个车间的一天」，所以只存这一对。
  const [detail, setDetail] = useState<{workshopId: string; actualDate: string} | null>(null);

  const totalsController = useRef<AbortController | null>(null);
  const dailyController = useRef<AbortController | null>(null);

  useEffect(() => {
    api.referenceData()
      .then(result => setWorkshops(result.data.workshops.filter(item => item.enabled)))
      .catch(() => setWorkshops([]));
  }, []);

  const rangeInvalid = !from || !to || from > to;

  const loadTotals = useCallback(() => {
    if (rangeInvalid) return;
    totalsController.current?.abort();
    const current = new AbortController();
    totalsController.current = current;
    setLoadingTotals(true);
    api.energyTotals({from, to, workshopId: workshopId || undefined}, current.signal)
      .then(result => { if (!current.signal.aborted) { setTotals(result.data); setError(null); } })
      .catch((cause: unknown) => {
        if (cause instanceof Error && cause.name === 'AbortError') return;
        setError(cause instanceof ApiError ? cause.message : '读取能源总量失败，请稍后重试。');
      })
      .finally(() => { if (!current.signal.aborted) setLoadingTotals(false); });
  }, [from, to, workshopId, rangeInvalid]);

  const loadDaily = useCallback(() => {
    if (rangeInvalid) return;
    dailyController.current?.abort();
    const current = new AbortController();
    dailyController.current = current;
    setLoadingDaily(true);
    api.energyDailyConsumptions({
      from, to,
      workshopId: workshopId || undefined,
      energyType: energyType || undefined,
      completeness: completeness || undefined,
      qualityStatus: qualityStatus || undefined,
      page,
      pageSize: PAGE_SIZE,
    }, current.signal)
      .then(result => { if (!current.signal.aborted) { setDaily(result.data); setError(null); } })
      .catch((cause: unknown) => {
        if (cause instanceof Error && cause.name === 'AbortError') return;
        setError(cause instanceof ApiError ? cause.message : '读取日来源明细失败，请稍后重试。');
      })
      .finally(() => { if (!current.signal.aborted) setLoadingDaily(false); });
  }, [from, to, workshopId, energyType, completeness, qualityStatus, page, rangeInvalid]);

  useEffect(() => { loadTotals(); }, [loadTotals]);
  useEffect(() => { loadDaily(); }, [loadDaily]);
  useEffect(() => () => { totalsController.current?.abort(); dailyController.current?.abort(); }, []);

  const totalPages = daily ? Math.max(1, Math.ceil(Number(daily.total ?? 0) / PAGE_SIZE)) : 1;
  const summary = daily?.summary;

  return (
    <div className="en-totals">
      <div className="en-toolbar">
        <label className="en-field">
          <span>起始日</span>
          <input type="date" value={from} max={to || undefined}
                 onChange={event => { setFrom(event.target.value); setPage(1); }} />
        </label>
        <label className="en-field">
          <span>结束日</span>
          <input type="date" value={to} min={from || undefined}
                 onChange={event => { setTo(event.target.value); setPage(1); }} />
        </label>
        <label className="en-field">
          <span>车间</span>
          <select value={workshopId} onChange={event => { setWorkshopId(event.target.value); setPage(1); }}>
            <option value="">全部车间</option>
            {workshops.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select>
        </label>
        <label className="en-field">
          <span>能源</span>
          <select value={energyType} onChange={event => { setEnergyType(event.target.value); setPage(1); }}>
            <option value="">全部能源</option>
            {ENERGY_TYPES.map(item => <option key={item.code} value={item.code}>{item.label}</option>)}
          </select>
        </label>
        <label className="en-field">
          <span>质量</span>
          <select value={qualityStatus} onChange={event => { setQualityStatus(event.target.value); setPage(1); }}>
            <option value="">不限</option>
            {QUALITY_OPTIONS.map(item => <option key={item.code} value={item.code}>{item.label}</option>)}
          </select>
        </label>
        <label className="en-field">
          <span>完整性</span>
          <select value={completeness} onChange={event => { setCompleteness(event.target.value); setPage(1); }}>
            <option value="">不限</option>
            <option value="COMPLETE">完整</option>
            <option value="PARTIAL">部分已知</option>
            <option value="MISSING">无可用来源</option>
          </select>
        </label>
        <button type="button" className="button button-small en-add"
                onClick={() => { loadTotals(); loadDaily(); }}>
          刷新
        </button>
      </div>

      {rangeInvalid && <p className="en-error">起始日不能晚于结束日。</p>}
      {error && <p className="en-error">{error}</p>}

      <section className="en-total-section">
        <h3 className="en-section-title">
          区间总量
          {totals && <span className="en-section-note">{totals.from} 至 {totals.to}</span>}
        </h3>
        {loadingTotals && !totals && <p className="en-hint">正在读取…</p>}
        {totals && totals.workshops.length === 0 && (
          <p className="en-empty">字典中没有可统计的生产车间。</p>
        )}
        {totals?.workshops.map(workshop => (
          <article className="en-total-card" key={workshop.workshopId}>
            <header className="en-total-head">
              <h4>{workshop.workshopName ?? `车间 ${workshop.workshopId}`}</h4>
              <span className="en-section-note">{workshop.workshopId}</span>
            </header>
            <div className="en-total-grid">
              <QuantityCard label="用电" quantity={workshop.electricity} />
              <QuantityCard label="用水" quantity={workshop.water} />
              <QuantityCard label="煤气" quantity={workshop.gas} />
              <QuantityCard label={outputLabel(workshop.workshopName)} quantity={workshop.qualifiedOutput} />
            </div>
          </article>
        ))}
      </section>

      <section className="en-total-section">
        <h3 className="en-section-title">
          每日来源
          {summary && (
            <span className="en-section-note">
              范围内 {summary.slotTotal} 个日槽：完整 {summary.completeCount}、
              部分已知 {summary.partialCount}、缺测 {summary.missingCount}
            </span>
          )}
        </h3>

        <div className="en-table-wrap">
          <table className="en-table">
            <thead>
              <tr>
                <th>实际日</th>
                <th>车间</th>
                <th>能源</th>
                <th>表计</th>
                <th>消耗</th>
                <th>已知部分</th>
                <th>完整性</th>
                <th>质量</th>
                <th>刷新时间</th>
                <th>缺项</th>
              </tr>
            </thead>
            <tbody>
              {loadingDaily && !daily && (
                <tr><td className="en-empty" colSpan={10}>正在读取…</td></tr>
              )}
              {daily && daily.items.length === 0 && (
                <tr><td className="en-empty" colSpan={10}>该范围内没有匹配的日来源记录。</td></tr>
              )}
              {daily?.items.map(item => {
                const key = `${item.actualDate}-${item.workshopId}-${item.energyType}`;
                return (
                  <DailyRow key={key} rowKey={key} item={item}
                            expanded={expanded === key}
                            onToggle={() => setExpanded(expanded === key ? null : key)}
                            onOpen={() => setDetail({workshopId: item.workshopId, actualDate: item.actualDate})} />
                );
              })}
            </tbody>
          </table>
        </div>

        {daily && (
          <div className="en-pager">
            <div>
              <button type="button" className="button button-small" disabled={page <= 1}
                      onClick={() => setPage(value => Math.max(1, value - 1))}>上一页</button>
              <span className="en-pager-index">第 {page} / {totalPages} 页</span>
              <button type="button" className="button button-small" disabled={page >= totalPages}
                      onClick={() => setPage(value => Math.min(totalPages, value + 1))}>下一页</button>
            </div>
            <span className="en-hint">共 {daily.total ?? 0} 条</span>
          </div>
        )}
      </section>

      {detail && (
        <EnergyDayDetail workshopId={detail.workshopId} actualDate={detail.actualDate}
                         onClose={() => setDetail(null)} />
      )}
    </div>
  );
}

function QuantityCard({label, quantity}: {label: string; quantity: EnergyQuantity}) {
  const missing = quantity.missingItems;
  return (
    <div className={`en-quantity en-quantity-${quantity.completeness.toLowerCase()}`}>
      <div className="en-quantity-head">
        <span className="en-quantity-label">{label}</span>
        <span className={`en-badge en-badge-${quantity.completeness.toLowerCase()}`}>
          {COMPLETENESS_LABELS[quantity.completeness]}
        </span>
      </div>
      <p className="en-quantity-value">
        {quantity.value ?? '—'}
        <span className="en-unit">{quantity.unit}</span>
      </p>
      {quantity.completeness !== 'COMPLETE' && (
        <p className="en-quantity-known">
          {quantity.knownValue
            ? <>已知 {quantity.knownValue}<span className="en-unit">{quantity.unit}</span>，数据未齐</>
            : '暂无有效来源'}
        </p>
      )}
      {missing.length > 0 && (
        <details className="en-gap">
          <summary>{missing.length} 条缺项</summary>
          <ul className="en-gap-list">
            {missing.slice(0, 8).map((item, index) => (
              <li key={`${item.code}-${item.actualDate ?? ''}-${index}`}>
                <code>{item.code}</code>
                {item.actualDate && <span className="en-gap-date">{item.actualDate}</span>}
                <span>{item.message}</span>
              </li>
            ))}
          </ul>
          {missing.length > 8 && <p className="en-hint">另有 {missing.length - 8} 条未展开。</p>}
        </details>
      )}
    </div>
  );
}

function DailyRow({item, rowKey, expanded, onToggle, onOpen}: {
  item: EnergyDailyConsumption; rowKey: string; expanded: boolean;
  onToggle: () => void; onOpen: () => void;
}) {
  return (
    <>
      <tr>
        <td className="en-mono">
          <button type="button" className="en-link" onClick={onOpen}
                  title="查看当日来源与分摊">
            {item.actualDate}
          </button>
        </td>
        <td>{item.workshopName ?? `车间 ${item.workshopId}`}</td>
        <td>{item.energyTypeName ?? item.energyType}</td>
        <td>
          {item.meter
            ? <><code>{item.meter.sensorCode}</code><span className="en-scope-detail">{item.meter.sensorName}</span></>
            : <span className="en-missing">未配置表计</span>}
        </td>
        <td className="en-mono">
          {item.value ?? <span className="en-muted">—</span>}
          {item.value && <span className="en-unit">{item.unit}</span>}
        </td>
        <td className="en-mono">{item.knownValue ?? <span className="en-muted">—</span>}</td>
        <td>
          <span className={`en-badge en-badge-${item.completeness.toLowerCase()}`}>
            {COMPLETENESS_LABELS[item.completeness]}
          </span>
          {item.calculationMethod && <span className="en-scope-detail">{item.calculationMethod}</span>}
        </td>
        <td>{item.qualityStatus ?? <span className="en-muted">—</span>}</td>
        <td className="en-mono">{item.refreshTime ? item.refreshTime.slice(0, 19).replace('T', ' ') : <span className="en-muted">—</span>}</td>
        <td>
          {item.missingItems.length === 0
            ? <span className="en-muted">—</span>
            : <button type="button" className="en-link" onClick={onToggle}>
                {item.missingItems.length} 项{expanded ? '（收起）' : ''}
              </button>}
        </td>
      </tr>
      {expanded && item.missingItems.length > 0 && (
        <tr className="en-row-gap" data-row-key={rowKey}>
          <td colSpan={10}>
            <ul className="en-gap-list">
              {item.missingItems.map((gap, index) => (
                <li key={`${gap.code}-${index}`}>
                  <code>{gap.code}</code>
                  <span>{gap.message}</span>
                </li>
              ))}
            </ul>
          </td>
        </tr>
      )}
    </>
  );
}
