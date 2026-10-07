import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { effectScope, reactive, createSSRApp } from "vue";
import { renderToString } from "@vue/server-renderer";
import { api } from "../api";
import { useWorkbenchQuality } from "./useWorkbenchQuality";
import QualityInsight from "./QualityInsight.vue";
import type { AnalysisReport, Trip } from "../types";
vi.mock("../api", () => ({ api: vi.fn() }));
let scope: ReturnType<typeof effectScope>;
function setup() {
  const p = reactive({
    trip: {
      id: 1,
      version: 3,
      title: "保存路书",
      destination: "成都",
      days: [
        { id: 11, items: [{ id: 101, itemName: "活动", itemType: "SCENIC" }] },
        {
          id: 12,
          items: [{ id: 102, itemName: "第二天节点", itemType: "SCENIC" }],
        },
      ],
    } as Trip,
    dirty: false,
    blocked: false,
  });
  scope = effectScope();
  const a = scope.run(() => useWorkbenchQuality(() => p))!;
  return { p, a };
}
function report(): AnalysisReport {
  return {
    id: "analysis",
    tripId: 1,
    baseVersion: 3,
    revision: 0,
    dayIds: [11, 12],
    mode: "walking",
    expiresAt: "2030-01-01T01:00:00Z",
    issues: [],
    sources: [],
    quality: {
      schemaVersion: 1,
      rulesVersion: "real-rules",
      profile: "DEFAULT",
      scope: "TRIP",
      baseVersion: 3,
      dayIds: [11, 12],
      status: "READY",
      totalScore: 0,
      days: [],
      findings: [],
      dimensions: [
        {
          key: "TIME",
          status: "READY",
          score: 0,
          weight: 40,
          deductionIds: [],
          unknownReasons: [],
        },
        {
          key: "PACE",
          status: "UNKNOWN",
          score: 99,
          weight: 25,
          deductionIds: [],
          unknownReasons: ["缺少时间"],
        },
        {
          key: "MEAL_REST",
          status: "READY",
          score: 82,
          weight: 20,
          deductionIds: [],
          unknownReasons: [],
        },
        {
          key: "BALANCE",
          status: "NOT_APPLICABLE",
          score: 100,
          weight: 15,
          deductionIds: [],
          unknownReasons: [],
        },
      ],
      external: {
        route: {
          status: "UNKNOWN",
          expected: 0,
          available: 0,
          sourceIds: [],
          reason: "未查",
        },
        weather: {
          status: "UNKNOWN",
          expected: 0,
          available: 0,
          sourceIds: [],
          reason: "未查",
        },
      },
    },
  };
}
beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2030-01-01T00:00:00Z"));
  vi.mocked(api).mockReset();
});
afterEach(() => {
  scope?.stop();
  vi.useRealTimers();
});
it("requires explicit saved-version analysis and requests real full-trip API", async () => {
  const { p, a } = setup();
  expect(a.valid.value).toBe(false);
  expect(a.state.value).toContain("尚未分析");
  vi.mocked(api).mockResolvedValue(report());
  await a.analyze();
  expect(api).toHaveBeenCalledWith(
    "/workbench/1/analyses",
    "POST",
    { baseVersion: 3, dayIds: [11, 12], mode: "walking" },
    expect.any(AbortSignal),
  );
  expect(a.valid.value).toBe(true);
  p.dirty = true;
  expect(a.valid.value).toBe(false);
});
it("invalidates changed content even at the same version, and undo/save never revives old scores", () => {
  const { p, a } = setup();
  a.accept(report());
  const original = p.trip.title;
  p.trip.title = "草稿";
  p.dirty = true;
  expect(a.report.value).toBeUndefined();
  p.trip.title = original;
  p.dirty = false;
  expect(a.valid.value).toBe(false);
  a.accept(report());
  p.trip.version = 4;
  expect(a.valid.value).toBe(false);
  a.accept(report());
  expect(a.valid.value).toBe(false);
});
it("discards a late response after edit and after navigation to another trip", async () => {
  const { p, a } = setup();
  let resolve!: (r: AnalysisReport) => void;
  vi.mocked(api).mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r as typeof resolve;
      }),
  );
  const pending = a.analyze();
  p.trip.title = "changed";
  p.dirty = true;
  p.trip.id = 2;
  resolve(report());
  await pending;
  expect(a.report.value).toBeUndefined();
  expect(a.busy.value).toBe(false);
});
it("expiry and failed refresh cannot masquerade as the latest valid result", async () => {
  const { a } = setup();
  a.accept(report());
  vi.advanceTimersByTime(3600000);
  expect(a.valid.value).toBe(false);
  expect(a.state.value).toContain("过期");
  vi.mocked(api).mockRejectedValue(new Error("失败，请重试"));
  await a.analyze();
  expect(a.valid.value).toBe(false);
  expect(a.error.value).toContain("重试");
});
it("keeps a real zero while UNKNOWN/NOT_APPLICABLE suppress even supplied numbers", async () => {
  const { p } = setup();
  const html = await renderToString(
    createSSRApp(QualityInsight, {
      trip: p.trip,
      report: report(),
      valid: true,
      state: "当前版本",
      busy: false,
      disabled: false,
      error: "",
    }),
  );
  expect(html).toContain('aria-label="行程总分"');
  expect(html).toContain("0 / 100");
  expect(html).toContain("暂无法判断");
  expect(html).toContain("不适用");
  expect(html).not.toContain("99 / 100");
  expect(html).not.toContain("100 / 100");
  expect((html.match(/<progress/g) || []).length).toBe(2);
  const hidden = await renderToString(
    createSSRApp(QualityInsight, {
      trip: p.trip,
      report: report(),
      valid: false,
      state: "已变化",
      busy: false,
      disabled: true,
      error: "",
    }),
  );
  expect(hidden).not.toContain("<progress");
  expect(hidden).not.toContain('aria-label="行程总分"');
});
it("resolves multi-day findings to the node's actual day and includes basic conflicts", async () => {
  const { p } = setup();
  const r = report();
  r.quality!.findings = [
    {
      id: "f",
      ruleId: "rule",
      severity: "WARNING",
      dayIds: [11, 12],
      itemIds: [102],
      message: "跨日问题",
      evidence: [],
      penalty: 0,
      evidenceIds: [],
      action: "MANUAL",
    },
  ];
  r.quality!.basicIssues = [
    {
      id: "conflict",
      code: "TIME_OVERLAP",
      severity: "ERROR",
      dayId: 11,
      itemIds: [101],
      message: "时间重叠",
      evidenceIds: [],
    },
  ];
  const html = await renderToString(
    createSSRApp(QualityInsight, {
      trip: p.trip,
      report: r,
      valid: true,
      state: "当前",
      busy: false,
      disabled: false,
      error: "",
    }),
  );
  expect(html).toContain("定位 第 2 天 · 第二天节点");
  expect(html).toContain("时间重叠");
});
