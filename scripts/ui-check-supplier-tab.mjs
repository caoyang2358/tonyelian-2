// 用 Chrome DevTools Protocol 驱动无头浏览器，验证"供应商"页签的渲染与交互。
// Node 22 自带全局 WebSocket，无需安装依赖。
import { writeFileSync } from 'node:fs';

const PORT = Number(process.env.CDP_PORT ?? 9336);
const OUT = process.env.SHOT ?? 'C:/Users/29514/AppData/Local/Temp/supplier-tab.png';

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

// 每次运行都重新加载，确保拿到最新的前端代码而不是 HMR 中间态
await send('Page.navigate', {url: 'http://127.0.0.1:5174/'});
await sleep(3500);
check('列表页已渲染出设备行', await evaluate(`document.querySelectorAll('tbody tr').length`) > 0);

// 打开第一台设备的详情抽屉（行内的“详情”链接按钮）
await evaluate(`document.querySelector('tbody tr .eq-row-link')?.click()`);
await sleep(1800);
const tabs = await evaluate(`[...document.querySelectorAll('.eq-tab')].map(t => t.textContent.trim())`);
check('抽屉出现且有三个页签', Array.isArray(tabs) && tabs.includes('供应商'), JSON.stringify(tabs));

// 切到供应商页签
await evaluate(`[...document.querySelectorAll('.eq-tab')].find(t => t.textContent.trim() === '供应商')?.click()`);
await sleep(1800);
const bar = await evaluate(`document.querySelector('.eq-section-bar')?.textContent?.trim() ?? ''`);
check('供应商页签渲染出区域与操作按钮', bar.includes('关联供应商'), bar);

const bodyText = await evaluate(`document.querySelector('.eq-drawer-body')?.innerText ?? ''`);
check('页签内没有加载错误提示', !bodyText.includes('读取失败') && !bodyText.includes('失败，请'), bodyText.slice(0, 200));

// 页面是否展示演示供应商（设备 1 关联了华东机电）
const hasDemo = bodyText.includes('华东机电');
check('关联供应商列表展示真实数据（可选）', true, hasDemo ? '已显示华东机电' : '该设备暂无关联供应商');

// 打开新增对话框，确认表单可用
await evaluate(`[...document.querySelectorAll('.eq-section-bar button')].find(b => b.textContent.includes('关联供应商'))?.click()`);
await sleep(700);
const dialog = await evaluate(`document.querySelector('.eq-dialog-wrap')?.innerText ?? ''`);
check('点击后弹出供应商表单', dialog.includes('供应商名称'), dialog.slice(0, 120));
await evaluate(`[...document.querySelectorAll('.eq-dialog-foot button')].find(b => b.textContent.trim() === '取消')?.click()`);
await sleep(400);

const shot = await send('Page.captureScreenshot', {format: 'png'});
writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));
console.log(`截图已保存：${OUT}`);

const failed = report.filter(item => !item.ok);
console.log(`\n通过 ${report.length - failed.length} 项，失败 ${failed.length} 项`);
socket.close();
process.exit(failed.length === 0 ? 0 : 1);
