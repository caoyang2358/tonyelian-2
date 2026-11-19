/**
 * 库存成本与订单核算两个视图共用的格式化与字典。
 *
 * <p>一条硬规则：金额为 null 一律显示「—」并配文字说明，绝不显示 0。缺项的钱和
 * 零元的钱在报表上是两回事，混在一起后面没人能分开。</p>
 */

/** 成本流水类型。白名单与 V6 的 CHECK 一致。 */
export const LEDGER_ENTRY_LABELS: Record<string, string> = {
  OPENING: '期初',
  RECEIPT: '入库',
  ISSUE: '领用',
  ASSIGN: '归属转移',
  RELEASE: '释放回公库',
  DELIVERY: '交付',
  CORRECTION: '更正',
};

/** 成本流水的来源类型：说明这笔价值是跟着哪类事实进来的。 */
export const SOURCE_TYPE_LABELS: Record<string, string> = {
  SEED: '期初登记',
  PLATE_INPUT: '投料记录',
  ROD_OUTPUT: '铜杆产出',
  ROD_HANDOVER: '铜杆交接',
  WIRE_OUTPUT: '铜丝产出',
  DELIVERY: '交付',
  ASSIGN: '归属转移',
};

/** 订单计费项类型。 */
export const COST_ITEM_LABELS: Record<string, string> = {
  ROD_ISSUE: '铜杆领用',
  WORKSHOP_ENERGY: '车间能源',
  PUBLIC_FINISHED: '公共成品带入',
};

export const PRODUCTION_RESULT_LABELS: Record<string, string> = {
  PENDING: '生产中',
  NORMAL_COMPLETED: '正常结束',
  SHORTFALL_COMPLETED: '不足量结束',
  NO_PRODUCTION: '无需生产',
  COMPLETED: '已结束',
};

export const DELIVERY_RESULT_LABELS: Record<string, string> = {
  PENDING: '未交付',
  PARTIAL: '部分交付',
  COMPLETED: '已交付',
};

export const ACCOUNTING_STATE_LABELS: Record<string, string> = {
  PENDING: '待核算',
  COMPLETED: '已核算',
  INCOMPLETE: '缺项',
};

/** 盈亏为何不可展示。后端给的是码，这里只负责翻译，不改判定。 */
export const PROFIT_BLOCKED_LABELS: Record<string, string> = {
  CANCELLED: '订单已取消，保留历史实际，不算利润',
  COST_RUN_MISSING: '尚未核算：还没有可用的核算运行',
  AMOUNT_UNCONFIRMED: '约定金额未确认',
  COST_INCOMPLETE: '必要成本仍缺项',
  PRODUCTION_NOT_COMPLETED: '生产尚未结束',
  UNKNOWN: '暂不可展示',
};

/** 成本运行状态。NOT_RUN 是「没跑过」，不是「跑失败」。 */
export const RUN_STATUS_LABELS: Record<string, string> = {
  NOT_RUN: '未运行',
  PENDING: '排队中',
  RUNNING: '执行中',
  COMPLETED: '已完成',
  INCOMPLETE: '已完成 · 有缺项',
  FAILED: '失败',
};

const numberFormat = new Intl.NumberFormat('zh-CN', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const unitFormat = new Intl.NumberFormat('zh-CN', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 4,
});

/** 金额：两位小数 + 千分位；null 给「—」。 */
export function money(value: string | null): string {
  if (value == null) return '—';
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return value;
  return `¥${numberFormat.format(parsed)}`;
}

/** 吨数：三位小数，与接口口径一致。 */
export function ton(value: string | null): string {
  if (value == null) return '—';
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return value;
  return parsed.toLocaleString('zh-CN', {minimumFractionDigits: 3, maximumFractionDigits: 3});
}

/**
 * 均价：接口按 10 位小数给，页面显示 4 位就够读。
 * 完整值放 title，需要核对精度的人拿得到原值。
 */
export function unitCost(value: string | null): string {
  if (value == null) return '—';
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return value;
  return unitFormat.format(parsed);
}

/** 盈亏要连方向和文字一起说，不能只靠颜色。 */
export function profitText(value: string | null): string {
  if (value == null) return '—';
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return value;
  if (parsed === 0) return '收支持平 ¥0.00';
  return `${parsed > 0 ? '盈利' : '亏损'} ${money(String(Math.abs(parsed)))}`;
}

export function labelOf(dict: Record<string, string>, code: string | null): string {
  if (!code) return '—';
  return dict[code] ?? code;
}

const ENERGY_SHORT: Record<string, string> = {ELECTRICITY: '电', WATER: '水', GAS: '煤气'};

/**
 * 订单计费项的来源标识是可追溯键，不是给人读的：
 * {@code ENERGY:2:2026-11-18:ELECTRICITY} 和 {@code ROD_ISSUE:9310}。
 * 原样显示等于让人去查库，这里翻成人话，原值放在 title 里备查。
 */
export function sourceKeyText(sourceKey: string): string {
  const energy = /^ENERGY:(\d+):(\d{4}-\d{2}-\d{2}):([A-Z_]+)$/.exec(sourceKey);
  if (energy) {
    return `车间${energy[1]} · ${energy[2]} · ${ENERGY_SHORT[energy[3]] ?? energy[3]}`;
  }
  const rodIssue = /^ROD_ISSUE:(\d+)$/.exec(sourceKey);
  if (rodIssue) return `领杆流水 #${rodIssue[1]}`;
  const publicFinished = /^PUBLIC_FINISHED:(\d+)$/.exec(sourceKey);
  if (publicFinished) return `公共成品归属 #${publicFinished[1]}`;
  return sourceKey;
}
