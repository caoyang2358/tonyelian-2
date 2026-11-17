export type ManagementStatus = 'IN_USE' | 'STOPPED' | 'SCRAPPED';
export type ParameterValueType = 'STRING' | 'DECIMAL' | 'INTEGER' | 'BOOLEAN';

/** 一期共享字段。首批契约只保证这七项，历史设备没有扩展档案。 */
export type EquipmentBase = {
  id: string; code: string; name: string; workshopId: string;
  workshopName: string; responsiblePerson: string | null; enabled: boolean;
};
/** S1-E01 列表项：共享字段 + 类型、管理状态、聚合版本与维保到期。未补档设备类型与状态为 null / 0。 */
export type Equipment = EquipmentBase & {
  typeCode: string | null; typeName: string | null;
  managementStatus: string | null; version: number;
  /** 最近一份维保合同的结束日；没有合同时为 null，状态固定 MISSING_DATA。 */
  maintenanceDueDate: string | null;
  maintenanceRemainingDays: number | null;
  maintenanceExpiryState: DeadlineExpiryState | null;
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

/** 历史页面验收专用记录不属于甲方生产设备，不进入任何业务选择器或清单。 */
const isBusinessEquipment = (equipment: {code: string}) => equipment.code !== 'KNOWLEDGE-VERIFY';

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
  }).filter(isBusinessEquipment);
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
      // 维保到期三项：无合同时日期为空，状态由后端给 MISSING_DATA，页面据此显示"待补"
      maintenanceDueDate: optional(row.maintenanceDueDate, '维保到期日'),
      maintenanceRemainingDays: wholeNumber(row.maintenanceRemainingDays, '维保剩余天数'),
      maintenanceExpiryState: row.maintenanceExpiryState == null
        ? null : deadlineExpiryState(row.maintenanceExpiryState),
    };
  });
}

export function mapEquipmentPage(value: unknown): EquipmentPageResult {
  const data = record(value, '设备列表');
  if (!Array.isArray(data.items)) throw new ApiError('设备列表缺少items数组。', 'INVALID_RESPONSE');
  const items = mapEquipmentRows(data.items).filter(isBusinessEquipment);
  const hiddenCount = data.items.length - items.length;
  const total = count(data.total, '总数');
  return {
    items,
    page: count(data.page, '页码'), pageSize: count(data.pageSize, '每页条数'),
    total: total == null ? null : Math.max(0, total - hiddenCount),
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
  startDate: string | null; endDate: string | null; remark: string | null;
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
        remark: optional(row.remark, '备注'),
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

export interface SensorDeleteResult { sensorId: string; deleted: boolean }
/** 测点删除结果用 sensorId 回执，字段名与设备删除不同，因此单独解析。 */
export function mapSensorDeleteResult(value: unknown): SensorDeleteResult {
  const data = record(value, '删除结果');
  const deleted = boolean(data.deleted, '删除结果');
  if (!deleted) throw new ApiError('服务未确认删除成功，请刷新后重试。', 'INVALID_RESPONSE');
  return {sensorId: id(data.sensorId, '测点ID'), deleted};
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

/**
 * 期限提醒设置（S1-T02 / T03）。
 *
 * <p>全系统共用一份：质保与维保各一个提前天数，改完同时影响期限面板与合同页签的
 * 「即将到期」判定，所以它不是某台设备、某份合同的属性，页面上要写明影响范围。</p>
 */
export interface ReminderSetting {
  warrantyLeadDays: number; maintenanceLeadDays: number; version: number; updatedAt: string | null;
}

export function mapReminderSetting(value: unknown): ReminderSetting {
  const row = record(value, '提醒设置');
  return {
    warrantyLeadDays: integer(row.warrantyLeadDays, '质保提前天数'),
    maintenanceLeadDays: integer(row.maintenanceLeadDays, '维保提前天数'),
    version: integer(row.version, '设置版本'),
    updatedAt: optional(row.updatedAt, '更新时间'),
  };
}

/** 附件归属对象（S1-F01—F05）：一套接口服务设备、合同、知识条目、维修单与巡检记录。 */
export type AttachmentOwnerType = 'EQUIPMENT' | 'MAINTENANCE_CONTRACT' | 'KNOWLEDGE_ARTICLE' | 'REPAIR_ORDER' | 'INSPECTION';
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
  if (raw === 'EQUIPMENT' || raw === 'MAINTENANCE_CONTRACT' || raw === 'KNOWLEDGE_ARTICLE' || raw === 'REPAIR_ORDER' || raw === 'INSPECTION') return raw;
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

/**
 * 测点监测状态（S1-P01/P09）。
 *
 * 四态分开是有意的：DISABLED 是配置决定，UNKNOWN 是从没采到，STALE 是采过但停了，
 * 只有 ONLINE 才代表当前有数。前端不要把它们合并成一个"离线"。
 */
export type SensorMonitoringStatus = 'ONLINE' | 'STALE' | 'UNKNOWN' | 'DISABLED';
/** 测点是否配全：有参数且有采集来源才算 READY。 */
export type SensorConfigurationState = 'READY' | 'PENDING';

export interface SensorSummary {
  sensorId: string; sensorCode: string; name: string;
  equipmentId: string; equipmentCode: string; equipmentName: string;
  workshopId: string; workshopName: string | null;
  location: string | null; sensorType: string | null; dataSource: string | null;
  enabled: boolean; parameterCount: number;
  monitoringStatus: SensorMonitoringStatus; configurationState: SensorConfigurationState;
}

export interface SensorParameterSummary {
  parameterId: string; parameterCode: string; name: string; dataType: string;
  unitCode: string | null; decimalPlaces: number; samplingIntervalSeconds: number | null;
  processId: string | null; enabled: boolean;
  ruleVersionId: string | null; ruleConfigured: boolean;
  /** 当前生效规则全文与配置版本：参数页签据此直接打开修改弹层，不必再查一次。 */
  rule: SensorRuleSnapshot | null; version: number;
}

export interface SensorQuery {
  equipmentId?: string; keyword?: string; enabled?: boolean;
  monitoringStatus?: SensorMonitoringStatus | ''; page?: number; pageSize?: number;
}

export interface SensorHistoryQuery { from: string; to: string; page?: number; pageSize?: number }

export interface SensorDetail extends SensorSummary {
  purpose: string | null; hasHistory: boolean; version: number;
  parameters: SensorParameterSummary[];
  /** 与 E02 设备详情同构：allowed 为 false 时 blockers 说明为什么不能删。 */
  deletion: { allowed: boolean; blockers: DeletionBlocker[] };
}

/**
 * 参数详情（P07 新建结果）。
 *
 * 比摘要多两项：规则全文与配置版本。摘要只知道"有没有规则"，详情才够回填表单。
 * rule 为 null 表示从未配规则，与"配了但停用"（rule.enabled=false）不是一回事。
 */
/**
 * 打开参数修改弹层所需的最小字段集。
 *
 * 参数摘要（P06）与参数详情（P07 返回）都能满足它：契约里没有"单参数详情"接口，
 * 参数页签拿到的就是摘要，摘要因此带全了规则全文与配置版本。
 */
export interface SensorParameterEditable {
  parameterId: string; parameterCode: string; name: string; dataType: string;
  unitCode: string | null; decimalPlaces: number; samplingIntervalSeconds: number | null;
  processId: string | null; enabled: boolean;
  rule: SensorRuleSnapshot | null; version: number;
}

export interface SensorParameterDetail {
  parameterId: string; sensorId: string; parameterCode: string; name: string; dataType: string;
  unitCode: string | null; decimalPlaces: number; samplingIntervalSeconds: number | null;
  processId: string | null; enabled: boolean;
  rule: SensorRuleSnapshot | null; ruleVersionId: string | null; version: number;
}

/**
 * 参数可写字段（P07）。
 *
 * 不含 dataSource：参数有没有真的接到采集，取决于采集映射建立。
 * rule 为 null 时不产生规则版本行；给了 rule 才有首版快照。
 */
export interface SensorParameterFormValues {
  parameterCode: string; name: string; dataType: string;
  unitCode: string | null; decimalPlaces: number | null;
  samplingIntervalSeconds: number | null; processId: string | null;
  enabled: boolean; rule: SensorRuleSnapshot | null; reason?: string | null;
}

/**
 * 参数配置更新（S1-P08）的可写字段。
 *
 * 不含 parameterCode 与 dataType：前者是采集映射的连接键，后者决定读数怎么解析，
 * 都不属于"配置"。也不允许 rule 为 null——规则快照只能追加，允许传 null 会让页面
 * 读回一份实际已不生效的旧规则；不想要规则时把 rule.enabled 设为 false。
 */
export interface SensorParameterConfigValues {
  name: string; unitCode: string | null; decimalPlaces: number | null;
  samplingIntervalSeconds: number | null; processId: string | null;
  enabled: boolean; rule: SensorRuleSnapshot; reason?: string | null;
}

/**
 * 参数配置更新结果。
 *
 * ruleVersionId 是当前生效的规则版本：判断依据没变时与 previousRuleVersionId 相同，
 * 页面据此区分"配置存了、规则没动"和"规则换了新版"。
 * affectedEventIds 是这次保存顺手结束掉的活动事件（原因 RULE_CHANGED）。判断依据变化时
 * 后端在同一事务里结束该参数的活动事件并回填事件号；判断依据没变、或变了但没有活动事件，都是空数组。
 */
export interface SensorParameterConfigResult {
  parameterId: string; sensorId: string; parameterCode: string;
  name: string; dataType: string; unitCode: string | null;
  decimalPlaces: number; samplingIntervalSeconds: number | null;
  processId: string | null; enabled: boolean;
  rule: SensorRuleSnapshot; version: number;
  previousRuleVersionId: string | null; ruleVersionId: string | null;
  affectedEventIds: string[]; effectNote: string;
}

/**
 * 测点可写字段（P03/P04）。
 *
 * 不含 dataSource：采集来源由现场对接产生，页面不能凭一次表单提交就声称"已接 PLC"。
 * 新建测点因此一律是 PENDING，直到采集映射真实建立。
 */
export interface SensorFormValues {
  sensorCode: string; name: string; equipmentId: string;
  sensorType: string | null; location: string | null; purpose: string | null; enabled: boolean;
}

export interface SensorLatestReading {
  parameterId: string; sensorId: string; sensorCode: string; parameterCode: string;
  value: string | null; valueText: string | null; unitCode: string | null;
  qualityStatus: string | null; limitStatus: string | null;
  collectTime: string | null; refreshTime: string | null;
  monitoringStatus: SensorMonitoringStatus; dataSource: string | null;
  missingFields: string[];
}

export interface SensorHistoryRecord {
  recordId: string; value: string | null; valueText: string | null;
  sourceUnit: string | null; qualityCode: string | null;
  collectTime: string | null; sourceSystem: string;
}

export interface SensorRuleSnapshot {
  normalLower: string | null; normalUpper: string | null;
  alarmLower: string | null; alarmUpper: string | null;
  unitCode: string | null; triggerMode: string | null;
  durationSeconds: number | null; enabled: boolean;
}

export interface SensorRuleVersion {
  ruleVersionId: string; ruleVersion: number; previousRuleVersionId: string | null;
  rule: SensorRuleSnapshot; effectiveAt: string | null;
  reason: string | null; operatorName: string; affectedEventIds: string[];
}

function monitoringStatus(value: unknown): SensorMonitoringStatus {
  const raw = str(value, '监测状态');
  if (raw === 'ONLINE' || raw === 'STALE' || raw === 'UNKNOWN' || raw === 'DISABLED') return raw;
  throw new ApiError(`监测状态「${raw}」不在约定范围内。`, 'INVALID_RESPONSE');
}

function configurationState(value: unknown): SensorConfigurationState {
  const raw = str(value, '配置状态');
  if (raw === 'READY' || raw === 'PENDING') return raw;
  throw new ApiError(`配置状态「${raw}」不在约定范围内。`, 'INVALID_RESPONSE');
}

function mapSensorSummary(value: unknown): SensorSummary {
  const row = record(value, '测点');
  return {
    sensorId: id(row.sensorId, '测点ID'),
    sensorCode: str(row.sensorCode, '测点编码'),
    name: str(row.name, '测点名称'),
    equipmentId: id(row.equipmentId, '设备ID'),
    equipmentCode: str(row.equipmentCode, '设备编码'),
    equipmentName: str(row.equipmentName, '设备名称'),
    workshopId: id(row.workshopId, '车间ID'),
    workshopName: optional(row.workshopName, '车间名称'),
    location: optional(row.location, '安装位置'),
    sensorType: optional(row.sensorType, '测点类型'),
    dataSource: optional(row.dataSource, '数据来源'),
    enabled: boolean(row.enabled, '启用状态'),
    parameterCount: count(row.parameterCount, '参数数量') ?? 0,
    monitoringStatus: monitoringStatus(row.monitoringStatus),
    configurationState: configurationState(row.configurationState),
  };
}

function mapParameterSummary(value: unknown): SensorParameterSummary {
  const row = record(value, '参数');
  return {
    parameterId: id(row.parameterId, '参数ID'),
    parameterCode: str(row.parameterCode, '参数编码'),
    name: str(row.name, '参数名称'),
    dataType: str(row.dataType, '数据类型'),
    unitCode: optional(row.unitCode, '单位'),
    decimalPlaces: integer(row.decimalPlaces, '小数位'),
    samplingIntervalSeconds: count(row.samplingIntervalSeconds, '采样周期'),
    processId: row.processId == null ? null : id(row.processId, '工序ID'),
    enabled: boolean(row.enabled, '启用状态'),
    ruleVersionId: row.ruleVersionId == null ? null : id(row.ruleVersionId, '规则版本ID'),
    ruleConfigured: boolean(row.ruleConfigured, '规则是否启用'),
    rule: row.rule == null ? null : mapRuleSnapshot(row.rule),
    version: integer(row.version, '配置版本'),
  };
}

export function mapSensorPage(value: unknown): PageResult<SensorSummary> {
  const data = record(value, '测点分页');
  if (!Array.isArray(data.items)) throw new ApiError('测点列表缺少items数组。', 'INVALID_RESPONSE');
  return {
    items: data.items.map(mapSensorSummary),
    page: count(data.page, '页码'), pageSize: count(data.pageSize, '每页条数'), total: count(data.total, '总数'),
  };
}

export function mapSensorDetail(value: unknown): SensorDetail {
  const row = record(value, '测点详情');
  const base = mapSensorSummary(row);
  if (!Array.isArray(row.parameters)) throw new ApiError('测点详情缺少parameters数组。', 'INVALID_RESPONSE');
  const deletion = row.deletion == null ? {allowed: false, blockers: []} : record(row.deletion, '删除可用性');
  const blockers = deletion.blockers == null ? [] : deletion.blockers;
  if (!Array.isArray(blockers)) throw new ApiError('删除阻塞项不是数组。', 'INVALID_RESPONSE');
  return {
    ...base,
    purpose: optional(row.purpose, '用途'),
    hasHistory: boolean(row.hasHistory, '是否已有历史'),
    version: integer(row.version, '版本'),
    parameters: row.parameters.map(mapParameterSummary),
    deletion: {
      allowed: deletion.allowed === true,
      blockers: blockers.map((item: unknown) => {
        const b = record(item, '删除阻塞项');
        return {type: str(b.type, '阻塞类型'), count: count(b.count, '阻塞数量') ?? 0, message: str(b.message, '阻塞说明')};
      }),
    },
  };
}

export function mapParameterPage(value: unknown): PageResult<SensorParameterSummary> {
  const data = record(value, '参数分页');
  if (!Array.isArray(data.items)) throw new ApiError('参数列表缺少items数组。', 'INVALID_RESPONSE');
  return {
    items: data.items.map(mapParameterSummary),
    page: count(data.page, '页码'), pageSize: count(data.pageSize, '每页条数'), total: count(data.total, '总数'),
  };
}

function mapRuleSnapshot(value: unknown): SensorRuleSnapshot {
  const rule = record(value, '规则快照');
  return {
    normalLower: optional(rule.normalLower, '正常下限'),
    normalUpper: optional(rule.normalUpper, '正常上限'),
    alarmLower: optional(rule.alarmLower, '报警下限'),
    alarmUpper: optional(rule.alarmUpper, '报警上限'),
    unitCode: optional(rule.unitCode, '单位'),
    triggerMode: optional(rule.triggerMode, '触发模式'),
    durationSeconds: count(rule.durationSeconds, '持续秒数'),
    enabled: boolean(rule.enabled, '规则启用'),
  };
}

export function mapParameterDetail(value: unknown): SensorParameterDetail {
  const row = record(value, '参数详情');
  return {
    parameterId: id(row.parameterId, '参数ID'),
    sensorId: id(row.sensorId, '测点ID'),
    parameterCode: str(row.parameterCode, '参数编码'),
    name: str(row.name, '参数名称'),
    dataType: str(row.dataType, '数据类型'),
    unitCode: optional(row.unitCode, '单位'),
    decimalPlaces: integer(row.decimalPlaces, '小数位'),
    samplingIntervalSeconds: count(row.samplingIntervalSeconds, '采样周期'),
    processId: row.processId == null ? null : id(row.processId, '工序ID'),
    enabled: boolean(row.enabled, '启用状态'),
    rule: row.rule == null ? null : mapRuleSnapshot(row.rule),
    ruleVersionId: row.ruleVersionId == null ? null : id(row.ruleVersionId, '规则版本ID'),
    version: integer(row.version, '版本'),
  };
}

export function mapParameterConfigResult(value: unknown): SensorParameterConfigResult {
  const row = record(value, '参数配置结果');
  if (!Array.isArray(row.affectedEventIds)) {
    throw new ApiError('参数配置结果缺少affectedEventIds数组。', 'INVALID_RESPONSE');
  }
  return {
    parameterId: id(row.parameterId, '参数ID'),
    sensorId: id(row.sensorId, '测点ID'),
    parameterCode: str(row.parameterCode, '参数编码'),
    name: str(row.name, '参数名称'),
    dataType: str(row.dataType, '数据类型'),
    unitCode: optional(row.unitCode, '单位'),
    decimalPlaces: integer(row.decimalPlaces, '小数位'),
    samplingIntervalSeconds: count(row.samplingIntervalSeconds, '采样周期'),
    processId: row.processId == null ? null : id(row.processId, '工序ID'),
    enabled: boolean(row.enabled, '启用状态'),
    rule: mapRuleSnapshot(row.rule),
    version: integer(row.version, '配置版本'),
    previousRuleVersionId: row.previousRuleVersionId == null
      ? null : id(row.previousRuleVersionId, '上一版规则ID'),
    ruleVersionId: row.ruleVersionId == null ? null : id(row.ruleVersionId, '规则版本ID'),
    affectedEventIds: row.affectedEventIds.map((item, index) => id(item, `受影响事件${index + 1}`)),
    effectNote: str(row.effectNote, '生效说明'),
  };
}

export function mapLatestReading(value: unknown): SensorLatestReading {
  const row = record(value, '最新读数');
  return {
    parameterId: id(row.parameterId, '参数ID'),
    sensorId: id(row.sensorId, '测点ID'),
    sensorCode: str(row.sensorCode, '测点编码'),
    parameterCode: str(row.parameterCode, '参数编码'),
    value: optional(row.value, '读数'),
    valueText: optional(row.valueText, '文本读数'),
    unitCode: optional(row.unitCode, '单位'),
    qualityStatus: optional(row.qualityStatus, '质量状态'),
    limitStatus: optional(row.limitStatus, '限值状态'),
    collectTime: optional(row.collectTime, '采集时间'),
    refreshTime: optional(row.refreshTime, '刷新时间'),
    monitoringStatus: monitoringStatus(row.monitoringStatus),
    dataSource: optional(row.dataSource, '数据来源'),
    missingFields: stringList(row.missingFields, '缺值字段'),
  };
}

export function mapHistoryPage(value: unknown): PageResult<SensorHistoryRecord> {
  const data = record(value, '历史读数分页');
  if (!Array.isArray(data.items)) throw new ApiError('历史读数缺少items数组。', 'INVALID_RESPONSE');
  return {
    items: data.items.map((item: unknown) => {
      const row = record(item, '历史读数');
      return {
        recordId: id(row.recordId, '记录ID'),
        value: optional(row.value, '读数'),
        valueText: optional(row.valueText, '文本读数'),
        sourceUnit: optional(row.sourceUnit, '来源单位'),
        qualityCode: optional(row.qualityCode, '质量码'),
        collectTime: optional(row.collectTime, '采集时间'),
        sourceSystem: str(row.sourceSystem, '来源系统'),
      };
    }),
    page: count(data.page, '页码'), pageSize: count(data.pageSize, '每页条数'), total: count(data.total, '总数'),
  };
}

export function mapRuleVersionPage(value: unknown): PageResult<SensorRuleVersion> {
  const data = record(value, '规则版本分页');
  if (!Array.isArray(data.items)) throw new ApiError('规则版本缺少items数组。', 'INVALID_RESPONSE');
  return {
    items: data.items.map((item: unknown) => {
      const row = record(item, '规则版本');
      return {
        ruleVersionId: id(row.ruleVersionId, '规则版本ID'),
        ruleVersion: integer(row.ruleVersion, '规则版本号'),
        previousRuleVersionId: row.previousRuleVersionId == null ? null : id(row.previousRuleVersionId, '上一版ID'),
        rule: mapRuleSnapshot(row.rule),
        effectiveAt: optional(row.effectiveAt, '生效时间'),
        reason: optional(row.reason, '修改原因'),
        operatorName: str(row.operatorName, '经办人'),
        affectedEventIds: stringList(row.affectedEventIds, '受影响事件'),
      };
    }),
    page: count(data.page, '页码'), pageSize: count(data.pageSize, '每页条数'), total: count(data.total, '总数'),
  };
}

/* ------------------------------------------------ 系统二 · 能源价格 S2-E04—E06 */

export type EnergyType = 'ELECTRICITY' | 'WATER' | 'GAS';
export type EnergyScope = 'GENERAL' | 'WORKSHOP';

/**
 * 能源类型及固定单位。
 *
 * <p>单位不单独维护一份：它由能源决定，页面只展示不给选，避免出现"电按立方米计价"。</p>
 */
export const ENERGY_TYPES: {code: EnergyType; label: string; unit: string}[] = [
  {code: 'ELECTRICITY', label: '电', unit: 'kWh'},
  {code: 'WATER', label: '水', unit: 'm³'},
  {code: 'GAS', label: '煤气', unit: 'Nm³'},
];

export const ENERGY_SCOPE_LABELS: Record<EnergyScope, string> = {
  GENERAL: '通用',
  WORKSHOP: '车间专用',
};

/** 一条价格记录。current=false 表示已被更正替换下来的历史行。 */
export interface EnergyPriceSummary {
  priceId: string;
  energyType: EnergyType;
  energyTypeName: string | null;
  workshopId: string | null;
  workshopName: string | null;
  scope: EnergyScope;
  effectiveDate: string;
  unitCode: string;
  unitPriceYuanPerUnit: string;
  version: number;
  current: boolean;
  supersedesPriceId: string | null;
  operatorName: string | null;
  reason: string | null;
  recordedAt: string | null;
}

/** 按实际用能日匹配到的价格；scope 说明它取自通用还是车间专用。 */
export interface EnergyPriceMatch {
  priceId: string;
  scope: EnergyScope;
  effectiveDate: string;
  unitCode: string;
  unitPriceYuanPerUnit: string;
  version: number;
}

export interface EnergyPricePageResult extends PageResult<EnergyPriceSummary> {
  /** 仅在同时给出能源与实际日时有值；null 表示"这次没问匹配"，不是"没匹配到"。 */
  matchedPrice: EnergyPriceMatch | null;
}

export interface EnergyPriceQuery {
  energyType?: string;
  workshopId?: string;
  scope?: string;
  effectiveFrom?: string;
  effectiveTo?: string;
  actualDate?: string;
  includeHistory?: boolean;
  page?: number;
  pageSize?: number;
}

/** 本次写入影响的日期范围；to 为 null 表示一直向后生效，不是范围未知。 */
export interface AffectedScope { from: string; to: string | null; workshopIds: string[] }

export interface EnergyPriceWriteValues {
  energyType: EnergyType;
  workshopId: string | null;
  effectiveDate: string;
  unitCode: string;
  unitPriceYuanPerUnit: string;
}

/** E05 新增结果。costRunId 恒为 null：核算服务未接入。 */
export interface EnergyPriceWriteResult extends EnergyPriceWriteValues {
  priceId: string; version: number; scope: EnergyScope;
  costRunId: string | null; affectedScope: AffectedScope;
}

/** E06 更正结果。原价格仍保留，新价格经 supersedesPriceId 指向它。 */
export interface EnergyPriceCorrectionResult {
  previousPriceId: string; priceId: string; version: number;
  supersedesPriceId: string; changeLogId: string;
  affectedScope: AffectedScope; costRunId: string | null;
  status: 'PENDING' | 'COMPLETE' | 'NOT_APPLICABLE';
}

/** 更正内容：只提交要改的字段，未出现的字段保留原值。 */
export interface EnergyPriceChanges {
  energyType?: EnergyType;
  workshopId?: string | null;
  effectiveDate?: string;
  unitCode?: string;
  unitPriceYuanPerUnit?: string;
}

function energyTypeOf(value: unknown): EnergyType {
  const raw = str(value, '能源类型');
  if (raw === 'ELECTRICITY' || raw === 'WATER' || raw === 'GAS') return raw;
  throw new ApiError(`能源类型「${raw}」不在约定范围内。`, 'INVALID_RESPONSE');
}

function scopeOf(value: unknown): EnergyScope {
  const raw = str(value, '价格范围');
  if (raw === 'GENERAL' || raw === 'WORKSHOP') return raw;
  throw new ApiError(`价格范围「${raw}」不在约定范围内。`, 'INVALID_RESPONSE');
}

function mapAffectedScope(value: unknown): AffectedScope {
  const data = record(value, '受影响范围');
  return {
    from: str(data.from, '影响开始日'),
    to: data.to == null ? null : str(data.to, '影响结束日'),
    workshopIds: Array.isArray(data.workshopIds) ? data.workshopIds.map(item => id(item, '车间ID')) : [],
  };
}

export function mapEnergyPricePage(value: unknown): EnergyPricePageResult {
  const data = record(value, '能源价格分页');
  if (!Array.isArray(data.items)) throw new ApiError('能源价格列表缺少items数组。', 'INVALID_RESPONSE');
  return {
    items: data.items.map((item: unknown) => {
      const row = record(item, '能源价格');
      return {
        priceId: id(row.priceId, '价格ID'),
        energyType: energyTypeOf(row.energyType),
        energyTypeName: optional(row.energyTypeName, '能源名称'),
        workshopId: row.workshopId == null ? null : id(row.workshopId, '车间ID'),
        workshopName: optional(row.workshopName, '车间名称'),
        scope: scopeOf(row.scope),
        effectiveDate: str(row.effectiveDate, '生效日'),
        unitCode: str(row.unitCode, '单位'),
        unitPriceYuanPerUnit: str(row.unitPriceYuanPerUnit, '单价'),
        version: integer(row.version, '版本号'),
        current: row.current === true,
        supersedesPriceId: row.supersedesPriceId == null ? null : id(row.supersedesPriceId, '被更正价格ID'),
        operatorName: optional(row.operatorName, '经办人'),
        reason: optional(row.reason, '说明'),
        recordedAt: optional(row.recordedAt, '录入时间'),
      };
    }),
    page: count(data.page, '页码'), pageSize: count(data.pageSize, '每页条数'), total: count(data.total, '总数'),
    matchedPrice: data.matchedPrice == null ? null : (() => {
      const row = record(data.matchedPrice, '匹配价格');
      return {
        priceId: id(row.priceId, '价格ID'),
        scope: scopeOf(row.scope),
        effectiveDate: str(row.effectiveDate, '生效日'),
        unitCode: str(row.unitCode, '单位'),
        unitPriceYuanPerUnit: str(row.unitPriceYuanPerUnit, '单价'),
        version: integer(row.version, '版本号'),
      };
    })(),
  };
}

export function mapEnergyPriceWriteResult(value: unknown): EnergyPriceWriteResult {
  const data = record(value, '新增价格结果');
  return {
    priceId: id(data.priceId, '价格ID'),
    version: integer(data.version, '版本号'),
    energyType: energyTypeOf(data.energyType),
    workshopId: data.workshopId == null ? null : id(data.workshopId, '车间ID'),
    scope: scopeOf(data.scope),
    effectiveDate: str(data.effectiveDate, '生效日'),
    unitCode: str(data.unitCode, '单位'),
    unitPriceYuanPerUnit: str(data.unitPriceYuanPerUnit, '单价'),
    costRunId: data.costRunId == null ? null : str(data.costRunId, '核算运行ID'),
    affectedScope: mapAffectedScope(data.affectedScope),
  };
}

export function mapEnergyPriceCorrectionResult(value: unknown): EnergyPriceCorrectionResult {
  const data = record(value, '更正价格结果');
  const status = str(data.status, '核算状态');
  if (status !== 'PENDING' && status !== 'COMPLETE' && status !== 'NOT_APPLICABLE') {
    throw new ApiError(`核算状态「${status}」不在约定范围内。`, 'INVALID_RESPONSE');
  }
  return {
    previousPriceId: id(data.previousPriceId, '原价格ID'),
    priceId: id(data.priceId, '新价格ID'),
    version: integer(data.version, '版本号'),
    supersedesPriceId: id(data.supersedesPriceId, '被更正价格ID'),
    changeLogId: id(data.changeLogId, '变更记录ID'),
    affectedScope: mapAffectedScope(data.affectedScope),
    costRunId: data.costRunId == null ? null : str(data.costRunId, '核算运行ID'),
    status,
  };
}

/**
 * 完整性三态。COMPLETE 可以包含明确的零；PARTIAL 只有 knownValue，value 为 null；
 * MISSING 两者都为 null。页面绝不能把 PARTIAL 的 knownValue 当完整值展示。
 */
export type EnergyCompleteness = 'COMPLETE' | 'PARTIAL' | 'MISSING';

/** 一条缺项：说清去哪里补，而不只是"数据缺失"。 */
export interface EnergyMissingItem {
  code: string;
  sourceType: string | null;
  sourceId: string | null;
  workshopId: string | null;
  actualDate: string | null;
  field: string | null;
  message: string;
}

export interface EnergyQuantity {
  unit: string;
  value: string | null;
  knownValue: string | null;
  completeness: EnergyCompleteness;
  missingItems: EnergyMissingItem[];
}

export interface WorkshopEnergyTotals {
  workshopId: string;
  workshopName: string | null;
  electricity: EnergyQuantity;
  water: EnergyQuantity;
  gas: EnergyQuantity;
  qualifiedOutput: EnergyQuantity;
}

export interface EnergyTotalsResult {
  from: string;
  to: string;
  workshops: WorkshopEnergyTotals[];
}

export interface EnergyTotalsQuery { from: string; to: string; workshopId?: string }

export interface EnergyMeterIdentity { sensorId: string; sensorCode: string; sensorName: string }

/** 一个日来源槽。meter 为 null 表示该日没有可用表计（缺计量），不是"表计信息加载失败"。 */
export interface EnergyDailyConsumption {
  actualDate: string;
  workshopId: string;
  workshopName: string | null;
  energyType: EnergyType;
  energyTypeName: string | null;
  unit: string;
  meter: EnergyMeterIdentity | null;
  value: string | null;
  knownValue: string | null;
  completeness: EnergyCompleteness;
  qualityStatus: string | null;
  calculationMethod: string | null;
  refreshTime: string | null;
  missingItems: EnergyMissingItem[];
}

/** 范围内全量槽的完整性分布，不受筛选影响；page/total 才是当前列表。 */
export interface EnergyDailySummary {
  slotTotal: number;
  completeCount: number;
  partialCount: number;
  missingCount: number;
}

export interface EnergyDailyConsumptionPage extends PageResult<EnergyDailyConsumption> {
  summary: EnergyDailySummary;
}

export interface EnergyDailyConsumptionQuery {
  from: string;
  to: string;
  workshopId?: string;
  energyType?: string;
  qualityStatus?: string;
  completeness?: string;
  page?: number;
  pageSize?: number;
}

/** 当时有效的价格快照。带上范围与版本，页面才能解释"为什么是这个价"。 */
export interface EnergyPriceSnapshot {
  priceId: string;
  scope: string;
  effectiveDate: string;
  unitCode: string;
  unitPriceYuanPerUnit: string;
  version: number;
}

/** 当日一类能源的来源、价格与费用。consumption 为 null 时 amountYuan 必为 null。 */
export interface EnergyDaySource {
  energyType: EnergyType;
  energyTypeName: string | null;
  unit: string;
  meter: EnergyMeterIdentity | null;
  consumption: string | null;
  knownConsumption: string | null;
  completeness: EnergyCompleteness;
  qualityStatus: string | null;
  calculationMethod: string | null;
  refreshTime: string | null;
  price: EnergyPriceSnapshot | null;
  amountYuan: string | null;
  missingItems: EnergyMissingItem[];
}

export interface EnergyShareEnergy {
  energyType: EnergyType;
  unit: string;
  allocatedConsumption: string | null;
  amountYuan: string | null;
}

/** 一个分摊对象；订单与公共份额同构，靠 targetType 区分。 */
export interface EnergyAllocationShare {
  targetType: string;
  orderId: string | null;
  orderNo: string | null;
  qualifiedQuantityTon: string | null;
  ratio: string | null;
  energies: EnergyShareEnergy[];
  amountYuan: string | null;
  missingItems: EnergyMissingItem[];
}

export interface EnergyReconciliation {
  energyType: EnergyType;
  unit: string;
  sourceConsumption: string | null;
  allocatedConsumption: string | null;
  publicConsumption: string | null;
  difference: string | null;
}

export interface EnergyDayConfirmation {
  confirmed: boolean;
  confirmedBy: string | null;
  confirmedAt: string | null;
  invalidated: boolean;
  note: string;
}

export interface EnergyDenominator {
  quantityTon: string | null;
  complete: boolean;
  note: string;
}

/**
 * S2-E03 当日来源与分摊。
 * status 只说明"能不能分摊"，不能分摊的原因看 missingItems 与 statusNote。
 */
export interface EnergyWorkshopDayAllocation {
  workshopId: string;
  workshopName: string | null;
  workshopType: string | null;
  actualDate: string;
  allocationBasis: string;
  status: string;
  statusNote: string;
  dayConfirmation: EnergyDayConfirmation;
  denominator: EnergyDenominator;
  sources: EnergyDaySource[];
  totalAmountYuan: string | null;
  allocations: EnergyAllocationShare[];
  publicShare: EnergyAllocationShare | null;
  reconciliation: EnergyReconciliation[];
  costRun: {costRunId: string | null; version: number | null; status: string | null};
  missingItems: EnergyMissingItem[];
}

function completenessOf(value: unknown): EnergyCompleteness {
  const raw = str(value, '完整性');
  if (raw === 'COMPLETE' || raw === 'PARTIAL' || raw === 'MISSING') return raw;
  throw new ApiError(`完整性「${raw}」不在约定范围内。`, 'INVALID_RESPONSE');
}

function nullableStr(value: unknown, name: string): string | null {
  return value == null ? null : str(value, name);
}

function mapMissingItems(value: unknown): EnergyMissingItem[] {
  if (value == null) return [];
  if (!Array.isArray(value)) throw new ApiError('缺项列表格式不符合接口约定。', 'INVALID_RESPONSE');
  return value.map((entry: unknown) => {
    const row = record(entry, '缺项');
    return {
      code: str(row.code, '缺项代码'),
      sourceType: nullableStr(row.sourceType, '缺项来源类型'),
      sourceId: row.sourceId == null ? null : id(row.sourceId, '缺项来源ID'),
      workshopId: row.workshopId == null ? null : id(row.workshopId, '缺项车间ID'),
      actualDate: nullableStr(row.actualDate, '缺项日期'),
      field: nullableStr(row.field, '缺项字段'),
      message: str(row.message, '缺项说明'),
    };
  });
}

function mapEnergyQuantity(value: unknown, name: string): EnergyQuantity {
  const row = record(value, name);
  return {
    unit: str(row.unit, `${name}单位`),
    value: nullableStr(row.value, `${name}数值`),
    knownValue: nullableStr(row.knownValue, `${name}已知部分`),
    completeness: completenessOf(row.completeness),
    missingItems: mapMissingItems(row.missingItems),
  };
}

export function mapEnergyTotals(value: unknown): EnergyTotalsResult {
  const data = record(value, '能源总量');
  if (!Array.isArray(data.workshops)) throw new ApiError('能源总量缺少工作车间列表。', 'INVALID_RESPONSE');
  return {
    from: str(data.from, '起始日'),
    to: str(data.to, '结束日'),
    workshops: data.workshops.map((entry: unknown) => {
      const row = record(entry, '车间总量');
      return {
        workshopId: id(row.workshopId, '车间ID'),
        workshopName: nullableStr(row.workshopName, '车间名称'),
        electricity: mapEnergyQuantity(row.electricity, '电'),
        water: mapEnergyQuantity(row.water, '水'),
        gas: mapEnergyQuantity(row.gas, '煤气'),
        qualifiedOutput: mapEnergyQuantity(row.qualifiedOutput, '合格产量'),
      };
    }),
  };
}

export function mapEnergyDailyConsumptionPage(value: unknown): EnergyDailyConsumptionPage {
  const data = record(value, '能源日明细');
  if (!Array.isArray(data.items)) throw new ApiError('能源日明细缺少items数组。', 'INVALID_RESPONSE');
  const summary = record(data.summary, '日明细汇总');
  return {
    items: data.items.map((entry: unknown) => {
      const row = record(entry, '日来源');
      return {
        actualDate: str(row.actualDate, '实际日'),
        workshopId: id(row.workshopId, '车间ID'),
        workshopName: nullableStr(row.workshopName, '车间名称'),
        energyType: energyTypeOf(row.energyType),
        energyTypeName: nullableStr(row.energyTypeName, '能源名称'),
        unit: str(row.unit, '单位'),
        meter: row.meter == null ? null : (() => {
          const meter = record(row.meter, '表计');
          return {
            sensorId: id(meter.sensorId, '表计ID'),
            sensorCode: str(meter.sensorCode, '表计编码'),
            sensorName: str(meter.sensorName, '表计名称'),
          };
        })(),
        value: nullableStr(row.value, '消耗量'),
        knownValue: nullableStr(row.knownValue, '已知消耗量'),
        completeness: completenessOf(row.completeness),
        qualityStatus: nullableStr(row.qualityStatus, '质量状态'),
        calculationMethod: nullableStr(row.calculationMethod, '计算方式'),
        refreshTime: nullableStr(row.refreshTime, '刷新时间'),
        missingItems: mapMissingItems(row.missingItems),
      };
    }),
    page: count(data.page, '页码'),
    pageSize: count(data.pageSize, '每页条数'),
    total: count(data.total, '总数'),
    summary: {
      slotTotal: integer(summary.slotTotal, '槽位总数'),
      completeCount: integer(summary.completeCount, '完整数'),
      partialCount: integer(summary.partialCount, '部分已知数'),
      missingCount: integer(summary.missingCount, '缺测数'),
    },
  };
}

function mapPriceSnapshot(value: unknown): EnergyPriceSnapshot | null {
  if (value == null) return null;
  const row = record(value, '价格快照');
  return {
    priceId: id(row.priceId, '价格ID'),
    scope: str(row.scope, '价格范围'),
    effectiveDate: str(row.effectiveDate, '生效日'),
    unitCode: str(row.unitCode, '计价单位'),
    unitPriceYuanPerUnit: str(row.unitPriceYuanPerUnit, '单价'),
    version: integer(row.version, '价格版本'),
  };
}

function mapDaySource(value: unknown): EnergyDaySource {
  const row = record(value, '当日来源');
  return {
    energyType: energyTypeOf(row.energyType),
    energyTypeName: nullableStr(row.energyTypeName, '能源名称'),
    unit: str(row.unit, '单位'),
    meter: row.meter == null ? null : (() => {
      const meter = record(row.meter, '表计');
      return {
        sensorId: id(meter.sensorId, '表计ID'),
        sensorCode: str(meter.sensorCode, '表计编码'),
        sensorName: str(meter.sensorName, '表计名称'),
      };
    })(),
    consumption: nullableStr(row.consumption, '消耗量'),
    knownConsumption: nullableStr(row.knownConsumption, '已知消耗量'),
    completeness: completenessOf(row.completeness),
    qualityStatus: nullableStr(row.qualityStatus, '质量状态'),
    calculationMethod: nullableStr(row.calculationMethod, '计算方式'),
    refreshTime: nullableStr(row.refreshTime, '刷新时间'),
    price: mapPriceSnapshot(row.price),
    amountYuan: nullableStr(row.amountYuan, '费用'),
    missingItems: mapMissingItems(row.missingItems),
  };
}

function mapAllocationShare(value: unknown): EnergyAllocationShare {
  const row = record(value, '分摊份额');
  const energies = Array.isArray(row.energies) ? row.energies : [];
  return {
    targetType: str(row.targetType, '分摊对象类型'),
    orderId: row.orderId == null ? null : id(row.orderId, '订单ID'),
    orderNo: nullableStr(row.orderNo, '订单号'),
    qualifiedQuantityTon: nullableStr(row.qualifiedQuantityTon, '归属合格量'),
    ratio: nullableStr(row.ratio, '分摊比例'),
    energies: energies.map((entry: unknown) => {
      const energy = record(entry, '分摊能源');
      return {
        energyType: energyTypeOf(energy.energyType),
        unit: str(energy.unit, '单位'),
        allocatedConsumption: nullableStr(energy.allocatedConsumption, '分摊消耗'),
        amountYuan: nullableStr(energy.amountYuan, '分摊费用'),
      };
    }),
    amountYuan: nullableStr(row.amountYuan, '分摊费用合计'),
    missingItems: mapMissingItems(row.missingItems),
  };
}

/** S2-E03：分母、来源、份额、校验四块必须一起解析，缺任何一块都会让页面显示错的比例。 */
export function mapWorkshopDayAllocation(value: unknown): EnergyWorkshopDayAllocation {
  const data = record(value, '当日分摊');
  const confirmation = record(data.dayConfirmation, '当日录全确认');
  const denominator = record(data.denominator, '分摊分母');
  const costRun = record(data.costRun, '核算运行');
  const reconciliation = Array.isArray(data.reconciliation) ? data.reconciliation : [];
  return {
    workshopId: id(data.workshopId, '车间ID'),
    workshopName: nullableStr(data.workshopName, '车间名称'),
    workshopType: nullableStr(data.workshopType, '车间类型'),
    actualDate: str(data.actualDate, '实际日'),
    allocationBasis: str(data.allocationBasis, '分摊依据'),
    status: str(data.status, '分摊状态'),
    statusNote: str(data.statusNote, '状态说明'),
    dayConfirmation: {
      confirmed: confirmation.confirmed === true,
      confirmedBy: nullableStr(confirmation.confirmedBy, '确认人'),
      confirmedAt: nullableStr(confirmation.confirmedAt, '确认时间'),
      invalidated: confirmation.invalidated === true,
      note: str(confirmation.note, '确认说明'),
    },
    denominator: {
      quantityTon: nullableStr(denominator.quantityTon, '分母吨数'),
      complete: denominator.complete === true,
      note: str(denominator.note, '分母说明'),
    },
    sources: Array.isArray(data.sources) ? data.sources.map(mapDaySource) : [],
    totalAmountYuan: nullableStr(data.totalAmountYuan, '当日费用合计'),
    allocations: Array.isArray(data.allocations) ? data.allocations.map(mapAllocationShare) : [],
    publicShare: data.publicShare == null ? null : mapAllocationShare(data.publicShare),
    reconciliation: reconciliation.map((entry: unknown) => {
      const row = record(entry, '合计校验');
      return {
        energyType: energyTypeOf(row.energyType),
        unit: str(row.unit, '单位'),
        sourceConsumption: nullableStr(row.sourceConsumption, '源总量'),
        allocatedConsumption: nullableStr(row.allocatedConsumption, '订单合计'),
        publicConsumption: nullableStr(row.publicConsumption, '公共合计'),
        difference: nullableStr(row.difference, '差额'),
      };
    }),
    costRun: {
      costRunId: costRun.costRunId == null ? null : id(costRun.costRunId, '核算运行ID'),
      version: costRun.version == null ? null : count(costRun.version, '核算版本'),
      status: nullableStr(costRun.status, '核算状态'),
    },
    missingItems: mapMissingItems(data.missingItems),
  };
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
  /** S1-T02 提醒设置：全系统共用的一份，期限面板与合同页签都按它判定 DUE_SOON。 */
  reminderSetting: (signal?: AbortSignal) =>
    requestApi('/api/v1/reminder-settings', mapReminderSetting, {signal}),
  /** S1-T03 保存提醒设置；必须带当前版本号，被他人先改过时后端返回 409。 */
  updateReminderSetting: (values: {warrantyLeadDays: number; maintenanceLeadDays: number},
                          meta: {requestId: string; operatorName: string; expectedVersion: number}) =>
    requestApi('/api/v1/reminder-settings', mapReminderSetting,
      {method: 'PUT', body: {...meta, ...values}}),
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
  /** S1-P01 测点分页；设备详情抽屉的"测点"页签按 equipmentId 过滤。 */
  sensors: (query: SensorQuery = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams();
    if (query.equipmentId) params.set('equipmentId', query.equipmentId);
    if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
    if (query.enabled !== undefined) params.set('enabled', String(query.enabled));
    if (query.monitoringStatus) params.set('monitoringStatus', query.monitoringStatus);
    if (query.page !== undefined) params.set('page', String(query.page));
    if (query.pageSize !== undefined) params.set('pageSize', String(query.pageSize));
    const text = params.toString();
    return requestApi(`/api/v1/sensors${text ? `?${text}` : ''}`, mapSensorPage, {signal});
  },
  /** S1-P02 测点详情，响应里带参数摘要，可直接渲染参数列表。 */
  sensorDetail: (sensorId: string, signal?: AbortSignal) =>
    requestApi(`/api/v1/sensors/${encodeURIComponent(sensorId)}`, mapSensorDetail, {signal}),
  /** S1-P06 参数定义分页；一测点可有多个参数。 */
  sensorParameters: (sensorId: string, page = 1, pageSize = 50, signal?: AbortSignal) =>
    requestApi(`/api/v1/sensors/${encodeURIComponent(sensorId)}/parameters?page=${page}&pageSize=${pageSize}`,
      mapParameterPage, {signal}),
  /** S1-P07 新增参数；可选带首版规则，同测点内参数编码唯一。 */
  createSensorParameter: (sensorId: string, values: SensorParameterFormValues,
                          meta: {requestId: string; operatorName: string}) =>
    requestApi(`/api/v1/sensors/${encodeURIComponent(sensorId)}/parameters`, mapParameterDetail,
      {method: 'POST', body: {...meta, ...values}}),
  /** S1-P08 保存参数配置；判断依据变化才追加规则版本，没变化只推进配置版本。 */
  updateSensorParameter: (parameterId: string, values: SensorParameterConfigValues,
                          meta: {requestId: string; operatorName: string; expectedVersion: number}) =>
    requestApi(`/api/v1/sensor-parameters/${encodeURIComponent(parameterId)}/configuration`,
      mapParameterConfigResult, {method: 'PUT', body: {...meta, ...values}}),
  /** S1-P03 新增测点；返回详情，首存版本 1、配置状态 PENDING。 */
  createSensor: (values: SensorFormValues, meta: {requestId: string; operatorName: string}) =>
    requestApi('/api/v1/sensors', mapSensorDetail, {method: 'POST', body: {...meta, ...values}}),
  /** S1-P04 修改测点资料与启停用；有采集记录时服务端会拒绝改编码。 */
  updateSensor: (sensorId: string, values: SensorFormValues,
                 meta: {requestId: string; operatorName: string; expectedVersion: number; reason?: string | null}) =>
    requestApi(`/api/v1/sensors/${encodeURIComponent(sensorId)}`, mapSensorDetail,
      {method: 'PUT', body: {...meta, ...values}}),
  /** S1-P05 删除误建测点；有采集或业务历史时服务端返回 409 及 blockers。 */
  deleteSensor: (sensorId: string,
                 meta: {requestId: string; operatorName: string; expectedVersion: number; reason: string}) =>
    requestApi(`/api/v1/sensors/${encodeURIComponent(sensorId)}`, mapSensorDeleteResult,
      {method: 'DELETE', body: {...meta, confirmed: true}}),
  /** S1-P09 最新读数；没有采集记录时返回缺值状态，不是 404。 */
  sensorLatest: (parameterId: string, signal?: AbortSignal) =>
    requestApi(`/api/v1/sensor-parameters/${encodeURIComponent(parameterId)}/latest`, mapLatestReading, {signal}),
  /** S1-P10 原始历史；区间上限由服务端把关，前端只负责给合理的默认值。 */
  sensorHistory: (parameterId: string, query: SensorHistoryQuery, signal?: AbortSignal) => {
    const params = new URLSearchParams();
    params.set('from', query.from);
    params.set('to', query.to);
    if (query.page !== undefined) params.set('page', String(query.page));
    if (query.pageSize !== undefined) params.set('pageSize', String(query.pageSize));
    return requestApi(`/api/v1/sensor-parameters/${encodeURIComponent(parameterId)}/history?${params.toString()}`,
      mapHistoryPage, {signal});
  },
  /** S1-P11 规则版本历史；旧版本不可覆盖，附各版接手时结束的事件。 */
  sensorRuleVersions: (parameterId: string, page = 1, pageSize = 20, signal?: AbortSignal) =>
    requestApi(`/api/v1/sensor-parameters/${encodeURIComponent(parameterId)}/rule-versions?page=${page}&pageSize=${pageSize}`,
      mapRuleVersionPage, {signal}),
  /** S2-E04 能源价格分页；带实际日时附按"车间专用优先、无则回退通用"算出的匹配价。 */
  energyPrices: (query: EnergyPriceQuery = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams();
    if (query.energyType) params.set('energyType', query.energyType);
    if (query.workshopId) params.set('workshopId', query.workshopId);
    if (query.scope) params.set('scope', query.scope);
    if (query.effectiveFrom) params.set('effectiveFrom', query.effectiveFrom);
    if (query.effectiveTo) params.set('effectiveTo', query.effectiveTo);
    if (query.actualDate) params.set('actualDate', query.actualDate);
    if (query.includeHistory) params.set('includeHistory', 'true');
    if (query.page !== undefined) params.set('page', String(query.page));
    if (query.pageSize !== undefined) params.set('pageSize', String(query.pageSize));
    const text = params.toString();
    return requestApi(`/api/v1/energy/prices${text ? `?${text}` : ''}`, mapEnergyPricePage, {signal});
  },
  /**
   * S2-E05 新增生效价。
   * workshopId 必须显式出现：null 表示通用范围，漏传会被服务端判为校验失败。
   */
  createEnergyPrice: (values: EnergyPriceWriteValues, meta: {requestId: string; operatorName: string}) =>
    requestApi('/api/v1/energy/prices', mapEnergyPriceWriteResult,
      {method: 'POST', body: {...meta, ...values}}),
  /** S2-E06 更正原价格：原行保留并退出当前有效，新行经 supersedesPriceId 指向它，必须填原因。 */
  correctEnergyPrice: (priceId: string, changes: EnergyPriceChanges,
                       meta: {requestId: string; operatorName: string; expectedVersion: number; reason: string}) =>
    requestApi(`/api/v1/energy/prices/${encodeURIComponent(priceId)}/corrections`,
      mapEnergyPriceCorrectionResult, {method: 'POST', body: {...meta, changes}}),
  /**
   * S2-E01 分车间四项总量。省略 workshopId 时返回全部生产车间。
   * 缺表计、覆盖未核验、产量未确认都会体现为 completeness，页面照实展示即可。
   */
  energyTotals: (query: EnergyTotalsQuery, signal?: AbortSignal) => {
    const params = new URLSearchParams({from: query.from, to: query.to});
    if (query.workshopId) params.set('workshopId', query.workshopId);
    return requestApi(`/api/v1/energy/totals?${params.toString()}`, mapEnergyTotals, {signal});
  },
  /** S2-E02 日来源明细；缺计量或缺读数的日槽同样返回，供页面定位缺口。 */
  energyDailyConsumptions: (query: EnergyDailyConsumptionQuery, signal?: AbortSignal) => {
    const params = new URLSearchParams({from: query.from, to: query.to});
    if (query.workshopId) params.set('workshopId', query.workshopId);
    if (query.energyType) params.set('energyType', query.energyType);
    if (query.qualityStatus) params.set('qualityStatus', query.qualityStatus);
    if (query.completeness) params.set('completeness', query.completeness);
    if (query.page !== undefined) params.set('page', String(query.page));
    if (query.pageSize !== undefined) params.set('pageSize', String(query.pageSize));
    return requestApi(`/api/v1/energy/daily-consumptions?${params.toString()}`,
      mapEnergyDailyConsumptionPage, {signal});
  },
  /** S2-E03 车间某日的来源与分摊。分母不成立时只给缺项，不给比例。 */
  energyDayAllocation: (workshopId: string, actualDate: string, signal?: AbortSignal) =>
    requestApi(`/api/v1/energy/workshops/${encodeURIComponent(workshopId)}`
      + `/days/${encodeURIComponent(actualDate)}/allocations`, mapWorkshopDayAllocation, {signal}),
  overview: (signal?: AbortSignal) => fetchApi('/api/v1/bootstrap/overview', mapOverview, signal),
  live: (signal?: AbortSignal) => fetchApi('/api/v1/health/live', mapHealth, signal),
  ready: (signal?: AbortSignal) => fetchApi('/api/v1/health/ready', mapHealth, signal),
};
