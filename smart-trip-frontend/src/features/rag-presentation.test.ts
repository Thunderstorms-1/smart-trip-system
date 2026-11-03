import { describe, expect, it } from "vitest";
import { createSSRApp } from "vue";
import { renderToString } from "@vue/server-renderer";
import RagSources from "./RagSources.vue";
import AiGenerationProgress from "./AiGenerationProgress.vue";
import type { RagSnapshot, Task, TravelRequirement } from "./types";

const requirement: TravelRequirement = {
  description: "苏州慢游",
  origin: "",
  destinations: ["苏州"],
  days: 1,
  startDate: "",
  timeDescription: "",
  includesTravelDays: true,
  travelers: 2,
  companions: "",
  budget: 500,
  budgetMax: null,
  budgetScope: "TOTAL",
  currency: "CNY",
  budgetIncludes: [],
  interests: ["园林"],
  pace: "RELAXED",
  exertion: "",
  transport: "",
  accommodation: "",
  mustVisit: [],
  avoid: [],
  timeConstraints: "",
  notes: "",
};

function snapshot(changes: Partial<RagSnapshot> = {}): RagSnapshot {
  return {
    status: "SUCCESS",
    ragEnabled: true,
    finalContextCount: 1,
    sources: [{
      chunkId: "used-1",
      documentId: "suzhou-gardens",
      ref: "K1",
      title: "实际参考的园林资料",
      sourceName: "UNESCO",
      sourceUrl: "https://whc.unesco.org/en/list/813/",
    }],
    candidates: [
      { chunkId: "used-1", title: "实际参考的园林资料", sourceName: "UNESCO" },
      { chunkId: "candidate-only", title: "仅候选的街巷资料", sourceName: "苏州资料来源" },
    ],
    ...changes,
  };
}

function task(rag?: RagSnapshot, changes: Partial<Task> = {}): Task {
  return {
    id: "offline-rag-task",
    type: "GENERATE",
    status: "RUNNING",
    phase: "MODEL",
    generation: {
      requirement,
      warnings: [],
      expiresAt: "2099-01-01T00:00:00Z",
      phase: "MODEL",
      phaseHistory: [
        { phase: "QUEUED", at: "2026-10-28T00:00:00Z" },
        { phase: "RETRIEVING", at: "2026-10-28T00:00:01Z" },
        { phase: "MODEL", at: "2026-10-28T00:00:02Z" },
      ],
      rag,
    },
    ...changes,
  };
}

async function sources(value?: Task) {
  return renderToString(createSSRApp(RagSources, { task: value }));
}

async function progress(value: Task) {
  return renderToString(createSSRApp(AiGenerationProgress, {
    task: value,
    submitting: false,
  }));
}

describe("RAG source and progress presentation from persisted task evidence", () => {
  it("shows annual validity separately from publication and explains partial applicability", async () => {
    const value = snapshot();
    value.sources![0] = { ...value.sources![0], sourceUpdatedAt: "2026-03-16",
      validFrom: "2026-09-15", validUntil: "2026-11-15", applicability: "PARTIALLY_APPLICABLE",
      applicableFrom: "2026-11-14", applicableUntil: "2026-11-15",
      applicabilityNote: "仅适用于行程中的2026-11-14至2026-11-15；行程其余日期不适用。" };
    const html = await sources(task(value));
    expect(html).toContain("2026-03-16");
    expect(html).toContain("资料适用期：2026-09-15 至 2026-11-15");
    expect(html).toContain("行程其余日期不适用");
  });

  it("hides sources when absent or explicitly disabled", async () => {
    for (const value of [undefined, task(), task(snapshot({ status: "DISABLED", ragEnabled: false }))]) {
      const html = await sources(value);
      expect(html).not.toContain("旅行参考资料");
      expect(html).not.toContain("rag-sources");
      expect(html).not.toContain("检索候选");
    }
  });

  it("keeps the original progress stages when RAG was never run", async () => {
    const value = task(snapshot({ status: "DISABLED", ragEnabled: false }));
    value.generation!.phaseHistory = [
      { phase: "QUEUED", at: "2026-10-28T00:00:00Z" },
      { phase: "MODEL", at: "2026-10-28T00:00:01Z" },
    ];
    const html = await progress(value);
    expect(html).toContain("正在为你构思这段旅程");
    expect(html).not.toContain("检索旅行参考资料");
    expect(html).not.toContain("旅行参考资料");
  });

  it("shows the actual RETRIEVING phase without claiming completed model work", async () => {
    const value = task(undefined, { phase: "RETRIEVING" });
    value.generation!.phase = "RETRIEVING";
    value.generation!.phaseHistory = [
      { phase: "QUEUED", at: "2026-10-28T00:00:00Z" },
      { phase: "RETRIEVING", at: "2026-10-28T00:00:01Z" },
    ];
    const html = await progress(value);
    expect(html).toContain("正在检索这段旅程的参考资料");
    expect(html).toContain("正在根据已确认的目的地和旅行条件检索资料");
    expect(html).toContain('aria-busy="true"');
    expect(html).toMatch(/class="is-active"[^>]*aria-current="step"/);
    expect(html).not.toContain("本次已传给生成模型");
    expect(html).not.toContain("已收到完整模型响应");
  });

  it("separates candidate documents from selected references and uses the actual source list count", async () => {
    // A stale count must not manufacture extra source entries in the UI.
    const html = await sources(task(snapshot({ finalContextCount: 99 })));
    expect(html).toContain("旅行资料检索完成");
    expect(html).toContain("1 条参考片段");
    expect(html).not.toContain("99 条参考片段");
    expect(html).toContain("检索候选（2 条，不代表全部用于生成）");
    const candidateIndex = html.indexOf('class="rag-candidates"');
    expect(candidateIndex).toBeGreaterThan(0);
    expect(html.slice(0, candidateIndex)).toContain("实际参考的园林资料");
    expect(html.slice(0, candidateIndex)).not.toContain("仅候选的街巷资料");
    expect(html.slice(candidateIndex)).toContain("仅候选的街巷资料");
    expect(html).toContain("[K1]");
    expect(html).toContain("更新时间未注明");
    expect(html).toContain("不代表票价、开放时间、天气或交通已经实时核实");
  });

  it.each(["MODEL", "PREVIEW"] as const)(
    "does not claim references reached the model at %s without MODEL_COMPLETED history",
    async (phase) => {
      const value = task(snapshot(), {
        phase,
        status: phase === "PREVIEW" ? "PREVIEW" : "RUNNING",
      });
      value.generation!.phase = phase;
      const html = await sources(value);
      expect(html).toContain("已准备的参考片段，模型调用是否完成待确认");
      expect(html).not.toContain("本次已传给生成模型的参考片段");
    },
  );

  it("claims delivery only after persisted MODEL_COMPLETED evidence", async () => {
    const value = task(snapshot(), { phase: "MODEL_COMPLETED" });
    value.generation!.phaseHistory!.push({
      phase: "MODEL_COMPLETED",
      at: "2026-10-28T00:00:03Z",
    });
    const html = await sources(value);
    expect(html).toContain("本次已传给生成模型的参考片段");
    expect(html).not.toContain("模型调用是否完成待确认");
    expect(html).toContain('href="https://whc.unesco.org/en/list/813/"');
    expect(html).toContain('rel="noopener noreferrer"');
  });

  it.each(["NO_RELEVANT_KNOWLEDGE", "INDEX_EMPTY", "UNAVAILABLE"])(
    "honestly reports no reliable references for %s fallback",
    async (status) => {
      const html = await sources(task(snapshot({
        status,
        sources: [],
        candidates: [],
        finalContextCount: 0,
        fallbackReason: "offline-fixture",
      })));
      expect(html).toContain("0 条参考片段");
      expect(html).toContain("未找到可靠参考资料，沿原流程继续规划");
      expect(html).not.toContain("本次已传给生成模型");
      expect(html).not.toContain("旅行资料检索完成");
      expect(html).not.toContain("检索候选");
    },
  );

  it("shows the stored task error for a strict retrieval failure", async () => {
    const value = task(snapshot({
      status: "FAILED", sources: [], candidates: [], finalContextCount: 0,
    }), {
      status: "FAILED",
      phase: "RETRIEVING",
      errorMessage: "旅行知识检索失败（ELASTICSEARCH_UNAVAILABLE），尚未发起模型生成",
    });
    value.generation!.phaseHistory = [
      { phase: "QUEUED", at: "2026-10-28T00:00:00Z" },
      { phase: "RETRIEVING", at: "2026-10-28T00:00:01Z" },
    ];
    const html = await progress(value);
    expect(html).toContain("这次规划暂未完成");
    expect(html).toContain("ELASTICSEARCH_UNAVAILABLE");
    expect(html).toContain("尚未发起模型生成");
    expect(html).toContain("旅行资料检索未完成，请查看任务原因");
    expect(html).toContain("查询最新状态");
    expect(html).not.toContain("本次已传给生成模型");
    expect(html).not.toContain("沿原流程继续规划");
  });

  it("distinguishes a usable degraded result from a total retrieval failure", async () => {
    const html = await sources(task(snapshot({ status: "DEGRADED" })));
    expect(html).toContain("已找到参考资料，部分检索能力降级");
    expect(html).toContain("已准备的参考片段");
    expect(html).not.toContain("旅行资料检索未完成");
  });

  it("renders only approved metadata and never renders bodies or private contexts", async () => {
    const value = task(snapshot());
    Object.assign(value.generation!, {
      _ragContext: "PRIVATE_CONTEXT_MUST_NOT_RENDER",
      _modelDiagnostics: { responseText: "PRIVATE_MODEL_OUTPUT_MUST_NOT_RENDER" },
    });
    Object.assign(value.generation!.rag!.sources![0]!, {
      content: "SOURCE_BODY_MUST_NOT_RENDER",
      embedding: [0.123, 0.456],
    });
    Object.assign(value.generation!.rag!.candidates![1]!, {
      content: "CANDIDATE_BODY_MUST_NOT_RENDER",
    });
    for (const html of [await sources(value), await progress(value)]) {
      for (const marker of [
        "PRIVATE_CONTEXT_MUST_NOT_RENDER",
        "PRIVATE_MODEL_OUTPUT_MUST_NOT_RENDER",
        "SOURCE_BODY_MUST_NOT_RENDER",
        "CANDIDATE_BODY_MUST_NOT_RENDER",
        "_ragContext",
        "embedding",
      ]) expect(html).not.toContain(marker);
      expect(html).toContain("实际参考的园林资料");
    }
  });

  it.each([
    "javascript:alert(1)",
    "data:text/html,<script>alert(1)</script>",
    "https://user:password@example.com/private",
  ])("does not turn unsafe or credential-bearing source URLs into links: %s", async (sourceUrl) => {
    const value = task(snapshot());
    value.generation!.rag!.sources![0]!.sourceUrl = sourceUrl;
    value.generation!.rag!.sources![0]!.title = "<script>UNTRUSTED_TITLE</script>";
    const html = await sources(value);
    expect(html).not.toContain('href="');
    expect(html).not.toContain("user:password");
    expect(html).not.toContain("<script>UNTRUSTED_TITLE");
    expect(html).toContain("&lt;script&gt;UNTRUSTED_TITLE&lt;/script&gt;");
  });

  it("labels retained older cultural references as historical background", async () => {
    const value = task(snapshot());
    Object.assign(value.generation!.rag!.sources![0]!, {
      sourceUpdatedAt: "2016-06-17",
      freshness: "OLDER_STATIC_REFERENCE",
    });
    const html = await sources(value);
    expect(html).toContain("2016-06-17");
    expect(html).toContain("历史背景资料");
  });
});
