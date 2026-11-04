export type ManagementStatus = 'IN_USE' | 'STOPPED' | 'SCRAPPED';
export type ParameterValueType = 'STRING' | 'DECIMAL' | 'INTEGER' | 'BOOLEAN';

/** 一期共享字段。首批契约只保证这七项，历史设备没有扩展档案。 */
export type EquipmentBase = {
  id: string; code: string; name: string; workshopId: string;
  workshopName: string; responsiblePerson: string | null; enabled: boolean;
};
/** S1-E01 列表项：共享字段 + 类型、管理状态与聚合版本。未补档设备后四项为 null / 0。 */
export type Equipment = EquipmentBase & {
  typeCode: string | null; typeName: string | null;
  managementStatus: string | null; version: number;
};
export type EquipmentQuery = {
  keyword?: string; workshopId?: string; typeCode?: string;
  managementStatus?: string; enabled?: boolean;
  page?: number; pageSize?: number;
};
export interface EquipmentPageResult {
  items: Equipment[]; page: number | null; pageSize: number | null; total: number | null;
}
export interface TechnicalParameter {
  key: string; label: string; valueType: ParameterValueType;
  value: string | boolean | null; unitLabel: string | null;
}
export interface EquipmentProfile {
  typeCode: string | null; model: string | null; processId: string | null;
  installationLocation: string | null; manufacturer: string | null;
  factorySerialNo: string | null; purchasedOn: string | null;
  commissionedOn: string | null; expectedLifeYears: number | null;
  warrantyExpiresOn: string | null; managementStatus: ManagementStatus | null;
  technicalParameters: TechnicalParameter[];
}
export interface DeletionBlocker { type: string; count: number; message: string }
export interface EquipmentDetail {
  id: string; code: string; name: string; workshopId: string; workshopName: string;
  responsiblePerson: string | null; enabled: boolean; profile: EquipmentProfile;
  version: number; missingFields: string[]; deletion: { allowed: boolean; blockers: DeletionBlocker[] };
}
export interface ChangeLogItem {
  id: string; operation: string; before: unknown; after: unknown;
  reason: string | null; operatorName: string; recordedAt: string;
  requestId: string | null; versionBefore: number | null; versionAfter: number | null;
}
export interface PageResult<T> {
  items: T[]; page: number | null; pageSize: number | null; total: number | null;
}
export interface ReferenceOption { code: string; name: string }
export interface WorkshopOption { id: string; code: string; name: string; enabled: boolean }
export interface ProcessOption extends WorkshopOption { workshopId: string; sequenceNo: number }
export interface UnitOption { id: string; code: string; name: string; category: string }
export interface ReferenceData {
  workshops: WorkshopOption[]; processes: ProcessOption[]; units: UnitOption[];
  equipmentTypes: ReferenceOption[]; equipmentManagementStatuses: ReferenceOption[];
}
/** 提交给 E03 / E04 的表单值。可空字段必须显式传 null，遗漏会被后端判为校验失败。 */
export interface EquipmentFormValues {
  code: string; name: string; workshopId: number;
  responsiblePerson: string | null; enabled: boolean; profile: EquipmentProfile;
}
export interface Overview {
  projectName: string; environment: string; scope: 'INITIALIZATION_ONLY';
  database: { status: string; schemaName: string | null; serverVersion: string | null; phase1TableCount: number | null; phase2TableCount: number | null };
  counts: { workshops: number | null; productionEquipment: number | null; sensors: number | null };
}
export interface Health { status: string; database?: { status: string; check?: string } }
export interface ApiResult<T> { data: T; timestamp: string }

/**
 * 后端业务错误。fields 来自 400 校验失败或 409 重复编码，可直接落到对应输入框；
 * detail 保留原始 data，便于读取 409 版本冲突里的 currentVersion。
 */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number | null;
  readonly fields: Record<string, string>;
  readonly detail: Record<string, unknown> | null;
  constructor(message: string, code: string, status: number | null = null,
              fields: Record<string, string> = {}, detail: Record<string, unknown> | null = null) {
    super(message); this.name = 'ApiError'; this.code = code; this.status = status;
    this.fields = fields; this.detail = detail;
  }
}

function record(value: unknown, name: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ApiError(`${name}格式不符合接口约定，请检查前后端版本。`, 'INVALID_RESPONSE');
  return value as Record<string, unknown>;
}
function str(value: unknown, name: string): string {
  if (typeof value !== 'string' || !value.trim()) throw new ApiError(`${name}缺失或格式有误。`, 'INVALID_RESPONSE');
  return value;
}
function optional(value: unknown, name: string): string | null { return value == null || value === '' ? null : str(value, name); }
function id(value: unknown, name: string): string {
  if (typeof value === 'string' && value.trim()) return value;
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) return String(value);
  throw new ApiError(`${name}缺失或超出安全范围。`, 'INVALID_RESPONSE');
}
function count(value: unknown, name: string): number | null {
  if (value == null) return null;
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return value;
  throw new ApiError(`${name}不是有效的非负整数。`, 'INVALID_RESPONSE');
}
function integer(value: unknown, name: string): number {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return value;
  throw new ApiError(`${name}不是有效的非负整数。`, 'INVALID_RESPONSE');
}
function boolean(value: unknown, name: string): boolean {
  if (value === true || value === 1) return true;
  if (value === false || value === 0) return false;
  throw new ApiError(`${name}不是有效布尔值。`, 'INVALID_RESPONSE');
}

/** 首批契约的七字段映射。保留原行为以兼容既有调用与单测。 */
export function mapEquipment(value: unknown): EquipmentBase[] {
  const data = record(value, '设备列表');
  if (!Array.isArray(data.items)) throw new ApiError('设备列表缺少items数组。', 'INVALID_RESPONSE');
  const ids = new Set<string>();
  return data.items.map((item: unknown) => {
    const row = record(item, '设备记录'), key = id(row.id, '设备ID');
    if (ids.has(key)) throw new ApiError('设备接口返回了重复ID，请检查查询关联。', 'INVALID_RESPONSE');
    ids.add(key);
    if (![true, false, 0, 1].includes(row.enabled as boolean | number)) throw new ApiError('设备启用状态不是有效布尔值。', 'INVALID_RESPONSE');
    return {id: key, code: str(row.code, '设备编码'), name: str(row.name, '设备名称'), workshopId: id(row.workshopId, '车间ID'), workshopName: str(row.workshopName, '车间名称'), responsiblePerson: optional(row.responsiblePerson, '责任人'), enabled: row.enabled === true || row.enabled === 1};
  });
}

/** S1-E01 扩展后的完整列表项映射：新增类型、类型名称、管理状态与版本。 */
export function mapEquipmentRows(source: unknown[]): Equipment[] {
  const ids = new Set<string>();
  return source.map((item: unknown) => {
    const row = record(item, '设备记录'), key = id(row.id, '设备ID');
    if (ids.has(key)) throw new ApiError('设备接口返回了重复ID，请检查查询关联。', 'INVALID_RESPONSE');
    ids.add(key);
    return {
      id: key, code: str(row.code, '设备编码'), name: str(row.name, '设备名称'),
      workshopId: id(row.workshopId, '车间ID'), workshopName: str(row.workshopName, '车间名称'),
      responsiblePerson: optional(row.responsiblePerson, '责任人'), enabled: boolean(row.enabled, '设备启用状态'),
      typeCode: optional(row.typeCode, '设备类型'), typeName: optional(row.typeName, '设备类型名称'),
      managementStatus: optional(row.managementStatus, '管理状态'),
      version: row.version == null ? 0 : integer(row.version, '设备版本'),
    };
  });
}

export function mapEquipmentPage(value: unknown): EquipmentPageResult {
  const data = record(value, '设备列表');
  if (!Array.isArray(data.items)) throw new ApiError('设备列表缺少items数组。', 'INVALID_RESPONSE');
  return {
    items: mapEquipmentRows(data.items),
    page: count(data.page, '页码'), pageSize: count(data.pageSize, '每页条数'), total: count(data.total, '总数'),
  };
}

export function mapTechnicalParameter(value: unknown): TechnicalParameter {
  const row = record(value, '技术参数');
  const valueType = str(row.valueType, '参数值类型');
  if (!['STRING', 'DECIMAL', 'INTEGER', 'BOOLEAN'].includes(valueType)) throw new ApiError('技术参数值类型不在约定范围内。', 'INVALID_RESPONSE');
  const raw = row.value;
  if (raw != null && typeof raw !== 'string' && typeof raw !== 'boolean' && typeof raw !== 'number') throw new ApiError('技术参数值格式有误。', 'INVALID_RESPONSE');
  return {
    key: str(row.key, '参数名'), label: str(row.label, '参数标签'), valueType: valueType as ParameterValueType,
    value: raw == null ? null : (typeof raw === 'number' ? String(raw) : raw as string | boolean),
    unitLabel: optional(row.unitLabel, '参数单位'),
  };
}

export function mapEquipmentProfile(value: unknown): EquipmentProfile {
  const p = value == null ? {} : record(value, '设备档案');
  const list = p.technicalParameters == null ? [] : p.technicalParameters;
  if (!Array.isArray(list)) throw new ApiError('技术参数不是数组。', 'INVALID_RESPONSE');
  return {
    typeCode: optional(p.typeCode, '设备类型'), model: optional(p.model, '型号'),
    processId: p.processId == null || p.processId === '' ? null : id(p.processId, '工序ID'),
    installationLocation: optional(p.installationLocation, '安装位置'),
    manufacturer: optional(p.manufacturer, '制造厂家'), factorySerialNo: optional(p.factorySerialNo, '出厂编号'),
    purchasedOn: optional(p.purchasedOn, '购置日期'), commissionedOn: optional(p.commissionedOn, '投用日期'),
    expectedLifeYears: p.expectedLifeYears == null ? null : integer(p.expectedLifeYears, '预计使用年限'),
    warrantyExpiresOn: optional(p.warrantyExpiresOn, '质保到期日'),
    managementStatus: optional(p.managementStatus, '管理状态') as ManagementStatus | null,
    technicalParameters: list.map(mapTechnicalParameter),
  };
}

export function mapEquipmentDetail(value: unknown): EquipmentDetail {
  const d = record(value, '设备详情');
  const missing = d.missingFields == null ? [] : d.missingFields;
  if (!Array.isArray(missing)) throw new ApiError('缺失字段提示不是数组。', 'INVALID_RESPONSE');
  const deletion = d.deletion == null ? {allowed: false, blockers: []} : record(d.deletion, '删除可用性');
  const blockers = deletion.blockers == null ? [] : deletion.blockers;
  if (!Array.isArray(blockers)) throw new ApiError('删除阻塞项不是数组。', 'INVALID_RESPONSE');
  return {
    id: id(d.id, '设备ID'), code: str(d.code, '设备编码'), name: str(d.name, '设备名称'),
    workshopId: id(d.workshopId, '车间ID'), workshopName: str(d.workshopName, '车间名称'),
    responsiblePerson: optional(d.responsiblePerson, '责任人'), enabled: boolean(d.enabled, '设备启用状态'),
    profile: mapEquipmentProfile(d.profile), version: integer(d.version, '设备版本'),
    missingFields: missing.map((item: unknown) => str(item, '缺失字段名')),
    deletion: {
      allowed: deletion.allowed === true,
      blockers: blockers.map((item: unknown) => {
        const b = record(item, '删除阻塞项');
        return {type: str(b.type, '阻塞类型'), count: count(b.count, '阻塞数量') ?? 0, message: str(b.message, '阻塞说明')};
      }),
    },
  };
}

export function mapChangeLogPage(value: unknown): PageResult<ChangeLogItem> {
  const data = record(value, '变更记录');
  if (!Array.isArray(data.items)) throw new ApiError('变更记录缺少items数组。', 'INVALID_RESPONSE');
  return {
    items: data.items.map((item: unknown) => {
      const row = record(item, '变更记录项');
      return {
        id: str(row.id, '变更记录ID'), operation: str(row.operation, '操作类型'),
        before: row.before ?? null, after: row.after ?? null,
        reason: optional(row.reason, '更正原因'), operatorName: str(row.operatorName, '操作人'),
        recordedAt: str(row.recordedAt, '记录时间'), requestId: optional(row.requestId, '请求编号'),
        versionBefore: count(row.versionBefore, '变更前版本'), versionAfter: count(row.versionAfter, '变更后版本'),
      };
    }),
    page: count(data.page, '页码'), pageSize: count(data.pageSize, '每页条数'), total: count(data.total, '总数'),
  };
}

export function mapReferenceData(value: unknown): ReferenceData {
  const d = record(value, '参考数据');
  const optionList = (source: unknown, name: string): ReferenceOption[] => {
    if (source == null) return [];
    if (!Array.isArray(source)) throw new ApiError(`${name}不是数组。`, 'INVALID_RESPONSE');
    return source.map((item: unknown) => { const o = record(item, name); return {code: str(o.code, `${name}编码`), name: str(o.name, `${name}名称`)}; });
  };
  const list = (source: unknown, name: string): unknown[] => {
    if (source == null) return [];
    if (!Array.isArray(source)) throw new ApiError(`${name}不是数组。`, 'INVALID_RESPONSE');
    return source;
  };
  return {
    workshops: list(d.workshops, '车间').map((item: unknown) => {
      const w = record(item, '车间');
      return {id: id(w.id, '车间ID'), code: str(w.code, '车间编码'), name: str(w.name, '车间名称'), enabled: boolean(w.enabled, '车间状态')};
    }),
    processes: list(d.processes, '工序').map((item: unknown) => {
      const p = record(item, '工序');
      return {id: id(p.id, '工序ID'), code: str(p.code, '工序编码'), name: str(p.name, '工序名称'),
        enabled: boolean(p.enabled, '工序状态'), workshopId: id(p.workshopId, '所属车间'), sequenceNo: count(p.sequenceNo, '工序顺序') ?? 0};
    }),
    units: list(d.units, '单位').map((item: unknown) => {
      const u = record(item, '单位');
      return {id: id(u.id, '单位ID'), code: str(u.code, '单位编码'), name: str(u.name, '单位名称'), category: optional(u.category, '单位分类') ?? ''};
    }),
    equipmentTypes: optionList(d.equipmentTypes, '设备类型'),
    equipmentManagementStatuses: optionList(d.equipmentManagementStatuses, '管理状态'),
  };
}

export function mapOverview(value: unknown): Overview {
  const data = record(value, '初始化概览'), db = record(data.database, '数据库状态'), n = record(data.counts, '数量概览');
  if (data.scope !== 'INITIALIZATION_ONLY') throw new ApiError('接口范围与当前初始化版本不一致。', 'INVALID_RESPONSE');
  return {projectName: str(data.projectName, '项目名称'), environment: str(data.environment, '环境名称'), scope: 'INITIALIZATION_ONLY', database: {status: str(db.status, '数据库状态'), schemaName: optional(db.schemaName, '数据库名称'), serverVersion: optional(db.serverVersion, '数据库版本'), phase1TableCount: count(db.phase1TableCount, '一期表数量'), phase2TableCount: count(db.phase2TableCount, '二期表数量')}, counts: {workshops: count(n.workshops, '车间数量'), productionEquipment: count(n.productionEquipment, '生产设备数量'), sensors: count(n.sensors, '传感器数量')}};
}
export function mapHealth(value: unknown): Health {
  const data = record(value, '服务状态'), result: Health = {status: str(data.status, '服务状态')};
  if (data.database !== undefined) { const db = record(data.database, '数据库连接'); result.database = {status: str(db.status, '数据库连接状态'), check: optional(db.check, '数据库检查') ?? undefined}; }
  return result;
}

/** S1-E05 删除结果。接口只回 {id, deleted}，deleted 必须为 true 才算成功。 */
export interface EquipmentDeleteResult { id: string; deleted: boolean }
export function mapDeleteResult(value: unknown): EquipmentDeleteResult {
  const data = record(value, '删除结果');
  const deleted = boolean(data.deleted, '删除结果');
  if (!deleted) throw new ApiError('服务未确认删除成功，请刷新后重试。', 'INVALID_RESPONSE');
  return {id: id(data.id, '设备ID'), deleted};
}

interface RequestOptions { signal?: AbortSignal; method?: 'GET' | 'POST' | 'PUT' | 'DELETE'; body?: unknown }

function extractFields(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  const fields = (value as Record<string, unknown>).fields;
  if (!fields || typeof fields !== 'object' || Array.isArray(fields)) return {};
  const result: Record<string, string> = {};
  for (const [key, message] of Object.entries(fields as Record<string, unknown>)) if (typeof message === 'string' && message.trim()) result[key] = message;
  return result;
}

export async function requestApi<T>(path: string, mapper: (value: unknown) => T, options: RequestOptions = {}, fetcher: typeof fetch = fetch): Promise<ApiResult<T>> {
  const {signal, method = 'GET', body} = options;
  let response: Response;
  const headers: Record<string, string> = {Accept: 'application/json'};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  try { response = await fetcher(path, {method, signal, headers, body: body === undefined ? undefined : JSON.stringify(body)}); }
  catch (error: unknown) {
    if (error instanceof Error && error.name === 'AbortError') throw error;
    throw new ApiError('暂时无法连接服务，请确认服务已启动后重试。', 'NETWORK_ERROR');
  }
  let value: unknown;
  try { value = await response.json(); }
  catch { throw new ApiError(response.ok ? '服务返回了无法识别的数据，请稍后重试。' : `服务请求失败（HTTP ${response.status}），请稍后重试。`, 'INVALID_RESPONSE', response.status); }
  const envelope = record(value, '接口响应');
  if (!response.ok || envelope.code !== 'OK') {
    const detail = envelope.data && typeof envelope.data === 'object' && !Array.isArray(envelope.data) ? envelope.data as Record<string, unknown> : null;
    throw new ApiError(
      typeof envelope.message === 'string' && envelope.message.trim() ? envelope.message : `读取失败（HTTP ${response.status}），请稍后重试。`,
      typeof envelope.code === 'string' ? envelope.code : 'HTTP_ERROR', response.status, extractFields(envelope.data), detail);
  }
  return {data: mapper(envelope.data), timestamp: str(envelope.timestamp, '接口时间')};
}

/** 兼容既有调用：第三位仍是 AbortSignal。 */
export async function fetchApi<T>(path: string, mapper: (value: unknown) => T, signal?: AbortSignal, fetcher: typeof fetch = fetch): Promise<ApiResult<T>> {
  return requestApi(path, mapper, {signal}, fetcher);
}

function queryString(query: EquipmentQuery): string {
  const params = new URLSearchParams();
  if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
  if (query.workshopId) params.set('workshopId', query.workshopId);
  if (query.typeCode) params.set('typeCode', query.typeCode);
  if (query.managementStatus) params.set('managementStatus', query.managementStatus);
  if (query.enabled !== undefined) params.set('enabled', String(query.enabled));
  if (query.page !== undefined) params.set('page', String(query.page));
  if (query.pageSize !== undefined) params.set('pageSize', String(query.pageSize));
  const text = params.toString();
  return text ? `?${text}` : '';
}

export const api = {
  equipment: (query: EquipmentQuery = {}, signal?: AbortSignal) => requestApi(`/api/v1/equipment${queryString(query)}`, mapEquipmentPage, {signal}),
  equipmentDetail: (equipmentId: string, signal?: AbortSignal) => requestApi(`/api/v1/equipment/${encodeURIComponent(equipmentId)}`, mapEquipmentDetail, {signal}),
  createEquipment: (values: EquipmentFormValues, meta: {requestId: string; operatorName: string}) =>
    requestApi('/api/v1/equipment', mapEquipmentDetail, {method: 'POST', body: {...meta, ...values}}),
  updateEquipment: (equipmentId: string, values: EquipmentFormValues, meta: {requestId: string; operatorName: string; expectedVersion: number; reason: string}) =>
    requestApi(`/api/v1/equipment/${encodeURIComponent(equipmentId)}`, mapEquipmentDetail, {method: 'PUT', body: {...meta, ...values}}),
  equipmentChanges: (equipmentId: string, page = 1, pageSize = 20, signal?: AbortSignal) =>
    requestApi(`/api/v1/equipment/${encodeURIComponent(equipmentId)}/changes?page=${page}&pageSize=${pageSize}`, mapChangeLogPage, {signal}),
  /** S1-E05 误建删除：必须带二次确认与当前版本号；有关联时后端返回 409 及 blockers。 */
  deleteEquipment: (equipmentId: string, meta: {requestId: string; operatorName: string; expectedVersion: number; reason: string}) =>
    requestApi(`/api/v1/equipment/${encodeURIComponent(equipmentId)}`, mapDeleteResult,
      {method: 'DELETE', body: {...meta, confirmed: true}}),
  referenceData: (signal?: AbortSignal) => requestApi('/api/v1/reference-data', mapReferenceData, {signal}),
  overview: (signal?: AbortSignal) => fetchApi('/api/v1/bootstrap/overview', mapOverview, signal),
  live: (signal?: AbortSignal) => fetchApi('/api/v1/health/live', mapHealth, signal),
  ready: (signal?: AbortSignal) => fetchApi('/api/v1/health/ready', mapHealth, signal),
};
