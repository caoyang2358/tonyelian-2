// 读数精度一致性：参数行按 decimalPlaces 显示，历史读数列表也必须一致，
// 不能出现参数行 1092、历史 1092.000000 这种"同一个值两种写法"。
// 依赖演示读数（scripts/seed_demo_readings.py）；没有采集记录时本脚本只做跳过说明，不算失败。
import { writeFileSync } from 'node:fs';

const PORT = Number(process.env.CDP_PORT ?? 9337);
const OUT = process.env.SHOT ?? 'C:/Users/29514/AppData/Local/Temp/reading-precision.png';

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

await evaluate(`(() => {
  const rows = [...document.querySelectorAll('tbody tr')];
  const row = rows.find(r => r.innerText.includes('SMELT-FUR-01')) ?? rows[0];
  row?.querySelector('.eq-row-link')?.click();
})()`);
await sleep(1800);
await evaluate(`[...document.querySelectorAll('.eq-tab')].find(t => t.textContent.trim() === '测点')?.click()`);
await sleep(2000);

// TEMP-001 是演示读数的落点（ods 与 ads 都有值），才验得到两种视图的对照。
await evaluate(`(() => {
  const head = [...document.querySelectorAll('.eq-sensor-head')].find(h => h.innerText.includes('TEMP-001'));
  if (head && head.getAttribute('aria-expanded') !== 'true') head.click();
})()`);
await sleep(1800);

const paramRow = await evaluate(`(() => {
  const row = [...document.querySelectorAll('.eq-sensor-param')]
    .find(p => p.innerText.includes('temperature'));
  return row ? {
    value: row.querySelector('.eq-sensor-value b')?.textContent?.trim() ?? '',
    meta: row.querySelector('.eq-sensor-value small')?.textContent?.trim() ?? ''
  } : null;
})()`);
check('展开 TEMP-001 并取到参数行读数', Boolean(paramRow?.value), JSON.stringify(paramRow));
check('参数行读数不是待采集（演示读数已灌入）',
  Boolean(paramRow?.value) && paramRow.value !== '待采集' && paramRow.value !== '—', paramRow?.value);

const expectedDecimals = (() => {
  const value = paramRow?.value ?? '';
  return value.includes('.') ? value.split('.')[1].length : 0;
})();
check('参数行按 decimalPlaces 显示且无多余小数位',
  /^-?\d+(\.\d+)?$/.test(paramRow?.value ?? '') && !/\.\d{4,}$/.test(paramRow?.value ?? ''),
  `${paramRow?.value}（小数位 ${expectedDecimals}）`);

await evaluate(`(() => {
  const row = [...document.querySelectorAll('.eq-sensor-param')]
    .find(p => p.innerText.includes('temperature'));
  [...(row?.querySelectorAll('.eq-row-link') ?? [])].find(b => b.textContent.includes('历史'))?.click();
})()`);
await sleep(2200);

const records = await evaluate(`[...document.querySelectorAll('.eq-sensor-record-value')].map(v => ({
  value: v.childNodes[0]?.textContent?.trim() ?? '',
  unit: v.querySelector('small')?.textContent?.trim() ?? ''
}))`);
check('历史读数弹层已打开', (await evaluate(`document.querySelector('.eq-dialog-wrap')?.innerText ?? ''`)).includes('只读视图'));

if (records.length === 0) {
  console.log('SKIP  这个区间没有采集记录，精度对照无从验证（先跑 scripts/seed_demo_readings.py）。');
} else {
  const decimalsOf = text => (text.includes('.') ? text.split('.')[1].length : 0);
  check('历史读数按参数精度显示，不再原样输出 6 位小数',
    records.every(r => decimalsOf(r.value) === expectedDecimals),
    JSON.stringify(records.slice(0, 3)));
  check('历史最新一条与参数行读数写法完全一致',
    records[0].value === paramRow.value, `历史 ${records[0].value} / 参数行 ${paramRow.value}`);
  check('历史读数带单位', records.every(r => r.unit.length > 0), JSON.stringify(records.slice(0, 2)));
  check('质量码与来源仍在（精度改动没有挤掉元信息）',
    (await evaluate(`document.querySelector('.eq-dialog-wrap')?.innerText ?? ''`)).includes('DEMO-SEED'));
}

const shot = await send('Page.captureScreenshot', {format: 'png'});
writeFileSync(OUT, Buffer.from(shot.result.data, 'base64'));

const failed = report.filter(item => !item.ok);
console.log(`\n合计 ${report.length} 项，通过 ${report.length - failed.length} 项，失败 ${failed.length} 项。截图：${OUT}`);
socket.close();
process.exit(failed.length === 0 ? 0 : 1);
