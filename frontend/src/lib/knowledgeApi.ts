import { ApiError, requestApi } from "./api.ts";
export interface Article {
  articleId: string;
  title: string;
  category: string;
  equipmentId: string | null;
  equipmentType: string | null;
  faultPhenomenon: string | null;
  content: string | null;
  steps: string[];
  attachmentIds: string[];
  sourceRepairOrderId: string | null;
  version: number;
  updatedAt: string;
}
export interface KnowledgeCommand {
  requestId: string;
  operatorName: string;
  expectedVersion?: number;
  reason: string;
  title: string;
  category: string;
  equipmentId: string | null;
  equipmentType: string | null;
  faultPhenomenon: string | null;
  content: string | null;
  steps: string[];
  attachmentIds: string[];
  sourceRepairOrderId: string | null;
}
export class KnowledgeError extends Error {
  code: string;
  fields: Record<string, string>;
  constructor(
    message: string,
    code: string,
    fields: Record<string, string> = {},
  ) {
    super(message);
    this.code = code;
    this.fields = fields;
  }
}
async function request<T>(
  path: string,
  method = "GET",
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  try {
    return (
      await requestApi(
        "/api/v1/knowledge-articles" + path,
        (value) => value as T,
        { method: method as "GET" | "POST" | "PUT", body, signal },
      )
    ).data;
  } catch (error) {
    if (error instanceof ApiError)
      throw new KnowledgeError(error.message, error.code, error.fields);
    throw error;
  }
}
export const knowledgeApi = {
  list: (filter: Record<string, string>, page: number, signal?: AbortSignal) =>
    request<{ items: Article[]; total: number }>(
      "?" +
        new URLSearchParams({ ...filter, page: String(page), pageSize: "20" }),
      "GET",
      undefined,
      signal,
    ),
  detail: (id: string) => request<Article>("/" + encodeURIComponent(id)),
  save: (id: string | undefined, body: KnowledgeCommand) =>
    request<Article>(
      id ? "/" + encodeURIComponent(id) : "",
      id ? "PUT" : "POST",
      body,
    ),
};
export const categories: Record<string, string> = {
  INTRODUCTION: "设备介绍",
  OPERATION_MAINTENANCE: "操作保养",
  COMMON_FAULT: "常见故障",
  TROUBLESHOOTING: "人工排查步骤",
  REPAIR_CASE: "维修案例",
};
