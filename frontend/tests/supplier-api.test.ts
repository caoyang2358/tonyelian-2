import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ApiError, mapSupplierDetail, mapSupplierPage, requestApi,
} from '../src/lib/api.ts';

const envelope = (data: unknown, code = 'OK') =>
  ({code, message: '请求成功', data, timestamp: '2026-11-04T10:00:00+08:00'});

test('供应商分页解析出字符串ID与关联设备数', () => {
  const page = mapSupplierPage({
    items: [{supplierId: '7', name: '华东机电', contactName: null, phone: '021-0000', equipmentCount: 2}],
    page: 1, pageSize: 20, total: 1,
  });

  assert.equal(page.items[0].supplierId, '7');
  assert.equal(page.items[0].contactName, null);
  assert.equal(page.items[0].equipmentCount, 2);
  assert.equal(page.total, 1);
});

test('供应商详情把equipmentIds统一成字符串数组', () => {
  const detail = mapSupplierDetail({
    supplierId: 7, name: '华东机电', contactName: '张工', phone: null, remark: null,
    equipmentIds: [1, '2'], version: 3, updatedAt: '2026-11-04T09:00:00+08:00',
  });

  assert.deepEqual(detail.equipmentIds, ['1', '2']);
  assert.equal(detail.version, 3);
});

test('详情缺少equipmentIds时按响应异常处理，不静默当空数组', () => {
  assert.throws(() => mapSupplierDetail({supplierId: '7', name: '华东机电', version: 1}),
    (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE');
});

test('新增供应商提交W字段与当前设备关联', async () => {
  let sent: Record<string, unknown> = {};
  const fetcher: typeof fetch = async (_input, init) => {
    sent = JSON.parse(String(init?.body));
    return new Response(JSON.stringify(envelope({
      supplierId: '7', name: '华东机电', contactName: null, phone: null, remark: null,
      equipmentIds: ['16'], version: 1, updatedAt: '2026-11-04T10:00:00+08:00',
    })));
  };

  const result = await requestApi('/api/v1/suppliers', mapSupplierDetail, {
    method: 'POST',
    body: {
      requestId: 'r-1', operatorName: '设备管理员', name: '华东机电',
      contactName: null, phone: null, remark: null, equipmentIds: ['16'],
    },
  }, fetcher);

  assert.equal(sent.requestId, 'r-1');
  assert.equal(sent.operatorName, '设备管理员');
  assert.deepEqual(sent.equipmentIds, ['16']);
  assert.deepEqual(result.data.equipmentIds, ['16']);
});

test('更新供应商携带expectedVersion且允许解除全部关联', async () => {
  let sent: Record<string, unknown> = {};
  const fetcher: typeof fetch = async (_input, init) => {
    sent = JSON.parse(String(init?.body));
    return new Response(JSON.stringify(envelope({
      supplierId: '7', name: '华东机电', contactName: null, phone: null, remark: null,
      equipmentIds: [], version: 4, updatedAt: '2026-11-04T10:05:00+08:00',
    })));
  };

  const result = await requestApi('/api/v1/suppliers/7', mapSupplierDetail, {
    method: 'PUT',
    body: {
      requestId: 'r-2', operatorName: '设备管理员', expectedVersion: 3,
      name: '华东机电', contactName: null, phone: null, remark: null, equipmentIds: [],
    },
  }, fetcher);

  assert.equal(sent.expectedVersion, 3);
  assert.deepEqual(sent.equipmentIds, []);
  assert.deepEqual(result.data.equipmentIds, []);
});

test('关联了不存在设备时字段错误可被页面定位', async () => {
  const fetcher: typeof fetch = async () => new Response(JSON.stringify({
    code: 'VALIDATION_ERROR', message: '请求参数校验未通过。',
    data: {fields: {equipmentIds: '以下设备不存在或不是生产设备：999'}},
    timestamp: '2026-11-04T10:00:00+08:00',
  }), {status: 400});

  await assert.rejects(
    requestApi('/api/v1/suppliers', mapSupplierDetail, {method: 'POST', body: {}}, fetcher),
    (error: unknown) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.status, 400);
      assert.match(error.fields.equipmentIds, /999/);
      return true;
    },
  );
});
