import assert from 'node:assert/strict';
import test from 'node:test';
import { ApiError, api, mapDeadlinePage } from '../src/lib/api.ts';

const envelope = (data: unknown, code = 'OK') =>
  ({code, message: '请求成功', data, timestamp: '2026-11-04T10:00:00+08:00'});

const row = (overrides: Record<string, unknown> = {}) => ({
  equipmentId: 17, equipmentCode: 'EQUIP-DEMO-002', equipmentName: '1#结晶牵引机',
  workshopId: 1, workshopName: '车间1 · 铜杆生产', responsiblePerson: null,
  reminderType: 'WARRANTY', contractId: null, contractNumber: null,
  purchasedOn: '2024-04-10', commissionedOn: '2024-07-01', expectedLifeYears: 12,
  dueDate: '2027-06-30', remainingDays: 238, expiryState: 'NORMAL',
  missingFields: [],
  ...overrides,
});

test('期限行把设备ID转成字符串并保留三个来源', () => {
  const page = mapDeadlinePage({
    items: [
      row(),
      row({reminderType: 'EXPECTED_LIFE', dueDate: '2036-07-01', remainingDays: 3527}),
      row({reminderType: 'MAINTENANCE', contractId: 18, contractNumber: 'HT-2026-001',
        dueDate: '2026-12-31', remainingDays: 57}),
    ],
    page: 1, pageSize: 20, total: 3,
  });

  assert.equal(page.items[0].equipmentId, '17');
  assert.deepEqual(page.items.map(item => item.reminderType),
    ['WARRANTY', 'EXPECTED_LIFE', 'MAINTENANCE']);
  assert.equal(page.items[2].contractId, '18');
  assert.equal(page.items[2].contractNumber, 'HT-2026-001');
  assert.equal(page.total, 3);
});

test('已超期的剩余天数为负数，不能被非负校验拦掉', () => {
  const page = mapDeadlinePage({
    items: [row({expiryState: 'EXPIRED', remainingDays: -5})],
    page: 1, pageSize: 20, total: 1,
  });

  assert.equal(page.items[0].remainingDays, -5);
  assert.equal(page.items[0].expiryState, 'EXPIRED');
});

/** 缺日期时接口返回 null，页面必须显示"未填"而不是 0 天。 */
test('缺日期时到期日与剩余天数都是null', () => {
  const page = mapDeadlinePage({
    items: [row({dueDate: null, remainingDays: null, expiryState: 'MISSING_DATA',
      missingFields: ['commissionedOn', 'expectedLifeYears']})],
    page: 1, pageSize: 20, total: 1,
  });

  assert.equal(page.items[0].dueDate, null);
  assert.equal(page.items[0].remainingDays, null);
  assert.deepEqual(page.items[0].missingFields, ['commissionedOn', 'expectedLifeYears']);
});

test('未知期限类型按响应异常处理', () => {
  assert.throws(() => mapDeadlinePage({
    items: [row({reminderType: 'QUALITY'})], page: 1, pageSize: 20, total: 1,
  }), (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE');
});

test('未知计算状态按响应异常处理，不静默当成正常', () => {
  assert.throws(() => mapDeadlinePage({
    items: [row({expiryState: 'SOMEDAY'})], page: 1, pageSize: 20, total: 1,
  }), (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE');
});

test('缺资料字段不是数组时按响应异常处理', () => {
  assert.throws(() => mapDeadlinePage({
    items: [row({missingFields: 'warrantyExpiresOn'})], page: 1, pageSize: 20, total: 1,
  }), (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE');
});

async function withFetch<T>(capture: {url: string}, run: () => Promise<T>): Promise<T> {
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: unknown) => {
    capture.url = String(input);
    return new Response(JSON.stringify(envelope({items: [], page: 1, pageSize: 20, total: 0})));
  }) as typeof fetch;
  try {
    return await run();
  } finally {
    globalThis.fetch = original;
  }
}

test('期限查询把类型、状态与分页拼进查询串', async () => {
  const capture = {url: ''};
  await withFetch(capture, () => api.deadlines({
    reminderType: 'WARRANTY', expiryState: 'DUE_SOON', page: 2, pageSize: 20,
  }));

  assert.match(capture.url, /\/api\/v1\/equipment-deadlines\?/);
  assert.match(capture.url, /reminderType=WARRANTY/);
  assert.match(capture.url, /expiryState=DUE_SOON/);
  assert.match(capture.url, /page=2/);
});

test('没有筛选条件时只请求路径本身', async () => {
  const capture = {url: ''};
  await withFetch(capture, () => api.deadlines());

  assert.equal(capture.url, '/api/v1/equipment-deadlines');
});
