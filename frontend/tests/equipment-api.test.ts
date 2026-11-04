import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ApiError, mapChangeLogPage, mapDeleteResult, mapEquipmentDetail, mapEquipmentPage,
  mapEquipmentRows, mapReferenceData, requestApi,
} from '../src/lib/api.ts';

const envelope = (data: unknown, code = 'OK') => ({code, message: '请求成功', data, timestamp: '2026-11-03T08:00:00+08:00'});

const listRow = {
  id: 16, code: 'EQUIP-001', name: '示例设备', workshopId: 1, workshopName: '车间1',
  responsiblePerson: null, enabled: true,
  typeCode: 'OTHER', typeName: '其他生产设备', managementStatus: 'IN_USE', version: 4,
};

const detailBody = {
  id: '16', code: 'EQUIP-001', name: '示例设备', workshopId: '1', workshopName: '车间1',
  responsiblePerson: null, enabled: true, version: 4,
  profile: {
    typeCode: 'OTHER', model: 'MODEL-X', processId: null, installationLocation: '东侧',
    manufacturer: '某厂', factorySerialNo: null, purchasedOn: '2024-03-01',
    commissionedOn: null, expectedLifeYears: 15, warrantyExpiresOn: null,
    managementStatus: 'IN_USE',
    technicalParameters: [
      {key: 'temperature', label: '熔体温度', valueType: 'DECIMAL', value: '1080.5', unitLabel: '摄氏度'},
      {key: 'online', label: '是否在线', valueType: 'BOOLEAN', value: true, unitLabel: null},
    ],
  },
  missingFields: ['profile.model'],
  deletion: {allowed: false, blockers: [{type: 'SENSOR', count: 1, message: '该设备已关联 1 个测点。'}]},
};

test('列表项解析保留待补档设备的空类型与空状态', () => {
  const rows = mapEquipmentRows([listRow, {...listRow, id: 3, typeCode: null, typeName: null, managementStatus: null, version: 0}]);
  assert.equal(rows[0].id, '16');
  assert.equal(rows[0].workshopId, '1');
  assert.equal(rows[0].typeName, '其他生产设备');
  assert.equal(rows[0].version, 4);
  assert.equal(rows[1].typeCode, null);
  assert.equal(rows[1].managementStatus, null);
  assert.equal(rows[1].version, 0);
});

test('无分页参数时元数据为null，显式分页时保留数值', () => {
  const plain = mapEquipmentPage({items: [listRow]});
  assert.equal(plain.page, null); assert.equal(plain.total, null);
  const paged = mapEquipmentPage({items: [listRow], page: 2, pageSize: 20, total: 41});
  assert.equal(paged.page, 2); assert.equal(paged.pageSize, 20); assert.equal(paged.total, 41);
});

test('详情解析出档案、技术参数、缺失字段与删除阻塞项', () => {
  const detail = mapEquipmentDetail(detailBody);
  assert.equal(detail.version, 4);
  assert.equal(detail.profile.purchasedOn, '2024-03-01');
  assert.equal(detail.profile.commissionedOn, null);
  assert.equal(detail.profile.technicalParameters[0].value, '1080.5');
  assert.equal(detail.profile.technicalParameters[1].value, true);
  assert.deepEqual(detail.missingFields, ['profile.model']);
  assert.equal(detail.deletion.allowed, false);
  assert.equal(detail.deletion.blockers[0].type, 'SENSOR');
});

test('详情缺档案时按空档案处理，版本为0', () => {
  const detail = mapEquipmentDetail({...detailBody, profile: null, version: 0, missingFields: ['profile.typeCode'], deletion: {allowed: false, blockers: []}});
  assert.equal(detail.version, 0);
  assert.equal(detail.profile.typeCode, null);
  assert.deepEqual(detail.profile.technicalParameters, []);
});

test('变更记录按契约映射版本与操作人', () => {
  const page = mapChangeLogPage({items: [{id: '9', operation: 'UPDATE', before: null, after: {code: 'X'}, reason: '补充型号', operatorName: '张工', recordedAt: '2026-11-03T23:11:19+08:00', requestId: 'rid', versionBefore: 1, versionAfter: 2}], page: 1, pageSize: 20, total: 1});
  assert.equal(page.items[0].operation, 'UPDATE');
  assert.equal(page.items[0].versionBefore, 1);
  assert.equal(page.items[0].versionAfter, 2);
  assert.equal(page.items[0].reason, '补充型号');
  assert.equal(page.total, 1);
});

test('参考数据把空工序列表当作空数组', () => {
  const reference = mapReferenceData({
    workshops: [{id: '1', code: 'WS', name: '车间1', enabled: true}],
    processes: [], units: [], equipmentTypes: [{code: 'OTHER', name: '其他'}],
    equipmentManagementStatuses: [{code: 'IN_USE', name: '在用'}],
  });
  assert.equal(reference.workshops[0].id, '1');
  assert.deepEqual(reference.processes, []);
  assert.equal(reference.equipmentTypes[0].code, 'OTHER');
});

test('写请求使用POST并携带JSON请求体', async () => {
  const captured = {url: '', init: {} as RequestInit};
  const fetcher: typeof fetch = async (input, init) => {
    captured.url = String(input);
    captured.init = init ?? {};
    return new Response(JSON.stringify(envelope(detailBody)), {status: 200});
  };
  const values = {
    code: 'EQUIP-001', name: '示例设备', workshopId: 1, responsiblePerson: null, enabled: true,
    profile: {typeCode: 'OTHER', model: null, processId: null, installationLocation: null, manufacturer: null, factorySerialNo: null, purchasedOn: null, commissionedOn: null, expectedLifeYears: null, warrantyExpiresOn: null, managementStatus: 'IN_USE' as const, technicalParameters: []},
  };
  const result = await requestApi('/api/v1/equipment', mapEquipmentDetail, {
    method: 'POST', body: {...values, requestId: 'rid-1', operatorName: '张工'},
  }, fetcher);
  assert.equal(result.data.id, '16');
  assert.equal(captured.init.method, 'POST');
  assert.equal((captured.init.headers as Record<string, string>)['Content-Type'], 'application/json');
  const sent = JSON.parse(String(captured.init.body));
  assert.equal(sent.requestId, 'rid-1');
  assert.equal(sent.profile.technicalParameters.length, 0);
  assert.equal(sent.profile.purchasedOn, null, '可空字段必须显式传 null 而不是被省略');
});

test('校验失败把字段提示暴露给页面', async () => {
  const fetcher: typeof fetch = async () => new Response(JSON.stringify({
    code: 'VALIDATION_ERROR', message: '请求参数校验未通过。',
    data: {fields: {'profile.typeCode': '设备类型为必填项。', code: '设备编码为必填项。'}},
    timestamp: '2026-11-03T08:00:00+08:00',
  }), {status: 400});
  await assert.rejects(requestApi('/api/v1/equipment', mapEquipmentDetail, {method: 'POST', body: {}}, fetcher), (error: unknown) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.status, 400);
    assert.equal(error.fields['profile.typeCode'], '设备类型为必填项。');
    assert.equal(error.fields.code, '设备编码为必填项。');
    return true;
  });
});

test('版本冲突保留服务器当前版本供页面提示', async () => {
  const fetcher: typeof fetch = async () => new Response(JSON.stringify({
    code: 'VERSION_CONFLICT', message: '记录已被他人修改，请重新读取后再提交。',
    data: {expectedVersion: 1, currentVersion: 4}, timestamp: '2026-11-03T08:00:00+08:00',
  }), {status: 409});
  await assert.rejects(requestApi('/api/v1/equipment/16', mapEquipmentDetail, {method: 'PUT', body: {}}, fetcher), (error: unknown) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.code, 'VERSION_CONFLICT');
    assert.equal(error.detail?.currentVersion, 4);
    return true;
  });
});
test('删除结果只认 deleted 为真，否则视为失败', () => {
  assert.deepEqual(mapDeleteResult({id: '16', deleted: true}), {id: '16', deleted: true});
  assert.throws(() => mapDeleteResult({id: '16', deleted: false}), (error: unknown) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.code, 'INVALID_RESPONSE');
    return true;
  });
});

test('删除请求使用DELETE并强制携带二次确认', async () => {
  let captured: {url: string; init: RequestInit} | null = null;
  const fetcher: typeof fetch = async (input, init) => {
    captured = {url: String(input), init: init ?? {}};
    return new Response(JSON.stringify(envelope({id: '16', deleted: true})), {status: 200});
  };
  const result = await requestApi('/api/v1/equipment/16', mapDeleteResult, {
    method: 'DELETE',
    body: {requestId: 'rid-del', operatorName: '张工', expectedVersion: 4, reason: '重复建账', confirmed: true},
  }, fetcher);
  assert.equal(result.data.deleted, true);
  assert.equal(captured!.url, '/api/v1/equipment/16');
  assert.equal(captured!.init.method, 'DELETE');
  const sent = JSON.parse(String(captured!.init.body));
  assert.equal(sent.confirmed, true);
  assert.equal(sent.expectedVersion, 4);
  assert.equal(sent.reason, '重复建账');
});

test('有关联时删除返回409并带回阻塞明细', async () => {
  const fetcher: typeof fetch = async () => new Response(JSON.stringify({
    code: 'RELATED_DATA_EXISTS', message: '该设备仍有关联业务数据，不能删除。',
    data: {blockers: [{type: 'REPAIR', count: 2, message: '该设备已关联 2 条维修工单。'}]},
    timestamp: '2026-11-04T08:00:00+08:00',
  }), {status: 409});
  await assert.rejects(
    requestApi('/api/v1/equipment/16', mapDeleteResult, {method: 'DELETE', body: {}}, fetcher),
    (error: unknown) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.code, 'RELATED_DATA_EXISTS');
      assert.equal(error.status, 409);
      assert.equal((error.detail?.blockers as Array<{type: string}>)[0].type, 'REPAIR');
      return true;
    },
  );
});
