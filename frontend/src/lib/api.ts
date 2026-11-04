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
/** 剩余天数可以是负数（已超期），所以不能复用只接受非负数的 count/integer。 */
function wholeNumber(value: unknown, name: string): number | null {
  if (value == null) return null;
  if (typeof value === 'number' && Number.isSafeInteger(value)) return value;
  throw new ApiError(`${name}不是有效的整数。`, 'INVALID_RESPONSE');
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
/** 供应商摘要（S1-S01）：列表行只带选择需要的字段。 */
export interface SupplierSummary {
  supplierId: string; name: string; contactName: string | null; phone: string | null; equipmentCount: number;
}

/** 供应商详情（S1-S02）。equipmentIds 为空数组表示当前没有关联设备。 */
export interface SupplierDetail {
  supplierId: string; name: string; contactName: string | null; phone: string | null;
  remark: string | null; equipmentIds: string[]; version: number; updatedAt: string | null;
}

/** 供应商可写字段；equipmentIds 按整体替换语义提交，空数组即解除全部关联。 */
export interface SupplierFormValues {
  name: string; contactName: string | null; phone: string | null; remark: string | null; equipmentIds: string[];
}

export function mapSupplierPage(value: unknown): PageResult<SupplierSummary> {
  const data = record(value, '供应商分页');
  if (!Array.isArray(data.items)) throw new ApiError('供应商列表缺少items数组。', 'INVALID_RESPONSE');
  return {
    items: data.items.map((item: unknown) => {
      const row = record(item, '供应商');
      return {
        supplierId: id(row.supplierId, '供应商ID'), name: str(row.name, '供应商名称'),
        contactName: optional(row.contactName, '联系人'), phone: optional(row.phone, '电话'),
        equipmentCount: integer(row.equipmentCount, '关联设备数'),
      };
    }),
    page: count(data.page, '页码'), pageSize: count(data.pageSize, '每页条数'), total: count(data.total, '总数'),
  };
}

export function mapSupplierDetail(value: unknown): SupplierDetail {
  const data = record(value, '供应商详情');
  if (!Array.isArray(data.equipmentIds)) throw new ApiError('供应商详情缺少equipmentIds数组。', 'INVALID_RESPONSE');
  return {
    supplierId: id(data.supplierId, '供应商ID'), name: str(data.name, '供应商名称'),
    contactName: optional(data.contactName, '联系人'), phone: optional(data.phone, '电话'),
    remark: optional(data.remark, '备注'),
    equipmentIds: data.equipmentIds.map((item: unknown) => id(item, '设备ID')),
    version: integer(data.version, '版本'), updatedAt: optional(data.updatedAt, '更新时间'),
  };
}

/** 合同期限状态，与服务端约定一致；UNKNOWN 表示结束日期缺失，不是"未到期"。 */
export type ContractExpiryState = 'ACTIVE' | 'EXPIRING_SOON' | 'EXPIRED' | 'UNKNOWN';

/** 维保合同摘要（S1-C01）。remainingDays 为负表示已超期天数。 */
export interface ContractSummary {
  contractId: string; serviceOrganization: string; contractNumber: string | null;
  startDate: string | null; endDate: string | null;
  equipmentCount: number; expiryState: ContractExpiryState; remainingDays: number | null;
}

/** 合同详情（S1-C02）。attachmentIds 由附件接口维护，这里只读。 */
export interface ContractDetail {
  contractId: string; serviceOrganization: string; contractNumber: string | null;
  startDate: string | null; endDate: string | null; remark: string | null;
  equipmentIds: string[]; attachmentIds: string[]; expiryState: ContractExpiryState;
  remainingDays: number | null; version: number; updatedAt: string | null;
}

/** 合同可写字段；equipmentIds 按整体替换提交，空数组即解除全部覆盖关系。 */
export interface ContractFormValues {
  serviceOrganization: string; contractNumber: string | null;
  startDate: string | null; endDate: string | null;
  remark: string | null; equipmentIds: string[];
}

function expiryState(value: unknown): ContractExpiryState {
  const raw = str(value, '期限状态');
  if (raw === 'ACTIVE' || raw === 'EXPIRING_SOON' || raw === 'EXPIRED' || raw === 'UNKNOWN') return raw;
  throw new ApiError(`期限状态「${raw}」不在约定范围内。`, 'INVALID_RESPONSE');
}

export function mapContractPage(value: unknown): PageResult<ContractSummary> {
  const data = record(value, '合同分页');
  if (!Array.isArray(data.items)) throw new ApiError('合同列表缺少items数组。', 'INVALID_RESPONSE');
  return {
    items: data.items.map((item: unknown) => {
      const row = record(item, '合同');
      return {
        contractId: id(row.contractId, '合同ID'),
        serviceOrganization: str(row.serviceOrganization, '服务单位'),
        contractNumber: optional(row.contractNumber, '合同编号'),
        startDate: optional(row.startDate, '开始日期'), endDate: optional(row.endDate, '结束日期'),
        equipmentCount: integer(row.equipmentCount, '覆盖设备数'),
        expiryState: expiryState(row.expiryState),
        remainingDays: wholeNumber(row.remainingDays, '剩余天数'),
      };
    }),
    page: count(data.page, '页码'), pageSize: count(data.pageSize, '每页条数'), total: count(data.total, '总数'),
  };
}

export function mapContractDetail(value: unknown): ContractDetail {
  const data = record(value, '合同详情');
  if (!Array.isArray(data.equipmentIds)) throw new ApiError('合同详情缺少equipmentIds数组。', 'INVALID_RESPONSE');
  if (!Array.isArray(data.attachmentIds)) throw new ApiError('合同详情缺少attachmentIds数组。', 'INVALID_RESPONSE');
  return {
    contractId: id(data.contractId, '合同ID'),
    serviceOrganization: str(data.serviceOrganization, '服务单位'),
    contractNumber: optional(data.contractNumber, '合同编号'),
    startDate: optional(data.startDate, '开始日期'), endDate: optional(data.endDate, '结束日期'),
    remark: optional(data.remark, '备注'),
    equipmentIds: data.equipmentIds.map((item: unknown) => id(item, '设备ID')),
    attachmentIds: data.attachmentIds.map((item: unknown) => id(item, '附件ID')),
    expiryState: expiryState(data.expiryState),
    remainingDays: wholeNumber(data.remainingDays, '剩余天数'),
    version: integer(data.version, '版本'), updatedAt: optional(data.updatedAt, '更新时间'),
  };
}

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
  // 上传是 multipart：Content-Type 必须由浏览器带上 boundary，手写反而会让后端解析不出文件。
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData;
  if (body !== undefined && !isForm) headers['Content-Type'] = 'application/json';
  try { response = await fetcher(path, {method, signal, headers, body: body === undefined ? undefined : isForm ? body as FormData : JSON.stringify(body)}); }
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

/** 期限类型（S1-T01）：质保、维保、预计寿命。 */
export type DeadlineReminderType = 'WARRANTY' | 'MAINTENANCE' | 'EXPECTED_LIFE';
/** 期限计算状态：待补资料、正常、即将到期、已超期。 */
export type DeadlineExpiryState = 'MISSING_DATA' | 'NORMAL' | 'DUE_SOON' | 'EXPIRED';

/**
 * 期限行（S1-T01）。一行是一台设备的**一个期限来源**，不是一台设备一行：
 * 同时有质保与预计寿命、或多份维保合同时会出多行，靠 reminderType 与 contractId 区分。
 */
export interface DeadlineItem {
  equipmentId: string; equipmentCode: string; equipmentName: string;
  workshopId: string; workshopName: string | null; responsiblePerson: string | null;
  reminderType: DeadlineReminderType; contractId: string | null; contractNumber: string | null;
  purchasedOn: string | null; commissionedOn: string | null; expectedLifeYears: number | null;
  dueDate: string | null; remainingDays: number | null; expiryState: DeadlineExpiryState;
  missingFields: string[];
}

export interface DeadlineQuery {
  equipmentId?: string; workshopId?: string; reminderType?: string;
  expiryState?: string; page?: number; pageSize?: number;
}

function deadlineReminderType(value: unknown): DeadlineReminderType {
  const raw = str(value, '期限类型');
  if (raw === 'WARRANTY' || raw === 'MAINTENANCE' || raw === 'EXPECTED_LIFE') return raw;
  throw new ApiError(`期限类型「${raw}」不在约定范围内。`, 'INVALID_RESPONSE');
}

function deadlineExpiryState(value: unknown): DeadlineExpiryState {
  const raw = str(value, '期限状态');
  if (raw === 'MISSING_DATA' || raw === 'NORMAL' || raw === 'DUE_SOON' || raw === 'EXPIRED') return raw;
  throw new ApiError(`期限状态「${raw}」不在约定范围内。`, 'INVALID_RESPONSE');
}

function stringList(value: unknown, name: string): string[] {
  if (!Array.isArray(value)) throw new ApiError(`${name}缺少数组。`, 'INVALID_RESPONSE');
  return value.map(item => str(item, name));
}

export function mapDeadlinePage(value: unknown): PageResult<DeadlineItem> {
  const data = record(value, '期限分页');
  if (!Array.isArray(data.items)) throw new ApiError('期限列表缺少items数组。', 'INVALID_RESPONSE');
  return {
    items: data.items.map((item: unknown) => {
      const row = record(item, '期限行');
      return {
        equipmentId: id(row.equipmentId, '设备ID'),
        equipmentCode: str(row.equipmentCode, '设备编码'),
        equipmentName: str(row.equipmentName, '设备名称'),
        workshopId: id(row.workshopId, '车间ID'),
        workshopName: optional(row.workshopName, '车间名称'),
        responsiblePerson: optional(row.responsiblePerson, '负责人'),
        reminderType: deadlineReminderType(row.reminderType),
        contractId: row.contractId == null ? null : id(row.contractId, '合同ID'),
        contractNumber: optional(row.contractNumber, '合同编号'),
        purchasedOn: optional(row.purchasedOn, '购置日期'),
        commissionedOn: optional(row.commissionedOn, '投用日期'),
        expectedLifeYears: count(row.expectedLifeYears, '预计年限'),
        dueDate: optional(row.dueDate, '到期日期'),
        remainingDays: wholeNumber(row.remainingDays, '剩余天数'),
        expiryState: deadlineExpiryState(row.expiryState),
        missingFields: stringList(row.missingFields, '缺资料字段'),
      };
    }),
    page: count(data.page, '页码'), pageSize: count(data.pageSize, '每页条数'), total: count(data.total, '总数'),
  };
}

/** 附件归属对象（S1-F01—F05）：一套接口服务设备、合同、知识条目与维修单。 */
export type AttachmentOwnerType = 'EQUIPMENT' | 'MAINTENANCE_CONTRACT' | 'KNOWLEDGE_ARTICLE' | 'REPAIR_ORDER';
/** 附件类别。IMAGE 用于档案配图，MANUAL 用于设备手册，DOCUMENT 用于合同正文，PHOTO 用于报修照片。 */
export type AttachmentFileKind = 'IMAGE' | 'MANUAL' | 'DOCUMENT' | 'PHOTO';

/**
 * 附件元信息。contentPath / downloadPath 由服务端给出，前端不自己拼地址——
 * 拼错了会得到 404 而不是"路径写错"的提示。
 */
export interface AttachmentItem {
  attachmentId: string; ownerType: AttachmentOwnerType; ownerId: string;
  fileKind: AttachmentFileKind; originalName: string; mediaType: string;
  size: number; version: number; updatedAt: string | null;
  contentPath: string; downloadPath: string;
}

export interface AttachmentQuery {
  ownerType: AttachmentOwnerType; ownerId: string; fileKind?: AttachmentFileKind | '';
  page?: number; pageSize?: number;
}

function attachmentOwnerType(value: unknown): AttachmentOwnerType {
  const raw = str(value, '附件归属类型');
  if (raw === 'EQUIPMENT' || raw === 'MAINTENANCE_CONTRACT' || raw === 'KNOWLEDGE_ARTICLE' || raw === 'REPAIR_ORDER') return raw;
  throw new ApiError(`附件归属类型「${raw}」不在约定范围内。`, 'INVALID_RESPONSE');
}

function attachmentFileKind(value: unknown): AttachmentFileKind {
  const raw = str(value, '附件类别');
  if (raw === 'IMAGE' || raw === 'MANUAL' || raw === 'DOCUMENT' || raw === 'PHOTO') return raw;
  throw new ApiError(`附件类别「${raw}」不在约定范围内。`, 'INVALID_RESPONSE');
}

function mapAttachment(value: unknown): AttachmentItem {
  const row = record(value, '附件');
  return {
    attachmentId: id(row.attachmentId, '附件ID'),
    ownerType: attachmentOwnerType(row.ownerType),
    ownerId: id(row.ownerId, '归属对象ID'),
    fileKind: attachmentFileKind(row.fileKind),
    originalName: str(row.originalName, '文件名'),
    mediaType: str(row.mediaType, '媒体类型'),
    size: integer(row.size, '文件大小'),
    version: integer(row.version, '附件版本'),
    updatedAt: optional(row.updatedAt, '更新时间'),
    contentPath: str(row.contentPath, '查看地址'),
    downloadPath: str(row.downloadPath, '下载地址'),
  };
}

export function mapAttachmentPage(value: unknown): PageResult<AttachmentItem> {
  const data = record(value, '附件分页');
  if (!Array.isArray(data.items)) throw new ApiError('附件列表缺少items数组。', 'INVALID_RESPONSE');
  return {
    items: data.items.map(mapAttachment),
    page: count(data.page, '页码'), pageSize: count(data.pageSize, '每页条数'), total: count(data.total, '总数'),
  };
}

export function mapAttachmentDetail(value: unknown): AttachmentItem {
  return mapAttachment(value);
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
  /** S1-S01 供应商分页；设备详情的"供应商"页签按 equipmentId 过滤。 */
  suppliers: (query: {equipmentId?: string; keyword?: string; page?: number; pageSize?: number} = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams();
    if (query.equipmentId) params.set('equipmentId', query.equipmentId);
    if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
    if (query.page !== undefined) params.set('page', String(query.page));
    if (query.pageSize !== undefined) params.set('pageSize', String(query.pageSize));
    const text = params.toString();
    return requestApi(`/api/v1/suppliers${text ? `?${text}` : ''}`, mapSupplierPage, {signal});
  },
  supplierDetail: (supplierId: string, signal?: AbortSignal) =>
    requestApi(`/api/v1/suppliers/${encodeURIComponent(supplierId)}`, mapSupplierDetail, {signal}),
  createSupplier: (values: SupplierFormValues, meta: {requestId: string; operatorName: string}) =>
    requestApi('/api/v1/suppliers', mapSupplierDetail, {method: 'POST', body: {...meta, ...values}}),
  updateSupplier: (supplierId: string, values: SupplierFormValues, meta: {requestId: string; operatorName: string; expectedVersion: number}) =>
    requestApi(`/api/v1/suppliers/${encodeURIComponent(supplierId)}`, mapSupplierDetail, {method: 'PUT', body: {...meta, ...values}}),
  /** S1-C01 合同分页；设备详情的"合同"页签按 equipmentId 过滤出覆盖本设备的合同。 */
  contracts: (query: {equipmentId?: string; serviceOrganization?: string; expiryState?: string; page?: number; pageSize?: number} = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams();
    if (query.equipmentId) params.set('equipmentId', query.equipmentId);
    if (query.serviceOrganization?.trim()) params.set('serviceOrganization', query.serviceOrganization.trim());
    if (query.expiryState) params.set('expiryState', query.expiryState);
    if (query.page !== undefined) params.set('page', String(query.page));
    if (query.pageSize !== undefined) params.set('pageSize', String(query.pageSize));
    const text = params.toString();
    return requestApi(`/api/v1/maintenance-contracts${text ? `?${text}` : ''}`, mapContractPage, {signal});
  },
  contractDetail: (contractId: string, signal?: AbortSignal) =>
    requestApi(`/api/v1/maintenance-contracts/${encodeURIComponent(contractId)}`, mapContractDetail, {signal}),
  createContract: (values: ContractFormValues, meta: {requestId: string; operatorName: string}) =>
    requestApi('/api/v1/maintenance-contracts', mapContractDetail, {method: 'POST', body: {...meta, ...values}}),
  updateContract: (contractId: string, values: ContractFormValues, meta: {requestId: string; operatorName: string; expectedVersion: number}) =>
    requestApi(`/api/v1/maintenance-contracts/${encodeURIComponent(contractId)}`, mapContractDetail, {method: 'PUT', body: {...meta, ...values}}),
  deadlines: (query: DeadlineQuery = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams();
    if (query.equipmentId) params.set('equipmentId', query.equipmentId);
    if (query.workshopId) params.set('workshopId', query.workshopId);
    if (query.reminderType) params.set('reminderType', query.reminderType);
    if (query.expiryState) params.set('expiryState', query.expiryState);
    if (query.page !== undefined) params.set('page', String(query.page));
    if (query.pageSize !== undefined) params.set('pageSize', String(query.pageSize));
    const text = params.toString();
    return requestApi(`/api/v1/equipment-deadlines${text ? `?${text}` : ''}`, mapDeadlinePage, {signal});
  },
  /** S1-F01 附件分页；同一台设备可能同时有配图、手册和合同附件，fileKind 用于分栏。 */
  attachments: (query: AttachmentQuery, signal?: AbortSignal) => {
    const params = new URLSearchParams();
    params.set('ownerType', query.ownerType);
    params.set('ownerId', query.ownerId);
    if (query.fileKind) params.set('fileKind', query.fileKind);
    if (query.page !== undefined) params.set('page', String(query.page));
    if (query.pageSize !== undefined) params.set('pageSize', String(query.pageSize));
    return requestApi(`/api/v1/attachments?${params.toString()}`, mapAttachmentPage, {signal});
  },
  attachmentDetail: (attachmentId: string, signal?: AbortSignal) =>
    requestApi(`/api/v1/attachments/${encodeURIComponent(attachmentId)}`, mapAttachmentDetail, {signal}),
  /** F04 的受控地址。download=true 时后端返回附件下载头，否则按类型内联查看。 */
  attachmentUrl: (attachmentId: string, download = false) =>
    `/api/v1/attachments/${encodeURIComponent(attachmentId)}/content${download ? '?download=true' : ''}`,
  /** S1-F03 上传并关联；业务对象（这里是设备）必须已存在。 */
  uploadAttachment: (file: File, meta: {requestId: string; operatorName: string; ownerType: AttachmentOwnerType; ownerId: string; fileKind: AttachmentFileKind}) => {
    const form = new FormData();
    form.append('requestId', meta.requestId);
    form.append('operatorName', meta.operatorName);
    form.append('ownerType', meta.ownerType);
    form.append('ownerId', meta.ownerId);
    form.append('fileKind', meta.fileKind);
    form.append('file', file);
    return requestApi('/api/v1/attachments', mapAttachmentDetail, {method: 'POST', body: form});
  },
  /** S1-F05 替换内容：保留附件 ID 与引用，版本加一，必须说明替换原因。 */
  replaceAttachmentContent: (attachmentId: string, file: File, meta: {requestId: string; operatorName: string; expectedVersion: number; reason: string}) => {
    const form = new FormData();
    form.append('requestId', meta.requestId);
    form.append('operatorName', meta.operatorName);
    form.append('expectedVersion', String(meta.expectedVersion));
    form.append('reason', meta.reason);
    form.append('file', file);
    return requestApi(`/api/v1/attachments/${encodeURIComponent(attachmentId)}/content`, mapAttachmentDetail,
      {method: 'PUT', body: form});
  },
  overview: (signal?: AbortSignal) => fetchApi('/api/v1/bootstrap/overview', mapOverview, signal),
  live: (signal?: AbortSignal) => fetchApi('/api/v1/health/live', mapHealth, signal),
  ready: (signal?: AbortSignal) => fetchApi('/api/v1/health/ready', mapHealth, signal),
};
