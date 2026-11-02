export interface Equipment {
  id: string; code: string; name: string; workshopId: string;
  workshopName: string; responsiblePerson: string | null; enabled: boolean;
}
export interface Overview {
  projectName: string; environment: string; scope: 'INITIALIZATION_ONLY';
  database: { status: string; schemaName: string | null; serverVersion: string | null; phase1TableCount: number | null; phase2TableCount: number | null };
  counts: { workshops: number | null; productionEquipment: number | null; sensors: number | null };
}
export interface Health { status: string; database?: { status: string; check?: string } }
export interface ApiResult<T> { data: T; timestamp: string }
export class ApiError extends Error {
  readonly code: string;
  readonly status: number | null;
  constructor(message: string, code: string, status: number | null = null) {
    super(message); this.name = 'ApiError'; this.code = code; this.status = status;
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
export function mapEquipment(value: unknown): Equipment[] {
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
export function mapOverview(value: unknown): Overview {
  const data = record(value, '初始化概览'), db = record(data.database, '数据库状态'), n = record(data.counts, '数量概览');
  if (data.scope !== 'INITIALIZATION_ONLY') throw new ApiError('接口范围与当前初始化版本不一致。', 'INVALID_RESPONSE');
  return {projectName: str(data.projectName, '项目名称'), environment: str(data.environment, '环境名称'), scope: 'INITIALIZATION_ONLY', database: {status: str(db.status, '数据库状态'), schemaName: optional(db.schemaName, '数据库名称'), serverVersion: optional(db.serverVersion, '数据库版本'), phase1TableCount: count(db.phase1TableCount, '一期表数量'), phase2TableCount: count(db.phase2TableCount, '二期表数量')}, counts: {workshops: count(n.workshops, '车间数量'), productionEquipment: count(n.productionEquipment, '生产设备数量'), sensors: count(n.sensors, '测点数量')}};
}
export function mapHealth(value: unknown): Health {
  const data = record(value, '服务状态'), result: Health = {status: str(data.status, '服务状态')};
  if (data.database !== undefined) { const db = record(data.database, '数据库连接'); result.database = {status: str(db.status, '数据库连接状态'), check: optional(db.check, '数据库检查') ?? undefined}; }
  return result;
}
export async function fetchApi<T>(path: string, mapper: (value: unknown) => T, signal?: AbortSignal, fetcher: typeof fetch = fetch): Promise<ApiResult<T>> {
  let response: Response;
  try { response = await fetcher(path, {signal, headers: {Accept: 'application/json'}}); }
  catch (error: unknown) {
    if (error instanceof Error && error.name === 'AbortError') throw error;
    throw new ApiError('暂时无法连接服务，请确认服务已启动后重试。', 'NETWORK_ERROR');
  }
  let value: unknown;
  try { value = await response.json(); }
  catch { throw new ApiError(response.ok ? '服务返回了无法识别的数据，请稍后重试。' : `服务请求失败（HTTP ${response.status}），请稍后重试。`, 'INVALID_RESPONSE', response.status); }
  const envelope = record(value, '接口响应');
  if (!response.ok || envelope.code !== 'OK') throw new ApiError(typeof envelope.message === 'string' && envelope.message.trim() ? envelope.message : `读取失败（HTTP ${response.status}），请稍后重试。`, typeof envelope.code === 'string' ? envelope.code : 'HTTP_ERROR', response.status);
  return {data: mapper(envelope.data), timestamp: str(envelope.timestamp, '接口时间')};
}
export const api = {
  equipment: (signal?: AbortSignal) => fetchApi('/api/v1/equipment', mapEquipment, signal),
  overview: (signal?: AbortSignal) => fetchApi('/api/v1/bootstrap/overview', mapOverview, signal),
  live: (signal?: AbortSignal) => fetchApi('/api/v1/health/live', mapHealth, signal),
  ready: (signal?: AbortSignal) => fetchApi('/api/v1/health/ready', mapHealth, signal),
};
