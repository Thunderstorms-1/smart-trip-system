import { test, expect, type Page } from "@playwright/test";
test("real backend saves node constraints and runs offline analysis on mobile", async ({
  page,
}) => {
  await page.goto("/login");
  await page.getByRole("button", { name: "还没有账号？创建一个" }).click();
  await page.getByLabel("用户名").fill("analysis_e2e_" + Date.now());
  await page.getByLabel("昵称", { exact: true }).fill("行程诊断游客");
  await page.getByLabel("密码", { exact: true }).fill("E2eAnalysis2026!");
  await page.getByRole("button", { name: "注册并开始旅行" }).click();
  await page.waitForURL("**/my-trips");
  await page
    .locator(".v3-page-heading")
    .getByRole("button", { name: "新建路书" })
    .click();
  await page.getByLabel("目的地", { exact: true }).fill("成都");
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await page.getByRole("button", { name: "创建私密路书", exact: true }).click();
  await page.waitForURL("**/trip/edit/*");
  await page.getByRole("button", { name: "＋ 添加行程节点" }).click();
  await page.getByLabel("地点 / 活动", { exact: true }).fill("公园漫步");
  await page.getByLabel("活动环境（由你确认）").selectOption("OUTDOOR");
  await page.getByLabel("智能调整时固定此安排").check();
  await page.getByRole("button", { name: "＋ 添加行程节点" }).click();
  await page.getByLabel("地点 / 活动", { exact: true }).fill("博物馆参观");
  await page.getByLabel("开始时间", { exact: true }).fill("10:00");
  await page.getByRole("button", { name: "保存路书", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "保存路书", exact: true }),
  ).toBeDisabled();
  await page.reload();
  await page
    .getByRole("button", { name: "选择安排：公园漫步", exact: true })
    .click();
  await expect(page.getByLabel("活动环境（由你确认）").first()).toHaveValue(
    "OUTDOOR",
  );
  await expect(page.getByLabel("智能调整时固定此安排").first()).toBeChecked();
  let external = 0;
  page.on("request", (r) => {
    if (/amap\.com|chat\/completions/.test(r.url())) external++;
  });
  await page
    .getByRole("button", { name: "行程诊断", exact: true })
    .first()
    .click();
  await page.getByRole("button", { name: "检查行程", exact: true }).click();
  await expect(
    page
      .locator(".analysis-panel")
      .getByText("两个安排的时间存在重叠", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "生成调整提案" }),
  ).toBeDisabled();
  await expect(
    page.getByText(/基础检查可继续使用.*未调用外部服务。/, {
      exact: false,
    }),
  ).toBeVisible();
  if (process.env.SMARTTRIP_EVIDENCE_DIR)
    await page.screenshot({
      path: process.env.SMARTTRIP_EVIDENCE_DIR + "/analysis-desktop.png",
      fullPage: true,
    });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
  if (process.env.SMARTTRIP_EVIDENCE_DIR)
    await page.screenshot({
      path: process.env.SMARTTRIP_EVIDENCE_DIR + "/analysis-mobile.png",
      fullPage: true,
    });
  expect(external).toBe(0);
  page.once("dialog", (d) => d.accept());
  await page.getByText("更多操作", { exact: true }).click();
  await page.getByRole("button", { name: "删除这份路书" }).click();
  await page.waitForURL("**/my-trips");
});
const trip = {
  id: 1,
  userId: 1,
  version: 1,
  title: "预览测试",
  destination: "成都",
  days: [
    {
      id: 11,
      dayIndex: 1,
      items: [
        {
          id: 101,
          itemType: "SCENIC",
          itemName: "公园",
          startTime: "09:00",
          costTimeHours: 2,
          costEstimate: 50,
        },
        {
          id: 102,
          itemType: "SCENIC",
          itemName: "博物馆",
          startTime: "10:00",
          costTimeHours: 1,
          costEstimate: 0,
        },
      ],
    },
  ],
};
const report = {
  id: "analysis",
  tripId: 1,
  baseVersion: 1,
  revision: 0,
  dayIds: [11],
  mode: "walking",
  expiresAt: "2099-01-01T00:00:00Z",
  issues: [
    {
      id: "overlap",
      code: "TIME_OVERLAP",
      severity: "ERROR",
      dayId: 11,
      itemIds: [101, 102],
      message: "两个安排的时间存在重叠",
      evidenceIds: [],
    },
  ],
  sources: [],
};
const proposal = {
  dayIds: [11],
  changes: [
    {
      itemId: 102,
      itemName: "博物馆",
      fromDayId: 11,
      toDayId: 11,
      fromDayIndex: 1,
      toDayIndex: 1,
      fromOrder: 2,
      toOrder: 2,
      fromTime: "10:00",
      toTime: "11:00",
      kinds: ["TIME"],
    },
  ],
  reasons: [{ itemIds: [102], evidenceIds: ["overlap"], text: "错开时间重叠" }],
  remainingIssues: [],
  unverifiedRouteDayIds: [],
  sources: [],
};
async function fixture(page: Page, conflict = false) {
  let saves = 0;
  await page.addInitScript(() =>
    localStorage.setItem("AUTH_TOKEN", "analysis-fixture"),
  );
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    let data: unknown = {};
    if (path === "/api/user/info") data = { id: 1, nickname: "游客" };
    else if (path === "/api/capabilities")
      data = { ai: true, geo: false, map: false, notice: "测试响应" };
    else if (path.startsWith("/api/membership"))
      data = [{ userId: 1, role: "OWNER" }];
    else if (path === "/api/workbench/1/analyses") data = report;
    else if (path === "/api/ai/tasks/task/apply") {
      saves++;
      if (conflict) {
        await route.fulfill({
          status: 409,
          contentType: "application/json",
          body: JSON.stringify({
            code: 409,
            message: "路书已被修改，请刷新后重试",
          }),
        });
        return;
      }
      data = {
        ...trip,
        version: 2,
        days: [
          {
            ...trip.days[0],
            items: trip.days[0].items.map((i) => ({
              ...i,
              startTime: i.id === 102 ? "11:00" : i.startTime,
            })),
          },
        ],
      };
    } else if (path === "/api/ai/tasks")
      data = {
        id: "task",
        type: "ADJUST",
        tripId: 1,
        baseVersion: 1,
        status: "PREVIEW",
        result: trip,
        proposal,
      };
    else if (path === "/api/workbench/1") data = trip;
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ code: 200, data }),
    });
  });
  return () => saves;
}
test("browser preview waits for one explicit confirmation and updates the saved draft", async ({
  page,
}) => {
  const saves = await fixture(page);
  await page.goto("/trip/edit/1");
  await page
    .getByRole("button", { name: "行程诊断", exact: true })
    .first()
    .click();
  await page.getByRole("button", { name: "检查行程", exact: true }).click();
  await page.getByRole("button", { name: "生成调整提案" }).click();
  await expect(page.getByRole("heading", { name: "调整差异" })).toBeVisible();
  expect(saves()).toBe(0);
  await expect(page.getByText("错开时间重叠", { exact: false })).toBeVisible();
  await page.getByRole("button", { name: "整份确认保存" }).click();
  await expect(
    page.getByRole("button", { name: "保存路书", exact: true }),
  ).toBeDisabled();
  expect(saves()).toBe(1);
  await page.getByRole("button", { name: "行程编辑", exact: true }).click();
  await page
    .getByRole("button", { name: "选择安排：博物馆", exact: true })
    .click();
  await expect(page.getByLabel("开始时间", { exact: true })).toHaveValue(
    "11:00",
  );
});
test("browser version conflict retains the proposed differences", async ({
  page,
}) => {
  await fixture(page, true);
  await page.goto("/trip/edit/1");
  await page
    .getByRole("button", { name: "行程诊断", exact: true })
    .first()
    .click();
  await page.getByRole("button", { name: "检查行程", exact: true }).click();
  await page.getByRole("button", { name: "生成调整提案" }).click();
  await page.getByRole("button", { name: "整份确认保存" }).click();
  await expect(
    page.getByText("路书已被修改，请刷新后重试", { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("heading", { name: "调整差异" })).toBeVisible();
});

test("Insight shares the constrained ADJUST preview with diagnostics and saves only after confirmation", async ({
  page,
}) => {
  const saves = await fixture(page);
  await page.goto("/trip/edit/1");
  const insight = page.locator("#workbench-insight");
  const adjust = insight.getByRole("region", { name: "受约束行程调整" });
  await expect(adjust).toBeVisible();
  await expect(adjust.getByRole("checkbox", { name: "第 1 天" })).toBeChecked();
  await adjust.getByRole("button", { name: "检查行程", exact: true }).click();
  await adjust
    .getByRole("button", { name: "生成调整提案", exact: true })
    .click();
  await expect(
    adjust.getByRole("heading", { name: "调整差异", exact: true }),
  ).toBeVisible();
  expect(saves()).toBe(0);
  await page
    .getByRole("button", { name: "行程诊断", exact: true })
    .first()
    .click();
  await expect(
    page
      .locator("#workbench-analysis-host")
      .getByRole("heading", { name: "调整差异", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "返回行程编辑", exact: true }).click();
  await expect(
    adjust.getByRole("heading", { name: "调整差异", exact: true }),
  ).toBeVisible();
  expect(saves()).toBe(0);
  await adjust
    .getByRole("button", { name: "整份确认保存", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "保存路书", exact: true }),
  ).toBeDisabled();
  expect(saves()).toBe(1);
  await page
    .getByRole("button", { name: "选择安排：博物馆", exact: true })
    .click();
  await expect(page.getByLabel("开始时间", { exact: true })).toHaveValue(
    "11:00",
  );
});
test("mobile Insight ADJUST keeps preview and differences on a version conflict", async ({
  page,
}) => {
  const saves = await fixture(page, true);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/trip/edit/1");
  await page.getByRole("button", { name: "行程洞察", exact: true }).click();
  const adjust = page
    .locator("#workbench-insight")
    .getByRole("region", { name: "受约束行程调整" });
  await adjust.getByRole("button", { name: "检查行程", exact: true }).click();
  await adjust
    .getByRole("button", { name: "生成调整提案", exact: true })
    .click();
  await adjust
    .getByRole("button", { name: "整份确认保存", exact: true })
    .click();
  await expect(
    adjust.getByText("路书已被修改，请刷新后重试", { exact: true }),
  ).toBeVisible();
  await expect(
    adjust.getByRole("heading", { name: "调整差异", exact: true }),
  ).toBeVisible();
  expect(saves()).toBe(1);
  await expect(
    adjust.getByText("原路书尚未改变。", { exact: false }),
  ).toBeVisible();
});
