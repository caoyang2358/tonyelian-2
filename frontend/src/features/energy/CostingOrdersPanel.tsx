import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type { CostOrderCostItem, CostOrderDetail, CostOrderItem, CostOrderPage, CostMissingItem } from '../../lib/api';
import {
  ACCOUNTING_STATE_LABELS, COST_ITEM_LABELS, DELIVERY_RESULT_LABELS,
  PRODUCTION_RESULT_LABELS, PROFIT_BLOCKED_LABELS, RUN_STATUS_LABELS,
  labelOf, money, profitText, sourceKeyText, ton,
} from './costingFormat';
import './costing.css';
import './energy.css';

/**
 * 「订单核算」视图：S2-K03 列表 + S2-K04 依据。
 *
 * <p>利润不是一个数字，是四道门都通过之后才给的结论：金额确认、成本完整、生产结束、
 * 订单未取消。任一道没过，页面就显示「—」并说明卡在哪——显示 0 元利润会被当成真实结果。</p>
 *
 * <p>口径也要写在脸上：本页只算材料与能源（{@code MATERIAL_AND_ENERGY_ONLY}），
 * 不含人工、折旧、制造费用，所以它是「估算」，不是财务利润。</p>
 */

const PAGE_SIZE = 20;

const ACCOUNTING_OPTIONS = [
  {code: 'PENDING', label: '待核算'},
  {code: 'COMPLETED', label: '已核算'},
  {code: 'INCOMPLETE', label: '缺项'},
];

export function CostingOrdersPanel() {
  const [accountingState, setAccountingState] = useState('');
  const [page, setPage] = useState(1);
  const [data, setData] = useState<CostOrderPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detailOrderId, setDetailOrderId] = useState<string | null>(null);

  const controller = useRef<AbortController | null>(null);

  const load = useCallback(() => {
    controller.current?.abort();
    const current = new AbortController();
    controller.current = current;
    setLoading(true);
    api.costingOrders({
      accountingState: accountingState || undefined,
      page,
      pageSize: PAGE_SIZE,
    }, current.signal)
      .then(result => { if (!current.signal.aborted) { setData(result.data); setError(null); } })
      .catch((cause: unknown) => {
        if (cause instanceof Error && cause.name === 'AbortError') return;
        setError(cause instanceof ApiError ? cause.message : '读取订单核算失败，请稍后重试。');
      })
      .finally(() => { if (!current.signal.aborted) setLoading(false); });
  }, [accountingState, page]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => () => { controller.current?.abort(); }, []);

  const items = data?.items ?? [];
  const totalPages = data ? Math.max(1, Math.ceil(Number(data.total ?? 0) / PAGE_SIZE)) : 1;
  const run = data?.costRun;
  const noRun = !run || run.costRunId == null;

  return (
    <div className="en-cost">
      <div className="en-toolbar">
        <label className="en-field">
          <span>核算状态</span>
          <select value={accountingState} onChange={event => { setAccountingState(event.target.value); setPage(1); }}>
            <option value="">全部状态</option>
            {ACCOUNTING_OPTIONS.map(item => <option key={item.code} value={item.code}>{item.label}</option>)}
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
            尚无核算运行：已知成本与完整成本暂为空，盈亏不予展示。
            订单数量与交期正常显示；未核算不等于成本为零。
          </p>
        )
        : (
          <p className="en-cost-run">
            <span className="en-badge en-badge-current">核算运行 #{run.costRunId}</span>
            <span>{labelOf(RUN_STATUS_LABELS, run.status)}</span>
            {run.completedAt && <span className="en-muted">完成于 {run.completedAt.replace('T', ' ').slice(0, 19)}</span>}
            <span className="en-muted">口径：材料与车间能源，不含人工、折旧、制造费用</span>
          </p>
        )}

      <div className="en-table-wrap">
        <table className="en-table">
          <thead>
            <tr>
              <th>订单</th>
              <th>需求量</th>
              <th>交期</th>
              <th>生产结果</th>
              <th>核算状态</th>
              <th>适用金额</th>
              <th>已知成本</th>
              <th>完整成本</th>
              <th>估算盈亏</th>
              <th>说明</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {loading && !data && <tr><td className="en-empty" colSpan={11}>正在读取…</td></tr>}
            {data && items.length === 0 && (
              <tr><td className="en-empty" colSpan={11}>暂无匹配的订单。</td></tr>
            )}
            {items.map(item => (
              <OrderRow key={item.orderId} item={item} onDetail={() => setDetailOrderId(item.orderId)} />
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
          <span className="en-hint">共 {data.total ?? 0} 张订单</span>
        </div>
      )}

      {detailOrderId && (
        <OrderCostDrawer orderId={detailOrderId} onClose={() => setDetailOrderId(null)} />
      )}
    </div>
  );
}

function OrderRow({item, onDetail}: {item: CostOrderItem; onDetail: () => void}) {
  // 这里显示的后端给的原因，不由前端推断：原因码有六种，猜错一种就把人指到错的地方。
  const reason = item.profitBlockedReason ? labelOf(PROFIT_BLOCKED_LABELS, item.profitBlockedReason) : null;
  const amountUnconfirmed = !item.amountConfirmed && item.agreedAmountYuan == null;
  return (
    <tr className={item.profitDisplayable ? undefined : 'en-row-gap'}>
      <td>
        <strong>{item.orderNo}</strong>
        <span className="en-scope-detail">{item.customerName}</span>
      </td>
      <td className="en-mono">{ton(item.requiredQuantityTon)}<span className="en-unit">吨</span></td>
      <td className="en-mono">{item.dueDate}</td>
      <td>
        {labelOf(PRODUCTION_RESULT_LABELS, item.productionResult)}
        <span className="en-scope-detail">{labelOf(DELIVERY_RESULT_LABELS, item.deliveryResult)}</span>
      </td>
      <td>
        <span className={`en-badge ${item.accountingState === 'COMPLETED' ? 'en-badge-complete'
          : item.accountingState === 'INCOMPLETE' ? 'en-badge-partial' : ''}`}>
          {labelOf(ACCOUNTING_STATE_LABELS, item.accountingState)}
        </span>
      </td>
      <td className="en-mono">
        {item.applicableAmountYuan != null ? money(item.applicableAmountYuan) : '—'}
        {amountUnconfirmed && <span className="en-scope-detail en-missing">尚未确认</span>}
      </td>
      <td className="en-mono">{item.knownCostAmountYuan != null ? money(item.knownCostAmountYuan) : '—'}</td>
      <td className="en-mono">
        {item.totalCostAmountYuan != null
          ? money(item.totalCostAmountYuan)
          : <span className="en-missing">待补齐</span>}
      </td>
      <td className="en-mono">
        {item.profitDisplayable
          ? profitText(item.estimatedProfitYuan)
          : <span className="en-muted" title={reason ?? undefined}>—</span>}
      </td>
      <td className="en-cost-note">
        {item.profitDisplayable
          ? <span className="en-muted">金额与成本齐备</span>
          : reason}
      </td>
      <td>
        <button type="button" className="en-link" onClick={onDetail}>核算依据</button>
      </td>
    </tr>
  );
}

function OrderCostDrawer({orderId, onClose}: {orderId: string; onClose: () => void}) {
  const [data, setData] = useState<CostOrderDetail | null>(null);
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
    api.costingOrderDetail(orderId, current.signal)
      .then(result => { if (!current.signal.aborted) { setData(result.data); setError(null); } })
      .catch((cause: unknown) => {
        if (cause instanceof Error && cause.name === 'AbortError') return;
        setError(cause instanceof ApiError ? cause.message : '读取订单核算依据失败。');
      })
      .finally(() => { if (!current.signal.aborted) setLoading(false); });
    return () => current.abort();
  }, [orderId]);

  return (
    <div className="en-drawer-mask" onClick={onClose} role="presentation">
      <aside className="en-drawer" onClick={event => event.stopPropagation()}
             role="dialog" aria-label="订单核算依据">
        <header className="en-drawer-head">
          <div>
            <h3>{data ? `${data.orderNo} · ${data.customerName}` : `订单 #${orderId}`}</h3>
            <p className="en-detail-basis">
              {data && `需求 ${ton(data.requiredQuantityTon)} 吨 · 交期 ${data.dueDate} · `}
              {data && labelOf(PRODUCTION_RESULT_LABELS, data.productionResult)}
              {data && ` · ${labelOf(ACCOUNTING_STATE_LABELS, data.accountingState)}`}
            </p>
          </div>
          <button type="button" className="en-drawer-close" onClick={onClose} aria-label="关闭">×</button>
        </header>
        <div className="en-drawer-body">
          {error && <p className="en-error">{error}</p>}
          {loading && !data && <p className="en-hint">正在读取…</p>}

          {data && (
            <>
              {data.cancelled && (
                <p className="en-notice">订单已取消：保留历史实际与已发生成本，不计算也不展示利润。</p>
              )}
              {data.recalculationPending && (
                <p className="en-notice">
                  完整成本仍缺项：下面列出的是已知部分。补齐来源后补算，才会给出完整成本与盈亏。
                </p>
              )}

              <section className="en-detail-block">
                <h4>金额</h4>
                <dl className="en-detail-pairs">
                  <dt>合同金额</dt><dd>{money(data.amount.contractAmountYuan)}</dd>
                  <dt>约定金额</dt><dd>{money(data.amount.agreedAmountYuan)}</dd>
                  <dt>是否确认</dt><dd>{data.amount.confirmed ? '已确认' : '未确认'}</dd>
                  <dt>适用金额</dt><dd>{money(data.amount.applicableAmountYuan)}</dd>
                </dl>
              </section>

              <section className="en-detail-block">
                <h4>成本</h4>
                <dl className="en-detail-pairs">
                  <dt>已知成本</dt><dd>{money(data.cost.knownCostAmountYuan)}</dd>
                  <dt>完整成本</dt>
                  <dd>{data.cost.totalCostAmountYuan != null
                    ? money(data.cost.totalCostAmountYuan)
                    : <span className="en-missing">待补齐</span>}</dd>
                  <dt>核算运行</dt><dd>{data.cost.costRunId ? `#${data.cost.costRunId}` : '尚未核算'}</dd>
                  <dt>计算口径</dt>
                  <dd>{data.calculationScope === 'MATERIAL_AND_ENERGY_ONLY'
                    ? '材料与车间能源，不含人工、折旧、制造费用' : data.calculationScope}</dd>
                </dl>
              </section>

              <section className="en-detail-block">
                <h4>计费项（{data.costItems.length}）</h4>
                {data.costItems.length === 0
                  ? <p className="en-hint">本次核算未为该订单生成计费项：尚无可核算的领料或能源归属，不代表成本为零。</p>
                  : (
                    <table className="en-table en-table-compact">
                      <thead>
                        <tr><th>类型</th><th>业务日期</th><th>来源</th><th>金额</th><th>依据</th></tr>
                      </thead>
                      <tbody>
                        {data.costItems.map(line => <CostItemRow key={line.orderCostItemId} line={line} />)}
                      </tbody>
                    </table>
                  )}
              </section>

              {data.missingItems.length > 0 && (
                <section className="en-detail-block">
                  <h4>缺项（{data.missingItems.length}）</h4>
                  <MissingList items={data.missingItems} />
                </section>
              )}
            </>
          )}
        </div>
      </aside>
    </div>
  );
}

function CostItemRow({line}: {line: CostOrderCostItem}) {
  return (
    <tr className={line.missingItems.length > 0 ? 'en-row-gap' : undefined}>
      <td>{labelOf(COST_ITEM_LABELS, line.itemType)}</td>
      <td className="en-mono">{line.businessDate}</td>
      <td className="en-mono" title={line.sourceKey}>{sourceKeyText(line.sourceKey)}</td>
      <td className="en-mono">
        {money(line.amountYuan)}
        {line.missingItems.length > 0 && <span className="en-scope-detail en-missing">已知部分</span>}
      </td>
      <td className="en-scope-detail">
        {line.stockCostLedgerId && <span>成本流水 #{line.stockCostLedgerId}</span>}
        {line.energyAllocationId && <span>能源分摊 #{line.energyAllocationId}</span>}
        {!line.stockCostLedgerId && !line.energyAllocationId && <span className="en-muted">无引用</span>}
      </td>
    </tr>
  );
}

function MissingList({items}: {items: CostMissingItem[]}) {
  return (
    <ul className="en-gap-list en-cost-missing">
      {items.map((item, index) => (
        <li key={`${item.code}-${index}`}><code>{item.code}</code><span>{item.message}</span></li>
      ))}
    </ul>
  );
}
