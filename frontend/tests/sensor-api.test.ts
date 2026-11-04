import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ApiError, api, mapHistoryPage, mapLatestReading, mapParameterPage, mapRuleVersionPage, mapSensorDetail, mapSensorPage,
} from '../src/lib/api.ts';

const sensor = {
  sensorId: '1', sensorCode: 'SPEED-001', name: '牵引速度',
  equipmentId: '3', equipmentCode: 'CAST-01', equipmentName: '1#结晶牵引机',
  workshopId: '1', workshopName: '车间1 · 铜杆生产',
  location: null, sensorType: '速度', dataSource: 'DEV_DEMO_METADATA',
  enabled: true, parameterCount: 1,
  monitoringStatus: 'UNKNOWN', configurationState: 'READY',
};

const parameter = {
  parameterId: '6', parameterCode: 'speed', name: '牵引速度', dataType: 'DECIMAL',
  unitCode: 'M_PER_MIN', decimalPlaces: 1, samplingIntervalSeconds: null,
  processId: null, enabled: true, ruleVersionId: null, ruleConfigured: false,
};

test('测点分页解析出字符串ID与四态监测状态', () => {
  const page = mapSensorPage({items: [sensor], page: 1, pageSize: 20, total: 1});

  assert.equal(page.items[0].sensorId, '1');
  assert.equal(page.items[0].equipmentId, '3');
  assert.equal(page.items[0].monitoringStatus, 'UNKNOWN');
  assert.equal(page.items[0].configurationState, 'READY');
  assert.equal(page.items[0].parameterCount, 1);
  assert.equal(page.total, 1);
});

test('未知监测状态按响应异常处理，不静默显示为在线', () => {
  assert.throws(() => mapSensorPage({items: [{...sensor, monitoringStatus: 'OFFLINE'}], page: 1, pageSize: 20, total: 1}),
    (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE');
});

test('未知配置状态按响应异常处理', () => {
  assert.throws(() => mapSensorPage({items: [{...sensor, configurationState: 'DONE'}], page: 1, pageSize: 20, total: 1}),
    (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE');
});

test('测点详情带参数摘要与版本', () => {
  const detail = mapSensorDetail({...sensor, purpose: null, hasHistory: false, version: 0, parameters: [parameter]});

  assert.equal(detail.version, 0);
  assert.equal(detail.hasHistory, false);
  assert.equal(detail.parameters.length, 1);
  assert.equal(detail.parameters[0].parameterId, '6');
  assert.equal(detail.parameters[0].ruleVersionId, null);
  assert.equal(detail.parameters[0].ruleConfigured, false);
});

test('详情缺少parameters数组视为响应不完整', () => {
  const {parameters: _drop, ...rest} = {...sensor, purpose: null, hasHistory: false, version: 0};
  assert.throws(() => mapSensorDetail(rest),
    (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE');
});

test('参数分页保留小数位与采样周期', () => {
  const page = mapParameterPage({items: [{...parameter, samplingIntervalSeconds: 30}], page: 1, pageSize: 50, total: 1});

  assert.equal(page.items[0].decimalPlaces, 1);
  assert.equal(page.items[0].samplingIntervalSeconds, 30);
  assert.equal(page.items[0].unitCode, 'M_PER_MIN');
});

test('最新读数区分缺值状态与缺值字段', () => {
  const reading = mapLatestReading({
    parameterId: '6', sensorId: '1', sensorCode: 'SPEED-001', parameterCode: 'speed',
    value: null, valueText: null, unitCode: 'M_PER_MIN',
    qualityStatus: null, limitStatus: null, collectTime: null, refreshTime: null,
    monitoringStatus: 'UNKNOWN', dataSource: 'DEV_DEMO_METADATA', missingFields: ['latestReading'],
  });

  assert.equal(reading.value, null);
  assert.equal(reading.monitoringStatus, 'UNKNOWN');
  assert.deepEqual(reading.missingFields, ['latestReading']);
});

test('读数保持十进制字符串，不转成浮点', () => {
  const reading = mapLatestReading({
    parameterId: '6', sensorId: '1', sensorCode: 'SPEED-001', parameterCode: 'speed',
    value: '12.500000', valueText: null, unitCode: 'M_PER_MIN',
    qualityStatus: 'GOOD', limitStatus: 'NORMAL',
    collectTime: '2026-11-04T10:00:00+08:00', refreshTime: '2026-11-04T10:00:05+08:00',
    monitoringStatus: 'ONLINE', dataSource: 'DEV_DEMO_METADATA', missingFields: [],
  });

  assert.equal(reading.value, '12.500000');
  assert.equal(reading.monitoringStatus, 'ONLINE');
});

test('历史分页解析记录ID与来源系统', () => {
  const page = mapHistoryPage({
    items: [{
      recordId: '9001', value: '12.5', valueText: null, sourceUnit: 'M_PER_MIN',
      qualityCode: 'GOOD', collectTime: '2026-11-04T10:00:00+08:00', sourceSystem: 'SIMULATOR',
    }],
    page: 1, pageSize: 20, total: 1,
  });

  assert.equal(page.items[0].recordId, '9001');
  assert.equal(page.items[0].value, '12.5');
  assert.equal(page.items[0].sourceSystem, 'SIMULATOR');
});

test('规则版本解析出快照与受影响事件', () => {
  const page = mapRuleVersionPage({
    items: [{
      ruleVersionId: '3', ruleVersion: 2, previousRuleVersionId: '2',
      rule: {
        normalLower: '0', normalUpper: '20', alarmLower: '-5', alarmUpper: '30',
        unitCode: 'M_PER_MIN', triggerMode: 'SUSTAINED', durationSeconds: 60, enabled: true,
      },
      effectiveAt: '2026-11-04T10:00:00+08:00', reason: '上调报警上限', operatorName: '设备管理员',
      affectedEventIds: ['7', '9'],
    }],
    page: 1, pageSize: 20, total: 1,
  });

  assert.equal(page.items[0].ruleVersion, 2);
  assert.equal(page.items[0].previousRuleVersionId, '2');
  assert.equal(page.items[0].rule.durationSeconds, 60);
  assert.deepEqual(page.items[0].affectedEventIds, ['7', '9']);
});

test('测点查询按条件拼装地址，空关键词不落到URL上', () => {
  const urls: string[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    urls.push(String(input));
    return new Response(JSON.stringify({
      code: 'OK', message: '', data: {items: [], page: 1, pageSize: 20, total: 0},
      timestamp: '2026-11-04T10:00:00+08:00',
    }));
  }) as typeof fetch;

  return Promise.all([
    api.sensors({equipmentId: '3', keyword: '  ', pageSize: 100}),
    api.sensorHistory('6', {from: '2026-11-04T00:00', to: '2026-11-04T12:00', page: 2, pageSize: 20}),
  ]).then(() => {
    assert.equal(urls[0], '/api/v1/sensors?equipmentId=3&pageSize=100');
    assert.ok(urls[1].startsWith('/api/v1/sensor-parameters/6/history?'));
    assert.ok(urls[1].includes('from=2026-11-04T00%3A00'));
    assert.ok(urls[1].includes('page=2'));
  }).finally(() => { globalThis.fetch = original; });
});

const detailPayload = {
  code: 'OK', message: '', timestamp: '2026-11-04T10:00:00+08:00',
  data: {
    sensorId: '12', sensorCode: 'TEMP-011', name: '炉壳温度',
    equipmentId: '1', equipmentCode: 'SMELT-FUR-01', equipmentName: '1#熔炼炉',
    workshopId: '1', workshopName: '车间1 · 铜杆生产',
    location: '东侧炉壳', sensorType: '温度', dataSource: null, purpose: null,
    enabled: true, parameterCount: 0, hasHistory: false,
    monitoringStatus: 'UNKNOWN', configurationState: 'PENDING',
    version: 1, parameters: [],
  },
};

test('新增测点提交正文字段并解析返回详情', async () => {
  const calls: {url: string; body: unknown}[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({url: String(input), body: init?.body ? JSON.parse(String(init.body)) : null});
    return new Response(JSON.stringify(detailPayload));
  }) as typeof fetch;

  try {
    const result = await api.createSensor({
      sensorCode: 'TEMP-011', name: '炉壳温度', equipmentId: '1',
      sensorType: '温度', location: '东侧炉壳', purpose: null, enabled: true,
    }, {requestId: 'req-1', operatorName: '设备管理员'});

    assert.equal(calls[0].url, '/api/v1/sensors');
    const body = calls[0].body as Record<string, unknown>;
    assert.equal(body.requestId, 'req-1');
    assert.equal(body.sensorCode, 'TEMP-011');
    // 采集来源不由页面提交：新建测点必须保持"待配置"。
    assert.equal('dataSource' in body, false);
    assert.equal(result.data.configurationState, 'PENDING');
    assert.equal(result.data.version, 1);
  } finally {
    globalThis.fetch = original;
  }
});

test('修改测点带上版本号与原因，地址含测点ID', async () => {
  const calls: {url: string; method?: string; body: unknown}[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      url: String(input), method: init?.method,
      body: init?.body ? JSON.parse(String(init.body)) : null,
    });
    return new Response(JSON.stringify({...detailPayload,
      data: {...detailPayload.data, enabled: false, monitoringStatus: 'DISABLED', version: 2}}));
  }) as typeof fetch;

  try {
    const result = await api.updateSensor('12', {
      sensorCode: 'TEMP-011', name: '炉壳温度', equipmentId: '1',
      sensorType: '温度', location: '东侧炉壳', purpose: null, enabled: false,
    }, {requestId: 'req-2', operatorName: '设备管理员', expectedVersion: 1, reason: '工位改造暂停采集'});

    assert.equal(calls[0].url, '/api/v1/sensors/12');
    assert.equal(calls[0].method, 'PUT');
    const body = calls[0].body as Record<string, unknown>;
    assert.equal(body.expectedVersion, 1);
    assert.equal(body.reason, '工位改造暂停采集');
    assert.equal(body.enabled, false);
    assert.equal(result.data.monitoringStatus, 'DISABLED');
    assert.equal(result.data.version, 2);
  } finally {
    globalThis.fetch = original;
  }
});
