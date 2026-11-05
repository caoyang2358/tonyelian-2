import { test } from "node:test";
import assert from "node:assert/strict";
import { knowledgeApi, KnowledgeError } from "../src/lib/knowledgeApi.ts";
const original = globalThis.fetch;
test("knowledge filters are intersected in one request and preserve page", async () => {
  globalThis.fetch = async (input) => {
    const url = new URL(String(input), "http://localhost");
    assert.equal(url.searchParams.get("equipmentId"), "8");
    assert.equal(url.searchParams.get("category"), "COMMON_FAULT");
    assert.equal(url.searchParams.get("keyword"), "振动");
    assert.equal(url.searchParams.get("page"), "2");
    return Response.json({
      code: "OK",
      timestamp: "2026-11-05T08:00:00+08:00",
      data: { items: [], total: 0 },
    });
  };
  try {
    assert.equal(
      (
        await knowledgeApi.list(
          { equipmentId: "8", category: "COMMON_FAULT", keyword: "振动" },
          2,
        )
      ).total,
      0,
    );
  } finally {
    globalThis.fetch = original;
  }
});
test("knowledge version conflict preserves fields and request body", async () => {
  globalThis.fetch = async (_input, init) => {
    const b = JSON.parse(String(init?.body));
    assert.equal(b.expectedVersion, 1);
    assert.deepEqual(b.steps, ["二", "一"]);
    assert.deepEqual(b.attachmentIds, []);
    return Response.json(
      {
        code: "VERSION_CONFLICT",
        message: "重新读取",
        data: { fields: { title: "核对" } },
      },
      { status: 409 },
    );
  };
  try {
    await assert.rejects(
      () =>
        knowledgeApi.save("1", {
          requestId: "retry",
          operatorName: "张工",
          expectedVersion: 1,
          reason: "更正",
          title: "案例",
          category: "REPAIR_CASE",
          equipmentId: "8",
          equipmentType: null,
          faultPhenomenon: null,
          content: null,
          steps: ["二", "一"],
          attachmentIds: [],
          sourceRepairOrderId: null,
        }),
      (e) =>
        e instanceof KnowledgeError &&
        e.code === "VERSION_CONFLICT" &&
        e.fields.title === "核对",
    );
  } finally {
    globalThis.fetch = original;
  }
});
