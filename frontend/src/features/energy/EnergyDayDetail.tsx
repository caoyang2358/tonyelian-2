import { useEffect, useRef, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import type { EnergyAllocationShare, EnergyWorkshopDayAllocation } from '../../lib/api';
import { formatPrice } from './energyPriceFormat';
import './energy.css';

/**
 * S2-E03 当日来源与分摊详情。
 *
 * <p>从总量表里点某一天进来，所以顶部固定车间与实际日，不再提供筛选——换一天就回列表点，
 * 这里再放一层日期选择只会让人忘记自己在看哪一天。</p>
 *
 * <p>三条展示规则，都来自「不能把不确定说成确定」：</p>
 * <ul>
 *   <li>分母不成立时不显示比例列，只显示「未建立分母」和缺项；</li>
 *   <li>分摊合计与源总量的差额只有在三项都算得出来时才给，缺一项就整行留空；</li>
 *   <li>核算运行没有就显示「未核算」，不拿「待核算」当「已核算」。</li>
 * </ul>
 */

const STATUS_LABELS: Record<string, string> = {
  PENDING_INPUT: '依据未齐',
  PENDING_COSTING: '待核算',
  NO_OUTPUT_CONFIRMED: '确认无产出',
  ALLOCATED: '已分摊',
};

const BASIS_LABELS: Record<string, string> = {
  OUTPUT_WEIGHT: '按当日实际合格量分摊',
  STOCK_COST: '按库存成本归集',
};

const ENERGY_LABELS: Record<string, string> = {
  ELECTRICITY: '电',
  WATER: '水',
  GAS: '煤气',
};

function statusClass(status: string): string {
  if (status === 'NO_OUTPUT_CONFIRMED') return 'en-badge-missing';
  if (status === 'ALLOCATED') return 'en-badge-complete';
  return 'en-badge-partial';
}

function percent(ratio: string | null): string {
  if (!ratio) return '—';
  const value = Number(ratio);
  if (!Number.isFinite(value)) return '—';
  return `${(value * 100).toFixed(2)}%`;
}

export function EnergyDayDetail({workshopId, actualDate, onClose}: {
  workshopId: string; actualDate: string; onClose: () => void;
}) {
  const [data, setData] = useState<EnergyWorkshopDayAllocation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    controller.current?.abort();
    const current = new AbortController();
    controller.current = current;
    setLoading(true);
    setError(null);
    api.energyDayAllocation(workshopId, actualDate, current.signal)
      .then(result => { if (!current.signal.aborted) setData(result.data); })
      .catch((cause: unknown) => {
        if (cause instanceof Error && cause.name === 'AbortError') return;
        setError(cause instanceof ApiError ? cause.message : '读取当日分摊失败，请稍后重试。');
      })
      .finally(() => { if (!current.signal.aborted) setLoading(false); });
    return () => current.abort();
  }, [workshopId, actualDate]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  const shares = data
    ? (data.publicShare ? [...data.allocations, data.publicShare] : data.allocations)
    : [];

  return (
    <div className="en-drawer-mask" onClick={onClose} role="presentation">
      <aside className="en-drawer" onClick={event => event.stopPropagation()} aria-label="当日来源与分摊">
        <header className="en-drawer-head">
          <div>
            <h3>{data?.workshopName ?? `车间 ${workshopId}`}</h3>
            <span className="en-section-note">{actualDate}</span>
          </div>
          <button type="button" className="en-drawer-close" onClick={onClose} aria-label="关闭">×</button>
        </header>

        <div className="en-drawer-body">
          {loading && !data && <p className="en-hint">正在读取…</p>}
          {error && <p className="en-error">{error}</p>}

          {data && (
            <>
              <div className="en-detail-status">
                <span className={`en-badge ${statusClass(data.status)}`}>
                  {STATUS_LABELS[data.status] ?? data.status}
                </span>
                <span className="en-detail-basis">{BASIS_LABELS[data.allocationBasis] ?? data.allocationBasis}</span>
                <p className="en-detail-note">{data.statusNote}</p>
              </div>

              <section className="en-detail-block">
                <h4>当日录全与分母</h4>
                <dl className="en-detail-pairs">
                  <dt>录全确认</dt>
                  <dd>
                    {data.dayConfirmation.confirmed
                      ? `已确认${data.dayConfirmation.confirmedBy ? `（${data.dayConfirmation.confirmedBy}）` : ''}`
                      : '未确认'}
                    {data.dayConfirmation.invalidated && <span className="en-missing">已失效</span>}
                  </dd>
                  <dt>分摊分母</dt>
                  <dd>
                    {data.denominator.quantityTon
                      ? <>{data.denominator.quantityTon} t{data.denominator.complete ? '' : '（未确认，可能随补录变化）'}</>
                      : '未建立'}
                  </dd>
                  <dt>说明</dt>
                  <dd>{data.denominator.note}</dd>
                </dl>
              </section>

              <section className="en-detail-block">
                <h4>计量来源与费用</h4>
                <div className="en-table-wrap">
                  <table className="en-table en-table-compact">
                    <thead>
                      <tr>
                        <th>能源</th>
                        <th>表计</th>
                        <th>消耗</th>
                        <th>已知部分</th>
                        <th>完整性</th>
                        <th>单价</th>
                        <th>费用</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.sources.map(source => (
                        <tr key={source.energyType}>
                          <td>{source.energyTypeName ?? ENERGY_LABELS[source.energyType] ?? source.energyType}</td>
                          <td>
                            {source.meter
                              ? <><code>{source.meter.sensorCode}</code>
                                  <span className="en-scope-detail">{source.meter.sensorName}</span></>
                              : <span className="en-missing">未配置表计</span>}
                          </td>
                          <td className="en-mono">
                            {source.consumption
                              ? <>{source.consumption}<span className="en-unit">{source.unit}</span></>
                              : <span className="en-muted">—</span>}
                          </td>
                          <td className="en-mono">
                            {source.knownConsumption
                              ? <>{source.knownConsumption}<span className="en-unit">{source.unit}</span></>
                              : <span className="en-muted">—</span>}
                          </td>
                          <td>
                            <span className={`en-badge en-badge-${source.completeness.toLowerCase()}`}>
                              {source.completeness === 'COMPLETE' ? '完整'
                                : source.completeness === 'PARTIAL' ? '部分已知' : '无可用来源'}
                            </span>
                          </td>
                          <td className="en-mono">
                            {source.price
                              ? <>{formatPrice(source.price.unitPriceYuanPerUnit)}
                                  <span className="en-scope-detail">
                                    {source.price.unitCode} · 版本 {source.price.version}
                                    {' · '}{source.price.scope === 'WORKSHOP' ? '车间专用' : '通用'}
                                  </span></>
                              : <span className="en-muted">无适用价格</span>}
                          </td>
                          <td className="en-mono">
                            {source.amountYuan
                              ? <>{source.amountYuan}<span className="en-unit">元</span></>
                              : <span className="en-muted">—</span>}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <p className="en-detail-total">
                  当日费用合计：
                  {data.totalAmountYuan
                    ? <strong>{data.totalAmountYuan} 元</strong>
                    : <span className="en-missing">不可合计（有能源缺测或缺价）</span>}
                </p>
              </section>

              <section className="en-detail-block">
                <h4>分摊明细</h4>
                {shares.length === 0
                  ? <p className="en-empty">{emptyShareText(data)}</p>
                  : (
                    <div className="en-table-wrap">
                      <table className="en-table en-table-compact">
                        <thead>
                          <tr>
                            <th>对象</th>
                            <th>归属合格量</th>
                            <th>比例</th>
                            <th>分摊消耗</th>
                            <th>费用</th>
                          </tr>
                        </thead>
                        <tbody>
                          {shares.map(share => (
                            <tr key={share.targetType === 'PUBLIC' ? 'PUBLIC' : `ORDER-${share.orderId}`}>
                              <td>
                                {share.targetType === 'PUBLIC'
                                  ? <strong>公共份额</strong>
                                  : <>{share.orderNo ?? `订单 ${share.orderId}`}</>}
                              </td>
                              <td className="en-mono">
                                {share.qualifiedQuantityTon
                                  ? <>{share.qualifiedQuantityTon} t</>
                                  : <span className="en-muted">—</span>}
                              </td>
                              <td className="en-mono">{percent(share.ratio)}</td>
                              <td className="en-mono">
                                {share.energies.every(item => item.allocatedConsumption === null)
                                  ? <span className="en-muted">—</span>
                                  : share.energies.map(item => (
                                    <span key={item.energyType} className="en-share-line">
                                      {ENERGY_LABELS[item.energyType] ?? item.energyType}
                                      {' '}
                                      {item.allocatedConsumption
                                        ? <>{item.allocatedConsumption}<span className="en-unit">{item.unit}</span></>
                                        : <span className="en-muted">—</span>}
                                    </span>
                                  ))}
                              </td>
                              <td className="en-mono">
                                {share.amountYuan
                                  ? <>{share.amountYuan}<span className="en-unit">元</span></>
                                  : <span className="en-muted">—</span>}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                <p className="en-detail-note">
                  {data.allocationBasis === 'STOCK_COST'
                    ? '车间一按库存成本归集材料与能源，同次投料被分次称重引用时只归集一次。'
                    : '按当天实际合格铜丝重量分摊全日消耗，含待机消耗，属于经营估算。'}
                </p>
                {shares.some(share => share.amountYuan === null) && (
                  <p className="en-detail-note">
                    费用为「—」表示该项缺少可计价的数量或单价，未按已知部分合计。
                  </p>
                )}
              </section>

              {data.reconciliation.length > 0 && (
                <section className="en-detail-block">
                  <h4>合计校验</h4>
                  <div className="en-table-wrap">
                    <table className="en-table en-table-compact">
                      <thead>
                        <tr>
                          <th>能源</th>
                          <th>源总量</th>
                          <th>订单合计</th>
                          <th>公共份额</th>
                          <th>差额</th>
                        </tr>
                      </thead>
                      <tbody>
                        {data.reconciliation.map(row => (
                          <tr key={row.energyType}>
                            <td>{ENERGY_LABELS[row.energyType] ?? row.energyType}</td>
                            <td className="en-mono">{row.sourceConsumption}<span className="en-unit">{row.unit}</span></td>
                            <td className="en-mono">{row.allocatedConsumption ?? '—'}</td>
                            <td className="en-mono">{row.publicConsumption ?? '—'}</td>
                            <td className="en-mono">
                              {row.difference === '0.000000'
                                ? <span className="en-ok">对齐</span>
                                : <span className="en-missing">{row.difference ?? '—'}</span>}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </section>
              )}

              <section className="en-detail-block">
                <h4>核算与缺项</h4>
                <dl className="en-detail-pairs">
                  <dt>核算运行</dt>
                  <dd>{data.costRun.costRunId ? `#${data.costRun.costRunId}` : '未核算（本版尚无核算运行）'}</dd>
                  <dt>核算版本</dt>
                  <dd>{data.costRun.version ?? '—'}</dd>
                </dl>
                {data.missingItems.length === 0
                  ? <p className="en-hint">无缺项。</p>
                  : (
                    <ul className="en-gap-list">
                      {data.missingItems.map((item, index) => (
                        <li key={`${item.code}-${index}`}>
                          <code>{item.code}</code>
                          <span>{item.message}</span>
                        </li>
                      ))}
                    </ul>
                  )}
              </section>
            </>
          )}
        </div>
      </aside>
    </div>
  );
}

function emptyShareText(data: EnergyWorkshopDayAllocation): string {
  if (data.status === 'NO_OUTPUT_CONFIRMED') {
    return '当日确认无合格产出，能源按车间费用归集，不产生订单份额。';
  }
  if (data.allocationBasis === 'STOCK_COST') {
    return '车间一按库存成本归集，本版未接入库存成本台账，暂无分摊结果。';
  }
  if (!data.denominator.quantityTon) {
    return '当日未建立分摊分母，暂不能分摊。';
  }
  return '当日有合格量但尚无订单归属，无法拆分到订单与公共份额。';
}

/** 单据里的比例列只在分母成立时才有意义，这一层判断由调用方保证顺序。 */
export function shareSummary(share: EnergyAllocationShare): string {
  return `${share.orderNo ?? share.targetType} ${percent(share.ratio)}`;
}
