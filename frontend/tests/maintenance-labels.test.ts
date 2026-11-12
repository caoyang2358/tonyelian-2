import assert from 'node:assert/strict';
import test from 'node:test';
import {
  actionTypeLabels, endReasonLabels, formatUnit, parameterText, parameterCodeLabels,
} from '../src/features/maintenance/labels.ts';

// 后端返回的是稳定英文枚举码，页面必须全部翻成中文；
// 这里盯的是「某个码没收录就会原样漏到界面上」这类回归。

test('流转动作覆盖库里实际写入的环节码', () => {
  for (const code of ['CREATE', 'SUBMIT', 'REVIEW', 'REPAIR_RESULT', 'ACCEPTANCE']) {
    assert.ok(actionTypeLabels[code], `缺少动作翻译：${code}`);
    assert.doesNotMatch(actionTypeLabels[code], /^[A-Z_]+$/, `${code} 仍是英文码`);
  }
});

test('参数名优先用中文名，缺失时按编码兜底', () => {
  assert.equal(parameterText('temperature', '上部温度'), '上部温度');
  assert.equal(parameterText('temperature'), '温度');
  assert.equal(parameterText('pressure'), '压力');
});

test('参数编码兜底表不含英文原值', () => {
  for (const [code, name] of Object.entries(parameterCodeLabels)) {
    assert.doesNotMatch(name, /^[a-z]+$/i, `${code} 的显示值仍是英文：${name}`);
  }
});

test('单位编码转常用符号，未收录的保持原样', () => {
  assert.equal(formatUnit('C'), '℃');
  assert.equal(formatUnit('PCT'), '%');
  assert.equal(formatUnit('M_PER_MIN'), 'm/min');
  assert.equal(formatUnit('Pa'), 'Pa');
  assert.equal(formatUnit(null), '');
});

test('结束原因翻译成业务说法', () => {
  assert.equal(endReasonLabels.RECOVERED, '自动恢复');
  assert.equal(endReasonLabels.RULE_CHANGED, '规则变更');
});
