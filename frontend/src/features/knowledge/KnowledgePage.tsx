import { useEffect, useRef, useState } from "react";
import { Icon } from "../../components/Icon";
import type { IconName } from "../../components/Icon";
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
import { RichTextEditor } from "./RichTextEditor";
import { hasRichContent, safeKnowledgeHtml } from "./richText";

const categoryFields: Record<string, {
  contentLabel: string;
  contentHint: string;
  faultLabel?: string;
  stepsLabel?: string;
  stepsHint?: string;
}> = {
  INTRODUCTION: { contentLabel: "设备详情介绍", contentHint: "可填写设备用途、结构组成、主要参数、运行特点和安全注意事项。" },
  OPERATION_MAINTENANCE: { contentLabel: "操作与保养说明", contentHint: "可填写启停要求、日常点检、润滑与周期保养要求。", stepsLabel: "标准操作与保养步骤", stepsHint: "按现场执行顺序填写，便于操作人员逐项核对。" },
  COMMON_FAULT: { contentLabel: "故障原因与处理建议", contentHint: "说明可能原因、影响范围、临时处置和需要升级处理的条件。", faultLabel: "典型故障现象", stepsLabel: "建议检查与处理顺序", stepsHint: "从安全确认、现象核实到恢复运行依次填写。" },
  TROUBLESHOOTING: { contentLabel: "排查说明与安全提示", contentHint: "说明排查前提、风险隔离和判断依据。", faultLabel: "待排查故障现象", stepsLabel: "人工排查步骤（必填）", stepsHint: "每一步写清检查位置、判断标准和下一步动作。" },
  REPAIR_CASE: { contentLabel: "维修过程与结论", contentHint: "记录故障原因、处理措施、替换部件、试运行结果和经验结论。", faultLabel: "报修与故障现象", stepsLabel: "维修处置过程", stepsHint: "按实际处置顺序整理关键动作。" },
};

const actionLabels: Record<string, string> = {
  CREATE: "发起报修", REVIEW: "审核意见", REPAIR_RESULT: "维修结果", ACCEPTANCE: "验收结论", SUBMIT: "提交处理",
};

const categoryIcons: Record<string, IconName> = {
  INTRODUCTION: "equipment",
  OPERATION_MAINTENANCE: "check",
  COMMON_FAULT: "alert",
  TROUBLESHOOTING: "search",
  REPAIR_CASE: "operations",
};

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
        "引用资料暂不可用，正文仍可阅读"
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
  const [view, setView] = useState<"list" | "detail" | "editor" | "source">(
    "list",
  );
  const panel = useRef<HTMLElement>(null);
  useEffect(() => {
    panel.current?.scrollIntoView({ block: "start" });
    document
      .getElementById("knowledge-view-title")
      ?.focus({ preventScroll: true });
  }, [view]);
  function returnToList() {
    selection.current++;
    setView("list");
    setDraft(null);
    setChoosing(false);
    setSource(null);
    setSourceError("");
  }
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
          setEquipment(e.data.items.filter((item) => item.enabled && item.code !== "KNOWLEDGE-VERIFY"));
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
    setView("detail");
    setSource(null);
    setSourceError("");
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
    setView("editor");
    setChoosing(false);
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
    if (!hasRichContent(draft.content) && !draft.steps.some((s) => s.trim())) {
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
      setView("detail");
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
    return `${e?.name ?? (id ? `设备 ${id}` : "按类型适用")} · ${types.find((t) => t.code === (e?.typeCode ?? type))?.name ?? "通用生产设备"}`;
  };
  return (
    <section
      ref={panel}
      className={`panel knowledge-page knowledge-view-${view}`}
    >
      <header className="knowledge-header">
        <div>
          <h2 id="knowledge-view-title" tabIndex={-1}>
            <span className="knowledge-heading-icon"><Icon name={view === "editor" ? "edit" : view === "source" ? "operations" : view === "detail" ? "document" : "knowledge"} size={18} /></span>
            {view === "list"
              ? "知识条目"
              : view === "editor"
                ? editingId
                  ? "编辑知识"
                  : "新增知识"
                : view === "source"
                  ? "整理维修案例"
                  : "知识详情"}
          </h2>
          <p>
            {view === "list"
              ? "选择设备和故障，查阅人工维护的知识与排查步骤。"
              : view === "editor"
                ? "完善适用范围、正文与排查步骤，核对后保存。"
                : view === "source"
                  ? "选择已验收工单，人工整理为可复用的维修知识。"
                  : "查看知识正文、人工步骤和引用资料。"}
          </p>
        </div>
        <div className="knowledge-actions">
          {view === "list" ? (
            <>
              <button className="button" onClick={back}>
                <Icon name="arrow" size={14} /><span>返回设备</span>
              </button>
              <button
                className="button"
                onClick={() => {
                  setSource(null);
                  setDraft(null);
                  setView("source");
                  setChoosing(true);
                  setOrderPage(1);
                }}
              >
                <Icon name="operations" size={15} /><span>从已验收工单整理案例</span>
              </button>
              <button
                className="button button-primary"
                onClick={() => {
                  setSource(null);
                  edit();
                }}
              >
                <Icon name="plus" size={16} /><span>新增知识</span>
              </button>
            </>
          ) : (
            <button className="button" disabled={saving} onClick={returnToList}>
              <Icon name="arrow" size={14} /><span>返回知识列表</span>
            </button>
          )}
        </div>
      </header>
      {referenceError && (
        <p role="alert">
          {referenceError}
          <button onClick={() => setReload((x) => x + 1)}>重试</button>
        </p>
      )}
      {view === "list" && (
        <>
          <div className="knowledge-filters">
            <label>
              <span className="knowledge-filter-label"><Icon name="equipment" size={15} />设备</span>
              <select
                value={filter.equipmentId}
                onChange={(e) => change("equipmentId", e.target.value)}
              >
                <option value="">全部设备</option>
                {equipment.map((e) => (
                  <option key={e.id} value={e.id}>
                    {e.name} · {e.typeName ?? "通用生产设备"}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span className="knowledge-filter-label"><Icon name="production" size={15} />设备类型</span>
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
              <span className="knowledge-filter-label"><Icon name="knowledge" size={15} />分类</span>
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
              <span className="knowledge-filter-label"><Icon name="search" size={15} />关键词 / 故障现象</span>
              <span className="knowledge-search-field"><Icon name="search" size={16} /><input value={filter.keyword} onChange={(e) => change("keyword", e.target.value)} /></span>
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
              <div className="knowledge-count">
                <Icon name="document" size={15} />共 <strong>{total}</strong> 篇知识
              </div>
              {items.length === 0 ? (
                <div className="knowledge-empty-state" role="status">
                  <span className="knowledge-empty-icon"><Icon name={Object.values(filter).some(Boolean) ? "search" : "knowledge"} size={28} /></span>
                  <strong>{Object.values(filter).some(Boolean) ? "没有匹配的知识" : "知识库暂无内容"}</strong>
                  <p>
                  {Object.values(filter).some(Boolean)
                    ? "请调整设备、类型、分类或关键词筛选条件。"
                    : "暂无知识，可新增文章或整理维修案例。"}
                  </p>
                </div>
              ) : (
                <div
                  className="table-region"
                  role="region"
                  aria-label="知识条目表，可横向滚动"
                  tabIndex={0}
                >
                  <table className="knowledge-list">
                    <thead>
                      <tr>
                        <th>知识标题 / 内容摘要</th>
                        <th>分类</th>
                        <th>适用范围</th>
                        <th>更新时间</th>
                        <th>操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {items.map((a) => (
                        <tr key={a.articleId}>
                          <td>
                            <button
                              className="knowledge-title-link"
                              data-article-id={a.articleId}
                              onClick={() => void read(a.articleId)}
                            >
                              {a.title}
                            </button>
                            <p className="knowledge-fault">
                              {a.faultPhenomenon || (a.category === "INTRODUCTION" ? "设备用途、结构组成与运行要点" : "查看正文与执行要点")}
                            </p>
                          </td>
                          <td>
                            <span className={`knowledge-badge knowledge-badge-${a.category.toLowerCase()}`}>
                              <Icon name={categoryIcons[a.category] ?? "knowledge"} size={13} />
                              {categories[a.category]}
                            </span>
                          </td>
                          <td><span className="knowledge-scope"><Icon name="equipment" size={14} />{eqLabel(a.equipmentId, a.equipmentType)}</span></td>
                          <td>
                            <span className="knowledge-time"><Icon name="clock" size={13} />{new Date(a.updatedAt).toLocaleString("zh-CN", {
                              hour12: false,
                            })}</span>
                          </td>
                          <td>
                            <button
                              className="knowledge-text-button"
                              onClick={() => void read(a.articleId)}
                            >
                              <span>查看详情</span><Icon name="arrow" size={13} />
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <div className="table-footer knowledge-pagination">
                <button
                  disabled={page === 1}
                  onClick={() => setPage((x) => x - 1)}
                >
                  上一页
                </button>{" "}
                第 {page} 页{" "}
                <button
                  disabled={page * 20 >= total}
                  onClick={() => setPage((x) => x + 1)}
                >
                  下一页
                </button>
              </div>
            </>
          )}
        </>
      )}
      {view === "detail" && detailLoading && <p role="status">正在打开文章…</p>}
      {view === "detail" && detailError && (
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
      {view === "detail" && article && (
        <article>
          <div className="knowledge-detail-heading">
            <span className={`knowledge-badge knowledge-badge-${article.category.toLowerCase()}`}><Icon name={categoryIcons[article.category] ?? "knowledge"} size={13} />{categories[article.category]}</span>
            <h3>{article.title}</h3>
            <p>适用于：{eqLabel(article.equipmentId, article.equipmentType)}</p>
          </div>
          <dl className="knowledge-business-fields">
            <div><dt>知识类型</dt><dd>{categories[article.category]}</dd></div>
            <div><dt>适用设备或范围</dt><dd>{eqLabel(article.equipmentId, article.equipmentType)}</dd></div>
            {categoryFields[article.category]?.faultLabel && <div className="wide"><dt>{categoryFields[article.category].faultLabel}</dt><dd>{article.faultPhenomenon ?? "未填写"}</dd></div>}
            <div><dt>最近维护时间</dt><dd>{new Date(article.updatedAt).toLocaleString("zh-CN", { hour12: false })}</dd></div>
            <div><dt>引用资料</dt><dd>{article.attachmentIds.length ? `${article.attachmentIds.length} 份` : "无"}</dd></div>
          </dl>
          <section className="knowledge-content-section">
            <h4>{categoryFields[article.category]?.contentLabel ?? "知识详情"}</h4>
            {article.content ? <div className="knowledge-rich-content" dangerouslySetInnerHTML={{ __html: safeKnowledgeHtml(article.content) }} /> : <p className="knowledge-empty">暂无正文内容</p>}
          </section>
          {!!article.steps.length && <section className="knowledge-content-section">
            <h4>{categoryFields[article.category]?.stepsLabel ?? "执行步骤"}</h4>
            <ol className="knowledge-steps">
              {article.steps.map((s, i) => <li key={i} style={{ whiteSpace: "pre-wrap" }}>{s}</li>)}
            </ol>
          </section>}
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
      {view === "source" && choosing && (
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
          <button onClick={returnToList}>关闭</button>
        </aside>
      )}
      {sourceError && !choosing && <p role="alert">{sourceError}</p>}
      {source && view !== "list" && (
        <details className="knowledge-source">
          <summary>源工单 {source.orderNo} · 查看维修记录</summary>
          <h3>源工单 {source.orderNo}</h3>
          <p>
            {source.equipmentName} · {source.status}
          </p>
          <p style={{ whiteSpace: "pre-wrap" }}>{source.description}</p>
          <ol>
            {source.actions.map((a) => (
              <li key={a.actionId}>
                {actionLabels[a.actionType] ?? "处理记录"}：{a.description ?? a.reason ?? a.result}
              </li>
            ))}
          </ol>
        </details>
      )}
      {view === "editor" && draft && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void save();
          }}
          className="knowledge-form"
        >
          <p className="knowledge-form-note">
            请人工核对内容后保存，标注必填的内容不能为空。
          </p>
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
              onChange={(e) => {
                const category = e.target.value;
                setDraft({
                  ...draft,
                  category,
                  faultPhenomenon: categoryFields[category].faultLabel ? draft.faultPhenomenon : null,
                  steps: categoryFields[category].stepsLabel ? draft.steps : [],
                });
              }}
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
                  {e.name} · {e.typeName ?? "通用生产设备"}
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
          {categoryFields[draft.category].faultLabel && <label>
            {categoryFields[draft.category].faultLabel}
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
          </label>}
          <RichTextEditor
            label={categoryFields[draft.category].contentLabel}
            placeholder={categoryFields[draft.category].contentHint}
            value={draft.content ?? ""}
            onChange={(content) => setDraft({ ...draft, content: hasRichContent(content) ? content : null })}
          />
          {categoryFields[draft.category].stepsLabel && <fieldset className="knowledge-steps-editor">
            <legend>{categoryFields[draft.category].stepsLabel}</legend>
            <p className="fieldset-help">{categoryFields[draft.category].stepsHint}</p>
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
          </fieldset>}
          <fieldset>
            <legend>附件引用</legend>
            {draft.attachmentIds.map((id) => (
              <div className="knowledge-attachment-reference" key={id}>
                <ul><Attachment id={id} /></ul>
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
              </div>
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
            <button type="button" disabled={saving} onClick={returnToList}>
              取消编辑
            </button>
          </div>
        </form>
      )}
      {view === "detail" && article && !draft && (
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
