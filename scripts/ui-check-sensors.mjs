// 用 Chrome DevTools Protocol 驱动无头浏览器，验证设备详情"测点"页签的渲染与只读约束。
// Node 22 自带全局 WebSocket，无需安装依赖。
import { writeFileSync } from 'node:fs';

const PORT = Number(process.env.CDP_PORT ?? 9337);
const OUT = process.env.SHOT ?? 'C:/Users/29514/AppData/Local/Temp/sensor-tab.png';

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
check('列表页已渲染出设备行', (await evaluate(`document.querySelectorAll('tbody tr').length`)) > 0);

// 选测点最多的那台设备（冶炼炉，5 个测点），避免落到空态上验不出列表。
await evaluate(`(() => {
  const rows = [...document.querySelectorAll('tbody tr')];
  const row = rows.find(r => r.innerText.includes('SMELT-FUR-01')) ?? rows[0];
  row?.querySelector('.eq-row-link')?.click();
})()`);
await sleep(1800);
const tabs = await evaluate(`[...document.querySelectorAll('.eq-tab')].map(t => t.textContent.trim())`);
check('抽屉含测点页签', Array.isArray(tabs) && tabs.includes('测点'), JSON.stringify(tabs));

await evaluate(`[...document.querySelectorAll('.eq-tab')].find(t => t.textContent.trim() === '测点')?.click()`);
await sleep(2000);

const bar = await evaluate(`document.querySelector('.eq-section-bar')?.innerText?.trim() ?? ''`);
check('页签渲染出测点区域与数量', bar.includes('测点') && /共\s*\d+\s*个/.test(bar), bar);

const bodyText = await evaluate(`document.querySelector('.eq-drawer-body')?.innerText ?? ''`);
check('没有读取失败提示', !bodyText.includes('读取失败') && !bodyText.includes('失败，请'), bodyText.slice(0, 160));
check('说明写明删除只用于纠正误建，其余情况请停用',
  bodyText.includes('删除只用于纠正误建') && bodyText.includes('停用'), bodyText.slice(-140));

const sensorCount = await evaluate(`document.querySelectorAll('.eq-sensor-item').length`);
check('测点列表非空', sensorCount > 0, `sensorCount=${sensorCount}`);

const heads = await evaluate(`[...document.querySelectorAll('.eq-sensor-head')].slice(0, 3).map(h => ({
  code: h.querySelector('.eq-sensor-id strong')?.textContent?.trim() ?? '',
  status: h.querySelector('.eq-badge')?.textContent?.trim() ?? '',
  params: h.innerText.match(/(\\d+)\\s*个参数/)?.[1] ?? ''
}))`);
check('每条测点显示编码与状态徽章',
  heads.length > 0 && heads.every(h => h.code.length > 0 && ['在线', '数据过期', '暂无数据', '已停用'].includes(h.status)),
  JSON.stringify(heads));
check('状态按四态区分而不是统一离线',
  heads.every(h => h.status !== '离线'), JSON.stringify(heads));

// 展开一个"有参数"的测点：空参数的测点没有参数行，验不到读数与历史入口。
await evaluate(`(() => {
  const head = [...document.querySelectorAll('.eq-sensor-head')]
    .find(h => /[1-9]\\d*\\s*个参数/.test(h.innerText));
  (head ?? document.querySelector('.eq-sensor-head'))?.click();
})()`);
await sleep(2200);
const expanded = await evaluate(`document.querySelector('.eq-sensor-body')?.innerText ?? ''`);
check('展开后显示用途与配置状态', expanded.includes('用途') && expanded.includes('采集'), expanded.slice(0, 160));

const paramRows = await evaluate(`[...document.querySelectorAll('.eq-sensor-param')].map(p => ({
  code: p.querySelector('.eq-sensor-param-name strong')?.textContent?.trim() ?? '',
  value: p.querySelector('.eq-sensor-value b')?.textContent?.trim() ?? '',
  meta: p.querySelector('.eq-sensor-value small')?.textContent?.trim() ?? ''
}))`);
check('参数行显示编码、读数与状态', paramRows.length > 0 && paramRows.every(p => p.code && p.value && p.meta),
  JSON.stringify(paramRows.slice(0, 3)));
check('无采集记录时读数为待采集', paramRows.every(p => p.value === '待采集' || p.value === '—' || /\d/.test(p.value)),
  JSON.stringify(paramRows.map(p => p.value)));

// 只看当前真正展开的那一条：列表里第一个测点未必是展开的那个。
const fold = await evaluate(`document.querySelector('.eq-sensor-head[aria-expanded="true"] .eq-sensor-fold')?.textContent?.trim() ?? ''`);
check('展开后折叠标识变为收起', fold === '收起', fold);

// 参数行上有两个入口（修改 / 历史·规则），按文案点，别依赖先后顺序。
const paramLinks = await evaluate(`(() => {
  const first = document.querySelectorAll('.eq-sensor-param')[0];
  return first ? [...first.querySelectorAll('.eq-row-link')].map(b => b.textContent.trim()) : [];
})()`);
check('参数行同时提供修改与历史入口',
  paramLinks.includes('修改') && paramLinks.includes('历史 / 规则'), JSON.stringify(paramLinks));

await evaluate(`[...document.querySelectorAll('.eq-sensor-param')][0]
  ?.querySelectorAll('.eq-row-link').forEach(b => {
    if (b.textContent.trim() === '历史 / 规则') b.click();
  })`);
await sleep(1800);
const dialog = await evaluate(`document.querySelector('.eq-dialog-wrap')?.innerText ?? ''`);
check('点击历史/规则弹出只读视图', dialog.includes('只读视图'), dialog.slice(0, 120));
check('历史视图默认最近区间且可调',
  (await evaluate(`document.querySelectorAll('.eq-dialog-wrap input[type=datetime-local]').length`)) === 2
  && dialog.includes('92 天'), dialog.slice(0, 160));
check('空区间给出待数据说明而不是报错',
  dialog.includes('没有采集记录') && !dialog.includes('失败'), dialog.slice(0, 240));

await evaluate(`[...document.querySelectorAll('.eq-dialog-wrap .eq-tab')].find(t => t.textContent.trim() === '规则版本')?.click()`);
await sleep(1500);
const rules = await evaluate(`document.querySelector('.eq-dialog-wrap')?.innerText ?? ''`);
check('规则版本视图可切换且有空态',
  rules.includes('规则版本') && (rules.includes('还没有规则版本') || rules.includes('生效')), rules.slice(0, 200));
check('规则视图不提供编辑入口', !rules.includes('保存规则') && !rules.includes('修改阈值'), rules.slice(-160));

const shot = await send('Page.captureScreenshot', {format: 'png'});
writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));
console.log(`截图已保存：${OUT}`);

await evaluate(`[...document.querySelectorAll('.eq-dialog-foot button')].find(b => b.textContent.trim() === '关闭')?.click()`);
await sleep(600);

// ---------------- 新增与修改（P03/P04） ----------------

// React 受控输入必须走原生 setter + input 事件，直接改 value 不会触发 state 更新。
await evaluate(`window.__setValue = (el, value) => {
  const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement : HTMLInputElement;
  Object.getOwnPropertyDescriptor(proto.prototype, 'value').set.call(el, value);
  el.dispatchEvent(new Event('input', {bubbles: true}));
};`);

const uiCode = `UI-AUTOTEST-${Date.now()}`;
await evaluate(`[...document.querySelectorAll('.eq-primary-button')].find(b => b.textContent.trim() === '新增测点')?.click()`);
await sleep(700);
const createForm = await evaluate(`document.querySelector('.eq-dialog-wrap')?.innerText ?? ''`);
check('新增按钮弹出测点表单', createForm.includes('新增测点') && createForm.includes('测点编码'), createForm.slice(0, 120));
check('表单说明待配置而非已对接',
  createForm.includes('待配置') && !createForm.includes('已对接'), createForm.slice(0, 160));
check('表单不含采集来源字段', !createForm.includes('采集来源'), createForm.slice(0, 200));

await evaluate(`[...document.querySelectorAll('.eq-dialog-foot button')].find(b => b.textContent.includes('新增测点'))?.click()`);
await sleep(400);
const requiredError = await evaluate(`document.querySelector('.eq-dialog-wrap .eq-form-error')?.textContent?.trim() ?? ''`);
check('未填编码时前端先行拦截', requiredError.includes('编码'), requiredError);

await evaluate(`window.__setValue(document.querySelector('.eq-dialog-wrap input[placeholder="如 TEMP-011"]'), '${uiCode}')`);
await evaluate(`window.__setValue(document.querySelector('.eq-dialog-wrap input[placeholder="如 炉壳温度"]'), '验收用测点')`);
await evaluate(`window.__setValue(document.querySelector('.eq-dialog-wrap input[placeholder="如 东侧炉壳"]'), '验收位置')`);
await sleep(200);
await evaluate(`[...document.querySelectorAll('.eq-dialog-foot button')].find(b => b.textContent.includes('新增测点'))?.click()`);
await sleep(2200);

const afterCreate = await evaluate(`document.querySelector('.eq-drawer-body')?.innerText ?? ''`);
check('新增后列表出现该测点', afterCreate.includes(uiCode), afterCreate.slice(0, 200));
check('新测点标记为暂无数据', afterCreate.includes('暂无数据'), afterCreate.slice(0, 240));

await evaluate(`(() => {
  const head = [...document.querySelectorAll('.eq-sensor-head')]
    .find(h => h.innerText.includes('${uiCode}'));
  head?.click();
})()`);
await sleep(2000);
const newDetail = await evaluate(`document.querySelector('.eq-sensor-body')?.innerText ?? ''`);
check('新测点详情显示待配置', newDetail.includes('待配置'), newDetail.slice(0, 160));
check('新测点没有参数时给出空态', newDetail.includes('还没有参数定义'), newDetail.slice(0, 200));

await evaluate(`[...document.querySelectorAll('.eq-sensor-body .eq-row-link')].find(b => b.textContent.trim() === '修改')?.click()`);
await sleep(800);
const editForm = await evaluate(`document.querySelector('.eq-dialog-wrap')?.innerText ?? ''`);
check('修改入口打开编辑表单', editForm.includes('修改测点') && editForm.includes('测点 ID'), editForm.slice(0, 120));
check('无采集记录时编码可编辑',
  (await evaluate(`document.querySelector('.eq-dialog-wrap input[placeholder="如 TEMP-011"]')?.disabled`)) === false);

await evaluate(`document.querySelector('.eq-dialog-wrap input[type=checkbox]')?.click()`);
await sleep(200);
const beforeShot = await send('Page.captureScreenshot', {format: 'png'});
writeFileSync(OUT.replace('sensor-tab', 'sensor-form'), Buffer.from(beforeShot.result.data, 'base64'));
await evaluate(`[...document.querySelectorAll('.eq-dialog-foot button')].find(b => b.textContent.includes('保存修改'))?.click()`);
await sleep(2200);

const afterEdit = await evaluate(`document.querySelector('.eq-drawer-body')?.innerText ?? ''`);
check('停用后列表显示已停用', afterEdit.includes('已停用'), afterEdit.slice(0, 240));

const listShot = await send('Page.captureScreenshot', {format: 'png'});
const listOut = OUT.replace(/\.png$/, '-list.png');
writeFileSync(listOut, Buffer.from(listShot.result.data, 'base64'));
console.log(`列表截图已保存：${listOut}`);
console.log(`本次页面创建的测点编码：${uiCode}（清理请用 scripts 里打印的 SQL 模板）`);

const failed = report.filter(item => !item.ok);
console.log(`\n通过 ${report.length - failed.length} 项，失败 ${failed.length} 项`);
socket.close();
process.exit(failed.length === 0 ? 0 : 1);
