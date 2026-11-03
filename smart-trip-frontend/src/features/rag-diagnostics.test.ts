import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createSSRApp } from "vue";
import { renderToString } from "@vue/server-renderer";
import RagDiagnostics from "./RagDiagnostics.vue";
import { createRagDiagnostics, defaultRagRequirement, parseRagRequirement, ragSourceUrl } from "./rag-diagnostics";

const routerSpies = vi.hoisted(() => ({ options: null as any, guard: null as any }));
vi.mock("vue-router", () => ({
  createWebHistory: vi.fn(() => ({})),
  createRouter: vi.fn((options) => {
    routerSpies.options = options;
    return { beforeEach(guard: unknown) { routerSpies.guard = guard; } };
  }),
}));
vi.mock("./route-motion", () => ({ routeScroll: vi.fn(), installRouteMotion: vi.fn() }));

const selected = { chunkId: "used", documentId: "public-doc", title: "实际参考来源", sourceName: "公开资料", sourceUrl: "https://example.org/source" };
const result = {
  previewOnly: true, chatModelCalled: false,
  rag: { status: "SUCCESS", bm25HitCount: 3, vectorHitCount: 2, mergedHitCount: 4,
    finalContextCount: 1, rerankProvider: "SKIPPED_RERANK_DISABLED", sources: [selected],
    candidates: [selected, { chunkId: "candidate", title: "仅候选" }] },
  snippets: [{ ...selected, excerpt: "来自真实接口的公开摘录", excerptTruncated: false }],
};
const envelope = (data: unknown, status = 200) => new Response(JSON.stringify({ code: status, data, message: status === 200 ? "ok" : "session expired" }), { status });
const text = () => JSON.stringify(defaultRagRequirement());

beforeEach(() => {
  vi.stubGlobal("localStorage", { getItem: vi.fn(() => "fixture-token"), removeItem: vi.fn() });
  vi.stubGlobal("window", { dispatchEvent: vi.fn() });
});
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.useRealTimers(); });

describe("read-only RAG diagnostics", () => {
  it("uses the exact existing requirement contract and retains unknown budget/date", () => {
    const requirement = defaultRagRequirement();
    expect(parseRagRequirement(text())).toEqual(requirement);
    expect(requirement).toMatchObject({ origin: "苏州", destinations: ["稻城亚丁"], days: 8,
      travelers: 2, startDate: null, budget: null, budgetMax: null, budgetScope: "UNKNOWN", currency: "" });
    const another = defaultRagRequirement(); another.interests.push("different");
    expect(requirement.interests).not.toContain("different");
  });
  it("only calls existing status/preview APIs and shared api() adds the session header", async () => {
    const fetcher = vi.fn(async (path: string) => envelope(path.includes("status") ? { ragEnabled: true, indexStatus: "READY" } : result));
    vi.stubGlobal("fetch", fetcher);
    const model = createRagDiagnostics();
    await model.checkStatus(); await model.search(text());
    expect(fetcher.mock.calls.map(row => row[0])).toEqual(["/api/rag/status?probe=true", "/api/rag/preview"]);
    const options = (fetcher.mock.calls as unknown as [string, RequestInit][])[1][1];
    expect(options.headers).toMatchObject({ Authorization: "Bearer fixture-token" });
    expect(JSON.parse(options.body as string)).toEqual({ requirement: defaultRagRequirement() });
    expect(model.preview.value?.rag.sources).toHaveLength(1);
    expect(model.preview.value?.rag.candidates).toHaveLength(2);
    expect(model.preview.value?.snippets[0].excerpt).toBe("来自真实接口的公开摘录");
    model.dispose();
  });
  it("makes no automatic request when displaying the page and warns about the read-only boundary", async () => {
    const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
    const html = await renderToString(createSSRApp(RagDiagnostics));
    expect(fetcher).not.toHaveBeenCalled();
    expect(html).toContain("不会生成路书、扣除 AI 额度或调用付费模型");
    expect(html).toContain("稻城亚丁旅行资料检索验收");
    expect(html).not.toContain("fixture-token");
  });
  it("rejects invalid JSON or destinations locally without sending a request", async () => {
    const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
    const model = createRagDiagnostics();
    for (const input of ["broken", "null", "[]", JSON.stringify({ ...defaultRagRequirement(), destinations: [] }),
      JSON.stringify({ ...defaultRagRequirement(), days: 1.5 }), JSON.stringify({ ...defaultRagRequirement(), budget: "3000" })]) {
      await model.search(input); expect(model.previewError.value).not.toBe("");
    }
    expect(fetcher).not.toHaveBeenCalled(); model.dispose();
  });
  it("does not submit twice and discards responses after leaving the page", async () => {
    let resolve!: (value: Response) => void;
    const fetcher = vi.fn(() => new Promise<Response>(done => { resolve = done; })); vi.stubGlobal("fetch", fetcher);
    const model = createRagDiagnostics(); const pending = model.search(text());
    expect(model.searching.value).toBe(true);
    await model.search(text()); expect(fetcher).toHaveBeenCalledOnce();
    model.dispose(); resolve(envelope(result)); await pending;
    expect(model.preview.value).toBeNull(); expect(model.searching.value).toBe(false);
  });
  it("shows authenticated-session failure without exposing or replacing the token", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => envelope(null, 401)));
    const model = createRagDiagnostics(); await model.checkStatus();
    expect(model.statusError.value).toContain("重新登录");
    expect(model.statusError.value).not.toContain("fixture-token");
    expect(model.status.value).toBeNull(); model.dispose();
  });
  it("does not turn disconnected or invalid-preview responses into success", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new Error("offline"); }));
    const model = createRagDiagnostics(); await model.search(text());
    expect(model.previewError.value).toContain("连接失败"); expect(model.preview.value).toBeNull();
    vi.stubGlobal("fetch", vi.fn(async () => envelope({ ...result, chatModelCalled: true })));
    await model.search(text()); expect(model.previewError.value).toContain("只读预览凭据");
    expect(model.preview.value).toBeNull(); model.dispose();
  });
  it("reports a bounded wait instead of spinning forever", async () => {
    vi.useFakeTimers();
    vi.stubGlobal("fetch", vi.fn((_path: string, options: RequestInit) => new Promise<Response>((_resolve, reject) => {
      options.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")), { once: true });
    })));
    const model = createRagDiagnostics(); const pending = model.search(text());
    await vi.advanceTimersByTimeAsync(100001); await pending;
    expect(model.previewError.value).toContain("等待超时"); expect(model.searching.value).toBe(false);
    model.dispose();
  });
  it("keeps disabled/no-hit/degraded states from the real response without invented sources", async () => {
    const model = createRagDiagnostics();
    for (const state of ["DISABLED", "NO_RELIABLE_KNOWLEDGE", "UNAVAILABLE", "DEGRADED"]) {
      vi.stubGlobal("fetch", vi.fn(async () => envelope({ ...result, snippets: [], rag: { ...result.rag, status: state, sources: [], fallbackReason: "ES_UNAVAILABLE" } })));
      await model.search(text()); expect(model.preview.value?.rag.status).toBe(state);
      expect(model.preview.value?.rag.sources).toEqual([]);
    }
    model.dispose();
  });
  it("only links public HTTP(S) sources without URL credentials", () => {
    expect(ragSourceUrl(selected)).toBe("https://example.org/source");
    for (const url of ["javascript:alert(1)", "https://secret@example.org", "invalid"])
      expect(ragSourceUrl({ chunkId: "test", sourceUrl: url })).toBeUndefined();
  });
});

describe("development-only route and existing auth guard", () => {
  it("registers the diagnostic route only in DEV and redirects anonymous users via the existing guard", async () => {
    vi.resetModules(); vi.stubEnv("DEV", true);
    await import("../router/index.js");
    const route = routerSpies.options.routes.find((value: { path: string }) => value.path === "/__dev/rag");
    expect(route.meta.requiresAuth).toBe(true);
    vi.stubGlobal("document", { title: "" });
    vi.stubGlobal("localStorage", { getItem: vi.fn(() => null) });
    expect(routerSpies.guard({ meta: route.meta, fullPath: "/__dev/rag" })).toEqual({ path: "/login", query: { redirect: "/__dev/rag" } });
  });
  it("does not register the diagnostic route for production", async () => {
    vi.resetModules(); vi.stubEnv("DEV", false);
    await import("../router/index.js");
    expect(routerSpies.options.routes.some((route: { path: string }) => route.path === "/__dev/rag")).toBe(false);
  });
});
