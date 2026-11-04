// 用 Chrome DevTools Protocol 驱动无头浏览器，验证设备详情"测点"页签里的删除流程（S1-P05）。
// Node 22 自带全局 WebSocket，无需安装依赖。
import { writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

import {loadDbConfig, mysqlArgs, preflightDatabase} from './db-env.mjs';

const PORT = Number(process.env.CDP_PORT ?? 9337);
const OUT = process.env.SHOT ?? 'C:/Users/29514/AppData/Local/Temp/sensor-delete.png';
const API = process.env.API_BASE ?? 'http://127.0.0.1:18280/api/v1';

// 先探库再动数据：端口不对时立刻退出，不留孤儿测点。
const DB = loadDbConfig();
preflightDatabase(DB);

function sql(statement) {
  return execFileSync(DB.bin, mysqlArgs(DB, statement), {encoding: 'utf8'});
}

async function api(method, pathname, body) {
  const response = await fetch(API + pathname, {
    method,
    headers: body ? {'Content-Type': 'application/json'} : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  return {status: response.status, payload: await response.json()};
}

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

// ---------------------------------------------------------------- 造数据
const stamp = Date.now().toString().slice(-6);
const cleanCode = `UI-P05-${stamp}`;
const blockedCode = `UI-P05-BLK-${stamp}`;

// 测点挂在哪个设备，页面就得打开哪个设备，否则列表里根本看不到造出来的测点。
const equipmentList = (await api('GET', '/equipment?pageSize=100')).payload.data.items;
const target = equipmentList.find(item => item.code === 'SMELT-FUR-01') ?? equipmentList[0];
const equipment = target.id;
const equipmentCode = target.code;

async function newSensor(code) {
  const result = await api('POST', '/sensors', {
    requestId: crypto.randomUUID(), operatorName: '界面验收',
    reason: '删除界面验收', sensorCode: code, name: '删除验收测点',
    equipmentId: equipment, sensorType: '温度', location: '验收位置',
    purpose: null, enabled: true,
  });
  if (result.status !== 200) throw new Error(`造测点失败：${result.status} ${JSON.stringify(result.payload)}`);
  return result.payload.data;
}

const clean = await newSensor(cleanCode);
const blocked = await newSensor(blockedCode);
sql(`INSERT INTO ods_sensor_data (source_system, sensor_code, parameter_code, collect_time, value_num)
     VALUES ('UIAUTOTEST', '${blockedCode}', 'TEMP', NOW(), 1.5);`);
console.log(`已造测点：可删 ${cleanCode}、带采集记录 ${blockedCode}`);

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

const fillHelper = `
  window.__confirmInput = () => document.querySelector('.eq-confirm-card input');
  window.__fillConfirm = function(value) {
    const el = window.__confirmInput();
    if (!el) return false;
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, value);
    el.dispatchEvent(new Event('input', {bubbles: true}));
    return true;
  };
  window.__openSensor = function(code) {
    const head = [...document.querySelectorAll('.eq-sensor-head')]
      .find(h => h.innerText.includes(code));
    head?.click();
    return Boolean(head);
  };
  window.__barButton = function(label) {
    return [...document.querySelectorAll('.eq-sensor-detail-bar .eq-row-link')]
      .find(b => b.textContent.trim() === label) ?? null;
  };
  true;
`;

await send('Page.navigate', {url: 'http://127.0.0.1:5174/'});
await sleep(3500);
await evaluate(fillHelper);
check('列表页已渲染出设备行', (await evaluate(`document.querySelectorAll('tbody tr').length`)) > 0);

await evaluate(`(() => {
  const rows = [...document.querySelectorAll('tbody tr')];
  const row = rows.find(r => r.innerText.includes('${equipmentCode}')) ?? rows[0];
  row?.querySelector('.eq-row-link')?.click();
})()`);
await sleep(1800);

await evaluate(`[...document.querySelectorAll('.eq-tab')].find(t => t.textContent.trim() === '测点')?.click()`);
await sleep(2200);

// ---------------------------------------------------------------- 有采集记录：入口置灰
const blockedOpened = await evaluate(`window.__openSensor('${blockedCode}')`);
check('能定位到带采集记录的测点', blockedOpened === true);
await sleep(2000);

const blockedButton = await evaluate(`(() => {
  const button = window.__barButton('删除');
  return button ? {disabled: button.disabled, title: button.title} : null;
})()`);
check('有关联时删除入口存在但置灰', blockedButton?.disabled === true, JSON.stringify(blockedButton));
check('置灰原因写在 title 里', (blockedButton?.title ?? '').includes('原始采集记录'), blockedButton?.title ?? '');

const blockedClicked = await evaluate(`(() => {
  const button = window.__barButton('删除');
  if (!button) return false;
  button.click();
  return document.querySelectorAll('.eq-confirm-card').length > 0;
})()`);
check('置灰状态点击不会打开确认层', blockedClicked === false);

// ---------------------------------------------------------------- 无关联：完整删除流程
await evaluate(`window.__openSensor('${cleanCode}')`);
await sleep(2000);

const cleanButton = await evaluate(`(() => {
  const button = window.__barButton('删除');
  return button ? {disabled: button.disabled, title: button.title} : null;
})()`);
check('无关联时删除入口可点', cleanButton?.disabled === false, JSON.stringify(cleanButton));

const countBefore = await evaluate(`document.querySelectorAll('.eq-sensor-item').length`);
await evaluate(`window.__barButton('删除')?.click()`);
await sleep(700);

const title = await evaluate(`document.querySelector('#eq-sensor-delete-title')?.textContent?.trim() ?? ''`);
check('弹出二次确认层', title === '确认删除这个测点？', title);

const confirmText = await evaluate(`document.querySelector('.eq-confirm-card .eq-confirm-text')?.innerText ?? ''`);
check('确认文案说明会连带清理参数与组网节点',
  confirmText.includes('参数') && confirmText.includes('组网节点'), confirmText);
check('确认文案说明不可撤销', confirmText.includes('不可撤销'), confirmText);

// 不填原因直接确认：必须拦在本地。
await evaluate(`document.querySelector('.eq-confirm-card .is-danger')?.click()`);
await sleep(600);
const reasonError = await evaluate(`document.querySelector('.eq-confirm-card .eq-form-error')?.textContent?.trim() ?? ''`);
check('空原因被拦下并提示', reasonError.includes('删除原因'), reasonError);

await evaluate(`window.__fillConfirm('误建测点，界面验收后删除')`);
await sleep(300);
check('原因输入框可填写', (await evaluate(`window.__confirmInput()?.value ?? ''`)) === '误建测点，界面验收后删除');

await evaluate(`document.querySelector('.eq-confirm-card .is-danger')?.click()`);
await sleep(2600);

const dialogLeft = await evaluate(`document.querySelectorAll('.eq-confirm-card').length`);
check('删除成功后确认层关闭', dialogLeft === 0, `cards=${dialogLeft}`);

const countAfter = await evaluate(`document.querySelectorAll('.eq-sensor-item').length`);
check('列表少一行', countAfter === countBefore - 1, `${countBefore} -> ${countAfter}`);

const stillThere = await evaluate(`[...document.querySelectorAll('.eq-sensor-head')]
  .some(h => h.innerText.includes('${cleanCode}'))`);
check('被删测点不再出现在列表里', stillThere === false);

const notice = await evaluate(`document.querySelector('.eq-notice')?.innerText ?? ''`);
check('页面没有失败提示', !notice.includes('失败'), notice);

const shot = await send('Page.captureScreenshot', {format: 'png'});
writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));

// ---------------------------------------------------------------- 清理
sql(`DELETE FROM ods_sensor_data WHERE source_system = 'UIAUTOTEST' AND sensor_code = '${blockedCode}';`);
for (const sensor of [blocked, clean]) {
  // clean 可能已被界面删掉，删不掉的说明本来就没了，两种都算正常。
  await api('DELETE', `/sensors/${sensor.sensorId}`, {
    requestId: crypto.randomUUID(), operatorName: '界面验收',
    expectedVersion: 1, reason: '界面验收结束清理', confirmed: true,
  });
}
const leftover = sql(`SELECT COUNT(*) FROM dim_sensor WHERE sensor_code LIKE 'UI-P05-%${stamp}%';`).trim();
check('验收测点已清理干净', leftover === '0', `仍剩 ${leftover} 个`);

const failed = report.filter(item => !item.ok);
console.log(`\n通过 ${report.length - failed.length} 项，失败 ${failed.length} 项`);
console.log(`截图：${OUT}`);

socket.close();
process.exit(failed.length === 0 ? 0 : 1);
