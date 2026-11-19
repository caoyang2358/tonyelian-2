import { useState } from 'react';
import type { Page } from '../../components/Icon';
import { CostingOrdersPanel } from './CostingOrdersPanel';
import { CostingStocksPanel } from './CostingStocksPanel';
import { EnergyPricePanel } from './EnergyPricePanel';
import { EnergyTotalsPanel } from './EnergyTotalsPanel';
import './energy.css';

/**
 * 系统二 · 生产与能源统计。
 *
 * <p>视图划分按《页面与交互规格》第 1 节：总量统计、订单核算、库存成本、能源价格。
 * 每日分摊不单独占页签——它是总量的详情，从某车间某日点进去看。</p>
 *
 * <p>四个视图背后都是真实接口：E01/E02 用量与日来源、E03 当日分摊、E04—E06 价格、
 * K01/K02 库存成本、K03/K04 订单核算。补算（K05/K06）暂时只在接口层，没有页面入口——
 * 「补算」是对全区间重跑一次成本，误点的代价比少一个按钮大，等运行查询与失败重试一起做。</p>
 */

type ViewId = 'totals' | 'costing' | 'stock' | 'price';

interface View {
  id: ViewId;
  label: string;
  hint: string;
  /** 未实现时给出具体依赖，让人知道等的是什么，而不是一句"敬请期待"。 */
  blocked?: string;
}

const VIEWS: View[] = [
  {
    id: 'totals',
    label: '总量统计',
    hint: '分车间的电、水、煤气与合格产量，以及每日来源明细',
  },
  {
    id: 'costing',
    label: '订单核算',
    hint: '订单的适用金额、已知与完整成本，以及估算盈亏',
  },
  {
    id: 'stock',
    label: '库存成本',
    hint: '铜板、铜杆与铜丝的实物量、在库价值及移动加权均价',
  },
  {
    id: 'price',
    label: '能源价格',
    hint: '通用与车间专用单价、按用能日生效',
  },
];

export interface EnergyStatisticsPageProps {
  item: {id: Exclude<Page, 'equipment'>; label: string; description: string; features: string[]};
  back: () => void;
}

export function EnergyStatisticsPage({item}: EnergyStatisticsPageProps) {
  const [view, setView] = useState<ViewId>('totals');
  const active = VIEWS.find(entry => entry.id === view)!;

  return (
    <section className="en-panel">
      <header className="en-panel-head">
        <div>
          <span className="section-kicker">{item.label}</span>
          <h2>{active.label}</h2>
          <p>{active.hint}</p>
        </div>
        <div className="en-view-switch" role="tablist" aria-label="统计视图">
          {VIEWS.map(entry => (
            <button
              key={entry.id}
              type="button"
              role="tab"
              aria-selected={view === entry.id}
              className={`en-view-tab ${view === entry.id ? 'active' : ''}`}
              onClick={() => setView(entry.id)}
            >
              {entry.label}
              {entry.blocked && <span className="en-view-pending">待接入</span>}
            </button>
          ))}
        </div>
      </header>

      {active.blocked
        ? <div className="en-blocked">
            <p className="en-blocked-title">{active.label}尚未接入</p>
            <p className="en-blocked-detail">{active.blocked}</p>
            <button type="button" className="button button-small" onClick={() => setView('totals')}>
              回到总量统计
            </button>
          </div>
        : view === 'totals'
          ? <EnergyTotalsPanel />
          : view === 'costing'
            ? <CostingOrdersPanel />
            : view === 'stock'
              ? <CostingStocksPanel />
              : <EnergyPricePanel />}
    </section>
  );
}
