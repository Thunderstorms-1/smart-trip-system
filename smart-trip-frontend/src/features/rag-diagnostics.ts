import { ref } from "vue";
import { api, ApiError } from "./api";
import type { RagSnapshot, RagSource, TravelRequirement } from "./types";

export interface RagKnowledgeStatus {
  ragEnabled: boolean;
  indexAlias: string;
  indexStatus: string;
  embeddingModel: string;
  embeddingVersion: string;
  embeddingStatus: string;
  mappedVectorDimensions: number | null;
  publicChunkCount: number | null;
  publicDocumentCountEstimate: number | null;
  failureReason: string;
  message?: string;
  durationMs?: number;
  rerankEnabled?: boolean;
}
export interface RagPreviewResult {
  previewOnly: boolean;
  chatModelCalled: boolean;
  rag: RagSnapshot & {
    retrievalDurationMs?: number;
    rerankDurationMs?: number;
    contextEstimatedTokens?: number;
  };
  snippets: (RagSource & { excerpt: string; excerptTruncated: boolean })[];
}

export function defaultRagRequirement(): TravelRequirement {
  return {
    description: "稻城亚丁旅行资料检索验收", origin: "苏州", destinations: ["稻城亚丁"],
    days: 8, startDate: null, timeDescription: "", includesTravelDays: true, travelers: 2,
    companions: "朋友", budget: null, budgetMax: null, budgetScope: "UNKNOWN", currency: "",
    budgetIncludes: [], interests: ["自然风光", "摄影", "徒步"], pace: "RELAXED", exertion: "",
    transport: "", accommodation: "", mustVisit: [], avoid: [], timeConstraints: "", notes: "",
  };
}
export function parseRagRequirement(text: string): TravelRequirement {
  let value: unknown;
  try { value = JSON.parse(text); } catch { throw new Error("需求 JSON 格式不正确，请检查引号和逗号。"); }
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("需求必须是结构化 JSON 对象。");
  const fields = value as Record<string, unknown>;
  // Keep this developer editor on the existing TravelRequirement contract; no arbitrary request body.
  const defaults = defaultRagRequirement();
  for (const [key, fallback] of Object.entries(defaults)) {
    const field = fields[key];
    if (Array.isArray(fallback)) {
      if (!Array.isArray(field) || field.some(v => typeof v !== "string")) throw new Error(`${key} 必须是字符串数组。`);
    } else if (["days", "travelers", "budget", "budgetMax"].includes(key)) {
      if (field !== null && (typeof field !== "number" || !Number.isFinite(field) || field < 0))
        throw new Error(`${key} 必须为非负数或 null。`);
      if (["days", "travelers"].includes(key) && field !== null && (!Number.isInteger(field) || (field as number) < 1))
        throw new Error(`${key} 必须为正整数或 null。`);
    } else if (key === "startDate") {
      if (field !== null && typeof field !== "string") throw new Error("startDate 必须为日期字符串或 null。");
    } else if (key === "includesTravelDays") {
      if (field !== null && typeof field !== "boolean") throw new Error("includesTravelDays 必须为布尔值或 null。");
    } else if (typeof field !== "string") throw new Error(`${key} 必须是字符串。`);
  }
  if (!(fields.destinations as string[]).some(v => v.trim())) throw new Error("请至少填写一个明确目的地。");
  if (!["UNKNOWN", "PER_PERSON", "TOTAL"].includes(fields.budgetScope as string)) throw new Error("预算口径不符合 TravelRequirement。");
  if (!["", "RELAXED", "BALANCED", "PACKED"].includes(fields.pace as string)) throw new Error("旅行节奏不符合 TravelRequirement。");
  return Object.fromEntries(Object.keys(defaults).map(key => [key, fields[key]])) as unknown as TravelRequirement;
}
export function ragSourceUrl(source: RagSource): string | undefined {
  try {
    const url = new URL(source.sourceUrl ?? "");
    if (["https:", "http:"].includes(url.protocol) && !url.username && !url.password) return url.href;
  } catch { /* Render the source name without a link. */ }
  return undefined;
}

/** Two read-only calls only. Uses the shared api() session behavior; never reads a token. */
export function createRagDiagnostics() {
  const status = ref<RagKnowledgeStatus | null>(null);
  const preview = ref<RagPreviewResult | null>(null);
  const checking = ref(false), searching = ref(false);
  const statusError = ref(""), previewError = ref("");
  const controllers = new Map<"status" | "preview", AbortController>();
  let disposed = false;
  async function execute(kind: "status" | "preview", requirement?: TravelRequirement) {
    if (disposed || controllers.has(kind)) return;
    const busy = kind === "status" ? checking : searching;
    const error = kind === "status" ? statusError : previewError;
    busy.value = true; error.value = "";
    const controller = new AbortController(); controllers.set(kind, controller);
    let timeout = false;
    const timer = setTimeout(() => { timeout = true; controller.abort(); }, kind === "status" ? 20000 : 100000);
    try {
      if (kind === "status") {
        const result = await api<RagKnowledgeStatus>("/rag/status?probe=true", "GET", undefined, controller.signal);
        if (!disposed && !controller.signal.aborted) status.value = result;
      } else {
        const result = await api<RagPreviewResult>("/rag/preview", "POST", { requirement }, controller.signal);
        if (!result.previewOnly || result.chatModelCalled !== false || !result.rag || !Array.isArray(result.snippets))
          throw new Error("接口没有返回有效的只读预览凭据，未将响应显示为检索成功。");
        if (!disposed && !controller.signal.aborted) preview.value = result;
      }
    } catch (failure) {
      if (disposed) return;
      error.value = timeout ? "本机检索等待超时，未生成路书；可检查服务后重新预览。"
        : controller.signal.aborted ? "已停止等待；未提交 AI 生成任务。"
        : failure instanceof ApiError && failure.status === 401 ? "登录已失效，请通过原登录页重新登录后访问此页面。"
        : failure instanceof Error ? failure.message : "RAG 只读检查失败，请检查本机服务。";
    } finally {
      clearTimeout(timer); controllers.delete(kind); busy.value = false;
    }
  }
  return {
    status, preview, checking, searching, statusError, previewError,
    checkStatus() { status.value = null; return execute("status"); },
    async search(text: string) {
      if (searching.value || disposed) return;
      previewError.value = ""; preview.value = null;
      let requirement: TravelRequirement;
      try { requirement = parseRagRequirement(text); }
      catch (error) { previewError.value = error instanceof Error ? error.message : "需求格式不正确。"; return; }
      await execute("preview", requirement);
    },
    cancelPreview() { controllers.get("preview")?.abort(); },
    dispose() { disposed = true; controllers.forEach(controller => controller.abort()); },
  };
}
