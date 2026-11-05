import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ApiError, mapContractDetail, mapContractPage, requestApi,
} from '../src/lib/api.ts';

const envelope = (data: unknown, code = 'OK') =>
  ({code, message: '请求成功', data, timestamp: '2026-11-04T10:00:00+08:00'});

const detailPayload = {
  contractId: '18', serviceOrganization: '华东维保服务中心', contractNumber: 'HT-001',
  startDate: '2026-01-01', endDate: '2026-12-31', remark: null,
  equipmentIds: [1, '3'], attachmentIds: [], expiryState: 'ACTIVE',
  remainingDays: 57, version: 2, updatedAt: '2026-11-04T10:00:00+08:00',
};

test('合同分页解析出字符串ID、状态与剩余天数', () => {
  const page = mapContractPage({
    items: [{
      contractId: 18, serviceOrganization: '华东维保服务中心', contractNumber: null,
      startDate: null, endDate: '2026-12-31', equipmentCount: 2,
      expiryState: 'EXPIRING_SOON', remainingDays: 12,
    }],
    page: 1, pageSize: 20, total: 1,
  });

  assert.equal(page.items[0].contractId, '18');
  assert.equal(page.items[0].contractNumber, null);
  assert.equal(page.items[0].startDate, null);
  assert.equal(page.items[0].expiryState, 'EXPIRING_SOON');
  assert.equal(page.items[0].remainingDays, 12);
});

/** 已超期的剩余天数是负数，不能被"非负整数"校验拦掉。 */
test('剩余天数允许负数以表示已超期', () => {
  const page = mapContractPage({
    items: [{
      contractId: '19', serviceOrganization: '华东维保', contractNumber: null,
      startDate: null, endDate: '2025-12-31', equipmentCount: 0,
      expiryState: 'EXPIRED', remainingDays: -308,
    }],
    page: 1, pageSize: 20, total: 1,
  });

  assert.equal(page.items[0].remainingDays, -308);
});

test('缺结束日期的合同剩余天数为null而不是0', () => {
  const page = mapContractPage({
    items: [{
      contractId: '20', serviceOrganization: '华东维保', contractNumber: null,
      startDate: null, endDate: null, equipmentCount: 0,
      expiryState: 'UNKNOWN', remainingDays: null,
    }],
    page: 1, pageSize: 20, total: 1,
  });

  assert.equal(page.items[0].remainingDays, null);
  assert.equal(page.items[0].expiryState, 'UNKNOWN');
});

test('未知期限状态按响应异常处理，不静默当成生效中', () => {
  assert.throws(() => mapContractPage({
    items: [{
      contractId: '21', serviceOrganization: '华东维保', contractNumber: null,
      startDate: null, endDate: null, equipmentCount: 0,
      expiryState: 'SOMEDAY', remainingDays: 1,
    }],
    page: 1, pageSize: 20, total: 1,
  }), (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE');
});

test('合同详情把设备与附件ID统一成字符串数组', () => {
  const detail = mapContractDetail(detailPayload);

  assert.deepEqual(detail.equipmentIds, ['1', '3']);
  assert.deepEqual(detail.attachmentIds, []);
  assert.equal(detail.expiryState, 'ACTIVE');
  assert.equal(detail.version, 2);
});

test('详情缺少attachmentIds时按响应异常处理', () => {
  const {attachmentIds: _omitted, ...withoutAttachments} = detailPayload;
  assert.throws(() => mapContractDetail(withoutAttachments),
    (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE');
});

test('新增合同提交W字段与当前设备关联', async () => {
  let sent: Record<string, unknown> = {};
  const fetcher: typeof fetch = async (_input, init) => {
    sent = JSON.parse(String(init?.body));
    return new Response(JSON.stringify(envelope(detailPayload)));
  };

  await requestApi('/api/v1/maintenance-contracts', mapContractDetail, {
    method: 'POST',
    body: {
      requestId: 'req-1', operatorName: '设备管理员',
      serviceOrganization: '华东维保服务中心', contractNumber: null,
      startDate: null, endDate: null, remark: null, equipmentIds: ['16'],
    },
  }, fetcher);

  assert.equal(sent.requestId, 'req-1');
  assert.equal(sent.serviceOrganization, '华东维保服务中心');
  assert.deepEqual(sent.equipmentIds, ['16']);
});

test('更新合同时带上expectedVersion做乐观锁', async () => {
  let sent: Record<string, unknown> = {};
  const fetcher: typeof fetch = async (_input, init) => {
    sent = JSON.parse(String(init?.body));
    return new Response(JSON.stringify(envelope(detailPayload)));
  };

  await requestApi('/api/v1/maintenance-contracts/18', mapContractDetail, {
    method: 'PUT',
    body: {
      requestId: 'req-2', operatorName: '设备管理员', expectedVersion: 2,
      serviceOrganization: '华东维保服务中心', contractNumber: 'HT-001',
      startDate: '2026-01-01', endDate: '2026-12-31', remark: null, equipmentIds: [],
    },
  }, fetcher);

  assert.equal(sent.expectedVersion, 2);
  assert.deepEqual(sent.equipmentIds, []);
});
