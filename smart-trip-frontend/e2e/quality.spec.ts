import {
  test,
  expect,
  type Page,
  type APIRequestContext,
} from "@playwright/test";
async function setup(page: Page, request: APIRequestContext, days: unknown[]) {
  const username =
      "quality_e2e_" +
      Date.now() +
      "_" +
      Math.random().toString(36).slice(2, 7),
    password = "QualityE2e2026!";
  const registered = await request.post("/api/auth/register", {
    data: { username, password, nickname: "质量诊断验收" },
  });
  expect(registered.ok()).toBe(true);
  const login = await request.post("/api/auth/login", {
    data: { username, password },
  });
  const token = (await login.json()).data.token;
  const headers = { Authorization: "Bearer " + token };
  const created = await request.post("/api/workbench", {
    headers,
    data: {
      title: "第五轮质量验收",
      destination: "成都",
      startDate: "2026-10-05",
      days,
    },
  });
  expect(created.ok()).toBe(true);
  const trip = (await created.json()).data;
  await page.addInitScript((t) => localStorage.setItem("AUTH_TOKEN", t), token);
  await page.goto("/trip/edit/" + trip.id);
  await page
    .getByRole("button", { name: "行程诊断", exact: true })
    .first()
    .click();
  await page.getByRole("button", { name: "检查行程", exact: true }).click();
  return {
    trip,
    headers,
    cleanup: () => request.delete("/api/workbench/" + trip.id, { headers }),
  };
}
const item = (
  name: string,
  type = "SCENIC",
  startTime?: string,
  costTimeHours = 1,
) => ({
  itemType: type,
  itemName: name,
  startTime,
  costTimeHours,
  costEstimate: 0,
});
test("real quality report covers all days although adjustment scope defaults to one day", async ({
  page,
  request,
}) => {
  const ctx = await setup(page, request, [
    { items: [item("重复活动", "SCENIC", "09:00")] },
    { items: [item("重复活动", "SCENIC", "10:00")] },
  ]);
  try {
    await expect(
      page.getByRole("region", { name: "全行程质量报告" }),
    ).toBeVisible();
    await expect(
      page.getByText("评价整份已保存路书；AI 仅调整下方选中的日期。"),
    ).toBeVisible();
    const load = page.getByRole("region", { name: "每日负荷" });
    await expect(
      load.getByText("第 1 天 · 轻松", { exact: true }),
    ).toBeVisible();
    await expect(
      load.getByText("第 2 天 · 轻松", { exact: true }),
    ).toBeVisible();
    await load.getByRole("button", { name: "查看第 2 天安排" }).click();
    await expect(page.getByLabel("开始时间", { exact: true })).toHaveValue(
      "10:00",
    );
    await page
      .getByRole("button", { name: "行程诊断", exact: true })
      .first()
      .click();
    await page.getByRole("button", { name: "检查行程", exact: true }).click();
    await page.setViewportSize({ width: 390, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
    if (process.env.SMARTTRIP_EVIDENCE_DIR)
      await page.screenshot({
        path:
          process.env.SMARTTRIP_EVIDENCE_DIR + "/quality-complete-mobile.png",
        fullPage: true,
      });
  } finally {
    await ctx.cleanup();
  }
});
test("real incomplete plans display no total score and can locate the precise saved node", async ({
  page,
  request,
}) => {
  const ctx = await setup(page, request, [{ items: [item("未定时间")] }]);
  try {
    const quality = page.getByRole("region", { name: "全行程质量报告" });
    await expect(quality.getByText(/信息不足，暂不评总分/)).toBeVisible();
    await expect(quality.locator("progress")).toHaveCount(0);
    const issues = page.getByRole("region", { name: "质量提醒" });
    await issues.getByRole("button", { name: "定位相关安排" }).click();
    await expect(
      page.locator("#plan-item-" + ctx.trip.days[0].items[0].id),
    ).toBeFocused();
    await page.getByLabel("开始时间", { exact: true }).fill("09:00");
    await page.getByRole("button", { name: "保存路书", exact: true }).click();
    await expect(
      page.getByRole("button", { name: "保存路书", exact: true }),
    ).toBeDisabled();
    await page
      .getByRole("button", { name: "行程诊断", exact: true })
      .first()
      .click();
    await page.getByRole("button", { name: "检查行程", exact: true }).click();
    await expect(
      page
        .getByRole("region", { name: "全行程质量报告" })
        .getByText(/信息不足，暂不评总分/),
    ).toHaveCount(0);
  } finally {
    await ctx.cleanup();
  }
});
test("real conflict-free busy day reports meal risk without inventing a restaurant or enabling AI", async ({
  page,
  request,
}) => {
  const ctx = await setup(page, request, [
    { items: [item("连贯游览", "SCENIC", "10:00", 5)] },
  ]);
  try {
    await expect(
      page.getByText("两个安排的时间存在重叠", { exact: true }),
    ).toHaveCount(0);
    const issues = page.getByRole("region", { name: "质量提醒" });
    await expect(
      issues.getByText(/中午安排较连续，建议预留午餐/),
    ).toBeVisible();
    await expect(issues.getByText(/AI 不会新增餐饮或休息节点/)).toBeVisible();
    await expect(
      page.getByRole("button", { name: "生成调整提案" }),
    ).toBeDisabled();
    await page.setViewportSize({ width: 390, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
    if (process.env.SMARTTRIP_EVIDENCE_DIR)
      await page.screenshot({
        path: process.env.SMARTTRIP_EVIDENCE_DIR + "/quality-busy-mobile.png",
        fullPage: true,
      });
    const reloaded = await request.get("/api/workbench/" + ctx.trip.id, {
      headers: ctx.headers,
    });
    expect((await reloaded.json()).data.days[0].items).toHaveLength(1);
  } finally {
    await ctx.cleanup();
  }
});
