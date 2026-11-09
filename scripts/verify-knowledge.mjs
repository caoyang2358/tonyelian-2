// Run only against the isolated local development database, with the backend running.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { writeFileSync, readFileSync } from "node:fs";
const base =
  process.env.KNOWLEDGE_VERIFY_URL ?? "http://127.0.0.1:18280/api/v1";
async function call(path, body, method = body ? "POST" : "GET", status = 200) {
  const r = await fetch(base + path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  const e = await r.json();
  assert.equal(r.status, status, JSON.stringify(e));
  return e;
}
const meta = () => ({ requestId: randomUUID(), operatorName: "知识库验收" });
if (process.argv.includes("--read-after-restart")) {
  const ids = JSON.parse(
    readFileSync(".local/knowledge-verification.json", "utf8"),
  );
  for (const id of ids)
    assert.equal((await call("/knowledge-articles/" + id)).data.articleId, id);
  console.log("PASS restart persistence", ids);
  process.exit(0);
}
const eq = (await call("/equipment")).data.items.find(
  (e) => e.code === "KNOWLEDGE-VERIFY",
);
assert.ok(eq, "Create KNOWLEDGE-VERIFY fixture first");
const equipmentId = String(eq.id),
  tag = "知识库验收-" + Date.now();
const draft = (extra = {}) => ({
  ...meta(),
  title: tag,
  category: "INTRODUCTION",
  equipmentId,
  equipmentType: "OTHER",
  faultPhenomenon: null,
  content: "安全文本 <img src=x onerror=alert(1)>",
  steps: [],
  attachmentIds: [],
  sourceRepairOrderId: null,
  ...extra,
});
const a = (await call("/knowledge-articles", draft())).data;
const type = (await call("/knowledge-articles", draft({ equipmentId: null })))
  .data;
const query = `/knowledge-articles?equipmentId=${equipmentId}&equipmentType=OTHER&category=INTRODUCTION&keyword=${encodeURIComponent(tag)}&pageSize=1`;
const first = (await call(query)).data,
  second = (await call(query + "&page=2")).data;
assert.equal(first.total, 2);
assert.equal(second.total, 2);
assert.notEqual(first.items[0].articleId, second.items[0].articleId);
const put = draft({
  expectedVersion: a.version,
  reason: "调整步骤并清空可空字段",
  content: null,
  steps: ["第二步", "第一步"],
  equipmentType: null,
});
const updated = (await call("/knowledge-articles/" + a.articleId, put, "PUT"))
  .data;
assert.deepEqual(updated.steps, ["第二步", "第一步"]);
assert.equal(updated.content, null);
assert.equal(updated.equipmentType, null);
assert.deepEqual(
  (await call("/knowledge-articles/" + a.articleId, put, "PUT")).data,
  updated,
);
assert.equal(
  (
    await call(
      "/knowledge-articles/" + a.articleId,
      { ...put, ...meta() },
      "PUT",
      409,
    )
  ).code,
  "VERSION_CONFLICT",
);
assert.equal(
  (
    await call(
      "/knowledge-articles/" + a.articleId,
      { ...put, title: "改内容" },
      "PUT",
      409,
    )
  ).code,
  "IDEMPOTENCY_CONFLICT",
);
const contenders = await Promise.all(
  [1, 2].map((n) =>
    fetch(base + "/knowledge-articles/" + a.articleId, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        ...put,
        ...meta(),
        expectedVersion: updated.version,
        title: tag + "并发" + n,
      }),
    }),
  ),
);
assert.deepEqual(contenders.map((r) => r.status).sort(), [200, 409]);
const same = draft({ title: tag + "同请求并发" });
const repeated = await Promise.all([
  call("/knowledge-articles", same),
  call("/knowledge-articles", same),
]);
assert.equal(repeated[0].data.articleId, repeated[1].data.articleId);
async function upload(ownerType, ownerId, fileKind, content, type, name) {
  const f = new FormData();
  Object.entries({ ...meta(), ownerType, ownerId, fileKind }).forEach(
    ([k, v]) => f.append(k, v),
  );
  f.append("file", new Blob([content], { type }), name);
  const r = await fetch(base + "/attachments", { method: "POST", body: f });
  const e = await r.json();
  assert.equal(r.status, 200, JSON.stringify(e));
  return e.data;
}
const manual = await upload(
  "EQUIPMENT",
  equipmentId,
  "MANUAL",
  "%PDF-1.4\nknowledge test",
  "application/pdf",
  "知识验收手册.pdf",
);
const refs = [];
for (let i = 0; i < 2; i++)
  refs.push(
    (
      await call(
        "/knowledge-articles",
        draft({ attachmentIds: [manual.attachmentId] }),
      )
    ).data,
  );
assert.equal(refs[0].attachmentIds[0], refs[1].attachmentIds[0]);
assert.equal(
  (await fetch(base + `/attachments/${manual.attachmentId}/content`)).status,
  200,
);
const png = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
  "base64",
);
const image = await upload(
  "KNOWLEDGE_ARTICLE",
  type.articleId,
  "IMAGE",
  png,
  "image/png",
  "知识验收.png",
);
const imageArticle = (await call("/knowledge-articles/" + type.articleId)).data;
assert.equal(imageArticle.version, type.version + 1);
assert.ok(imageArticle.attachmentIds.includes(image.attachmentId));
assert.equal(
  (
    await call(
      "/knowledge-articles/" + type.articleId,
      draft({ expectedVersion: type.version, reason: "旧表单" }),
      "PUT",
      409,
    )
  ).code,
  "VERSION_CONFLICT",
);
const cleared = (
  await call(
    "/knowledge-articles/" + type.articleId,
    draft({
      expectedVersion: imageArticle.version,
      reason: "清空附件",
      attachmentIds: [],
    }),
    "PUT",
  )
).data;
assert.deepEqual(cleared.attachmentIds, []);
let order = (
  await call("/repair-orders", { ...meta(), sourceType: "MANUAL", equipmentId })
).data;
const oid = order.repairOrderId;
const caseDraft = () =>
  draft({ category: "REPAIR_CASE", sourceRepairOrderId: oid });
assert.equal(
  (await call("/knowledge-articles", caseDraft(), "POST", 409)).code,
  "STATE_CONFLICT",
);
await call(
  "/repair-orders/" + oid,
  {
    ...meta(),
    expectedVersion: order.version,
    reason: "完善故障",
    description: "验收测试故障",
    supplement: null,
    reporterName: "验收人",
    photoAttachmentIds: [],
  },
  "PUT",
);
order = (await call("/repair-orders/" + oid)).data;
for (const [endpoint, fields] of [
  ["submissions", {}],
  ["reviews", { result: "APPROVE", reason: "需要维修" }],
  [
    "repair-results",
    {
      description: "测试维修结果",
      performedAt: new Date().toISOString(),
      attachmentIds: [],
    },
  ],
]) {
  order = (
    await call(`/repair-orders/${oid}/${endpoint}`, {
      ...meta(),
      expectedVersion: order.version,
      ...fields,
    })
  ).data;
}
assert.equal(order.status, "ACCEPTANCE_PENDING");
assert.equal(
  (await call("/knowledge-articles", caseDraft(), "POST", 409)).code,
  "STATE_CONFLICT",
);
order = (
  await call(`/repair-orders/${oid}/acceptances`, {
    ...meta(),
    expectedVersion: order.version,
    result: "FAIL",
    acceptedAt: new Date().toISOString(),
    reason: "未通过",
  })
).data;
assert.equal(
  (await call("/knowledge-articles", caseDraft(), "POST", 409)).code,
  "STATE_CONFLICT",
);
order = (
  await call(`/repair-orders/${oid}/repair-results`, {
    ...meta(),
    expectedVersion: order.version,
    description: "补充维修",
    performedAt: new Date().toISOString(),
    attachmentIds: [],
  })
).data;
order = (
  await call(`/repair-orders/${oid}/acceptances`, {
    ...meta(),
    expectedVersion: order.version,
    result: "PASS",
    acceptedAt: new Date().toISOString(),
    reason: "通过",
  })
).data;
assert.equal(order.status, "COMPLETED");
const repairCase = (await call("/knowledge-articles", caseDraft())).data;
assert.equal(repairCase.sourceRepairOrderId, oid);
await call(
  "/knowledge-articles",
  draft({
    category: "REPAIR_CASE",
    equipmentId: null,
    equipmentType: "ROLLING_MILL",
    sourceRepairOrderId: oid,
  }),
  "POST",
  400,
);
const caseEdit = (
  await call(
    "/knowledge-articles/" + repairCase.articleId,
    draft({
      category: "REPAIR_CASE",
      expectedVersion: repairCase.version,
      reason: "核对来源",
      sourceRepairOrderId: null,
    }),
    "PUT",
  )
).data;
assert.equal(caseEdit.sourceRepairOrderId, oid);
await call(
  "/knowledge-articles",
  draft({ category: "TROUBLESHOOTING", steps: [], faultPhenomenon: "异常" }),
  "POST",
  400,
);
await call(
  "/knowledge-articles",
  draft({ attachmentIds: [manual.attachmentId, manual.attachmentId] }),
  "POST",
  400,
);
const rollback = draft({ attachmentIds: ["999999999999"] });
await call("/knowledge-articles", rollback, "POST", 404);
const retryResult = (
  await call("/knowledge-articles", { ...rollback, attachmentIds: [] })
).data;
assert.ok(retryResult.articleId);
writeFileSync(
  ".local/knowledge-verification.json",
  JSON.stringify([a.articleId, type.articleId, repairCase.articleId]),
);
console.log(
  "PASS: persistence, filters/dedup/paging, ordered steps, nullable fields, concurrent versions, concurrent idempotency, shared manual, upload version, clearing attachments, repair states/source preservation, rollback",
);
