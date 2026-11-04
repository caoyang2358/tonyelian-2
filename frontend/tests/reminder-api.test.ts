import assert from 'node:assert/strict';
import test from 'node:test';
import { ApiError, api, mapReminderSetting } from '../src/lib/api.ts';

const envelope = (data: unknown, code = 'OK') =>
  ({code, message: '请求成功', data, timestamp: '2026-11-04T16:00:00+08:00'});

const setting = (overrides: Record<string, unknown> = {}) => ({
  warrantyLeadDays: 30, maintenanceLeadDays: 30, version: 1,
  updatedAt: '2026-11-03T09:12:44+08:00', ...overrides,
});

test('提醒设置读回两个提前天数、版本与更新时间', () => {
  const value = mapReminderSetting(setting({warrantyLeadDays: 45, maintenanceLeadDays: 90, version: 3}));

  assert.equal(value.warrantyLeadDays, 45);
  assert.equal(value.maintenanceLeadDays, 90);
  assert.equal(value.version, 3);
  assert.equal(value.updatedAt, '2026-11-03T09:12:44+08:00');
});

/** 0 是合法值（到期当天才提醒），不能被当成缺值或非法值挡掉。 */
test('提前天数为0是合法配置', () => {
  const value = mapReminderSetting(setting({warrantyLeadDays: 0, maintenanceLeadDays: 0}));

  assert.equal(value.warrantyLeadDays, 0);
  assert.equal(value.maintenanceLeadDays, 0);
});

test('负数提前天数按响应异常处理，不静默取绝对值', () => {
  assert.throws(() => mapReminderSetting(setting({warrantyLeadDays: -1})),
    (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE');
});

test('版本号缺失时按响应异常处理', () => {
  assert.throws(() => mapReminderSetting({warrantyLeadDays: 30, maintenanceLeadDays: 30}),
    (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE');
});

interface Capture { url: string; method: string; body: unknown }

async function withFetch<T>(capture: Capture, payload: unknown, run: () => Promise<T>,
                            status = 200): Promise<T> {
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: unknown, init?: RequestInit) => {
    capture.url = String(input);
    capture.method = init?.method ?? 'GET';
    capture.body = typeof init?.body === 'string' ? JSON.parse(init.body) : init?.body;
    return new Response(JSON.stringify(payload), {status});
  }) as typeof fetch;
  try {
    return await run();
  } finally {
    globalThis.fetch = original;
  }
}

test('读取提醒设置走顶层共用路径', async () => {
  const capture: Capture = {url: '', method: '', body: null};
  await withFetch(capture, envelope(setting()), () => api.reminderSetting());

  assert.equal(capture.url, '/api/v1/reminder-settings');
  assert.equal(capture.method, 'GET');
});

test('保存提醒设置用PUT并带幂等编号、经办人与当前版本', async () => {
  const capture: Capture = {url: '', method: '', body: null};
  await withFetch(capture, envelope(setting({warrantyLeadDays: 45, maintenanceLeadDays: 90, version: 2})),
    () => api.updateReminderSetting({warrantyLeadDays: 45, maintenanceLeadDays: 90},
      {requestId: 'req-reminder-1', operatorName: '设备管理员', expectedVersion: 1}));

  assert.equal(capture.url, '/api/v1/reminder-settings');
  assert.equal(capture.method, 'PUT');
  assert.deepEqual(capture.body, {
    requestId: 'req-reminder-1', operatorName: '设备管理员', expectedVersion: 1,
    warrantyLeadDays: 45, maintenanceLeadDays: 90,
  });
});

/** 两个管理员同时改：后提交的人必须看到冲突，而不是把自己的值静默盖上去。 */
test('版本冲突返回VERSION_CONFLICT而不是静默覆盖', async () => {
  const capture: Capture = {url: '', method: '', body: null};
  await assert.rejects(
    () => withFetch(capture,
      {code: 'VERSION_CONFLICT', message: '设置已被他人修改，请刷新后重试。',
        data: {currentVersion: 3}, timestamp: '2026-11-04T16:00:00+08:00'},
      () => api.updateReminderSetting({warrantyLeadDays: 30, maintenanceLeadDays: 30},
        {requestId: 'req-reminder-2', operatorName: '设备管理员', expectedVersion: 2}), 409),
    (error: unknown) => error instanceof ApiError
      && error.code === 'VERSION_CONFLICT'
      && error.status === 409);
});
