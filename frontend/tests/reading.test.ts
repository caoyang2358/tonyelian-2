import assert from 'node:assert/strict';
import test from 'node:test';
import { formatReadingValue } from '../src/lib/reading.ts';

// 读数在库里是 DECIMAL(20,6)，读回一律带 6 位小数。页面按参数精度显示，
// 所以 0/1/2/3 位都要能正确收拢，且不能因为转 Number 丢掉大数末位。

test('按参数精度补齐小数位', () => {
  assert.equal(formatReadingValue('1092.000000', 0), '1092');
  assert.equal(formatReadingValue('1092.000000', 1), '1092.0');
  assert.equal(formatReadingValue('1092.000000', 2), '1092.00');
  assert.equal(formatReadingValue('1092.5', 3), '1092.500');
});

test('多余小数位四舍五入，且能连续进位', () => {
  assert.equal(formatReadingValue('9.99', 1), '10.0');
  assert.equal(formatReadingValue('0.999999', 2), '1.00');
  assert.equal(formatReadingValue('1092.5678', 2), '1092.57');
  assert.equal(formatReadingValue('99.95', 1), '100.0');
});

test('负数按绝对值进位，负零不额外带符号', () => {
  assert.equal(formatReadingValue('-3.250000', 1), '-3.3');
  assert.equal(formatReadingValue('-1.005', 2), '-1.01');
  assert.equal(formatReadingValue('-0.000000', 2), '0.00');
});

test('省略整数位或小数位也能读出', () => {
  assert.equal(formatReadingValue('.5', 2), '0.50');
  assert.equal(formatReadingValue('5.', 2), '5.00');
  assert.equal(formatReadingValue('0', 2), '0.00');
  assert.equal(formatReadingValue('0001092.000000', 0), '1092');
});

test('超出 Number 精度的大数不丢末位', () => {
  assert.equal(formatReadingValue('123456789012345678.000000', 0), '123456789012345678');
  assert.equal(formatReadingValue('123456789012345678', 2), '123456789012345678.00');
});

test('文本型读数原样返回，空值返回 null', () => {
  assert.equal(formatReadingValue('开', 2), '开');
  assert.equal(formatReadingValue('N/A', 1), 'N/A');
  assert.equal(formatReadingValue(null, 2), null);
  assert.equal(formatReadingValue(undefined, 2), null);
  assert.equal(formatReadingValue('   ', 2), null);
});

test('精度参数越界时收敛到 0—6 位', () => {
  assert.equal(formatReadingValue('1.2345678', 9), '1.234568');
  assert.equal(formatReadingValue('1.6', -1), '2');
  assert.equal(formatReadingValue('1.6', Number.NaN), '2');
});
