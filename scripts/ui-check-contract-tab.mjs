// 用 Chrome DevTools Protocol 驱动无头浏览器，验证"维保合同"页签的渲染与交互。
// Node 22 自带全局 WebSocket，无需安装依赖。
import { writeFileSync } from 'node:fs';

const PORT = Number(process.env.CDP_PORT ?? 9337);
const OUT = process.env.SHOT ?? 'C:/Users/29514/AppData/Local/Temp/contract-tab.png';

async function waitForTargets() {
  for (let i = 0; i < 40; i += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${PORT}/json/list`);
      const targets = await response.json();
      const page = targets.find(item => item.type === 'page' && item.url.includes('5174'));
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

await send('Page.navigate', {url: 'http://127.0.0.1:5174/'});
await sleep(3500);
check('列表页已渲染出设备行', await evaluate(`document.querySelectorAll('tbody tr').length`) > 0);

await evaluate(`document.querySelector('tbody tr .eq-row-link')?.click()`);
await sleep(1800);
const tabs = await evaluate(`[...document.querySelectorAll('.eq-tab')].map(t => t.textContent.trim())`);
check('抽屉出现且含维保合同页签', Array.isArray(tabs) && tabs.includes('维保合同'), JSON.stringify(tabs));

await evaluate(`[...document.querySelectorAll('.eq-tab')].find(t => t.textContent.trim() === '维保合同')?.click()`);
await sleep(1800);
const bar = await evaluate(`document.querySelector('.eq-section-bar')?.textContent?.trim() ?? ''`);
check('合同页签渲染出区域与新建按钮', bar.includes('新建合同'), bar);

const bodyText = await evaluate(`document.querySelector('.eq-drawer-body')?.innerText ?? ''`);
check('页签内没有读取失败提示', !bodyText.includes('读取失败') && !bodyText.includes('失败，请'), bodyText.slice(0, 200));
check('展示演示合同（华东维保服务中心）', bodyText.includes('华东维保服务中心'), bodyText.slice(0, 160));
check('展示期限徽标', (await evaluate(`document.querySelectorAll('.eq-badge').length`)) > 0);
check('展示起止日期', bodyText.includes('2026-01-01') && bodyText.includes('2026-12-31'), bodyText.slice(0, 200));
check('展示覆盖设备数', /覆盖\s*\d+\s*台设备/.test(bodyText), bodyText.slice(0, 200));

await evaluate(`[...document.querySelectorAll('.eq-section-bar button')].find(b => b.textContent.includes('新建合同'))?.click()`);
await sleep(700);
const dialog = await evaluate(`document.querySelector('.eq-dialog-wrap')?.innerText ?? ''`);
check('点击后弹出合同表单', dialog.includes('服务单位'), dialog.slice(0, 120));
check('表单含起止日期输入', (await evaluate(`document.querySelectorAll('.eq-dialog-wrap input[type=date]').length`)) === 2);

// 直接点保存：必填项为空时应先提示服务单位，而不是放到后端去报错
await evaluate(`[...document.querySelectorAll('.eq-dialog-foot button')].find(b => b.textContent.includes('保存'))?.click()`);
await sleep(500);
const requiredError = await evaluate(`document.querySelector('.eq-form-error')?.textContent?.trim() ?? ''`);
check('服务单位为空时前端先行拦截', requiredError.includes('服务单位'), requiredError);

// 填了服务单位之后再制造日期颠倒，应该被日期校验挡下
await evaluate(`(() => {
  const all = document.querySelectorAll('.eq-dialog-wrap input');
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  setter.call(all[0], 'REVIEW-前端校验'); all[0].dispatchEvent(new Event('input', {bubbles: true}));
  const dates = document.querySelectorAll('.eq-dialog-wrap input[type=date]');
  setter.call(dates[0], '2026-12-31'); dates[0].dispatchEvent(new Event('input', {bubbles: true}));
  setter.call(dates[1], '2026-01-01'); dates[1].dispatchEvent(new Event('input', {bubbles: true}));
})()`);
await sleep(300);
await evaluate(`[...document.querySelectorAll('.eq-dialog-foot button')].find(b => b.textContent.includes('保存'))?.click()`);
await sleep(700);
const formError = await evaluate(`document.querySelector('.eq-form-error')?.textContent?.trim() ?? ''`);
check('结束早于开始时前端给出提示', formError.includes('结束日期'), formError);

await evaluate(`[...document.querySelectorAll('.eq-dialog-foot button')].find(b => b.textContent.trim() === '取消')?.click()`);
await sleep(400);

const shot = await send('Page.captureScreenshot', {format: 'png'});
writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));
console.log(`截图已保存：${OUT}`);

const failed = report.filter(item => !item.ok);
console.log(`\n通过 ${report.length - failed.length} 项，失败 ${failed.length} 项`);
socket.close();
process.exit(failed.length === 0 ? 0 : 1);
