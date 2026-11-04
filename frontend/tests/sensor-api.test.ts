import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ApiError, api, mapHistoryPage, mapLatestReading, mapParameterDetail, mapParameterPage,
  mapRuleVersionPage, mapSensorDeleteResult, mapSensorDetail, mapSensorPage,
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

// ---------------------------------------------------------------- P07 新增参数

const parameterDetail = {
  parameterId: '501', sensorId: '12', parameterCode: 'PRESSURE', name: '出口压力',
  dataType: 'DECIMAL', unitCode: 'Pa', decimalPlaces: 2, samplingIntervalSeconds: 60,
  processId: null, enabled: true,
  rule: {
    normalLower: '0.5', normalUpper: '1.5', alarmLower: '0.1', alarmUpper: '2',
    unitCode: 'Pa', triggerMode: 'SUSTAINED', durationSeconds: 30, enabled: true,
  },
  ruleVersionId: '9001', version: 1,
};

const parameterDetailPayload = {
  code: 'OK', message: '成功', data: parameterDetail, timestamp: '2026-11-04T13:40:00+08:00',
};

test('参数详情解析出规则全文与配置版本', () => {
  const detail = mapParameterDetail(parameterDetail);

  assert.equal(detail.parameterId, '501');
  assert.equal(detail.sensorId, '12');
  assert.equal(detail.version, 1);
  assert.equal(detail.ruleVersionId, '9001');
  assert.equal(detail.rule?.normalLower, '0.5');
  assert.equal(detail.rule?.triggerMode, 'SUSTAINED');
  assert.equal(detail.rule?.durationSeconds, 30);
});

test('未配规则时 rule 为 null，不伪造一版空规则', () => {
  const detail = mapParameterDetail({...parameterDetail, rule: null, ruleVersionId: null});

  assert.equal(detail.rule, null);
  assert.equal(detail.ruleVersionId, null);
});

test('新增参数走测点下的参数地址，规则可为 null', async () => {
  const calls: {url: string; method?: string; body: unknown}[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      url: String(input), method: init?.method,
      body: init?.body ? JSON.parse(String(init.body)) : null,
    });
    return new Response(JSON.stringify(parameterDetailPayload));
  }) as typeof fetch;

  try {
    const result = await api.createSensorParameter('12', {
      parameterCode: 'PRESSURE', name: '出口压力', dataType: 'DECIMAL',
      unitCode: null, decimalPlaces: 2, samplingIntervalSeconds: null,
      processId: null, enabled: true, rule: null,
    }, {requestId: 'req-p07', operatorName: '设备管理员'});

    assert.equal(calls[0].url, '/api/v1/sensors/12/parameters');
    assert.equal(calls[0].method, 'POST');
    const body = calls[0].body as Record<string, unknown>;
    assert.equal(body.requestId, 'req-p07');
    assert.equal(body.parameterCode, 'PRESSURE');
    // 可空字段必须显式出现，漏传会被后端判成校验失败。
    assert.equal('unitCode' in body, true);
    assert.equal(body.unitCode, null);
    assert.equal('rule' in body, true);
    assert.equal(body.rule, null);
    assert.equal('dataSource' in body, false);
    assert.equal(result.data.parameterId, '501');
  } finally {
    globalThis.fetch = original;
  }
});

// ---------------------------------------------------------------- P05 删除测点

test('详情解析出删除可行性，有关联时给出阻塞项', () => {
  const detail = mapSensorDetail({
    ...sensor, parameters: [], purpose: null, hasHistory: true, version: 3,
    deletion: {
      allowed: false,
      blockers: [
        {type: 'COLLECTION_DATA', count: 1200, message: '该测点已有 1200 条原始采集记录。'},
      ],
    },
  });

  assert.equal(detail.deletion.allowed, false);
  assert.equal(detail.deletion.blockers[0].type, 'COLLECTION_DATA');
  assert.equal(detail.deletion.blockers[0].count, 1200);
});

test('可删除时 blockers 为空数组而不是 undefined', () => {
  const detail = mapSensorDetail({
    ...sensor, parameters: [], purpose: null, hasHistory: false, version: 1,
    deletion: {allowed: true, blockers: []},
  });

  assert.equal(detail.deletion.allowed, true);
  assert.deepEqual(detail.deletion.blockers, []);
});

test('缺少 deletion 时按不可删除处理，不默认放开入口', () => {
  const detail = mapSensorDetail({...sensor, parameters: [], hasHistory: false, version: 1});

  // 服务端没给结论就不能假设能删，宁可让按钮置灰。
  assert.equal(detail.deletion.allowed, false);
  assert.deepEqual(detail.deletion.blockers, []);
});

test('删除请求走 DELETE，并自带二次确认', async () => {
  const calls: {url: string; method?: string; body: unknown}[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      url: String(input), method: init?.method,
      body: init?.body ? JSON.parse(String(init.body)) : null,
    });
    return new Response(JSON.stringify({
      code: 'OK', message: '成功', data: {sensorId: '12', deleted: true},
      timestamp: '2026-11-04T15:20:00+08:00',
    }));
  }) as typeof fetch;

  try {
    const result = await api.deleteSensor('12', {
      requestId: 'req-p05', operatorName: '设备管理员', expectedVersion: 3, reason: '误建',
    });

    assert.equal(calls[0].url, '/api/v1/sensors/12');
    assert.equal(calls[0].method, 'DELETE');
    const body = calls[0].body as Record<string, unknown>;
    assert.equal(body.requestId, 'req-p05');
    assert.equal(body.expectedVersion, 3);
    assert.equal(body.reason, '误建');
    assert.equal(body.confirmed, true);
    assert.equal(result.data.sensorId, '12');
    assert.equal(result.data.deleted, true);
  } finally {
    globalThis.fetch = original;
  }
});

test('服务未确认删除时报错，不当作成功', () => {
  assert.throws(() => mapSensorDeleteResult({sensorId: '12', deleted: false}),
    (error: unknown) => error instanceof ApiError);
});
