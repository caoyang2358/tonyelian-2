import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
const target = await (
  await fetch("http://127.0.0.1:9338/json/new", { method: "PUT" })
).json();
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener("open", r));
let seq = 0;
const pending = new Map();
ws.addEventListener("message", (e) => {
  const m = JSON.parse(e.data);
  if (m.id) {
    pending.get(m.id)?.(m);
    pending.delete(m.id);
  }
});
const send = (method, params = {}) =>
  new Promise((r) => {
    const id = ++seq;
    pending.set(id, r);
    ws.send(JSON.stringify({ id, method, params }));
  });
const run = async (expression) => {
  const r = await send("Runtime.evaluate", {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  if (r.result.exceptionDetails)
    throw Error(JSON.stringify(r.result.exceptionDetails));
  return r.result.result.value;
};
const wait = () => new Promise((r) => setTimeout(r, 600));
const click = async (text) => {
  assert.ok(
    await run(
      `(()=>{const b=[...document.querySelectorAll('button')].find(b=>(b.textContent.trim()===${JSON.stringify(text)}||b.querySelector('span')?.textContent===${JSON.stringify(text)}));if(!b)return false;b.click();return true})()`,
    ),
    text,
  );
  await wait();
};
await send("Page.navigate", { url: "http://127.0.0.1:5174/" });
await wait();
await click("运维知识库");
assert.ok(await run("document.body.innerText.includes('选择设备和故障')"));
await click("新增知识");
assert.equal(
  await run('document.querySelectorAll(".knowledge-page form").length'),
  1,
);
await click("添加步骤");
await click("添加步骤");
assert.equal(
  await run(
    'document.querySelectorAll(".knowledge-page fieldset textarea").length',
  ),
  2,
);
await click("取消编辑");
const firstTitle = await run(
  "document.querySelector('.knowledge-page ul button')?.textContent",
);
assert.ok(firstTitle);
await click(firstTitle);
assert.equal(
  await run('document.querySelectorAll(".knowledge-page article").length'),
  1,
);
assert.equal(
  await run('document.querySelectorAll(".knowledge-page article img").length'),
  0,
);
// React must render the stored HTML-looking content as text.
assert.equal(
  await run(
    'document.querySelectorAll(".knowledge-page article [onerror]").length',
  ),
  0,
);
await send("Page.captureScreenshot", { format: "png" }).then((r) =>
  writeFileSync(
    ".local/knowledge-page.png",
    Buffer.from(r.result.data, "base64"),
  ),
);
await click("设备管理");
assert.ok(await run("document.body.innerText.includes('设备')"));
await click("运维管理");
assert.ok(await run("document.body.innerText.includes('维修')"));
await click("设备组网");
assert.ok(await run("document.body.innerText.includes('车间')"));
await click("运维知识库");
// Force an unavailable list API and verify explicit failure + retry.
await run(
  `window.__originalFetch=window.fetch;window.fetch=(input,init)=>String(input).includes('/knowledge-articles?')?Promise.reject(new TypeError('offline')):window.__originalFetch(input,init)`,
);
await run(
  `(()=>{const input=[...document.querySelectorAll('.knowledge-page input')][0];const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;setter.call(input,'失败测试');input.dispatchEvent(new Event('input',{bubbles:true}));})()`,
);
await wait();
assert.ok(
  await run("document.querySelector('.knowledge-page [role=alert]')!==null"),
);
await run("window.fetch=window.__originalFetch");
await click("重试");
const allEquipment = await (
  await fetch("http://127.0.0.1:18280/api/v1/equipment")
).json();
const missingType = allEquipment.data.items.find((e) => !e.typeCode);
if (missingType) {
  const body = {
    requestId: crypto.randomUUID(),
    operatorName: "页面验收",
    title: "缺类型与无图验证",
    category: "INTRODUCTION",
    equipmentId: String(missingType.id),
    equipmentType: null,
    faultPhenomenon: null,
    content: "<img onerror=alert(1)> 应显示为安全文本",
    steps: [],
    attachmentIds: [],
  };
  const created = await (
    await fetch("http://127.0.0.1:18280/api/v1/knowledge-articles", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    })
  ).json();
  assert.equal(created.code, "OK");
  await click("设备管理");
  await click("运维知识库");
  await click(body.title);
  assert.ok(
    await run(
      "document.querySelector('.knowledge-page article').innerText.includes('待补充')",
    ),
  );
  assert.equal(
    await run(
      "document.querySelectorAll('.knowledge-page article img').length",
    ),
    0,
  );
}
const articles = await (
  await fetch("http://127.0.0.1:18280/api/v1/knowledge-articles?pageSize=100")
).json();
const withAttachment = articles.data.items.find((a) => a.attachmentIds.length);
assert.ok(withAttachment);
await run(
  `window.fetch=(input,init)=>String(input).includes('/attachments/')?Promise.reject(new TypeError('unavailable')):window.__originalFetch(input,init)`,
);
await run(
  `document.querySelectorAll('.knowledge-page > ul button')[${articles.data.items.indexOf(withAttachment)}].click()`,
);
await wait();
assert.ok(
  await run(
    "document.querySelector('.knowledge-page article').innerText.includes('不可用')",
  ),
);
assert.ok(
  await run(
    "document.querySelector('.knowledge-page article').innerText.includes('安全文本')",
  ),
);
await run("window.fetch=window.__originalFetch");
await send("Emulation.setDeviceMetricsOverride", {
  width: 1440,
  height: 1000,
  deviceScaleFactor: 1,
  mobile: false,
});
await send("Page.captureScreenshot", { format: "png" }).then((r) =>
  writeFileSync(
    ".local/knowledge-page.png",
    Buffer.from(r.result.data, "base64"),
  ),
);
console.log(
  "PASS knowledge browse/detail/form/steps/safe text/API failure/retry and equipment/maintenance/network navigation",
);
ws.close();
