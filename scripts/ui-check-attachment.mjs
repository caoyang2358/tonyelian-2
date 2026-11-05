// 用 Chrome DevTools Protocol 驱动无头浏览器，验证设备详情"附件"页签的渲染与交互。
// Node 22 自带全局 WebSocket，无需安装依赖。
import { writeFileSync } from 'node:fs';

const PORT = Number(process.env.CDP_PORT ?? 9337);
const OUT = process.env.SHOT ?? 'C:/Users/29514/AppData/Local/Temp/attachment-tab.png';

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

await send('Page.navigate', {url: 'http://127.0.0.1:5174/'});
await sleep(3500);
check('列表页已渲染出设备行', await evaluate(`document.querySelectorAll('tbody tr').length`) > 0);

await evaluate(`document.querySelector('tbody tr .eq-row-link')?.click()`);
await sleep(1800);
const tabs = await evaluate(`[...document.querySelectorAll('.eq-tab')].map(t => t.textContent.trim())`);
check('抽屉出现且含附件页签', Array.isArray(tabs) && tabs.includes('附件'), JSON.stringify(tabs));

await evaluate(`[...document.querySelectorAll('.eq-tab')].find(t => t.textContent.trim() === '附件')?.click()`);
await sleep(1800);
const bar = await evaluate(`document.querySelector('.eq-section-bar')?.textContent?.trim() ?? ''`);
check('附件页签渲染出区域与上传入口', bar.includes('附件') && bar.includes('上传附件'), bar);

const bodyText = await evaluate(`document.querySelector('.eq-drawer-body')?.innerText ?? ''`);
check('页签内没有读取失败提示', !bodyText.includes('读取失败') && !bodyText.includes('失败，请'), bodyText.slice(0, 200));
check('展示验收附件', bodyText.includes('附件验收'), bodyText.slice(0, 200));
check('展示类别与大小', /(设备配图|设备手册|文档资料)\s*·\s*\d+\s*(B|KB|MB)/.test(bodyText), bodyText.slice(0, 240));
check('展示版本号', /v\d+/.test(bodyText), bodyText.slice(0, 200));

const links = await evaluate(`[...document.querySelectorAll('.eq-supplier-actions a')].map(a => ({text: a.textContent.trim(), href: a.getAttribute('href')}))`);
check('每条附件都有查看与下载入口',
  links.some(l => l.text === '查看' && /\/content$/.test(l.href ?? ''))
  && links.some(l => l.text === '下载' && /download=true$/.test(l.href ?? '')),
  JSON.stringify(links.slice(0, 4)));

check('说明不含删除入口', !bodyText.includes('删除附件') && !bodyText.includes('移除附件'), bodyText.slice(0, 200));

await evaluate(`[...document.querySelectorAll('.eq-supplier-actions button')].find(b => b.textContent.trim() === '替换')?.click()`);
await sleep(600);
const dialog = await evaluate(`document.querySelector('.eq-dialog-wrap')?.innerText ?? ''`);
check('点击后弹出替换表单', dialog.includes('替换附件内容'), dialog.slice(0, 120));
check('表单含文件选择与原因输入',
  (await evaluate(`document.querySelectorAll('.eq-dialog-wrap input[type=file]').length`)) === 1
  && dialog.includes('替换原因'));

// 不选文件直接提交：应在前端拦下，而不是发一个空请求到后端
await evaluate(`[...document.querySelectorAll('.eq-dialog-foot button')].find(b => b.textContent.includes('确认替换'))?.click()`);
await sleep(500);
const requiredError = await evaluate(`document.querySelector('.eq-dialog-wrap .eq-form-error')?.textContent?.trim() ?? ''`);
check('未选文件时前端先行拦截', requiredError.includes('新文件'), requiredError);

const shot = await send('Page.captureScreenshot', {format: 'png'});
writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));
console.log(`截图已保存：${OUT}`);

// 关掉弹窗再拍一张列表本身，便于检查窄抽屉里的换行与按钮对齐。
await evaluate(`[...document.querySelectorAll('.eq-dialog-foot button')].find(b => b.textContent.trim() === '取消')?.click()`);
await sleep(500);
const listShot = await send('Page.captureScreenshot', {format: 'png'});
const listOut = OUT.replace(/\.png$/, '-list.png');
writeFileSync(listOut, Buffer.from(listShot.result.data, 'base64'));
console.log(`列表截图已保存：${listOut}`);

const failed = report.filter(item => !item.ok);
console.log(`\n通过 ${report.length - failed.length} 项，失败 ${failed.length} 项`);
socket.close();
process.exit(failed.length === 0 ? 0 : 1);
