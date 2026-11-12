/* 运维管理页面的中文文案映射：后端返回稳定英文枚举码，前端负责翻译显示。 */

export const eventStateLabels: Record<string, string> = {
  ONGOING: '进行中', RECOVERED: '已恢复结束', ENDED: '已结束',
};
export const handlingStateLabels: Record<string, string> = {
  PENDING: '待处理', ACKNOWLEDGED: '已知悉', DEFER_REPAIR: '暂不报修',
};
/** 处理优先级：后端按"是否恢复 + 是否已人工处理"推导，前端只负责翻译。 */
export const priorityLabels: Record<string, string> = {
  URGENT: '紧急', HIGH: '高', NORMAL: '一般', LOW: '低',
};

/** 后端小数以字符串返回（如 96.500000），展示时去掉多余的尾零：96.500000 → 96.5。 */
export function formatReading(value: string | null): string {
  if (value == null) return '—';
  const num = Number(value);
  return Number.isFinite(num) ? String(num) : value;
}
/** 故障类别：与库里的 chk_p2_repair_fault_category 取值一一对应。 */
export const faultCategoryLabels: Record<string, string> = {
  MECHANICAL: '机械', ELECTRICAL: '电气', INSTRUMENT: '仪表', PROCESS: '工艺', OTHER: '其他',
};
export const orderStatusLabels: Record<string, string> = {
  DRAFT: '待提交', REVIEW_PENDING: '待审核', RETURNED_FOR_INFO: '待补充',
  IN_REPAIR: '维修中', ACCEPTANCE_PENDING: '待验收', COMPLETED: '已完成', CLOSED: '已关闭',
};
export const sourceTypeLabels: Record<string, string> = {
  ALERT_EVENT: '异常事件', INSPECTION: '巡检', MANUAL: '人工上报',
};
/** 事件来源：采集判定与人工补录必须一眼区分，否则补录会看起来像自动报警。 */
export const eventSourceLabels: Record<string, string> = {
  REALTIME: '自动采集', HISTORY: '历史回放', MANUAL: '人工补录',
};
export const inspectionResultLabels: Record<string, string> = {NORMAL: '正常', ABNORMAL: '异常'};
export const reviewResultLabels: Record<string, string> = {
  APPROVE: '审核通过', RETURN_FOR_INFO: '退回补充', NO_REPAIR: '无需维修', DUPLICATE: '重复单',
};
export const acceptanceResultLabels: Record<string, string> = {PASS: '验收通过', FAIL: '验收不通过'};
export const triggerModeLabels: Record<string, string> = {IMMEDIATE: '立即触发', SUSTAINED: '持续触发'};
export const endReasonLabels: Record<string, string> = {RECOVERED: '自动恢复', RULE_CHANGED: '规则变更'};
/** 参数编码兜底翻译：库里参数名是人工录入的，取不到名称时用编码翻译成中文。 */
export const parameterCodeLabels: Record<string, string> = {
  temperature: '温度', pressure: '压力', level: '液位', speed: '速度', diameter: '直径', tension: '张力',
  TEMP: '温度', PRESSURE: '压力', LEVEL: '液位', SPEED: '速度', DIAMETER: '直径', TENSION: '张力',
};
/** 单位编码 → 常用符号；未收录的保持原样（Pa、N 这类本身就是通用写法）。 */
const unitLabels: Record<string, string> = {
  C: '℃', '°C': '℃', PCT: '%', PERCENT: '%', M_PER_MIN: 'm/min', M_PER_SEC: 'm/s', MM: 'mm', M: 'm',
};
/** 参数展示名：优先用库里的中文参数名，缺失时按编码翻译。 */
export function parameterText(code: string | null | undefined, name?: string | null): string {
  if (name) return name;
  return label(parameterCodeLabels, code);
}
export function formatUnit(code: string | null | undefined): string {
  if (!code) return '';
  return unitLabels[code] ?? code;
}
/** 流转动作：库里存的是环节编码（CREATE / REVIEW …），页面一律显示业务动作名。 */
export const actionTypeLabels: Record<string, string> = {
  CREATE: '创建工单', SUBMIT: '提交审核', REVIEW: '审核派工',
  REPAIR_RESULT: '登记维修结果', ACCEPTANCE: '验收',
  SAVE: '保存资料', APPROVE: '审核通过', RETURN_FOR_INFO: '退回补充',
  NO_REPAIR: '无需维修', DUPLICATE: '重复单', RECORD_REPAIR_RESULT: '登记维修结果',
  ACCEPT_PASS: '验收通过', ACCEPT_FAIL: '验收不通过',
};
export const availableActionLabels: Record<string, string> = {
  SAVE: '保存资料', SUBMIT: '提交审核', APPROVE: '审核通过', RETURN_FOR_INFO: '退回补充',
  NO_REPAIR: '无需维修', DUPLICATE: '重复单', RECORD_REPAIR_RESULT: '登记维修结果',
  ACCEPT_PASS: '验收通过', ACCEPT_FAIL: '验收不通过',
};

export function label(map: Record<string, string>, value: string | null | undefined, fallback = '—'): string {
  if (value == null || value === '') return fallback;
  return map[value] ?? value;
}

export function formatTime(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** datetime-local 输入框的当前值（本地时区，精确到分钟）。 */
export function localInputValue(offsetMinutes = 0): string {
  const date = new Date(Date.now() + offsetMinutes * 60000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
