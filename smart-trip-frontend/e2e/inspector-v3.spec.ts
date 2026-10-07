import {
  test,
  expect,
  type Page,
  type APIRequestContext,
} from "@playwright/test";
async function setup(page: Page, request: APIRequestContext) {
  const username =
      "inspector_" + Date.now() + Math.random().toString(36).slice(2, 6),
    password = "Inspector2026!";
  expect(
    (
      await request.post("/api/auth/register", {
        data: { username, password, nickname: "编辑验收" },
      })
    ).ok(),
  ).toBe(true);
  const token = (
    await (
      await request.post("/api/auth/login", { data: { username, password } })
    ).json()
  ).data.token;
  const headers = { Authorization: "Bearer " + token };
  const node = (name: string) => ({
    itemName: name,
    itemType: "SCENIC",
    startTime: "09:00",
    costTimeHours: 1.3,
    costEstimate: 12.34,
    environmentType: "OUTDOOR",
    scheduleLocked: true,
    aiTips: "原始备注",
    transportTips: "交通提示",
  });
  const response = await request.post("/api/workbench", {
    headers,
    data: {
      title: "Inspector真实验收",
      destination: "成都",
      days: [
        { items: [node("节点甲"), node("节点乙")] },
        { items: [node("节点丙")] },
      ],
    },
  });
  expect(response.ok()).toBe(true);
  const trip = (await response.json()).data;
  await page.addInitScript((t) => localStorage.setItem("AUTH_TOKEN", t), token);
  await page.goto("/trip/edit/" + trip.id);
  await page.locator(".wb-node-select").first().waitFor();
  return {
    trip,
    read: async () =>
      (
        await (
          await request.get("/api/workbench/" + trip.id, { headers })
        ).json()
      ).data,
    cleanup: () => request.delete("/api/workbench/" + trip.id, { headers }),
  };
}
const select = (page: Page, name: string) =>
  page.getByRole("button", { name: "选择安排：" + name, exact: true }).click();
const inspector = (page: Page) =>
  page.getByRole("dialog", { name: "安排 Inspector" });
async function save(page: Page) {
  const modal = page.locator(".wb-inspector-dialog:modal");
  const button = (await modal.count())
    ? modal.getByRole("button", { name: "保存路书", exact: true })
    : page.getByRole("button", { name: "保存路书", exact: true });
  await button.click();
  await expect(button).toBeDisabled();
}

test("desktop edits original draft, preserves selection after reorder and save, restores focus after cross-day/delete", async ({
  page,
  request,
}) => {
  const c = await setup(page, request);
  try {
    await select(page, "节点甲");
    const panel = inspector(page);
    await expect(panel).toBeVisible();
    expect((await panel.boundingBox())!.x).toBeGreaterThan(700);
    await expect(
      page.locator(".wb-timeline-region .wb-node-fields"),
    ).toHaveCount(0);
    await page.getByLabel("地点 / 活动", { exact: true }).fill("直接修改甲");
    await expect(
      page.locator("#timeline-item-" + c.trip.days[0].items[0].id),
    ).toContainText("直接修改甲");
    expect((await c.read()).days[0].items[0].itemName).toBe("节点甲");
    await page.getByLabel("旅行备注").fill("同一草稿备注");
    await page.getByRole("button", { name: "向下移动节点" }).click();
    await expect(panel).toContainText("第 2 个安排");
    await expect
      .poll(() => panel.evaluate((el) => el.contains(document.activeElement)))
      .toBe(true);
    await save(page);
    await expect(page.getByLabel("地点 / 活动", { exact: true })).toHaveValue(
      "直接修改甲",
    );
    expect((await c.read()).days[0].items[1]).toMatchObject({
      id: c.trip.days[0].items[0].id,
      aiTips: "同一草稿备注",
      costTimeHours: 1.3,
      costEstimate: 12.34,
      transportTips: "交通提示",
      scheduleLocked: true,
    });
    await page.getByRole("button", { name: "收起编辑", exact: true }).click();
    await expect(
      page.locator("#timeline-item-" + c.trip.days[0].items[0].id),
    ).toBeFocused();
    await select(page, "直接修改甲");
    await page.getByLabel("移动节点到另一天").selectOption("1");
    await expect(panel).toHaveCount(0);
    await expect(
      page.getByRole("button", { name: "＋ 添加行程节点" }),
    ).toBeFocused();
    await page.getByRole("button", { name: /^第 2 天 ·/ }).click();
    await select(page, "直接修改甲");
    await expect(page.getByLabel("旅行备注")).toHaveValue("同一草稿备注");
    await save(page);
    await page.getByRole("button", { name: "删除", exact: true }).click();
    await expect(
      page.getByRole("button", { name: "选择安排：节点丙", exact: true }),
    ).toBeFocused();
    await select(page, "节点丙");
    await page.getByRole("button", { name: "删除", exact: true }).click();
    await expect(
      page.getByRole("button", { name: "＋ 添加行程节点" }),
    ).toBeFocused();
    await save(page);
    expect((await c.read()).days[1].items).toHaveLength(0);
  } finally {
    page.on("dialog", (d) => d.accept());
    await c.cleanup();
  }
});
test("mobile Bottom/Full Sheet traps focus, saves/rebinds new node and restores safe scrolling/focus", async ({
  page,
  request,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const c = await setup(page, request);
  try {
    await page.getByRole("button", { name: "＋ 添加行程节点" }).click();
    const panel = inspector(page);
    await expect(panel).toBeVisible();
    await expect(page.getByLabel("地点 / 活动", { exact: true })).toBeFocused();
    expect(await page.evaluate(() => document.body.style.overflow)).toBe(
      "hidden",
    );
    expect((await panel.boundingBox())!.height).toBeLessThan(844 * 0.8);
    await page.getByRole("button", { name: "展开编辑面板" }).click();
    expect((await panel.boundingBox())!.height).toBeGreaterThan(844 * 0.9);
    await page.getByLabel("地点 / 活动", { exact: true }).fill("移动端新增");
    await page.getByLabel("旅行备注").fill("Full Sheet 备注");
    await save(page);
    await expect(page.getByLabel("地点 / 活动", { exact: true })).toHaveValue(
      "移动端新增",
    );
    const added = (await c.read()).days[0].items.at(-1);
    expect(added.id).toBeTruthy();
    expect(added.aiTips).toBe("Full Sheet 备注");
    for (let i = 0; i < 15; i++) await page.keyboard.press("Tab");
    expect(
      await panel.evaluate((el) => el.contains(document.activeElement)),
    ).toBe(true);
    expect(
      await panel
        .locator(".wb-inspector-footer")
        .evaluate((el) => parseFloat(getComputedStyle(el).paddingBottom)),
    ).toBeGreaterThanOrEqual(12);
    await page.keyboard.press("Escape");
    await expect(panel).toHaveCount(0);
    await expect(page.locator("#timeline-item-" + added.id)).toBeFocused();
    expect(await page.evaluate(() => document.body.style.overflow)).toBe("");
    await select(page, "移动端新增");
    await page.getByLabel("旅行备注").fill("关闭不会丢草稿");
    await page.getByRole("button", { name: "收起编辑", exact: true }).click();
    await select(page, "移动端新增");
    await expect(page.getByLabel("旅行备注")).toHaveValue("关闭不会丢草稿");
    await page.keyboard.press("Escape");
  } finally {
    page.on("dialog", (d) => d.accept());
    await c.cleanup();
  }
});
test("resizing and nested existing POI dialog release only their own scroll locks", async ({
  page,
  request,
}) => {
  await page.route("**/api/capabilities", (r) =>
    r.fulfill({
      json: {
        code: 200,
        data: {
          ai: false,
          geo: true,
          poi: true,
          map: false,
          weather: false,
          route: false,
        },
      },
    }),
  );
  let searches = 0;
  await page.route("**/api/geo/search?**", (r) => {
    searches++;
    return r.fulfill({ json: { code: 200, data: [] } });
  });
  const c = await setup(page, request);
  try {
    await page.evaluate(() => (document.body.style.overflow = "clip"));
    await select(page, "节点甲");
    expect(await page.evaluate(() => document.body.style.overflow)).toBe(
      "clip",
    );
    await page.setViewportSize({ width: 350, height: 800 });
    expect(await page.evaluate(() => document.body.style.overflow)).toBe(
      "hidden",
    );
    await page
      .getByRole("button", { name: "关联真实地点", exact: true })
      .click();
    await expect(page.locator("dialog[open]")).toHaveCount(2);
    await page.keyboard.press("Escape");
    await expect(page.locator("dialog[open]")).toHaveCount(1);
    expect(await page.evaluate(() => document.body.style.overflow)).toBe(
      "hidden",
    );
    await expect(
      page.getByRole("button", { name: "关联真实地点", exact: true }),
    ).toBeFocused();
    await page.setViewportSize({ width: 1440, height: 1000 });
    await expect(inspector(page)).toBeVisible();
    await expect
      .poll(() => page.evaluate(() => document.body.style.overflow))
      .toBe("clip");
    await page.keyboard.press("Escape");
    await expect(
      page.getByRole("button", { name: "选择安排：节点甲", exact: true }),
    ).toBeFocused();
    expect(searches).toBe(0);
  } finally {
    await c.cleanup();
  }
});
