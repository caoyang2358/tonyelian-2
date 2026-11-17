import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ApiError, mapEnergyPriceCorrectionResult, mapEnergyPricePage, mapEnergyPriceWriteResult,
} from '../src/lib/api.ts';
import { formatPrice, priceError } from '../src/features/energy/energyPriceFormat.ts';

const row = (overrides: Record<string, unknown> = {}) => ({
  priceId: 9201,
  energyType: 'ELECTRICITY',
  energyTypeName: '电',
  workshopId: 2,
  workshopName: '车间2 · 铜丝拉制',
  scope: 'WORKSHOP',
  effectiveDate: '2027-01-01',
  unitCode: 'kWh',
  unitPriceYuanPerUnit: '0.8000000000',
  version: 0,
  current: true,
  supersedesPriceId: null,
  operatorName: '张工',
  reason: null,
  recordedAt: '2027-01-25T09:00:00+08:00',
  ...overrides,
});

const page = (data: Record<string, unknown>) =>
  mapEnergyPricePage({items: [], page: 1, pageSize: 20, total: 0, matchedPrice: null, ...data});

test('价格分页把数字ID转成字符串，并识别车间专用范围', () => {
  const result = page({items: [row()], total: 1});
  const item = result.items[0];
  assert.equal(item.priceId, '9201');
  assert.equal(item.workshopId, '2');
  assert.equal(item.scope, 'WORKSHOP');
  assert.equal(item.unitPriceYuanPerUnit, '0.8000000000');
  assert.equal(item.current, true);
});

test('车间为空时判定为通用范围', () => {
  const result = page({
    items: [row({workshopId: null, workshopName: null, scope: 'GENERAL'})],
    total: 1,
  });
  assert.equal(result.items[0].workshopId, null);
  assert.equal(result.items[0].scope, 'GENERAL');
});

test('带实际日时解析出匹配价格及其范围', () => {
  const result = page({
    matchedPrice: {
      priceId: 9100, scope: 'GENERAL', effectiveDate: '2026-12-01',
      unitCode: 'kWh', unitPriceYuanPerUnit: '0.9000000000', version: 0,
    },
  });
  assert.equal(result.matchedPrice?.priceId, '9100');
  assert.equal(result.matchedPrice?.scope, 'GENERAL');
});

test('没有匹配价时保持空，不用零价冒充', () => {
  const result = page({});
  assert.equal(result.matchedPrice, null);
});

test('未知能源类型按响应异常处理，不静默放过', () => {
  assert.throws(
    () => page({items: [row({energyType: 'STEAM'})]}),
    (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE',
  );
});

test('新增结果解析受影响范围，结束日为空表示一直生效', () => {
  const result = mapEnergyPriceWriteResult({
    priceId: 9202, version: 0, energyType: 'ELECTRICITY', workshopId: '2', scope: 'WORKSHOP',
    effectiveDate: '2027-02-01', unitCode: 'kWh', unitPriceYuanPerUnit: '1.1000000000',
    costRunId: null,
    affectedScope: {from: '2027-02-01', to: null, workshopIds: ['2']},
  });
  assert.equal(result.priceId, '9202');
  assert.equal(result.affectedScope.to, null);
  assert.deepEqual(result.affectedScope.workshopIds, ['2']);
  assert.equal(result.costRunId, null);
});

test('更正结果保留原价格与新价格的替代关系', () => {
  const result = mapEnergyPriceCorrectionResult({
    previousPriceId: 9201, priceId: 9203, version: 0, supersedesPriceId: 9201, changeLogId: 9602,
    affectedScope: {from: '2027-01-01', to: '2027-01-31', workshopIds: ['2']},
    costRunId: null, status: 'PENDING',
  });
  assert.equal(result.previousPriceId, '9201');
  assert.equal(result.supersedesPriceId, '9201');
  assert.equal(result.changeLogId, '9602');
  assert.equal(result.affectedScope.to, '2027-01-31');
  assert.equal(result.status, 'PENDING');
});

test('未知核算状态按响应异常处理', () => {
  assert.throws(
    () => mapEnergyPriceCorrectionResult({
      previousPriceId: 1, priceId: 2, version: 0, supersedesPriceId: 1, changeLogId: 3,
      affectedScope: {from: '2027-01-01', to: null, workshopIds: []},
      costRunId: null, status: 'RUNNING',
    }),
    (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE',
  );
});

test('单价显示去掉多余尾零但保留两位小数', () => {
  assert.equal(formatPrice('0.8000000000'), '0.80');
  assert.equal(formatPrice('1.1000000000'), '1.10');
  assert.equal(formatPrice('0'), '0.00');
  assert.equal(formatPrice('0.9500000000'), '0.95');
  assert.equal(formatPrice('12'), '12.00');
});

test('单价预校验与后端同口径', () => {
  assert.equal(priceError('0'), null);
  assert.equal(priceError('0.8000000000'), null);
  assert.equal(priceError(''), '单价为必填项。');
  assert.match(priceError('-0.5') ?? '', /不小于 0/);
  assert.match(priceError('abc') ?? '', /不小于 0/);
  assert.match(priceError('1.12345678901') ?? '', /10 位小数/);
});
