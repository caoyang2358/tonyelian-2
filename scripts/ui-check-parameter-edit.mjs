// 用 Chrome DevTools Protocol 驱动无头浏览器，验证参数"修改"流程（S1-P08）。
// Node 22 自带全局 WebSocket，无需安装依赖。
//
//   node scripts/ui-check-parameter-edit.mjs
//
// 前置：后端 18280、前端 5174、Chrome 以 --remote-debugging-port=9337 启动。
// 脚本自己造测点和参数，结束时用 MySQL 清理（改过规则的参数会阻止测点删除，所以不能走 P05）。
import { writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

import {loadDbConfig, mysqlArgs, preflightDatabase} from './db-env.mjs';

const PORT = Number(process.env.CDP_PORT ?? 9337);
const OUT = process.env.SHOT ?? 'C:/Users/29514/AppData/Local/Temp/sensor-parameter-edit.png';
const API = process.env.API_BASE ?? 'http://127.0.0.1:18280/api/v1';

// ---------------------------------------------------------------- 后端与数据库
async function api(method, path, body) {
  const response = await fetch(API + path, {
    method,
    headers: body ? {'Content-Type': 'application/json'} : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = await response.json().catch(() => ({}));
  return {status: response.status, payload};
}

// 先探库再动数据：端口不对时立刻退出，不留孤儿测点/参数（清理只能走 MySQL）。
const DB = loadDbConfig();
preflightDatabase(DB);

function sql(statement) {
  return execFileSync(DB.bin, mysqlArgs(DB, statement), {encoding: 'utf8'}).trim();
}

const stamp = Date.now().toString().slice(-6);
const sensorCode = `UI-P08-${stamp}`;

// ---------------------------------------------------------------- 造数据
const equipmentList = await api('GET', '/equipment?pageSize=1');
const equipment = equipmentList.payload?.data?.items?.[0];
if (!equipment) throw new Error('库里没有设备，无法造测点');

const createdSensor = await api('POST', '/sensors', {
  requestId: crypto.randomUUID(), operatorName: '界面验收', reason: 'P08 界面验收',
  sensorCode, name: '配置修改验收测点', equipmentId: equipment.id,
  sensorType: '压力', location: '验收位置', purpose: null, enabled: true,
});
if (createdSensor.status !== 200) throw new Error(`造测点失败：${JSON.stringify(createdSensor.payload)}`);
const sensorId = String(createdSensor.payload.data.sensorId);

const createdParameter = await api('POST', `/sensors/${sensorId}/parameters`, {
  requestId: crypto.randomUUID(), operatorName: '界面验收', reason: null,
  parameterCode: 'PRESSURE', name: '出口压力', dataType: 'DECIMAL',
  unitCode: 'Pa', decimalPlaces: 2, samplingIntervalSeconds: 60,
  processId: null, enabled: true,
  rule: {
    normalLower: '0', normalUpper: '100', alarmLower: '-10', alarmUpper: '150',
    unitCode: 'Pa', triggerMode: 'SUSTAINED', durationSeconds: 30, enabled: true,
  },
});
if (createdParameter.status !== 200) throw new Error(`造参数失败：${JSON.stringify(createdParameter.payload)}`);
const parameterId = String(createdParameter.payload.data.parameterId);

// ---------------------------------------------------------------- 浏览器
async function waitForTargets() {
  for (let i = 0; i < 40; i += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${PORT}/json/list`);
      const targets = await response.json();
      const page = targets.find(item => item.type === 'page');
      if (page) return page;
    } catch { /* 浏览器还没起来 */ }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error('未找到可调试页面');
}

const page = await waitForTargets();
const socket = new WebSocket(page.webSocketDebuggerUrl);
const pending = new Map();
let nextId = 0;

socket.addEventListener('message', event => {
  const message = JSON.parse(event.data);
  const resolve = pending.get(message.id);
  if (resolve) { pending.delete(message.id); resolve(message); }
});

function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++nextId;
    pending.set(id, resolve);
    socket.send(JSON.stringify({id, method, params}));
    setTimeout(() => reject(new Error(`CDP 超时：${method}`)), 15000);
  });
}

await new Promise(resolve => socket.addEventListener('open', resolve));
await send('Runtime.enable');
await send('Page.enable');

async function evaluate(expression) {
  const result = await send('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
  if (result.result?.exceptionDetails) throw new Error(JSON.stringify(result.result.exceptionDetails));
  return result.result?.result?.value;
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const report = [];
const check = (name, ok, detail = '') => {
  report.push({name, ok, detail});
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${ok || !detail ? '' : `  -> ${detail}`}`);
};

// React 受控组件要改原生 setter 再派发事件，否则 state 不会更新。
const FILL_HELPER = `
  window.__fieldByLabel = function(text) {
    return [...document.querySelectorAll('.eq-form-field')]
      .find(f => (f.querySelector('span')?.textContent ?? '').includes(text));
  };
  window.__fillLabel = function(text, value) {
    const el = window.__fieldByLabel(text)?.querySelector('input,select');
    if (!el) return false;
    const proto = el.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, value);
    el.dispatchEvent(new Event('input', {bubbles: true}));
    el.dispatchEvent(new Event('change', {bubbles: true}));
    return true;
  };
  true;
`;

await send('Page.navigate', {url: 'http://127.0.0.1:5174/'});
await sleep(3500);
await evaluate(FILL_HELPER);

await evaluate(`(() => {
  const rows = [...document.querySelectorAll('tbody tr')];
  const row = rows.find(r => r.innerText.includes(${JSON.stringify(equipment.equipmentCode ?? equipment.code ?? '')}))
    ?? rows[0];
  row?.querySelector('.eq-row-link')?.click();
})()`);
await sleep(1800);

await evaluate(`[...document.querySelectorAll('.eq-tab')].find(t => t.textContent.trim() === '测点')?.click()`);
await sleep(2200);

const found = await evaluate(`(() => {
  const head = [...document.querySelectorAll('.eq-sensor-head')]
    .find(h => h.innerText.includes(${JSON.stringify(sensorCode)}));
  head?.click();
  return Boolean(head);
})()`);
check('验收测点出现在测点页签里', found === true);
await sleep(2200);

// ---------------------------------------------------------------- 打开修改弹层
const editOpened = await evaluate(`(() => {
  const button = [...document.querySelectorAll('.eq-sensor-param-actions .eq-row-link')]
    .find(b => b.textContent.trim() === '修改');
  button?.click();
  return Boolean(button);
})()`);
check('参数行提供“修改”入口', editOpened === true);
await sleep(900);

const dialog = await evaluate(`(() => {
  const wrap = document.querySelector('.eq-dialog-wrap');
  if (!wrap) return null;
  const fields = [...wrap.querySelectorAll('.eq-form-field')];
  const codeInput = fields.find(f => (f.querySelector('span')?.textContent ?? '').includes('参数编码'))
    ?.querySelector('input');
  const nameInput = fields.find(f => (f.querySelector('span')?.textContent ?? '').includes('参数名称'))
    ?.querySelector('input');
  const typeSelect = fields.find(f => (f.querySelector('span')?.textContent ?? '').includes('数据类型'))
    ?.querySelector('select');
  return {
    label: wrap.getAttribute('aria-label'),
    head: wrap.querySelector('.eq-dialog-head h2')?.textContent?.trim(),
    sub: wrap.querySelector('.eq-dialog-head p')?.textContent?.trim(),
    codeDisabled: Boolean(codeInput?.disabled),
    codeValue: codeInput?.value,
    nameValue: nameInput?.value,
    typeDisabled: Boolean(typeSelect?.disabled),
    ruleBlock: Boolean(wrap.querySelector('.eq-rule-block')),
    toggleRuleSwitch: [...wrap.querySelectorAll('.eq-sensor-switch')]
      .some(s => s.textContent.includes('同时配置告警规则')),
    bodyText: wrap.innerText,
    submitText: wrap.querySelector('.eq-dialog-foot .is-primary')?.textContent?.trim(),
  };
})()`);
check('弹层标题为“修改参数”', dialog?.head === '修改参数', dialog?.head);
check('副标题带当前配置版本', /当前配置版本 v\d+/.test(dialog?.sub ?? ''), dialog?.sub);
check('参数编码已预填且只读', dialog?.codeValue === 'PRESSURE' && dialog?.codeDisabled === true,
  `${dialog?.codeValue} disabled=${dialog?.codeDisabled}`);
check('数据类型只读', dialog?.typeDisabled === true, String(dialog?.typeDisabled));
check('名称已按当前值预填', dialog?.nameValue === '出口压力', dialog?.nameValue);
check('规则区直接展开，不需要再勾选', dialog?.ruleBlock === true && dialog?.toggleRuleSwitch === false,
  `block=${dialog?.ruleBlock} toggle=${dialog?.toggleRuleSwitch}`);
check('说明写明改阈值会追加新版本', (dialog?.bodyText ?? '').includes('追加一版新规则'),
  (dialog?.bodyText ?? '').slice(0, 120));
check('按钮文案为“保存配置”', dialog?.submitText === '保存配置', dialog?.submitText);

// ---------------------------------------------------------------- 只改名称：判断依据不变
await evaluate(`window.__fillLabel('参数名称', '出口压力（改名）')`);
await sleep(300);
await evaluate(`document.querySelector('.eq-dialog-foot .is-primary')?.click()`);
await sleep(2500);

const afterRename = await evaluate(`(() => ({
  dialogOpen: Boolean(document.querySelector('.eq-dialog-wrap')),
  notice: [...document.querySelectorAll('.eq-form-hint')]
    .map(p => p.textContent.trim()).find(t => t.startsWith('上次保存')) ?? '',
  row: [...document.querySelectorAll('.eq-sensor-param')]
    .map(li => li.innerText.replace(/\\s+/g, ' ')).find(t => t.includes('PRESSURE')) ?? '',
}))()`);
check('保存后弹层关闭', afterRename.dialogOpen === false);
check('提示写明判断依据未变化', afterRename.notice.includes('未变化'), afterRename.notice);
check('列表上的参数名已更新', afterRename.row.includes('出口压力（改名）'), afterRename.row);

// ---------------------------------------------------------------- 改阈值：判断依据变化
await evaluate(`(() => {
  const button = [...document.querySelectorAll('.eq-sensor-param-actions .eq-row-link')]
    .find(b => b.textContent.trim() === '修改');
  button?.click();
})()`);
await sleep(900);
await evaluate(`window.__fillLabel('正常上限', '130')`);
await sleep(300);
await evaluate(`document.querySelector('.eq-dialog-foot .is-primary')?.click()`);
await sleep(2500);

const afterThreshold = await evaluate(`[...document.querySelectorAll('.eq-form-hint')]
  .map(p => p.textContent.trim()).find(t => t.startsWith('上次保存')) ?? ''`);
check('提示写明判断依据已变化', afterThreshold.includes('判断依据已变化'), afterThreshold);
// P08 已实现事件结束：提示应交代活动事件的处理结果，而不是像实现前那样写「尚未接入」。
check('提示如实交代活动事件的处理结果',
  afterThreshold.includes('活动事件') && !afterThreshold.includes('尚未接入'), afterThreshold);

// ---------------------------------------------------------------- 后端复核
const versions = await api('GET', `/sensor-parameters/${parameterId}/rule-versions?page=1&pageSize=20`);
const items = versions.payload?.data?.items ?? [];
check('规则版本累计 2 版（首版 + 改阈值）', items.length === 2, `${items.length} 版`);
check('最新一版是改后的阈值',
  String(items[0]?.rule?.normalUpper) === '130' || Number(items[0]?.rule?.normalUpper) === 130,
  items[0]?.rule?.normalUpper);
check('旧版本仍是原阈值',
  Number(items[1]?.rule?.normalUpper) === 100, items[1]?.rule?.normalUpper);

const shot = await send('Page.captureScreenshot', {format: 'png'});
writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));
console.log(`截图：${OUT}`);

// ---------------------------------------------------------------- 清理
// 改过规则的参数会阻止测点删除（P05 的既定规则），所以这里直接清表。
sql(`UPDATE p2_alert_rule_version SET previous_rule_version_id = NULL WHERE parameter_id = ${parameterId};`);
sql(`DELETE FROM p2_alert_rule_version WHERE parameter_id = ${parameterId};`);
sql(`DELETE FROM p2_sensor_parameter_profile WHERE parameter_id = ${parameterId};`);
sql(`DELETE FROM p2_change_log WHERE object_type = 'SENSOR_PARAMETER' AND object_id = '${parameterId}';`);
sql(`DELETE FROM dim_sensor_parameter WHERE parameter_id = ${parameterId};`);
sql(`DELETE FROM p2_change_log WHERE object_type = 'SENSOR' AND object_id = '${sensorId}';`);
sql(`DELETE FROM p2_sensor_profile WHERE sensor_id = ${sensorId};`);
sql(`DELETE FROM dim_sensor WHERE sensor_id = ${sensorId};`);
const leftover = sql(`SELECT COUNT(*) FROM dim_sensor WHERE sensor_code = '${sensorCode}';`);
check('验收数据已清理干净', leftover === '0', leftover);

const failed = report.filter(item => !item.ok);
console.log(`\n通过 ${report.length - failed.length} 项，失败 ${failed.length} 项`);
process.exit(failed.length === 0 ? 0 : 1);
