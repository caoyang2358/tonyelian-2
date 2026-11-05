// 用 Chrome DevTools Protocol 驱动无头浏览器，验证"期限提醒 → 提醒设置"（S1-T02/T03）。
// 这条脚本会真实修改 p2_reminder_setting，跑完必须还原成进入时的取值。
// Node 22 自带全局 WebSocket，无需安装依赖。
import { writeFileSync } from 'node:fs';

const PORT = Number(process.env.CDP_PORT ?? 9338);
const OUT = process.env.SHOT ?? 'C:/Users/29514/AppData/Local/Temp/reminder-setting.png';

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

/** React 受控输入必须走原生 setter 再派发 input，直接赋 value 不会触发重渲染。 */
async function type(index, value) {
  await evaluate(`(() => {
    const input = document.querySelectorAll('.eq-dl-setting-field input')[${index}];
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(input, '${value}');
    input.dispatchEvent(new Event('input', {bubbles: true}));
  })()`);
  await sleep(200);
}

const clickText = text => evaluate(
  `[...document.querySelectorAll('button')].find(b => b.textContent.trim() === '${text}')?.click()`);

await send('Page.navigate', {url: 'http://127.0.0.1:5174/'});
await sleep(3500);
check('列表页已渲染出设备行', await evaluate(`document.querySelectorAll('tbody tr').length`) > 0);

await clickText('期限提醒');
await sleep(2200);
check('期限面板已打开',
  (await evaluate(`document.querySelector('.eq-dl-drawer h2')?.textContent?.trim() ?? ''`)) === '期限提醒');

check('期限面板头部有「提醒设置」入口', await evaluate(
  `[...document.querySelectorAll('.eq-dl-setting-toggle')].some(b => b.textContent.trim() === '提醒设置')`));

await clickText('提醒设置');
await sleep(1600);

check('点击后行内展开设置区', await evaluate(`document.querySelectorAll('.eq-dl-setting').length`) === 1);
check('抽屉仍然是唯一浮层，没有叠第二层弹层',
  await evaluate(`document.querySelectorAll('.eq-overlay').length`) === 1);

const settingText = await evaluate(`document.querySelector('.eq-dl-setting')?.innerText ?? ''`);
check('写明「全系统生效」的影响范围', settingText.includes('全系统生效'), settingText.slice(0, 120));
check('显示当前配置版本号', /版本\s*v\d+/.test(settingText), settingText.slice(0, 120));
check('说明只影响「即将到期」判定', settingText.includes('即将到期') && settingText.includes('不发送通知'));

const original = await evaluate(
  `[...document.querySelectorAll('.eq-dl-setting-field input')].map(i => i.value)`);
check('两个输入框带回当前真实配置', original.length === 2 && original.every(v => v !== ''),
  JSON.stringify(original));

// 前端校验：负数不该发到服务端
await type(0, '-1');
await clickText('保存');
await sleep(700);
const invalid = await evaluate(`document.querySelector('.eq-dl-setting .eq-alert')?.innerText ?? ''`);
check('负数在提交前被拦下并说明原因', invalid.includes('非负整数'), invalid);
check('校验失败时设置区保持展开', await evaluate(`document.querySelectorAll('.eq-dl-setting').length`) === 1);

// 真实保存：维保提前量拉过 57 天，让 2026-12-31 的两份合同变成即将到期
await type(0, '30');
await type(1, '60');
await clickText('保存');
await sleep(2800);

const notice = await evaluate(`document.querySelector('.eq-dl-notice')?.innerText ?? ''`);
check('保存后给出影响面提示', notice.includes('已更新') && notice.includes('即将到期'), notice);
const match = /当前\s*(\d+)\s*条即将到期/.exec(notice);
check('提示里带改后的「即将到期」条数', Boolean(match), notice);
check('维保提前量拉到60天后出现即将到期记录', match !== null && Number(match[1]) >= 2, notice);
check('提示里标出增减量', /（[+-]\d+）/.test(notice), notice);
check('保存成功后设置区自动收起', await evaluate(`document.querySelectorAll('.eq-dl-setting').length`) === 0);

const badges = await evaluate(
  `[...document.querySelectorAll('.eq-dl-item .eq-badge')].map(b => b.textContent.trim())`);
check('列表按新设置重算出「即将到期」行', badges.includes('即将到期'), JSON.stringify(badges.slice(0, 10)));

const shot = await send('Page.captureScreenshot', {format: 'png'});
writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));
console.log(`截图已保存：${OUT}`);

// Esc 只收起设置区，不能把抽屉一起关掉
await clickText('提醒设置');
await sleep(1500);
await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
await sleep(600);
check('按 Esc 先收起设置区',
  await evaluate(`document.querySelectorAll('.eq-dl-setting').length`) === 0);
check('按 Esc 收起设置区时抽屉还在',
  await evaluate(`document.querySelectorAll('.eq-dl-drawer').length`) === 1);

// 还原：把进入时的取值写回去，避免验证污染共用配置
await clickText('提醒设置');
await sleep(1500);
await type(0, original[0]);
await type(1, original[1]);
await clickText('保存');
await sleep(2800);
const restoredNotice = await evaluate(`document.querySelector('.eq-dl-notice')?.innerText ?? ''`);
check('还原保存同样给出条数回流提示', restoredNotice.includes('已更新'), restoredNotice);

await clickText('提醒设置');
await sleep(1500);
const restoredAgain = await evaluate(
  `[...document.querySelectorAll('.eq-dl-setting-field input')].map(i => i.value)`);
check('验证结束后配置已还原为进入时的取值',
  JSON.stringify(restoredAgain) === JSON.stringify(original),
  `${JSON.stringify(original)} -> ${JSON.stringify(restoredAgain)}`);

await evaluate(`document.querySelector('.eq-drawer-close')?.click()`);
await sleep(600);
check('关闭后面板消失', (await evaluate(`document.querySelectorAll('.eq-dl-drawer').length`)) === 0);

const failed = report.filter(item => !item.ok);
console.log(`\n通过 ${report.length - failed.length} 项，失败 ${failed.length} 项`);
if (failed.length) failed.forEach(item => console.log(`  - ${item.name}：${item.detail}`));
socket.close();
process.exit(failed.length === 0 ? 0 : 1);
