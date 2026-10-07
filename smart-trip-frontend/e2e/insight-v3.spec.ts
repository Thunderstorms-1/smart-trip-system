import {
  test,
  expect,
  type Page,
  type APIRequestContext,
} from "@playwright/test";
import type { AnalysisReport } from "../src/features/types";
async function setup(page: Page, request: APIRequestContext, days: unknown[]) {
  const username =
      "insight_" + Date.now() + Math.random().toString(36).slice(2, 7),
    password = "InsightTest2026!";
  expect(
    (
      await request.post("/api/auth/register", {
        data: { username, password, nickname: "洞察验收" },
      })
    ).ok(),
  ).toBe(true);
  const login = await request.post("/api/auth/login", {
    data: { username, password },
  });
  const token = (await login.json()).data.token;
  const headers = { Authorization: "Bearer " + token };
  const created = await request.post("/api/workbench", {
    headers,
    data: {
      title: "洞察真实数据验收",
      destination: "成都",
      startDate: "2026-10-10",
      days,
    },
  });
  expect(created.ok()).toBe(true);
  const trip = (await created.json()).data;
  await page.addInitScript((t) => localStorage.setItem("AUTH_TOKEN", t), token);
  await page.goto("/trip/edit/" + trip.id);
  await page.locator(".wb-node-select").first().waitFor();
  return {
    trip,
    cleanup: () => request.delete("/api/workbench/" + trip.id, { headers }),
  };
}
const item = (name: string, time?: string) => ({
  itemName: name,
  itemType: "SCENIC",
  startTime: time,
  costTimeHours: 2,
  costEstimate: 0,
});
async function analyze(page: Page) {
  const response = page.waitForResponse(
    (r) => r.url().endsWith("/analyses") && r.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "分析已保存行程", exact: true })
    .click();
  const r = (await (await response).json()).data as AnalysisReport;
  await expect(page.locator(".wb-quality-insight")).toContainText(
    "当前已保存版本的分析",
  );
  return r;
}
test("real UNKNOWN and single-day NOT_APPLICABLE are distinct from a score, with node focus", async ({
  page,
  request,
}) => {
  const c = await setup(page, request, [{ items: [item("未填写时间")] }]);
  try {
    const insight = page.locator(".wb-quality-insight");
    await expect(insight).toContainText("尚未分析");
    await expect(insight.locator("progress")).toHaveCount(0);
    const r = await analyze(page);
    expect(r.quality?.totalScore).toBeNull();
    await expect(insight).toContainText("暂不评总分");
    await expect(insight).toContainText("暂无法判断");
    await expect(insight).toContainText("不适用");
    await insight
      .getByRole("button", { name: /定位 第 1 天 · 未填写时间/ })
      .first()
      .click();
    await expect(
      page.locator("#plan-item-" + c.trip.days[0].items[0].id),
    ).toBeFocused();
    await page.getByLabel("开始时间", { exact: true }).fill("09:00");
    await expect(insight).toContainText("草稿已变化");
    await expect(insight.locator("progress")).toHaveCount(0);
    await page.getByRole("button", { name: "保存路书", exact: true }).click();
    await expect(
      page.getByRole("button", { name: "保存路书", exact: true }),
    ).toBeDisabled();
    await expect(insight).toContainText("重新分析");
    await expect(insight.locator(".wb-insight-score")).toHaveCount(0);
    await page.getByRole("button", { name: "收起编辑", exact: true }).click();
    await analyze(page);
    await expect(insight.locator(".wb-insight-score")).toHaveCount(1);
  } finally {
    await c.cleanup();
  }
});
test("real four dimensions survive day selection, bridge the existing diagnostic, and locate exact second-day node on mobile", async ({
  page,
  request,
}) => {
  const c = await setup(page, request, [
    { items: [item("重复活动", "09:00")] },
    { items: [{ ...item("重复活动", "09:00"), costTimeHours: 8 }] },
  ]);
  try {
    const insight = page.locator(".wb-quality-insight");
    const r = await analyze(page);
    expect(r.quality?.dimensions).toHaveLength(4);
    for (const d of r.quality!.dimensions) {
      const row = insight.locator(".wb-insight-dimensions > div").filter({
        hasText: (
          {
            TIME: "时间安排",
            PACE: "行程节奏",
            MEAL_REST: "用餐与休息",
            BALANCE: "每日均衡",
          } as const
        )[d.key],
      });
      await expect(row).toContainText(
        d.status === "READY" && d.score != null
          ? d.score + " / 100"
          : d.status === "NOT_APPLICABLE"
            ? "不适用"
            : "暂无法判断",
      );
    }
    await page.getByRole("button", { name: /^第 2 天 ·/ }).click();
    await expect(insight).toContainText("当前已保存版本");
    await page
      .getByRole("button", { name: "行程诊断", exact: true })
      .first()
      .click();
    const response = page.waitForResponse(
      (r) => r.url().endsWith("/analyses") && r.request().method() === "POST",
    );
    await page.getByRole("button", { name: "检查行程", exact: true }).click();
    await response;
    await page
      .getByRole("button", { name: "返回行程编辑", exact: true })
      .click();
    await expect(insight).toContainText("当前已保存版本");
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole("button", { name: "行程洞察", exact: true }).click();
    await expect(insight).toBeVisible();
    await expect(
      insight.getByRole("button", { name: "关闭洞察", exact: true }),
    ).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(
      page.getByRole("button", { name: "行程洞察", exact: true }),
    ).toBeFocused();
    await page.getByRole("button", { name: "行程洞察", exact: true }).click();
    await insight
      .getByRole("button", { name: /定位 第 2 天 · 重复活动/ })
      .first()
      .click();
    await expect(insight).not.toBeVisible();
    await expect(
      page.getByRole("dialog", { name: "安排 Inspector" }),
    ).toBeVisible();
    await expect(page.getByLabel("地点 / 活动", { exact: true })).toHaveValue(
      "重复活动",
    );
    await page.keyboard.press("Escape");
    await expect(
      page.locator("#timeline-item-" + c.trip.days[1].items[0].id),
    ).toBeFocused();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
  } finally {
    await c.cleanup();
  }
});
test("a real delayed analysis response cannot restore scores after a draft edit", async ({
  page,
  request,
}) => {
  const c = await setup(page, request, [
    { items: [item("等待分析", "09:00")] },
  ]);
  try {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    let received!: () => void;
    const started = new Promise<void>((r) => (received = r));
    await page.route("**/analyses", async (route) => {
      const response = await route.fetch();
      received();
      await gate;
      await route.fulfill({ response });
    });
    await page
      .getByRole("button", { name: "分析已保存行程", exact: true })
      .click();
    await started;
    await page.locator(".wb-node-select").first().click();
    await page.getByLabel("地点 / 活动", { exact: true }).fill("已变化");
    release();
    await expect(page.locator(".wb-quality-insight")).toContainText(
      "草稿已变化",
    );
    await expect(page.locator(".wb-insight-score")).toHaveCount(0);
    await expect(page.locator(".wb-quality-insight progress")).toHaveCount(0);
  } finally {
    page.on("dialog", (d) => d.accept());
    await c.cleanup();
  }
});
