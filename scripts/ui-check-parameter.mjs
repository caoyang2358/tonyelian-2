// 用 Chrome DevTools Protocol 驱动无头浏览器，验证设备详情"测点"页签里的新增参数流程（S1-P07）。
// Node 22 自带全局 WebSocket，无需安装依赖。
import { writeFileSync } from 'node:fs';

const PORT = Number(process.env.CDP_PORT ?? 9337);
const OUT = process.env.SHOT ?? 'C:/Users/29514/AppData/Local/Temp/sensor-parameter.png';

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
// 这里挂到 window 上，后续各段脚本直接调用，避免每段重复拼接函数声明。
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
  window.__selectLabel = function(text, value) {
    const el = window.__fieldByLabel(text)?.querySelector('select');
    if (!el) return false;
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set.call(el, value);
    el.dispatchEvent(new Event('change', {bubbles: true}));
    return true;
  };
  true;
`;

const stamp = Date.now().toString().slice(-6);
const code = `UI-P07-${stamp}`;

await send('Page.navigate', {url: 'http://127.0.0.1:5174/'});
await sleep(3500);
await evaluate(FILL_HELPER);
check('列表页已渲染出设备行', (await evaluate(`document.querySelectorAll('tbody tr').length`)) > 0);

await evaluate(`(() => {
  const rows = [...document.querySelectorAll('tbody tr')];
  const row = rows.find(r => r.innerText.includes('SMELT-FUR-01')) ?? rows[0];
  row?.querySelector('.eq-row-link')?.click();
})()`);
await sleep(1800);

await evaluate(`[...document.querySelectorAll('.eq-tab')].find(t => t.textContent.trim() === '测点')?.click()`);
await sleep(2000);

await evaluate(`(() => {
  const head = [...document.querySelectorAll('.eq-sensor-head')]
    .find(h => /[1-9]\\d*\\s*个参数/.test(h.innerText));
  (head ?? document.querySelector('.eq-sensor-head'))?.click();
})()`);
await sleep(2200);

const barLinks = await evaluate(`[...document.querySelectorAll('.eq-sensor-detail-bar .eq-row-link')]
  .map(b => b.textContent.trim())`);
check('测点展开后提供新增参数入口', barLinks.includes('新增参数'), JSON.stringify(barLinks));

const before = await evaluate(`document.querySelectorAll('.eq-sensor-param').length`);
check('已有参数行可数', before > 0, `before=${before}`);

await evaluate(`[...document.querySelectorAll('.eq-sensor-detail-bar .eq-row-link')]
  .find(b => b.textContent.trim() === '新增参数')?.click()`);
await sleep(900);

const dialogTitle = await evaluate(`document.querySelector('.eq-dialog-wrap')?.getAttribute('aria-label') ?? ''`);
check('弹层为新增参数', dialogTitle === '新增参数', dialogTitle);

const ruleHidden = await evaluate(`document.querySelectorAll('.eq-rule-block').length === 0`);
check('未勾选时规则区隐藏，避免默认造一版空规则', ruleHidden);

const unitOptions = await evaluate(`
  [...(window.__fieldByLabel('计量单位')?.querySelectorAll('option') ?? [])].map(o => o.value)
`);
check('单位下拉已从参考数据加载', Array.isArray(unitOptions) && unitOptions.includes('Pa'),
  JSON.stringify(unitOptions));

await evaluate(`
  window.__fillLabel('参数编码', '${code}');
  window.__fillLabel('参数名称', '界面验收参数');
  window.__fillLabel('计量单位', 'Pa');
  window.__fillLabel('小数位', '2');
  window.__fillLabel('采样周期（秒）', '60');
  true;
`);
await sleep(400);

// 勾选"同时配置告警规则"：规则区应出现，且能填边界。
await evaluate(`(() => {
  const box = [...document.querySelectorAll('.eq-sensor-switch input')]
    .find(input => input.closest('.eq-sensor-switch')?.innerText.includes('告警规则'));
  if (!box) return false;
  if (!box.checked) box.click();
  return true;
})()`);
await sleep(500);

const ruleShown = await evaluate(`document.querySelectorAll('.eq-rule-block').length`);
check('勾选后出现规则编辑区', ruleShown === 1, `blocks=${ruleShown}`);

await evaluate(`
  window.__fillLabel('正常下限', '0.4');
  window.__fillLabel('正常上限', '1.6');
  true;
`);
await sleep(300);

await evaluate(`window.__selectLabel('触发模式', 'IMMEDIATE')`);
await sleep(400);
const immediateDisabled = await evaluate(`window.__fieldByLabel('持续秒数')?.querySelector('input')?.disabled ?? null`);
check('切到立即触发后持续秒数输入被禁用', immediateDisabled === true, String(immediateDisabled));

// 切回持续触发并给正整数秒数，再保存。
await evaluate(`window.__selectLabel('触发模式', 'SUSTAINED')`);
await sleep(300);
await evaluate(`window.__fillLabel('持续秒数', '30')`);
await sleep(400);

await evaluate(`document.querySelector('.eq-dialog-foot .is-primary')?.click()`);
await sleep(3200);

const stillOpen = await evaluate(`document.querySelectorAll('.eq-dialog-wrap').length`);
check('保存成功后弹层关闭', stillOpen === 0, `dialogs=${stillOpen}`);

const formError = await evaluate(`document.querySelector('.eq-form-error')?.textContent?.trim() ?? ''`);
check('没有残留错误提示', formError === '', formError);

const after = await evaluate(`document.querySelectorAll('.eq-sensor-param').length`);
check('参数行数增加一条', after === before + 1, `${before} -> ${after}`);

const codes = await evaluate(`[...document.querySelectorAll('.eq-sensor-param-name strong')]
  .map(el => el.textContent.trim())`);
check('新参数出现在列表里', codes.includes(code), JSON.stringify(codes));

const newRow = await evaluate(`(() => {
  const row = [...document.querySelectorAll('.eq-sensor-param')]
    .find(p => p.querySelector('.eq-sensor-param-name strong')?.textContent.trim() === '${code}');
  return {
    meta: row?.querySelector('.eq-sensor-param-name small')?.textContent?.trim() ?? '',
    value: row?.querySelector('.eq-sensor-value b')?.textContent?.trim() ?? '',
  };
})()`);
check('新参数带出单位与数据类型', newRow.meta.includes('Pa') && newRow.meta.includes('DECIMAL'), newRow.meta);
check('新参数没有采集值时显示待采集', newRow.value === '待采集' || newRow.value === '—', newRow.value);

const notice = await evaluate(`document.querySelector('.eq-notice')?.innerText ?? ''`);
check('页面没有失败提示', !notice.includes('失败'), notice);

const shot = await send('Page.captureScreenshot', {format: 'png'});
writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));

const failed = report.filter(item => !item.ok);
console.log(`\n通过 ${report.length - failed.length} 项，失败 ${failed.length} 项`);
console.log(`截图：${OUT}`);
console.log('\n清理（按需执行）：');
console.log(`  DELETE FROM p2_change_log WHERE object_type='SENSOR_PARAMETER' AND object_id IN `
  + `(SELECT parameter_id FROM dim_sensor_parameter WHERE parameter_code='${code}');`);
console.log(`  DELETE FROM p2_alert_rule_version WHERE parameter_id IN `
  + `(SELECT parameter_id FROM dim_sensor_parameter WHERE parameter_code='${code}');`);
console.log(`  DELETE FROM p2_sensor_parameter_profile WHERE parameter_id IN `
  + `(SELECT parameter_id FROM dim_sensor_parameter WHERE parameter_code='${code}');`);
console.log(`  DELETE FROM dim_sensor_parameter WHERE parameter_code='${code}';`);

socket.close();
process.exit(failed.length === 0 ? 0 : 1);
