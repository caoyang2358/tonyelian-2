// 用 Chrome DevTools Protocol 驱动无头浏览器，验证"期限提醒"面板的渲染与筛选。
// Node 22 自带全局 WebSocket，无需安装依赖。
import { writeFileSync } from 'node:fs';

const PORT = Number(process.env.CDP_PORT ?? 9338);
const OUT = process.env.SHOT ?? 'C:/Users/29514/AppData/Local/Temp/deadline-panel.png';

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

/** React 受控 select 必须走原生 setter 再派发 change，直接赋 value 不会触发重渲染。 */
async function pick(selectIndex, value) {
  await evaluate(`(() => {
    const select = document.querySelectorAll('.eq-dl-filter select')[${selectIndex}];
    const setter = Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype, 'value').set;
    setter.call(select, '${value}');
    select.dispatchEvent(new Event('change', {bubbles: true}));
  })()`);
  await sleep(1400);
}

await send('Page.navigate', {url: 'http://127.0.0.1:5174/'});
await sleep(3500);
check('列表页已渲染出设备行', await evaluate(`document.querySelectorAll('tbody tr').length`) > 0);

const hasButton = await evaluate(
  `[...document.querySelectorAll('button')].some(b => b.textContent.trim() === '期限提醒')`);
check('列表页头部有「期限提醒」入口', hasButton);

await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.trim() === '期限提醒')?.click()`);
await sleep(2200);

const title = await evaluate(`document.querySelector('.eq-dl-drawer h2')?.textContent?.trim() ?? ''`);
check('点击后打开期限面板', title === '期限提醒', title);

const rows = await evaluate(`document.querySelectorAll('.eq-dl-item').length`);
check('面板列出期限行', rows > 0, `实际 ${rows} 行`);

const headText = await evaluate(`document.querySelector('.eq-drawer-head')?.innerText ?? ''`);
check('头部显示总条数', /共\s*\d+\s*条/.test(headText), headText.slice(0, 120));

const body = await evaluate(`document.querySelector('.eq-dl-drawer .eq-drawer-body')?.innerText ?? ''`);
check('没有读取失败提示', !body.includes('失败，请') && !body.includes('无法识别'), body.slice(0, 160));
check('展示真实到期日期', /20\d\d-\d\d-\d\d/.test(body), body.slice(0, 200));
check('展示剩余天数文案', /剩余\s*\d+\s*天/.test(body), body.slice(0, 200));
check('展示待补资料状态', body.includes('待补资料'), body.slice(0, 200));
check('缺资料时说明缺哪个字段', body.includes('缺少'), body.slice(0, 200));

const badges = await evaluate(`[...document.querySelectorAll('.eq-dl-item .eq-badge')].map(b => b.textContent.trim())`);
check('每行都有类型与状态两个徽标', badges.length >= rows * 2, JSON.stringify(badges.slice(0, 8)));

// 按状态筛选：只看已超期
await pick(1, 'EXPIRED');
const expiredText = await evaluate(`document.querySelector('.eq-dl-drawer .eq-drawer-body')?.innerText ?? ''`);
const expiredEmpty = expiredText.includes('没有期限记录');
check('筛选「已超期」后只剩超期行或空态提示',
  expiredEmpty || !expiredText.includes('待补资料'), expiredText.slice(0, 160));

// 回到全部，按类型筛选：只看质保
await pick(1, '');
await pick(0, 'WARRANTY');
const types = await evaluate(`[...document.querySelectorAll('.eq-dl-item .eq-badge.is-type')].map(b => b.textContent.trim())`);
check('筛选「质保」后全部是质保行',
  types.length > 0 && types.every(text => text === '质保'), JSON.stringify(types.slice(0, 8)));

await pick(0, '');
const pager = await evaluate(`document.querySelector('.eq-dl-page')?.textContent?.trim() ?? ''`);
check('页脚显示页码', /第\s*\d+\s*\/\s*\d+\s*页/.test(pager), pager);

const shot = await send('Page.captureScreenshot', {format: 'png'});
writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));
console.log(`截图已保存：${OUT}`);

await evaluate(`document.querySelector('.eq-drawer-close')?.click()`);
await sleep(600);
check('关闭后面板消失', (await evaluate(`document.querySelectorAll('.eq-dl-drawer').length`)) === 0);

const failed = report.filter(item => !item.ok);
console.log(`\n通过 ${report.length - failed.length} 项，失败 ${failed.length} 项`);
if (failed.length) failed.forEach(item => console.log(`  - ${item.name}：${item.detail}`));
socket.close();
process.exit(failed.length === 0 ? 0 : 1);
