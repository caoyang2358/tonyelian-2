import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, COST_MATERIAL_TYPES, api } from '../../lib/api';
import type { CostLedgerItem, CostLedgerPage, CostStockItem, CostStockPage, CostMissingItem } from '../../lib/api';
import { LEDGER_ENTRY_LABELS, RUN_STATUS_LABELS, SOURCE_TYPE_LABELS, labelOf, money, ton, unitCost } from './costingFormat';
import './costing.css';
import './energy.css';

/**
 * 「库存成本」视图：S2-K01 物料成本 + S2-K02 成本流水。
 *
 * <p>三件事必须分开说清楚，否则这一页会误导人：</p>
 * <ul>
 *   <li><b>数量和金额不是一回事</b>。实物量来自库存余额，金额来自成本流水；没有核算运行时
 *       数量照常显示，金额和均价留空——不能给「0 元库存」。</li>
 *   <li><b>已知不等于完整</b>。缺项时只给「已知部分」，总价值与均价留空，并把缺项摊开给人看。</li>
 *   <li><b>公共自由池没记账</b>。成品铜丝的自由公共份额至今没有按规格单独记，
 *       这栏显示「未记账」而不是拿整件物料的均价顶上。</li>
 * </ul>
 */

const PAGE_SIZE = 20;

export function CostingStocksPanel() {
  const [materialType, setMaterialType] = useState('');
  const [page, setPage] = useState(1);
  const [data, setData] = useState<CostStockPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [ledger, setLedger] = useState<{materialId: string; materialName: string} | null>(null);

  const controller = useRef<AbortController | null>(null);

  const load = useCallback(() => {
    controller.current?.abort();
    const current = new AbortController();
    controller.current = current;
    setLoading(true);
    api.costingStocks({
      materialType: materialType || undefined,
      page,
      pageSize: PAGE_SIZE,
    }, current.signal)
      .then(result => { if (!current.signal.aborted) { setData(result.data); setError(null); } })
      .catch((cause: unknown) => {
        if (cause instanceof Error && cause.name === 'AbortError') return;
        setError(cause instanceof ApiError ? cause.message : '读取库存成本失败，请稍后重试。');
      })
      .finally(() => { if (!current.signal.aborted) setLoading(false); });
  }, [materialType, page]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => () => { controller.current?.abort(); }, []);

  const totalPages = data ? Math.max(1, Math.ceil(Number(data.total ?? 0) / PAGE_SIZE)) : 1;
  const items = data?.items ?? [];
  const run = data?.costRun;
  const noRun = !run || run.costRunId == null;

  return (
    <div className="en-cost">
      <div className="en-toolbar">
        <label className="en-field">
          <span>物料类型</span>
          <select value={materialType} onChange={event => { setMaterialType(event.target.value); setPage(1); }}>
            <option value="">全部物料</option>
            {COST_MATERIAL_TYPES.map(item => <option key={item.code} value={item.code}>{item.label}</option>)}
          </select>
        </label>
        <button type="button" className="button button-small en-add" onClick={load} disabled={loading}>
          {loading ? '读取中…' : '刷新'}
        </button>
      </div>

      {error && <p className="en-error">{error}</p>}

      {noRun
        ? (
          <p className="en-notice">
            还没有核算运行：下面的实物量来自库存余额，金额与均价要等补算之后才有依据。
            没测算过的库存不是「零元库存」。
          </p>
        )
        : (
          <p className="en-cost-run">
            <span className="en-badge en-badge-current">核算运行 #{run.costRunId}</span>
            <span>{labelOf(RUN_STATUS_LABELS, run.status)}</span>
            {run.ruleVersion && <span className="en-muted">规则 {run.ruleVersion}</span>}
            {run.completedAt && <span className="en-muted">完成于 {run.completedAt.replace('T', ' ').slice(0, 19)}</span>}
            {data && (
              <span className="en-muted">
                均价口径 {data.unitCostBasis === 'MOVING_WEIGHTED_AVERAGE' ? '移动加权平均' : data.unitCostBasis}
              </span>
            )}
          </p>
        )}

      {data && data.missingItems.length > 0 && !noRun && (
        <MissingList items={data.missingItems} />
      )}

      <div className="en-table-wrap">
        <table className="en-table">
          <thead>
            <tr>
              <th>物料</th>
              <th>实物量</th>
              <th>有效预留</th>
              <th>可用量</th>
              <th>自由公共份额</th>
              <th>已知在库价值</th>
              <th>总在库价值</th>
              <th>均价</th>
              <th>缺项</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {loading && !data && <tr><td className="en-empty" colSpan={10}>正在读取…</td></tr>}
            {data && items.length === 0 && (
              <tr><td className="en-empty" colSpan={10}>没有匹配的物料。</td></tr>
            )}
            {items.map(item => (
              <StockRow key={item.materialId} item={item}
                        onLedger={() => setLedger({materialId: item.materialId, materialName: item.materialName})} />
            ))}
          </tbody>
        </table>
      </div>

      {data && (
        <div className="en-pager">
          <div>
            <button type="button" className="button button-small" disabled={page <= 1}
                    onClick={() => setPage(value => Math.max(1, value - 1))}>上一页</button>
            <span className="en-pager-index">第 {page} / {totalPages} 页</span>
            <button type="button" className="button button-small" disabled={page >= totalPages}
                    onClick={() => setPage(value => Math.min(totalPages, value + 1))}>下一页</button>
          </div>
          <span className="en-hint">共 {data.total ?? 0} 件物料</span>
        </div>
      )}

      {ledger && (
        <CostLedgerDrawer materialId={ledger.materialId} materialName={ledger.materialName}
                          onClose={() => setLedger(null)} />
      )}
    </div>
  );
}

function StockRow({item, onLedger}: {item: CostStockItem; onLedger: () => void}) {
  const publicPoolTracked = item.freePublicQuantityTon != null;
  return (
    <tr className={item.knownOnly ? 'en-row-gap' : undefined}>
      <td>
        <strong>{item.materialName}</strong>
        <span className="en-scope-detail">{item.materialCode}{item.specification ? ` · ${item.specification}` : ''}</span>
      </td>
      <td className="en-mono">{ton(item.physicalQuantityTon)}<span className="en-unit">吨</span></td>
      <td className="en-mono">{ton(item.reservedQuantityTon)}</td>
      <td className="en-mono">{ton(item.availableQuantityTon)}</td>
      <td className="en-mono">
        {publicPoolTracked
          ? <>{ton(item.freePublicQuantityTon)}<span className="en-unit">吨</span></>
          : <span className="en-muted" title="成品铜丝的公共自由份额尚未按规格单独记账">未记账</span>}
      </td>
      <td className="en-mono">
        {item.costRunId == null
          ? <span className="en-muted">未核算</span>
          : money(item.knownValueYuan)}
      </td>
      <td className="en-mono">
        {item.totalValueYuan != null
          ? money(item.totalValueYuan)
          : <span className="en-missing" title="还有缺项未补齐，不给完整总额">待补齐</span>}
      </td>
      <td className="en-mono">
        {item.unitCostYuanPerTon != null
          ? <span title={`${item.unitCostYuanPerTon} 元/吨`}>{unitCost(item.unitCostYuanPerTon)}<span className="en-unit">元/吨</span></span>
          : <span className="en-missing" title="完整总额算不出，均价同样不给">—</span>}
      </td>
      <td>
        {item.missingItems.length === 0
          ? <span className="en-badge en-badge-complete">完整</span>
          : (
            <>
              {/* 金额完整但有结构性缺口（如公共池未记账）时，别让「缺项」被读成「钱没算清」。 */}
              {!item.knownOnly && <span className="en-badge en-badge-complete">金额完整</span>}
              <MissingList items={item.missingItems} compact />
            </>
          )}
      </td>
      <td>
        <button type="button" className="en-link" onClick={onLedger}>成本流水</button>
      </td>
    </tr>
  );
}

function MissingList({items, compact}: {items: CostMissingItem[]; compact?: boolean}) {
  if (compact) {
    return (
      <details className="en-gap">
        <summary>{items.length} 条缺项</summary>
        <ul className="en-gap-list">
          {items.slice(0, 6).map((item, index) => (
            <li key={`${item.code}-${index}`}><code>{item.code}</code><span>{item.message}</span></li>
          ))}
        </ul>
      </details>
    );
  }
  return (
    <ul className="en-gap-list en-cost-missing">
      {items.slice(0, 6).map((item, index) => (
        <li key={`${item.code}-${index}`}><code>{item.code}</code><span>{item.message}</span></li>
      ))}
    </ul>
  );
}

/** K02：一件物料的成本怎么流进来的。数量事实仍以库存流水为准，这里只解释价值。 */
function CostLedgerDrawer({materialId, materialName, onClose}: {
  materialId: string; materialName: string; onClose: () => void;
}) {
  const [page, setPage] = useState(1);
  const [data, setData] = useState<CostLedgerPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  useEffect(() => {
    controller.current?.abort();
    const current = new AbortController();
    controller.current = current;
    setLoading(true);
    api.costingStockLedger({materialId, page, pageSize: 20}, current.signal)
      .then(result => { if (!current.signal.aborted) { setData(result.data); setError(null); } })
      .catch((cause: unknown) => {
        if (cause instanceof Error && cause.name === 'AbortError') return;
        setError(cause instanceof ApiError ? cause.message : '读取成本流水失败。');
      })
      .finally(() => { if (!current.signal.aborted) setLoading(false); });
    return () => current.abort();
  }, [materialId, page]);

  const totalPages = data ? Math.max(1, Math.ceil(Number(data.total ?? 0) / 20)) : 1;

  return (
    <div className="en-drawer-mask" onClick={onClose} role="presentation">
      <aside className="en-drawer" onClick={event => event.stopPropagation()}
             role="dialog" aria-label={`${materialName} 成本流水`}>
        <header className="en-drawer-head">
          <div>
            <h3>{materialName} · 成本流水</h3>
            <p className="en-detail-basis en-cost-runline">
              {data?.costRunId
                ? <span className="en-badge en-badge-current">核算运行 #{data.costRunId}</span>
                : <span className="en-muted">尚无核算运行</span>}
              <span>每行是一个价值动作，数量事实仍以库存流水为准</span>
            </p>
          </div>
          <button type="button" className="en-drawer-close" onClick={onClose} aria-label="关闭">×</button>
        </header>
        <div className="en-drawer-body">
          {error && <p className="en-error">{error}</p>}
          {data && data.missingItems && data.missingItems.length > 0 && (
            <p className="en-notice">还没有核算运行，成本流水为空——不是没有成本，是还没核算。</p>
          )}
          <div className="en-table-wrap">
            <table className="en-table en-table-compact">
              <thead>
                <tr>
                  <th>业务日期</th>
                  <th>类型</th>
                  <th>来源</th>
                  <th>数量依据</th>
                  <th>单价</th>
                  <th>本行金额</th>
                  <th>订单</th>
                </tr>
              </thead>
              <tbody>
                {loading && !data && <tr><td className="en-empty" colSpan={7}>正在读取…</td></tr>}
                {data && data.items.length === 0 && (
                  <tr><td className="en-empty" colSpan={7}>这次运行没有该物料的成本流水。</td></tr>
                )}
                {data?.items.map(row => <LedgerRow key={row.stockCostLedgerId} row={row} />)}
              </tbody>
            </table>
          </div>
          {data && data.items.length > 0 && (
            <div className="en-pager">
              <div>
                <button type="button" className="button button-small" disabled={page <= 1}
                        onClick={() => setPage(value => Math.max(1, value - 1))}>上一页</button>
                <span className="en-pager-index">第 {page} / {totalPages} 页</span>
                <button type="button" className="button button-small" disabled={page >= totalPages}
                        onClick={() => setPage(value => Math.min(totalPages, value + 1))}>下一页</button>
              </div>
              <span className="en-hint">共 {data.total ?? 0} 行</span>
            </div>
          )}
        </div>
      </aside>
    </div>
  );
}

function LedgerRow({row}: {row: CostLedgerItem}) {
  return (
    <tr className={row.missingItems.length > 0 ? 'en-row-gap' : undefined}>
      <td className="en-mono">{row.businessDate}</td>
      <td>{labelOf(LEDGER_ENTRY_LABELS, row.entryType)}</td>
      <td>
        {labelOf(SOURCE_TYPE_LABELS, row.sourceType)}
        <span className="en-scope-detail">
          {row.sourceId !== '0' ? `#${row.sourceId}` : '无外部来源'}
          {row.movementId ? ` · 流水 #${row.movementId}` : ''}
        </span>
      </td>
      <td className="en-mono">{ton(row.quantityBasisTon)}<span className="en-unit">吨</span></td>
      <td className="en-mono">{unitCost(row.unitCostYuanPerTon)}</td>
      <td className="en-mono">
        {money(row.costDeltaYuan)}
        {row.missingItems.length > 0 && <span className="en-scope-detail en-missing">已知部分</span>}
      </td>
      <td className="en-mono">{row.orderId ? `#${row.orderId}` : <span className="en-muted">公共池</span>}</td>
    </tr>
  );
}
