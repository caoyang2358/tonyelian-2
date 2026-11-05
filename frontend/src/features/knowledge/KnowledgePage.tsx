import { useEffect, useRef, useState } from "react";
import type { PendingPageProps } from "../../components/PendingPage";
import { api } from "../../lib/api";
import type { Equipment, ReferenceOption } from "../../lib/api";
import {
  categories,
  knowledgeApi,
  KnowledgeError,
} from "../../lib/knowledgeApi";
import type { Article, KnowledgeCommand } from "../../lib/knowledgeApi";
import { newRequestId, repairOrderApi } from "../../lib/maintenanceApi";
import type {
  RepairOrderDetail,
  RepairOrderItem,
} from "../../lib/maintenanceApi";

function Attachment({ id }: { id: string }) {
  const [name, setName] = useState("读取附件…");
  const [error, setError] = useState(false);
  useEffect(() => {
    let active = true;
    api
      .attachmentDetail(id)
      .then((r) => {
        if (active) setName(r.data.originalName);
      })
      .catch(() => {
        if (active) setError(true);
      });
    return () => {
      active = false;
    };
  }, [id]);
  async function open(download: boolean) {
    try {
      const r = await fetch(api.attachmentUrl(id, download));
      if (!r.ok) throw Error();
      const url = URL.createObjectURL(await r.blob());
      const a = document.createElement("a");
      a.href = url;
      if (download) a.download = name;
      else {
        a.target = "_blank";
        a.rel = "noopener";
      }
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 60000);
    } catch {
      setError(true);
    }
  }
  return (
    <li>
      {error ? (
        `附件 ${id} 不可用，正文仍可阅读`
      ) : (
        <>
          {name}{" "}
          <button type="button" onClick={() => void open(false)}>
            打开
          </button>{" "}
          <button type="button" onClick={() => void open(true)}>
            下载
          </button>
        </>
      )}
    </li>
  );
}
const empty = (): KnowledgeCommand => ({
  requestId: newRequestId(),
  operatorName: "",
  reason: "",
  title: "",
  category: "INTRODUCTION",
  equipmentId: null,
  equipmentType: null,
  faultPhenomenon: null,
  content: null,
  steps: [],
  attachmentIds: [],
  sourceRepairOrderId: null,
});
export function KnowledgePage({ back }: PendingPageProps) {
  const [filter, setFilter] = useState({
    equipmentId: "",
    equipmentType: "",
    category: "",
    keyword: "",
  });
  const [page, setPage] = useState(1),
    [total, setTotal] = useState(0),
    [items, setItems] = useState<Article[]>([]);
  const [loading, setLoading] = useState(true),
    [error, setError] = useState(""),
    [reload, setReload] = useState(0);
  const [equipment, setEquipment] = useState<Equipment[]>([]),
    [types, setTypes] = useState<ReferenceOption[]>([]),
    [referenceError, setReferenceError] = useState("");
  const [article, setArticle] = useState<Article | null>(null),
    [detailLoading, setDetailLoading] = useState(false),
    [detailError, setDetailError] = useState("");
  const [draft, setDraft] = useState<KnowledgeCommand | null>(null),
    [editingId, setEditingId] = useState<string>(),
    [saving, setSaving] = useState(false),
    [saveError, setSaveError] = useState(""),
    [conflict, setConflict] = useState(false);
  const [orders, setOrders] = useState<RepairOrderItem[]>([]),
    [orderPage, setOrderPage] = useState(1),
    [orderTotal, setOrderTotal] = useState(0),
    [source, setSource] = useState<RepairOrderDetail | null>(null),
    [sourceError, setSourceError] = useState(""),
    [choosing, setChoosing] = useState(false);
  const [manuals, setManuals] = useState<
      { attachmentId: string; originalName: string }[]
    >([]),
    [manualPage, setManualPage] = useState(1),
    [manualTotal, setManualTotal] = useState(0),
    [manualError, setManualError] = useState("");
  const lastArticle = useRef<string | null>(null);
  const selection = useRef(0);
  const retry = useRef<{ fingerprint: string; id: string } | null>(null);
  useEffect(() => {
    let active = true;
    Promise.all([api.equipment(), api.referenceData()])
      .then(([e, t]) => {
        if (active) {
          setEquipment(e.data.items);
          setTypes(t.data.equipmentTypes);
          setReferenceError("");
        }
      })
      .catch(() => {
        if (active) setReferenceError("设备与类型读取失败，请重试");
      });
    return () => {
      active = false;
    };
  }, [reload]);
  useEffect(() => {
    const c = new AbortController();
    setLoading(true);
    setError("");
    knowledgeApi
      .list(filter, page, c.signal)
      .then((r) => {
        setItems(r.items);
        setTotal(r.total);
      })
      .catch((e) => {
        if (!c.signal.aborted) setError(e.message || "读取失败");
      })
      .finally(() => {
        if (!c.signal.aborted) setLoading(false);
      });
    return () => c.abort();
  }, [filter, page, reload]);
  useEffect(() => {
    let active = true;
    setManuals([]);
    setManualError("");
    if (draft?.equipmentId)
      api
        .attachments({
          ownerType: "EQUIPMENT",
          ownerId: draft.equipmentId,
          fileKind: "MANUAL",
          page: manualPage,
          pageSize: 20,
        })
        .then((r) => {
          if (active) {
            setManuals(r.data.items);
            setManualTotal(r.data.total ?? 0);
          }
        })
        .catch(() => {
          if (active) setManualError("手册读取失败");
        });
    return () => {
      active = false;
    };
  }, [draft?.equipmentId, manualPage, reload]);
  useEffect(() => {
    if (!choosing) return;
    let active = true;
    setSourceError("");
    repairOrderApi
      .list({ status: "COMPLETED", equipmentId: filter.equipmentId }, orderPage)
      .then((r) => {
        if (active) {
          setOrders(r.items);
          setOrderTotal(r.total);
        }
      })
      .catch((e) => {
        if (active) setSourceError(e.message);
      });
    return () => {
      active = false;
    };
  }, [choosing, orderPage, filter.equipmentId, reload]);
  function change(key: keyof typeof filter, value: string) {
    setFilter({ ...filter, [key]: value });
    setPage(1);
  }
  async function read(id: string) {
    lastArticle.current = id;
    const n = ++selection.current;
    setDetailLoading(true);
    setDetailError("");
    setArticle(null);
    try {
      const r = await knowledgeApi.detail(id);
      if (n === selection.current) setArticle(r);
    } catch (e) {
      if (n === selection.current) setDetailError((e as Error).message);
    } finally {
      if (n === selection.current) setDetailLoading(false);
    }
  }
  function edit(a?: Article) {
    setEditingId(a?.articleId);
    setDraft(
      a
        ? { ...empty(), ...a, expectedVersion: a.version, reason: "" }
        : empty(),
    );
    setSaveError("");
    setConflict(false);
    setManualPage(1);
    retry.current = null;
  }
  async function save() {
    if (!draft) return;
    if (!draft.equipmentId && !draft.equipmentType) {
      setSaveError("请选择适用设备或类型");
      return;
    }
    if (!draft.content?.trim() && !draft.steps.some((s) => s.trim())) {
      setSaveError("正文和步骤至少填写一项");
      return;
    }
    if (draft.category === "TROUBLESHOOTING" && !draft.steps.length) {
      setSaveError("人工排查必须填写步骤");
      return;
    }
    setSaving(true);
    setSaveError("");
    const fingerprint = JSON.stringify({ ...draft, requestId: "" });
    if (retry.current?.fingerprint !== fingerprint)
      retry.current = { fingerprint, id: newRequestId() };
    try {
      const a = await knowledgeApi.save(editingId, {
        ...draft,
        requestId: retry.current.id,
      });
      setArticle(a);
      setDraft(null);
      setReload((x) => x + 1);
    } catch (e) {
      setSaveError(
        (e as Error).message +
          (e instanceof KnowledgeError
            ? Object.entries(e.fields)
                .map(([k, v]) => ` ${k}: ${v}`)
                .join("")
            : ""),
      );
      if (e instanceof KnowledgeError) {
        const key = Object.keys(e.fields)[0];
        if (key)
          document.querySelector<HTMLElement>("[name=" + key + "]")?.focus();
      }
      setConflict(e instanceof KnowledgeError && e.code === "VERSION_CONFLICT");
    } finally {
      setSaving(false);
    }
  }
  async function choose(id: string) {
    try {
      const r = await repairOrderApi.detail(id);
      setSource(r);
      edit();
      setDraft({
        ...empty(),
        category: "REPAIR_CASE",
        equipmentId: r.equipmentId,
        sourceRepairOrderId: id,
        title: `维修案例：${r.orderNo}`,
        faultPhenomenon: r.description,
        content: [
          r.description,
          ...r.actions
            .filter((a) => a.actionType === "REPAIR_RESULT")
            .map((a) => a.description),
        ]
          .filter(Boolean)
          .join("\n"),
      });
      setChoosing(false);
    } catch (e) {
      setSourceError((e as Error).message);
    }
  }
  const eqLabel = (id: string | null, type: string | null) => {
    const e = equipment.find((e) => e.id === id);
    return `${e?.name ?? (id ? `设备 ${id}` : "按类型适用")} · ${types.find((t) => t.code === (e?.typeCode ?? type))?.name ?? "待补充"}`;
  };
  return (
    <section className="panel knowledge-page">
      <span className="section-kicker">系统一</span>
      <h2>运维知识库</h2>
      <p>选择设备和故障，查阅人工维护的知识与排查步骤。</p>
      <div className="toolbar">
        <button onClick={back}>返回设备</button>{" "}
        <button
          onClick={() => {
            setSource(null);
            edit();
          }}
        >
          新增知识
        </button>{" "}
        <button
          onClick={() => {
            setChoosing(true);
            setOrderPage(1);
          }}
        >
          从已验收工单整理案例
        </button>
      </div>
      {referenceError && (
        <p role="alert">
          {referenceError}
          <button onClick={() => setReload((x) => x + 1)}>重试</button>
        </p>
      )}
      <div
        style={{ display: "flex", gap: 12, flexWrap: "wrap", margin: "20px 0" }}
      >
        <label>
          设备
          <select
            value={filter.equipmentId}
            onChange={(e) => change("equipmentId", e.target.value)}
          >
            <option value="">全部设备</option>
            {equipment.map((e) => (
              <option key={e.id} value={e.id}>
                {e.name} · {e.typeName ?? "待补充"}
              </option>
            ))}
          </select>
        </label>
        <label>
          设备类型
          <select
            value={filter.equipmentType}
            onChange={(e) => change("equipmentType", e.target.value)}
          >
            <option value="">全部类型</option>
            {types.map((t) => (
              <option key={t.code} value={t.code}>
                {t.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          分类
          <select
            value={filter.category}
            onChange={(e) => change("category", e.target.value)}
          >
            <option value="">全部分类</option>
            {Object.entries(categories).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </label>
        <label>
          关键词 / 故障现象
          <input
            value={filter.keyword}
            onChange={(e) => change("keyword", e.target.value)}
          />
        </label>
      </div>
      {loading ? (
        <p role="status">正在读取知识…</p>
      ) : error ? (
        <p role="alert">
          {error}
          <button onClick={() => setReload((x) => x + 1)}>重试</button>
        </p>
      ) : (
        <>
          <p>共 {total} 篇</p>
          {items.length === 0 ? (
            <p>
              {Object.values(filter).some(Boolean)
                ? "没有匹配的知识，请调整筛选条件。"
                : "暂无知识，可新增文章或整理维修案例。"}
            </p>
          ) : (
            <ul>
              {items.map((a) => (
                <li key={a.articleId}>
                  <button onClick={() => void read(a.articleId)}>
                    {a.title}
                  </button>{" "}
                  <span>
                    {categories[a.category]} ·{" "}
                    {eqLabel(a.equipmentId, a.equipmentType)}
                  </span>
                  <p>{a.faultPhenomenon}</p>
                </li>
              ))}
            </ul>
          )}
          <button disabled={page === 1} onClick={() => setPage((x) => x - 1)}>
            上一页
          </button>{" "}
          第 {page} 页{" "}
          <button
            disabled={page * 20 >= total}
            onClick={() => setPage((x) => x + 1)}
          >
            下一页
          </button>
        </>
      )}
      {detailLoading && <p role="status">正在打开文章…</p>}
      {detailError && (
        <p role="alert">
          {detailError}
          <button
            onClick={() => {
              if (lastArticle.current) void read(lastArticle.current);
            }}
          >
            重试
          </button>
        </p>
      )}
      {article && (
        <article style={{ borderTop: "1px solid #ddd", marginTop: 24 }}>
          <h3>{article.title}</h3>
          <p>
            {categories[article.category]} ·{" "}
            {eqLabel(article.equipmentId, article.equipmentType)} · 版本{" "}
            {article.version}
          </p>
          <p>故障现象：{article.faultPhenomenon ?? "无"}</p>
          <p style={{ whiteSpace: "pre-wrap" }}>{article.content}</p>
          <ol>
            {article.steps.map((s, i) => (
              <li key={i} style={{ whiteSpace: "pre-wrap" }}>
                {s}
              </li>
            ))}
          </ol>
          <ul>
            {article.attachmentIds.map((id) => (
              <Attachment key={id} id={id} />
            ))}
          </ul>
          {!article.attachmentIds.length && (
            <p>无附件，可直接阅读正文与步骤。</p>
          )}
          {article.sourceRepairOrderId && (
            <button
              onClick={() => {
                repairOrderApi
                  .detail(article.sourceRepairOrderId!)
                  .then(setSource)
                  .catch((e) => setSourceError(e.message));
              }}
            >
              查看源工单
            </button>
          )}{" "}
          <button onClick={() => edit(article)}>编辑知识</button>
        </article>
      )}
      {choosing && (
        <aside>
          <h3>选择已验收工单</h3>
          {sourceError && (
            <p role="alert">
              {sourceError}
              <button onClick={() => setReload((x) => x + 1)}>重试</button>
            </p>
          )}
          {!orders.length && <p>暂无可选择的已验收工单</p>}
          {orders.map((o) => (
            <p key={o.repairOrderId}>
              <button onClick={() => void choose(o.repairOrderId)}>
                {o.orderNo} · {o.equipmentName}
              </button>
            </p>
          ))}
          <button
            disabled={orderPage === 1}
            onClick={() => setOrderPage((x) => x - 1)}
          >
            上一页
          </button>
          <button
            disabled={orderPage * 20 >= orderTotal}
            onClick={() => setOrderPage((x) => x + 1)}
          >
            下一页
          </button>
          <button onClick={() => setChoosing(false)}>关闭</button>
        </aside>
      )}
      {sourceError && !choosing && <p role="alert">{sourceError}</p>}
      {source && (
        <aside>
          <h3>源工单 {source.orderNo}</h3>
          <p>
            {source.equipmentName} · {source.status}
          </p>
          <p style={{ whiteSpace: "pre-wrap" }}>{source.description}</p>
          <ol>
            {source.actions.map((a) => (
              <li key={a.actionId}>
                {a.actionType}：{a.description ?? a.reason ?? a.result}
              </li>
            ))}
          </ol>
        </aside>
      )}
      {draft && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void save();
          }}
          style={{
            borderTop: "1px solid #ddd",
            marginTop: 24,
            display: "grid",
            gap: 12,
          }}
        >
          <h3>{editingId ? "编辑知识" : "新增知识"} · 请人工核对内容后保存</h3>
          <label>
            标题
            <input
              required
              maxLength={200}
              name="title"
              value={draft.title}
              onChange={(e) => setDraft({ ...draft, title: e.target.value })}
            />
          </label>
          <label>
            分类
            <select
              name="category"
              value={draft.category}
              onChange={(e) => setDraft({ ...draft, category: e.target.value })}
            >
              {Object.entries(categories).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </label>
          <label>
            适用设备
            <select
              name="equipmentId"
              value={draft.equipmentId ?? ""}
              onChange={(e) => {
                setManualPage(1);
                setDraft({ ...draft, equipmentId: e.target.value || null });
              }}
            >
              <option value="">不指定设备</option>
              {equipment.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.name} · {e.typeName ?? "待补充"}
                </option>
              ))}
            </select>
          </label>
          <label>
            适用类型
            <select
              name="equipmentType"
              value={draft.equipmentType ?? ""}
              onChange={(e) =>
                setDraft({ ...draft, equipmentType: e.target.value || null })
              }
            >
              <option value="">不指定类型</option>
              {types.map((t) => (
                <option key={t.code} value={t.code}>
                  {t.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            故障现象
            <input
              required={["COMMON_FAULT", "TROUBLESHOOTING"].includes(
                draft.category,
              )}
              name="faultPhenomenon"
              value={draft.faultPhenomenon ?? ""}
              onChange={(e) =>
                setDraft({ ...draft, faultPhenomenon: e.target.value || null })
              }
            />
          </label>
          <label>
            正文
            <textarea
              rows={7}
              name="content"
              value={draft.content ?? ""}
              onChange={(e) =>
                setDraft({ ...draft, content: e.target.value || null })
              }
            />
          </label>
          <fieldset>
            <legend>有序步骤（正文或步骤至少一项；人工排查必须有步骤）</legend>
            {draft.steps.map((s, i) => (
              <div key={i}>
                <label>
                  步骤 {i + 1}
                  <textarea
                    name="steps"
                    required
                    value={s}
                    onChange={(e) =>
                      setDraft({
                        ...draft,
                        steps: draft.steps.map((v, j) =>
                          i === j ? e.target.value : v,
                        ),
                      })
                    }
                  />
                </label>
                <button
                  type="button"
                  disabled={i === 0}
                  onClick={() => {
                    const steps = [...draft.steps];
                    [steps[i - 1], steps[i]] = [steps[i], steps[i - 1]];
                    setDraft({ ...draft, steps });
                  }}
                >
                  上移
                </button>
                <button
                  type="button"
                  disabled={i === draft.steps.length - 1}
                  onClick={() => {
                    const steps = [...draft.steps];
                    [steps[i + 1], steps[i]] = [steps[i], steps[i + 1]];
                    setDraft({ ...draft, steps });
                  }}
                >
                  下移
                </button>
                <button
                  type="button"
                  onClick={() =>
                    setDraft({
                      ...draft,
                      steps: draft.steps.filter((_, j) => j !== i),
                    })
                  }
                >
                  删除步骤
                </button>
              </div>
            ))}
            <button
              type="button"
              onClick={() =>
                setDraft({ ...draft, steps: [...draft.steps, ""] })
              }
            >
              添加步骤
            </button>
          </fieldset>
          <fieldset>
            <legend>附件引用</legend>
            {draft.attachmentIds.map((id) => (
              <p key={id}>
                附件 {id}{" "}
                <button
                  type="button"
                  onClick={() =>
                    setDraft({
                      ...draft,
                      attachmentIds: draft.attachmentIds.filter(
                        (v) => v !== id,
                      ),
                    })
                  }
                >
                  移除引用
                </button>
              </p>
            ))}
            {manualError && (
              <p role="alert">
                {manualError}
                <button type="button" onClick={() => setReload((x) => x + 1)}>
                  重试
                </button>
              </p>
            )}
            {manuals.map((m) => (
              <label key={m.attachmentId}>
                <input
                  type="checkbox"
                  checked={draft.attachmentIds.includes(m.attachmentId)}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      attachmentIds: e.target.checked
                        ? [...draft.attachmentIds, m.attachmentId]
                        : draft.attachmentIds.filter(
                            (v) => v !== m.attachmentId,
                          ),
                    })
                  }
                />
                {m.originalName}
              </label>
            ))}
            {draft.equipmentId && (
              <>
                <button
                  type="button"
                  disabled={manualPage === 1}
                  onClick={() => setManualPage((x) => x - 1)}
                >
                  上一页手册
                </button>
                <button
                  type="button"
                  disabled={manualPage * 20 >= manualTotal}
                  onClick={() => setManualPage((x) => x + 1)}
                >
                  下一页手册
                </button>
              </>
            )}
            <p>新增配图：先保存文章，取得文章编号后上传。</p>
          </fieldset>
          <label>
            经办人
            <input
              required
              maxLength={100}
              name="operatorName"
              value={draft.operatorName}
              onChange={(e) =>
                setDraft({ ...draft, operatorName: e.target.value })
              }
            />
          </label>
          {editingId && (
            <label>
              修改原因
              <input
                required
                maxLength={1000}
                name="reason"
                value={draft.reason}
                onChange={(e) => setDraft({ ...draft, reason: e.target.value })}
              />
            </label>
          )}
          {saveError && <p role="alert">{saveError}</p>}
          {conflict && (
            <p>
              文章已被他人修改，草稿已保留。
              <button
                type="button"
                onClick={() => {
                  if (editingId)
                    knowledgeApi
                      .detail(editingId)
                      .then((a) => {
                        setArticle(a);
                        setDraft({ ...draft, expectedVersion: a.version });
                        setConflict(false);
                        setSaveError(
                          "已读取最新正文在上方，请核对草稿差异后再保存。",
                        );
                      })
                      .catch((e) => setSaveError(e.message));
                }}
              >
                读取最新版本并核对
              </button>
            </p>
          )}
          <div>
            <button disabled={saving || conflict}>
              {saving ? "保存中…" : "确认保存"}
            </button>{" "}
            <button
              type="button"
              disabled={saving}
              onClick={() => setDraft(null)}
            >
              取消编辑
            </button>
          </div>
        </form>
      )}
      {article && !draft && (
        <label>
          为当前文章上传配图（经办人必填）
          <input placeholder="经办人" id="knowledge-upload-operator" />
          <input
            type="file"
            accept="image/*"
            disabled={saving}
            onChange={async (e) => {
              const file = e.target.files?.[0];
              const operator = (
                document.getElementById(
                  "knowledge-upload-operator",
                ) as HTMLInputElement
              ).value.trim();
              if (!file) return;
              if (!operator) {
                setDetailError("请先填写上传经办人");
                return;
              }
              setSaving(true);
              try {
                await api.uploadAttachment(file, {
                  requestId: newRequestId(),
                  operatorName: operator,
                  ownerType: "KNOWLEDGE_ARTICLE",
                  ownerId: article.articleId,
                  fileKind: "IMAGE",
                });
                await read(article.articleId);
                setReload((x) => x + 1);
              } catch (err) {
                setDetailError((err as Error).message);
              } finally {
                setSaving(false);
              }
            }}
          />
        </label>
      )}
    </section>
  );
}
