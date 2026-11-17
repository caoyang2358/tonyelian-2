import {requestApi, type ApiResult} from './api';

/** 生产记录接口客户端（S2-R01–R19）。数量为三位小数字符串，金额两位。 */

export function newRequestId(): string {
  return crypto.randomUUID();
}

export const RECORD_TYPES = ['EXECUTION', 'PLATE_INPUT', 'ROD_HANDOVER', 'ROD_OUTPUT', 'WIRE_OUTPUT', 'NO_OUTPUT'] as const;
export type RecordType = (typeof RECORD_TYPES)[number];

export const RECORD_TYPE_LABELS: Record<RecordType, string> = {
  EXECUTION: '实际起止', PLATE_INPUT: '铜板投料', ROD_HANDOVER: '领杆交接',
  ROD_OUTPUT: '铜杆称重', WIRE_OUTPUT: '铜丝称重', NO_OUTPUT: '零产出确认',
};

export const EXECUTION_STATE_LABELS: Record<string, string> = {
  NOT_STARTED: '未开始', IN_PROGRESS: '进行中', STOPPED: '已停止', COMPLETED: '已结束',
};

export const WEIGHING_STATE_LABELS: Record<string, string> = {
  PENDING: '待统计', RECORDED: '已有称重', CONFIRMED_ZERO: '明确零产出',
};

export interface TaskSummary {
  taskId: string; workshopId: string; materialId: string;
  plannedDate: string; sequenceNo: number; orderIds: string[]; version: number;
  plannedQualifiedTon: string | null; actualQualifiedTon: string | null; varianceTon: string | null;
  executionState: string; weighingState: string;
}

export interface TaskDetail extends TaskSummary {
  planId: string; plannedPlateTon: string | null; plannedRodUseTon: string | null;
  materialName: string | null; materialSpec: string | null;
  orders: {orderId: string; orderNo: string; customerName: string; quantityKind: string;
    quantityTon: string | null; version: number; productionResult: string}[];
  plateInputSummaryTon: string | null; plateInputCount: number;
  executions: {recordId: string; actualDate: string; startedAt: string | null;
    stoppedAt: string | null; operatorName: string; version: number}[];
  handoverCount: number;
  reservations: {reservationId: string; materialId: string; reservedTon: string | null;
    remainingTon: string | null}[];
  inventory: {materialId: string; physicalTon: string | null; version: number} | null;
  missingItems: string[]; availableActions: string[];
}

export interface TimelineItem {
  recordType: RecordType; recordId: string; actualDate: string; recordedAt: string | null;
  workshopId: string | null; materialId: string | null; taskId: string | null;
  operatorName: string; source: string;
  productionGroupId?: string; groupVersion?: number; anchorActualDate?: string;
  quantityTon?: string | null; sentQuantityTon?: string | null; receivedQuantityTon?: string | null;
  orderId?: string | null; voidedAt?: string | null;
}

export interface RecordDetailPayload {
  recordType: string; recordId: string; original: Record<string, unknown> | null;
  current: Record<string, unknown> | null; version: number;
  corrections: {changeLogId: string; action: string; reason: string | null;
    operatorName: string; recordedAt: string | null}[];
  movement: {movementId: string; movementType: string; quantityDeltaTon: string;
    physicalAfterTon: string; businessDate: string} | null;
  missingItems: string[];
  handoverState?: string; errorRate?: string | null;
  taskShares?: {taskId: string; quantityTon: string}[];
  allocations?: {allocationId: string; orderId: string; orderNo: string;
    quantityTon: string; releasedTon: string}[];
}

export interface DayStatus {
  workshopId: string; actualDate: string; version: number; sourceRevision: number;
  confirmedSourceRevision: number | null; recordsComplete: boolean;
  confirmedBy: string | null; confirmedAt: string | null;
  knownQualifiedQuantityTon: string | null;
  blockingItems: {code: string; taskId?: string; message: string}[];
  missingItems: {code: string; message: string}[];
}

/** 松散映射：后端信封已由 requestApi 校验，这里只挑页面需要的字段。 */
function mapList<T>(value: unknown, map: (item: Record<string, unknown>) => T): {items: T[]; total: number} {
  const data = (value ?? {}) as Record<string, unknown>;
  const rawItems = Array.isArray(data.items) ? data.items : [];
  return {items: rawItems.map(item => map(item as Record<string, unknown>)), total: Number(data.total ?? 0)};
}

function s(value: unknown): string | null {
  return value == null || value === '' ? null : String(value);
}

function mapTask(value: Record<string, unknown>): TaskSummary {
  return {
    taskId: String(value.taskId), workshopId: String(value.workshopId),
    materialId: String(value.materialId), plannedDate: String(value.plannedDate),
    sequenceNo: Number(value.sequenceNo),
    orderIds: Array.isArray(value.orderIds) ? value.orderIds.map(String) : [],
    version: Number(value.version),
    plannedQualifiedTon: s(value.plannedQualifiedTon),
    actualQualifiedTon: s(value.actualQualifiedTon),
    varianceTon: s(value.varianceTon),
    executionState: String(value.executionState),
    weighingState: String(value.weighingState),
  };
}

export async function fetchTasks(query: {date?: string; workshopId?: string; executionState?: string;
  page?: number}, signal?: AbortSignal): Promise<ApiResult<{items: TaskSummary[]; total: number}>> {
  const params = new URLSearchParams();
  if (query.date) { params.set('plannedFrom', query.date); params.set('plannedTo', query.date); }
  if (query.workshopId) params.set('workshopId', query.workshopId);
  if (query.executionState) params.set('executionState', query.executionState);
  params.set('page', String(query.page ?? 1));
  params.set('pageSize', '20');
  return requestApi(`/api/v1/production/tasks?${params}`, value => mapList(value, mapTask), {signal});
}

export async function fetchTaskDetail(taskId: string, signal?: AbortSignal): Promise<ApiResult<TaskDetail>> {
  return requestApi(`/api/v1/production/tasks/${taskId}`, value => {
    const data = value as Record<string, unknown>;
    return {...mapTask(data), planId: String(data.planId),
      plannedPlateTon: s(data.plannedPlateTon), plannedRodUseTon: s(data.plannedRodUseTon),
      materialName: s(data.materialName), materialSpec: s(data.materialSpec),
      orders: Array.isArray(data.orders) ? data.orders.map((o: Record<string, unknown>) => ({
        orderId: String(o.orderId), orderNo: String(o.orderNo), customerName: String(o.customerName),
        quantityKind: String(o.quantityKind), quantityTon: s(o.quantityTon),
        version: Number(o.version), productionResult: String(o.productionResult)})) : [],
      plateInputSummaryTon: s(data.plateInputSummaryTon),
      plateInputCount: Number(data.plateInputCount ?? 0),
      executions: Array.isArray(data.executions) ? data.executions.map((e: Record<string, unknown>) => ({
        recordId: String(e.recordId), actualDate: String(e.actualDate), startedAt: s(e.startedAt),
        stoppedAt: s(e.stoppedAt), operatorName: String(e.operatorName), version: Number(e.version)})) : [],
      handoverCount: Number(data.handoverCount ?? 0),
      reservations: Array.isArray(data.reservations) ? data.reservations.map((r: Record<string, unknown>) => ({
        reservationId: String(r.reservationId), materialId: String(r.materialId),
        reservedTon: s(r.reservedTon), remainingTon: s(r.remainingTon)})) : [],
      inventory: data.inventory ? data.inventory as TaskDetail['inventory'] : null,
      missingItems: Array.isArray(data.missingItems) ? data.missingItems.map(String) : [],
      availableActions: Array.isArray(data.availableActions) ? data.availableActions.map(String) : [],
    };
  }, {signal});
}

function mapTimeline(value: Record<string, unknown>): TimelineItem {
  return {
    recordType: String(value.recordType) as RecordType, recordId: String(value.recordId),
    actualDate: String(value.actualDate), recordedAt: s(value.recordedAt),
    workshopId: s(value.workshopId), materialId: s(value.materialId), taskId: s(value.taskId),
    operatorName: String(value.operatorName ?? ''), source: String(value.source ?? 'PLANNED'),
    productionGroupId: s(value.productionGroupId) ?? undefined,
    groupVersion: value.groupVersion == null ? undefined : Number(value.groupVersion),
    anchorActualDate: s(value.anchorActualDate) ?? undefined,
    quantityTon: s(value.quantityTon), sentQuantityTon: s(value.sentQuantityTon),
    receivedQuantityTon: s(value.receivedQuantityTon), orderId: s(value.orderId),
    voidedAt: s(value.voidedAt),
  };
}

export async function fetchRecords(query: {actualFrom?: string; actualTo?: string; workshopId?: string;
  recordType?: string; source?: string; taskId?: string; productionGroupId?: string; page?: number},
  signal?: AbortSignal): Promise<ApiResult<{items: TimelineItem[]; total: number}>> {
  const params = new URLSearchParams();
  if (query.actualFrom) params.set('actualFrom', query.actualFrom);
  if (query.actualTo) params.set('actualTo', query.actualTo);
  if (query.workshopId) params.set('workshopId', query.workshopId);
  if (query.recordType) params.set('recordType', query.recordType);
  if (query.source) params.set('source', query.source);
  if (query.taskId) params.set('taskId', query.taskId);
  if (query.productionGroupId) params.set('productionGroupId', query.productionGroupId);
  params.set('page', String(query.page ?? 1));
  params.set('pageSize', '20');
  return requestApi(`/api/v1/production/records?${params}`, value => mapList(value, mapTimeline), {signal});
}

export async function fetchRecordDetail(recordType: string, recordId: string,
                                         signal?: AbortSignal): Promise<ApiResult<RecordDetailPayload>> {
  return requestApi(`/api/v1/production/records/${recordType}/${recordId}`, value => {
    const data = value as Record<string, unknown>;
    return {
      recordType: String(data.recordType), recordId: String(data.recordId),
      original: (data.original ?? null) as Record<string, unknown> | null,
      current: (data.current ?? null) as Record<string, unknown> | null,
      version: Number(data.version ?? 0),
      corrections: Array.isArray(data.corrections) ? data.corrections.map((c: Record<string, unknown>) => ({
        changeLogId: String(c.changeLogId), action: String(c.action), reason: s(c.reason),
        operatorName: String(c.operatorName ?? ''), recordedAt: s(c.recordedAt)})) : [],
      movement: (data.movement ?? null) as RecordDetailPayload['movement'],
      missingItems: Array.isArray(data.missingItems) ? data.missingItems.map(String) : [],
      handoverState: s(data.handoverState) ?? undefined, errorRate: s(data.errorRate),
      taskShares: Array.isArray(data.taskShares) ? data.taskShares.map((t: Record<string, unknown>) => ({
        taskId: String(t.taskId), quantityTon: String(t.quantityTon)})) : [],
      allocations: Array.isArray(data.allocations) ? data.allocations.map((a: Record<string, unknown>) => ({
        allocationId: String(a.allocationId), orderId: String(a.orderId), orderNo: String(a.orderNo),
        quantityTon: String(a.quantityTon), releasedTon: String(a.releasedTon)})) : [],
    };
  }, {signal});
}

export async function fetchDayStatus(workshopId: string, actualDate: string,
                                      signal?: AbortSignal): Promise<ApiResult<DayStatus>> {
  return requestApi(`/api/v1/production/workshops/${workshopId}/days/${actualDate}/record-status`, value => {
    const data = value as Record<string, unknown>;
    return {
      workshopId: String(data.workshopId), actualDate: String(data.actualDate),
      version: Number(data.version ?? 0), sourceRevision: Number(data.sourceRevision ?? 0),
      confirmedSourceRevision: data.confirmedSourceRevision == null ? null : Number(data.confirmedSourceRevision),
      recordsComplete: Boolean(data.recordsComplete), confirmedBy: s(data.confirmedBy),
      confirmedAt: s(data.confirmedAt), knownQualifiedQuantityTon: s(data.knownQualifiedQuantityTon),
      blockingItems: Array.isArray(data.blockingItems) ? data.blockingItems.map((b: Record<string, unknown>) => ({
        code: String(b.code), taskId: s(b.taskId) ?? undefined, message: String(b.message)})) : [],
      missingItems: Array.isArray(data.missingItems) ? data.missingItems.map((m: Record<string, unknown>) => ({
        code: String(m.code), message: String(m.message)})) : [],
    };
  }, {signal});
}

/** 写入命令：统一 requestId + operatorName。 */
export async function postProduction(path: string, body: Record<string, unknown>): Promise<unknown> {
  const result = await requestApi<unknown>(path, value => value, {method: 'POST', body});
  return result.data;
}
