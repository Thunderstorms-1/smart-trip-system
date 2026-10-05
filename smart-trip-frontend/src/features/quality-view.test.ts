import { expect, it } from "vitest";
import { createSSRApp } from "vue";
import { renderToString } from "@vue/server-renderer";
import QualityOverview from "./analysis/QualityOverview.vue";
import DayQualityCards from "./analysis/DayQualityCards.vue";
import QualityFindings from "./analysis/QualityFindings.vue";
import QualityComparison from "./analysis/QualityComparison.vue";
import type { QualityReport, Trip } from "./types";
const trip: Trip = {
  id: 1,
  version: 1,
  title: "测试路书",
  destination: "成都",
  days: [
    {
      id: 11,
      items: [
        { id: 101, itemType: "SCENIC", itemName: "重复名字", costEstimate: 0 },
      ],
    },
    {
      id: 12,
      items: [
        { id: 102, itemType: "SCENIC", itemName: "重复名字", costEstimate: 0 },
      ],
    },
  ],
};
function quality(): QualityReport {
  return {
    schemaVersion: 1,
    rulesVersion: "quality-v1",
    profile: "DEFAULT",
    scope: "TRIP",
    baseVersion: 1,
    dayIds: [11, 12],
    status: "INSUFFICIENT",
    totalScore: null,
    dimensions: [
      {
        key: "TIME",
        status: "UNKNOWN",
        score: null,
        weight: 40,
        deductionIds: [],
        unknownReasons: ["缺少时间"],
      },
    ],
    findings: [],
    days: [
      {
        dayId: 11,
        dayIndex: 1,
        loadLevel: "UNKNOWN",
        coverage: {
          total: 1,
          complete: 0,
          percent: 0,
          missingItemIds: [101],
          overnightItemIds: [],
        },
        metrics: {
          nodeCount: 1,
          scenicCount: 1,
          activityCount: 1,
          plannedActivityMinutes: 120,
          occupiedActivityMinutes: 0,
          scheduledFoodMinutes: 0,
          scheduledTrafficMinutes: 0,
          daySpanMinutes: null,
          longestContinuousMinutes: 0,
          unallocatedMinutes: 0,
          gaps: [],
        },
        dimensions: [],
        findingIds: [],
      },
    ],
    external: {
      route: {
        status: "UNKNOWN",
        expected: 1,
        available: 0,
        sourceIds: [],
        reason: "未查",
      },
      weather: {
        status: "UNKNOWN",
        expected: 2,
        available: 0,
        sourceIds: [],
        reason: "未查",
      },
    },
  };
}
it("unknown total and dimensions are explained without fabricated progress", async () => {
  const html = await renderToString(
    createSSRApp(QualityOverview, { quality: quality(), trip }),
  );
  expect(html).toContain("信息不足，暂不评总分");
  expect(html).toContain("缺少时间");
  expect(html).not.toContain("<progress");
  expect(html).toContain("评价整份已保存路书");
});
it("a genuine zero score is rendered and is not replaced with unknown", async () => {
  const q = quality();
  q.totalScore = 0;
  q.dimensions[0] = {
    ...q.dimensions[0]!,
    status: "READY",
    score: 0,
    unknownReasons: [],
  };
  const html = await renderToString(
    createSSRApp(QualityOverview, { quality: q, trip }),
  );
  expect(html).toContain('value="0"');
  expect(html).toContain("0 / 100");
  expect(html).not.toContain("信息不足，暂不评总分");
});
it("not applicable balance is shown without a full-score bar", async () => {
  const q = quality();
  q.dimensions = [
    {
      key: "BALANCE",
      status: "NOT_APPLICABLE",
      score: null,
      weight: 15,
      deductionIds: [],
      unknownReasons: [],
    },
  ];
  const html = await renderToString(
    createSSRApp(QualityOverview, { quality: q, trip }),
  );
  expect(html).toContain("每日均衡");
  expect(html).toContain("不适用");
  expect(html).not.toContain("<progress");
});
it("whole-trip basic deductions include the affected day outside the selected AI day", async () => {
  const q = quality();
  q.dimensions[0] = { ...q.dimensions[0]!, deductionIds: ["overlap:12"] };
  q.basicIssues = [
    {
      id: "overlap:12",
      code: "TIME_OVERLAP",
      severity: "ERROR",
      dayId: 12,
      itemIds: [102],
      message: "两个安排重叠",
      evidenceIds: [],
    },
  ];
  const html = await renderToString(
    createSSRApp(QualityOverview, { quality: q, trip }),
  );
  expect(html).toContain("第 2 天：两个安排重叠");
});
it("manual meal findings do not invite automatic node insertion", async () => {
  const q = quality();
  q.findings = [
    {
      id: "meal:11",
      ruleId: "QUALITY_LUNCH_WINDOW",
      severity: "WARNING",
      dayIds: [11],
      itemIds: [101],
      message: "建议预留午餐",
      evidence: [
        { metric: "occupied", actual: 120, threshold: 90, unit: "分钟" },
      ],
      penalty: 10,
      evidenceIds: [],
      action: "MANUAL",
    },
  ];
  const html = await renderToString(
    createSSRApp(QualityFindings, {
      quality: q,
      trip,
      dayIds: [11],
      disabled: true,
    }),
  );
  expect(html).toContain("AI 不会新增餐饮或休息节点");
  expect(html).toContain("120 分钟");
  expect(html).toContain("disabled");
});
it("day metrics distinguish lower-bound statistics from confirmed rest", async () => {
  const html = await renderToString(
    createSSRApp(DayQualityCards, { quality: quality(), disabled: false }),
  );
  expect(html).toContain("无法充分判断");
  expect(html).toContain("0 / 1");
  expect(html).toContain("不等于已确认休息");
  expect(html).toContain("未能确定");
});
it("comparison keeps unknown scores as unknown and shows independently calculated after data", async () => {
  const before = quality(),
    after = quality();
  after.totalScore = 95;
  after.dimensions[0] = {
    ...after.dimensions[0]!,
    score: 100,
    status: "READY",
  };
  const html = await renderToString(
    createSSRApp(QualityComparison, {
      comparison: {
        schemaVersion: 2,
        before,
        after,
        resolved: ["missing"],
        remaining: [],
        added: [],
      },
    }),
  );
  expect(html).toContain("原总分 暂不评 → 调整后 95");
  expect(html).toContain("未知分项不按 0 或 100 比较");
  expect(html).toContain("暂无法判断 → 100");
});
