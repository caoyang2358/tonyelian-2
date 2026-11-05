import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ApiError, api, mapAttachmentDetail, mapAttachmentPage, requestApi,
} from '../src/lib/api.ts';

const envelope = (data: unknown, code = 'OK') =>
  ({code, message: '请求成功', data, timestamp: '2026-11-04T10:00:00+08:00'});

const row = {
  attachmentId: '12', ownerType: 'EQUIPMENT', ownerId: '3', fileKind: 'MANUAL',
  originalName: '设备手册.pdf', mediaType: 'application/pdf', size: 2048, version: 2,
  updatedAt: '2026-11-04T10:00:00+08:00',
  contentPath: '/api/v1/attachments/12/content',
  downloadPath: '/api/v1/attachments/12/content?download=true',
};

test('附件分页解析出字符串ID、大小与受控地址', () => {
  const page = mapAttachmentPage({items: [row], page: 1, pageSize: 20, total: 1});

  assert.equal(page.items[0].attachmentId, '12');
  assert.equal(page.items[0].ownerType, 'EQUIPMENT');
  assert.equal(page.items[0].fileKind, 'MANUAL');
  assert.equal(page.items[0].size, 2048);
  assert.equal(page.items[0].version, 2);
  assert.equal(page.items[0].contentPath, '/api/v1/attachments/12/content');
  assert.equal(page.total, 1);
});

test('未知附件类别按响应异常处理，不静默当图片', () => {
  assert.throws(() => mapAttachmentDetail({...row, fileKind: 'VIDEO'}),
    (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE');
});

test('缺少受控内容地址视为响应不完整', () => {
  const {contentPath: _drop, ...rest} = row;
  assert.throws(() => mapAttachmentDetail(rest),
    (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE');
});

test('下载地址按attachmentId拼接并保留download参数', () => {
  assert.equal(api.attachmentUrl('12'), '/api/v1/attachments/12/content');
  assert.equal(api.attachmentUrl('12', true), '/api/v1/attachments/12/content?download=true');
});

test('列表请求带上归属对象与可选类别', async () => {
  const original = globalThis.fetch;
  let requested = '';
  globalThis.fetch = (async (input: string | URL | Request) => {
    requested = String(input);
    return new Response(JSON.stringify(envelope({items: [row], page: 2, pageSize: 50, total: 51})));
  }) as typeof fetch;

  try {
    const result = await api.attachments(
      {ownerType: 'EQUIPMENT', ownerId: '3', fileKind: 'MANUAL', page: 2, pageSize: 50});
    assert.equal(result.data.items.length, 1);
    assert.equal(result.data.total, 51);
  } finally {
    globalThis.fetch = original;
  }

  assert.equal(requested,
    '/api/v1/attachments?ownerType=EQUIPMENT&ownerId=3&fileKind=MANUAL&page=2&pageSize=50');
});

test('不指定类别时不带fileKind参数，交给服务端返回全部', async () => {
  const original = globalThis.fetch;
  let requested = '';
  globalThis.fetch = (async (input: string | URL | Request) => {
    requested = String(input);
    return new Response(JSON.stringify(envelope({items: [], page: 1, pageSize: 20, total: 0})));
  }) as typeof fetch;

  try {
    await api.attachments({ownerType: 'MAINTENANCE_CONTRACT', ownerId: '5'});
  } finally {
    globalThis.fetch = original;
  }

  assert.equal(requested, '/api/v1/attachments?ownerType=MAINTENANCE_CONTRACT&ownerId=5');
});

test('上传使用multipart且不手写Content-Type', async () => {
  let sentBody: unknown = null;
  let sentHeaders: Record<string, string> = {};
  const fetcher: typeof fetch = async (_input, init) => {
    sentBody = init?.body;
    sentHeaders = (init?.headers ?? {}) as Record<string, string>;
    return new Response(JSON.stringify(envelope(row)));
  };

  const file = new File([new Uint8Array([1, 2, 3])], '设备手册.pdf', {type: 'application/pdf'});
  const form = new FormData();
  form.append('requestId', 'r-1');
  form.append('operatorName', '设备管理员');
  form.append('ownerType', 'EQUIPMENT');
  form.append('ownerId', '3');
  form.append('fileKind', 'MANUAL');
  form.append('file', file);

  const result = await requestApi('/api/v1/attachments', mapAttachmentDetail,
    {method: 'POST', body: form}, fetcher);

  assert.ok(sentBody instanceof FormData);
  assert.equal(sentHeaders['Content-Type'], undefined, 'multipart 的 Content-Type 必须交给浏览器生成');
  assert.equal((sentBody as FormData).get('ownerType'), 'EQUIPMENT');
  assert.equal((sentBody as FormData).get('fileKind'), 'MANUAL');
  assert.equal(result.data.attachmentId, '12');
});

test('替换提交U字段：版本号转字符串、原因必填', () => {
  const form = new FormData();
  form.append('requestId', 'r-2');
  form.append('operatorName', '设备管理员');
  form.append('expectedVersion', String(2));
  form.append('reason', '厂家补发新版');
  form.append('file', new File([new Uint8Array([4])], 'a.pdf', {type: 'application/pdf'}));

  assert.equal(form.get('expectedVersion'), '2');
  assert.equal(form.get('reason'), '厂家补发新版');
});
