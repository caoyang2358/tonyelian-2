import assert from 'node:assert/strict';
import test from 'node:test';
import { ApiError, fetchApi, mapEquipment, mapHealth, mapOverview } from '../src/lib/api.ts';
const row = {id: 7, code: 'ROLL-01', name: '1#多道轧机组', workshopId: 1, workshopName: '车间一', responsiblePerson: null, enabled: 1};
const envelope = (data: unknown, code = 'OK') => ({code, message: '请求成功', data, timestamp: '2026-11-02T08:00:00Z'});
test('设备映射保留一期标识与空责任人，正确处理布尔状态和空列表', () => {
  assert.deepEqual(mapEquipment({items: [row]}), [{...row, id: '7', workshopId: '1', enabled: true}]);
  assert.equal(mapEquipment({items: [{...row, enabled: false}]})[0].enabled, false);
  assert.deepEqual(mapEquipment({items: []}), []);
});
test('拒绝重复设备ID、错误布尔值及不安全数字ID', () => {
  assert.throws(() => mapEquipment({items: [row, row]}), /重复ID/);
  assert.throws(() => mapEquipment({items: [{...row, enabled: 'true'}]}), /布尔值/);
  assert.throws(() => mapEquipment({items: [{...row, id: Number.MAX_SAFE_INTEGER + 1}]}), /安全范围/);
});
test('未知数量保持null，真实零数量保留0', () => {
  const result = mapOverview({projectName: '二期', environment: 'local', scope: 'INITIALIZATION_ONLY', database: {status: 'DOWN'}, counts: {workshops: null, productionEquipment: 0}});
  assert.equal(result.counts.workshops, null); assert.equal(result.counts.productionEquipment, 0); assert.equal(result.counts.sensors, null);
});
test('健康检查按对象解析数据库状态', () => { assert.deepEqual(mapHealth({status: 'UP', database: {status: 'UP', check: 'SELECT 1'}}), {status: 'UP', database: {status: 'UP', check: 'SELECT 1'}}); });
test('成功信封中的空数据仍是正常返回', async () => {
  const fetcher: typeof fetch = async () => new Response(JSON.stringify(envelope({items: []})), {status: 200});
  assert.deepEqual((await fetchApi('/api/v1/equipment', mapEquipment, undefined, fetcher)).data, []);
});
test('HTTP及业务失败不可冒充空数据，保留安全后端消息', async () => {
  const fetcher: typeof fetch = async () => new Response(JSON.stringify({...envelope(null, 'DATABASE_UNAVAILABLE'), message: '数据库尚未就绪'}), {status: 503});
  await assert.rejects(fetchApi('/api/v1/equipment', mapEquipment, undefined, fetcher), (error: unknown) => error instanceof ApiError && error.status === 503 && error.message === '数据库尚未就绪');
  const failed: typeof fetch = async () => new Response(JSON.stringify({...envelope(null, 'FAILED'), message: '读取失败'}), {status: 200});
  await assert.rejects(fetchApi('/api/v1/equipment', mapEquipment, undefined, failed), /读取失败/);
});
test('断网及非JSON响应显示可重试错误', async () => {
  const offline: typeof fetch = async () => { throw new TypeError('Failed to fetch'); };
  await assert.rejects(fetchApi('/api/v1/equipment', mapEquipment, undefined, offline), (error: unknown) => error instanceof ApiError && error.code === 'NETWORK_ERROR');
  const html: typeof fetch = async () => new Response('<html>Unavailable</html>', {status: 502});
  await assert.rejects(fetchApi('/api/v1/equipment', mapEquipment, undefined, html), /HTTP 502/);
});
test('取消请求保持取消语义，不转成网络故障', async () => {
  const aborted: typeof fetch = async () => { throw new DOMException('Aborted', 'AbortError'); };
  await assert.rejects(fetchApi('/api/v1/equipment', mapEquipment, undefined, aborted), (error: unknown) => error instanceof Error && error.name === 'AbortError');
});
