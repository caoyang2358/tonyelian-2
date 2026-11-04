#!/usr/bin/env node
/**
 * 布局回归：左侧导航在长页面滚动、抽屉打开、窄屏与打印下都应保持正确行为。
 *
 * 背景：设备管理页内容远长于视口，而侧栏原先只是网格里的普通条目
 * （position: static，高度被拉伸到整页高），因此下拉页面时导航会一起滚走。
 * 运维管理等页面内容不足一屏，问题看不出来，所以这条最容易在后续改样式时回归。
 *
 * 用法：先启动前端（默认 5174）与带 --remote-debugging-port 的 Chrome，
 *   CDP_PORT=9338 node scripts/ui-check-layout.mjs
 */
const PORT = process.env.CDP_PORT || '9338';
const APP = process.env.APP_URL || 'http://127.0.0.1:5174/';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0, failed = 0;
const check = (name, ok, detail = '') => {
  if (ok) { passed++; console.log(`  PASS  ${name}${detail ? ' — ' + detail : ''}`); }
  else { failed++; console.log(`  FAIL  ${name}${detail ? ' — ' + detail : ''}`); }
};

const created = await fetch(`http://127.0.0.1:${PORT}/json/new`, {method: 'PUT'});
const target = await created.json();
const ws = new WebSocket(target.webSocketDebuggerUrl);
let seq = 0;
const pending = new Map();
ws.addEventListener('message', event => {
  const message = JSON.parse(event.data);
  if (message.id && pending.has(message.id)) { pending.get(message.id)(message); pending.delete(message.id); }
});
await new Promise(resolve => ws.addEventListener('open', resolve));
const send = (method, params = {}) => new Promise(resolve => {
  const id = ++seq;
  pending.set(id, resolve);
  ws.send(JSON.stringify({id, method, params}));
});
const evaluate = async expression => {
  const reply = await send('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
  if (reply.result?.exceptionDetails) throw new Error(JSON.stringify(reply.result.exceptionDetails).slice(0, 300));
  return reply.result?.result?.value;
};
const setView = (width, height) => send('Emulation.setDeviceMetricsOverride',
  {width, height, deviceScaleFactor: 1, mobile: false});
const box = () => evaluate(`(() => {
  const el = document.querySelector('.sidebar');
  const rect = el.getBoundingClientRect();
  return JSON.stringify({
    top: Math.round(rect.top), left: Math.round(rect.left),
    width: Math.round(rect.width), height: Math.round(rect.height),
    position: getComputedStyle(el).position,
    innerScrollable: el.scrollHeight > el.clientHeight + 1,
    scrollTop: el.scrollTop,
    docHeight: document.documentElement.scrollHeight,
    viewHeight: window.innerHeight,
    scrollY: Math.round(window.scrollY),
    brandTop: Math.round(document.querySelector('.brand-copy').getBoundingClientRect().top),
    overflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth
  });
})()`);

await send('Page.enable');
await send('Runtime.enable');
await setView(1440, 800);
await send('Page.navigate', {url: APP});
await sleep(2600);

console.log('== 1. 桌面：长页面滚动时侧栏固定 ==');
let state = JSON.parse(await box());
check('设备管理页内容高于视口（本用例的前提成立）', state.docHeight > state.viewHeight + 200,
  `文档 ${state.docHeight} / 视口 ${state.viewHeight}`);
check('侧栏使用粘性定位', state.position === 'sticky', state.position);
check('侧栏高度等于视口高度', state.height === state.viewHeight, `${state.height} vs ${state.viewHeight}`);
check('侧栏宽度为栅格声明的 250px', state.width === 250, `${state.width}px`);
check('页面无横向溢出', state.overflowX <= 0, `${state.overflowX}px`);

for (const offset of [200, 500, 2000]) {
  await evaluate(`window.scrollTo(0, ${offset})`);
  await sleep(300);
  state = JSON.parse(await box());
  check(`滚到 ${offset}px 后侧栏仍贴顶`, state.top === 0, `top=${state.top}, scrollY=${state.scrollY}`);
  check(`滚到 ${offset}px 后品牌标题仍在视口内`, state.brandTop >= 0 && state.brandTop < state.viewHeight, `brandTop=${state.brandTop}`);
}
await evaluate('window.scrollTo(0, 0)');
await sleep(250);

console.log('== 2. 窄屏（≤1150）仍是固定侧栏 ==');
await setView(1100, 800);
await sleep(400);
await evaluate('window.scrollTo(0, 400)');
await sleep(300);
state = JSON.parse(await box());
check('1100px 宽度下侧栏仍贴顶', state.top === 0, `top=${state.top}`);
check('1100px 宽度下侧栏收窄为 215px', state.width === 215, `${state.width}px`);
await evaluate('window.scrollTo(0, 0)');
await sleep(250);

console.log('== 3. 极矮视口：导航改为侧栏内部滚动 ==');
await setView(1440, 500);
await sleep(450);
state = JSON.parse(await box());
check('视口 500px 时侧栏高度跟随视口', state.height === 500, `${state.height}`);
check('导航内容超出侧栏高度，内部可滚', state.innerScrollable, `scrollHeight>clientHeight=${state.innerScrollable}`);
await evaluate(`document.querySelector('.sidebar').scrollTop = 200`);
await sleep(300);
state = JSON.parse(await box());
check('侧栏内部可独立滚动（页面本身不动）', state.scrollTop > 0 && state.scrollY === 0,
  `sidebarScrollTop=${state.scrollTop}, scrollY=${state.scrollY}`);
await evaluate(`document.querySelector('.sidebar').scrollTop = 0; document.activeElement?.blur()`);
await sleep(200);

console.log('== 4. 键盘可达：Tab 到末项时侧栏自行滚动 ==');
await evaluate(`(() => {
  const items = [...document.querySelectorAll('.navigation-item')];
  items[items.length - 1].focus();
})()`);
await sleep(350);
const focused = await evaluate(`JSON.stringify({
  sidebarScrollTop: Math.round(document.querySelector('.sidebar').scrollTop),
  label: document.activeElement?.textContent?.slice(0, 6),
  visibleInViewport: (() => { const r = document.activeElement.getBoundingClientRect(); return r.top >= 0 && r.bottom <= window.innerHeight; })()
})`);
const focusState = JSON.parse(focused);
check('末项获得焦点后侧栏自动滚动以露出该项', focusState.sidebarScrollTop > 0, `scrollTop=${focusState.sidebarScrollTop}`);
check('获焦项完整落在视口内', focusState.visibleInViewport, `focus=${focusState.label}`);
await evaluate(`document.querySelector('.sidebar').scrollTop = 0; document.activeElement?.blur()`);

console.log('== 5. 七个导航页都保持固定侧栏 ==');
await setView(1440, 800);
await sleep(400);
const labels = JSON.parse(await evaluate(`JSON.stringify([...document.querySelectorAll('.navigation-item')].map(b => b.textContent.replace('待开发','').trim()))`));
for (const label of labels) {
  await evaluate(`(() => {
    const btn = [...document.querySelectorAll('.navigation-item')].find(b => b.textContent.includes(${JSON.stringify(label)}));
    btn?.click();
  })()`);
  await sleep(600);
  const current = JSON.parse(await evaluate(`JSON.stringify({
    page: document.querySelector('.breadcrumb strong')?.textContent,
    position: getComputedStyle(document.querySelector('.sidebar')).position,
    height: Math.round(document.querySelector('.sidebar').getBoundingClientRect().height),
    top: Math.round(document.querySelector('.sidebar').getBoundingClientRect().top)
  })`));
  check(`「${label}」页侧栏仍为粘性定位`, current.position === 'sticky' && current.page === label,
    `position=${current.position}, page=${current.page}, height=${current.height}`);
}
await evaluate(`(() => {
  const btn = [...document.querySelectorAll('.navigation-item')].find(b => b.textContent.includes('设备管理'));
  btn?.click();
})()`);
await sleep(800);

console.log('== 6. 抽屉打开期间侧栏不跳位 ==');
await evaluate('window.scrollTo(0, 400)');
await sleep(350);
await evaluate(`[...document.querySelectorAll('tbody tr')][0]?.querySelector('.eq-row-link, button, a')?.click()`);
await sleep(1200);
const opened = JSON.parse(await evaluate(`JSON.stringify({
  drawer: !!document.querySelector('.eq-overlay'),
  sidebarTop: Math.round(document.querySelector('.sidebar').getBoundingClientRect().top),
  sidebarWidth: Math.round(document.querySelector('.sidebar').getBoundingClientRect().width),
  scrollY: Math.round(window.scrollY)
})`));
check('详情抽屉已打开（本用例的前提成立）', opened.drawer, `${opened.drawer}`);
check('抽屉打开、body 锁滚动时侧栏仍贴顶', opened.sidebarTop === 0, `top=${opened.sidebarTop}, scrollY=${opened.scrollY}`);
check('抽屉打开期间侧栏宽度未变化', opened.sidebarWidth === 250, `${opened.sidebarWidth}px`);
await evaluate(`document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}))`);
await sleep(900);
const closed = JSON.parse(await evaluate(`JSON.stringify({
  drawer: !!document.querySelector('.eq-overlay'),
  sidebarTop: Math.round(document.querySelector('.sidebar').getBoundingClientRect().top),
  scrollY: Math.round(window.scrollY)
})`));
check('Esc 关闭后侧栏仍贴顶且滚动位置保持', closed.drawer === false && closed.sidebarTop === 0 && closed.scrollY === 400,
  `drawer=${closed.drawer}, top=${closed.sidebarTop}, scrollY=${closed.scrollY}`);
await evaluate('window.scrollTo(0, 0)');
await sleep(250);

console.log('== 7. 打印：侧栏回到普通文档流，不被裁成一页 ==');
await send('Emulation.setEmulatedMedia', {media: 'print'});
await sleep(400);
state = JSON.parse(await box());
check('打印媒体下侧栏为 static', state.position === 'static', state.position);
check('打印媒体下侧栏不锁视口高度', state.height !== state.viewHeight, `height=${state.height}, viewHeight=${state.viewHeight}`);
await send('Emulation.setEmulatedMedia', {media: ''});
await sleep(300);

console.log('== 8. 手机断点（≤760）恢复普通文档流 ==');
await setView(700, 800);
await sleep(450);
await evaluate('window.scrollTo(0, 300)');
await sleep(350);
state = JSON.parse(await box());
check('手机断点侧栏改为 static', state.position === 'static', state.position);
check('手机断点侧栏不再锁视口高度', state.height < state.viewHeight, `height=${state.height}, viewHeight=${state.viewHeight}`);
check('手机断点侧栏随页面正常上移（不再粘住）', state.top < 0, `top=${state.top}, scrollY=${state.scrollY}`);
check('手机断点页面无横向溢出', state.overflowX <= 0, `${state.overflowX}px`);

await fetch(`http://127.0.0.1:${PORT}/json/close/${target.id}`);
ws.close();

console.log(`\n结果：通过 ${passed} 项，失败 ${failed} 项`);
process.exit(failed === 0 ? 0 : 1);
