/* 运维管理模块 API 层：对应后端 S1-A01~A04、S1-I01~I04、S1-R01~R09、S1-M01。
 * 信封与错误处理沿用 lib/api.ts 的约定（code=OK、错误抛 ApiError 携带服务端消息）。 */

export class MaintenanceApiError extends Error {
  readonly code: string;
  readonly status: number;
  constructor(message: string, code: string, status: number) {
    super(message);
    this.name = 'MaintenanceApiError';
    this.code = code;
    this.status = status;
  }
}

interface Envelope { code: string; message: string; data: unknown }

async function request<T>(method: 'GET' | 'POST' | 'PUT', path: string, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch('/api/v1' + path, {
      method,
      headers: body === undefined ? {Accept: 'application/json'} : {Accept: 'application/json', 'Content-Type': 'application/json'},
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new MaintenanceApiError('暂时无法连接服务，请确认后端已启动。', 'NETWORK_ERROR', 0);
  }
  let envelope: Envelope;
  try {
    envelope = await response.json() as Envelope;
  } catch {
    throw new MaintenanceApiError(`服务返回了无法识别的数据（HTTP ${response.status}）。`, 'INVALID_RESPONSE', response.status);
  }
  if (!response.ok || envelope.code !== 'OK') {
    throw new MaintenanceApiError(
      typeof envelope.message === 'string' && envelope.message.trim() ? envelope.message : `请求失败（HTTP ${response.status}）。`,
      typeof envelope.code === 'string' ? envelope.code : 'HTTP_ERROR',
      response.status);
  }
  return envelope.data as T;
}

export function newRequestId(): string {
  return typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `req-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/** 把 datetime-local 的本地时间值转成带时区的 ISO 8601。 */
export function toOffsetIso(value: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? date.toISOString() : null;
}

/* ---------------- 事件提醒（S1-A） ---------------- */

export interface AlertEventItem {
  eventId: string; equipmentId: string; equipmentName: string;
  sensorId: string; sensorCode: string; parameterId: string; parameterCode: string;
  startedAt: string; triggeredAt: string; recoveredAt: string | null; endedAt: string | null;
  endReason: string | null; eventState: string; handlingState: string;
  /** 处理优先级：URGENT / HIGH / NORMAL / LOW，由事件状态与人工处理状态推导。 */
  priority: string;
  repairOrderIds: string[]; version: number;
}
export interface AlertEventPage { items: AlertEventItem[]; page: number; pageSize: number; total: number }
export interface RuleSnapshot {
  ruleVersionId: string; parameterId: string; ruleVersion: number;
  normalLower: string | null; normalUpper: string | null; alarmLower: string | null; alarmUpper: string | null;
  unitCode: string | null; triggerMode: string | null; durationSeconds: number | null;
  effectiveAt: string; reason: string | null; operatorName: string;
}
export interface RepairOrderRef { repairOrderId: string; orderNo: string; status: string }
export interface AlertEventDetail extends AlertEventItem {
  triggerValue: string | null; unitCode: string | null; sourceReference: unknown;
  ruleSnapshot: RuleSnapshot | null; repairOrders: RepairOrderRef[];
}
export interface HandlingRecordItem { handlingRecordId: string; action: string; reason: string; operatorName: string; recordedAt: string }
export interface HandlingRecordPage { items: HandlingRecordItem[]; page: number; pageSize: number; total: number }
export interface HandlingResult { handlingRecordId: string; handlingState: string; version: number; updatedAt: string }

/** 列表排序：TIME = 触发时间倒序（默认），PRIORITY = 未恢复且未处理的事件优先。 */
export type AlertEventSort = 'TIME' | 'PRIORITY';

export interface AlertEventFilter {
  eventState?: string; handlingState?: string; equipmentId?: string; from?: string; to?: string; sort?: AlertEventSort;
}

function queryOf(filter: Record<string, string | number | undefined>): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(filter)) {
    if (value !== undefined && value !== '') query.set(key, String(value));
  }
  const text = query.toString();
  return text ? `?${text}` : '';
}

export const maintenanceApi = {
  alertEvents: (filter: AlertEventFilter = {}, page = 1, pageSize = 20) =>
    request<AlertEventPage>('GET', `/alert-events${queryOf({...filter, page, pageSize})}`),
  alertEvent: (eventId: string) => request<AlertEventDetail>('GET', `/alert-events/${encodeURIComponent(eventId)}`),
  handlingRecords: (eventId: string, page = 1, pageSize = 20) =>
    request<HandlingRecordPage>('GET', `/alert-events/${encodeURIComponent(eventId)}/handling-records${queryOf({page, pageSize})}`),
  addHandlingRecord: (eventId: string, body: {requestId: string; operatorName: string; expectedVersion: number; action: string; reason: string}) =>
    request<HandlingResult>('POST', `/alert-events/${encodeURIComponent(eventId)}/handling-records`, body),
};

export interface InspectionItem {
  inspectionId: string; equipmentId: string; equipmentName: string;
  inspectedAt: string; inspectorName: string; content: string; result: string;
  repairOrderIds: string[];
  /** 现场照片张数；照片本身走共用附件接口按需拉取。 */
  photoCount: number;
  version: number;
}
export interface InspectionPage { items: InspectionItem[]; page: number; pageSize: number; total: number }
export interface InspectionDetail extends InspectionItem { description: string | null; operatorName: string; recordedAt: string }
export interface InspectionFilter { equipmentId?: string; result?: string; from?: string; to?: string }

export interface InspectionWriteFields {
  operatorName: string; equipmentId: string; inspectedAt: string;
  inspectorName: string; content: string; result: string; description: string;
}

export const inspectionApi = {
  list: (filter: InspectionFilter = {}, page = 1, pageSize = 20) =>
    request<InspectionPage>('GET', `/inspections${queryOf({...filter, page, pageSize})}`),
  detail: (inspectionId: string) => request<InspectionDetail>('GET', `/inspections/${encodeURIComponent(inspectionId)}`),
  create: (fields: InspectionWriteFields) => request<InspectionDetail>('POST', '/inspections', {
    requestId: newRequestId(), operatorName: fields.operatorName, equipmentId: fields.equipmentId,
    inspectedAt: toOffsetIso(fields.inspectedAt), inspectorName: fields.inspectorName,
    content: fields.content, result: fields.result,
    description: fields.description.trim() ? fields.description : null,
  }),
  update: (inspectionId: string, expectedVersion: number, reason: string, fields: InspectionWriteFields) =>
    request<{inspection: InspectionDetail; version: number}>('PUT', `/inspections/${encodeURIComponent(inspectionId)}`, {
      requestId: newRequestId(), operatorName: fields.operatorName, expectedVersion, reason,
      inspectedAt: toOffsetIso(fields.inspectedAt), inspectorName: fields.inspectorName,
      content: fields.content, result: fields.result,
      description: fields.description.trim() ? fields.description : null,
    }),
};

/* ---------------- 维修工单（S1-R） ---------------- */

export interface RepairOrderItem {
  repairOrderId: string; orderNo: string; equipmentId: string; equipmentName: string;
  sourceType: string; sourceId: string | null;
  /** 故障类别：MECHANICAL / ELECTRICAL / INSTRUMENT / PROCESS / OTHER；历史工单可能为空。 */
  faultCategory: string | null;
  status: string;
  createdAt: string; firstSubmittedAt: string | null; completedAt: string | null; version: number;
}
export interface RepairOrderPage { items: RepairOrderItem[]; page: number; pageSize: number; total: number }
export interface RepairActionItem {
  actionId: string; actionType: string; result: string | null; previousStatus: string | null; nextStatus: string;
  occurredAt: string; recordedAt: string; operatorName: string; reason: string | null; description: string | null;
}
export interface RepairOrderDetail {
  repairOrderId: string; orderNo: string; equipmentId: string; equipmentName: string;
  sourceType: string; sourceId: string | null; sourceSnapshot: unknown;
  faultCategory: string | null; status: string;
  description: string | null; supplement: string | null; reporterName: string | null;
  photoAttachmentIds: string[];
  availableActions: string[]; actions: RepairActionItem[];
  createdAt: string; updatedAt: string; firstSubmittedAt: string | null; completedAt: string | null; version: number;
}
export interface RepairOrderCreated {
  repairOrderId: string; orderNo: string; status: string; sourceSnapshot: unknown; editableFields: string[]; version: number;
}
export interface RepairOrderFilter { equipmentId?: string; status?: string; sourceType?: string; from?: string; to?: string }
export interface RepairActionResult { repairOrderId: string; status: string; actionId: string; version: number; updatedAt: string }

export const repairOrderApi = {
  list: (filter: RepairOrderFilter = {}, page = 1, pageSize = 20) =>
    request<RepairOrderPage>('GET', `/repair-orders${queryOf({...filter, page, pageSize})}`),
  detail: (repairOrderId: string) => request<RepairOrderDetail>('GET', `/repair-orders/${encodeURIComponent(repairOrderId)}`),
  actions: (repairOrderId: string, page = 1, pageSize = 50) =>
    request<{items: RepairActionItem[]; page: number; pageSize: number; total: number}>('GET',
      `/repair-orders/${encodeURIComponent(repairOrderId)}/actions${queryOf({page, pageSize})}`),
  create: (body: {
    sourceType: string; sourceId?: string; equipmentId?: string; operatorName: string;
    faultCategory?: string | null; description?: string | null; reporterName?: string | null;
  }) => request<RepairOrderCreated>('POST', '/repair-orders', {requestId: newRequestId(), ...body}),
  save: (repairOrderId: string, body: {
    operatorName: string; expectedVersion: number; reason: string;
    description: string; supplement: string; reporterName: string;
    faultCategory?: string | null; photoAttachmentIds?: string[];
  }) => request<{repairOrder: RepairOrderDetail}>('PUT', `/repair-orders/${encodeURIComponent(repairOrderId)}`,
    {requestId: newRequestId(), ...body}),
  submit: (repairOrderId: string, body: {operatorName: string; expectedVersion: number}) =>
    request<RepairActionResult>('POST', `/repair-orders/${encodeURIComponent(repairOrderId)}/submissions`, {requestId: newRequestId(), ...body}),
  review: (repairOrderId: string, body: {operatorName: string; expectedVersion: number; result: string; reason: string}) =>
    request<RepairActionResult>('POST', `/repair-orders/${encodeURIComponent(repairOrderId)}/reviews`, {requestId: newRequestId(), ...body}),
  recordRepairResult: (repairOrderId: string, body: {
    operatorName: string; expectedVersion: number; performedAt: string; description: string; attachmentIds?: string[];
  }) =>
    request<RepairActionResult>('POST', `/repair-orders/${encodeURIComponent(repairOrderId)}/repair-results`, {requestId: newRequestId(), ...body}),
  accept: (repairOrderId: string, body: {operatorName: string; expectedVersion: number; result: string; acceptedAt: string; reason: string}) =>
    request<RepairActionResult>('POST', `/repair-orders/${encodeURIComponent(repairOrderId)}/acceptances`, {requestId: newRequestId(), ...body}),
};

/* ---------------- 运维概览（S1-M01） ---------------- */

export interface EquipmentReportCount { equipmentId: string; equipmentName: string; reportCount: number; completedCount: number }
export interface MaintenanceOverview {
  from: string; to: string; generatedAt: string;
  inspectionCount: number; alertEventCount: number;
  pendingRepairCount: number; completedRepairCount: number;
  averageHandlingSeconds: string | null; handlingSampleCount: number;
  equipmentReportCounts: EquipmentReportCount[];
  deadlineReminderCount: number | null; deadlineReminderNote: string | null;
  caliber: Record<string, string>;
}

export const overviewApi = {
  maintenanceOverview: (filter: {workshopId?: string; equipmentId?: string; from: string; to: string}) =>
    request<MaintenanceOverview>('GET', `/maintenance-overview${queryOf(filter)}`),
};
