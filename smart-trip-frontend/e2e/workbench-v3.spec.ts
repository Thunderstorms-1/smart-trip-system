import { test, expect, type Page } from "@playwright/test";

// These are test-only API fixtures. Production reads the existing workbench API.
const seed = () => ({
  id: 1,
  userId: 1,
  version: 0,
  title: "工作台测试夹具",
  destination: "成都",
  summary: "真实字段的界面回归",
  startDate: "2026-10-12",
  days: [
    {
      id: 11,
      dayTheme: "城中漫步",
      items: [
        {
          id: 101,
          itemType: "SCENIC",
          itemName: "公园",
          startTime: "09:00",
          costTimeHours: 1.3,
          costEstimate: 12.34,
          environmentType: "OUTDOOR",
          scheduleLocked: true,
          aiTips: "保留备注",
          transportTips: "保留交通提示",
          poiId: "B00001",
        },
        {
          id: 102,
          itemType: "FOOD",
          itemName: "午餐",
          startTime: "12:00",
          costTimeHours: 1,
          costEstimate: 30,
        },
      ],
    },
    {
      id: 12,
      dayTheme: "自由探索",
      items: [
        { id: 103, itemType: "OTHER", itemName: "未定活动", costEstimate: 0 },
      ],
    },
  ],
});
async function fixture(page: Page, viewer = false, conflict = false) {
  let data = seed(),
    nextId = 200,
    attempts = 0;
  const providerCalls: string[] = [];
  await page.addInitScript(() =>
    localStorage.setItem("AUTH_TOKEN", "workbench-test-token"),
  );
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    let response: unknown = {};
    if (path === "/api/user/info")
      response = { id: viewer ? 2 : 1, nickname: "测试游客" };
    else if (path === "/api/capabilities")
      response = {
        ai: false,
        geo: false,
        map: false,
        poi: false,
        route: false,
        weather: false,
        status: {
          ai: "DISABLED",
          geo: "DISABLED",
          map: "DISABLED",
          poi: "DISABLED",
          route: "DISABLED",
          weather: "DISABLED",
        },
      };
    else if (path === "/api/membership/trips/1")
      response = [
        { userId: viewer ? 2 : 1, role: viewer ? "VIEWER" : "OWNER" },
      ];
    else if (
      path === "/api/workbench/1" &&
      route.request().method() === "PUT"
    ) {
      attempts++;
      if (conflict && attempts === 1) {
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
      const body = route.request().postDataJSON();
      for (const day of body.days)
        for (const node of day.items) {
          expect(node).not.toHaveProperty("selected");
          expect(node).not.toHaveProperty("nodeKey");
          if (node.id == null) node.id = nextId++;
        }
      data = { ...body, version: data.version + 1 };
      response = data;
    } else if (path === "/api/workbench/1") response = data;
    else if (path.endsWith("/revisions")) response = [];
    else if (/\/geo\/|\/ai\//.test(path)) {
      providerCalls.push(path);
      response = {};
    } else if (path === "/api/community/posts")
      response = { items: [], hasMore: false };
    else if (path === "/api/workbench") response = [];
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ code: 200, data: response }),
    });
  });
  return { data: () => data, providerCalls, attempts: () => attempts };
}
async function select(page: Page, name: string) {
  await page
    .getByRole("button", { name: "选择安排：" + name, exact: true })
    .click();
}
async function save(page: Page) {
  await page.getByRole("button", { name: "保存路书", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "保存路书", exact: true }),
  ).toBeDisabled();
}

test("timeline selection, unsaved reorder, save identity and cross-day transfer keep every field", async ({
  page,
}) => {
  const f = await fixture(page);
  await page.goto("/trip/edit/1");
  await expect(page.locator(".wb-node-fields")).toHaveCount(0);
  await select(page, "公园");
  await expect(page.getByLabel("预估花费（元）")).toHaveValue("12.34");
  await expect(page.getByLabel("停留时长（小时）")).toHaveValue("1.3");
  await expect(page.getByLabel("智能调整时固定此安排")).toBeChecked();
  await page.getByLabel("开始时间", { exact: true }).fill("09:30");
  await page.getByRole("button", { name: "收起编辑" }).click();
  await expect(
    page.getByRole("button", { name: "选择安排：公园", exact: true }),
  ).toBeFocused();
  await page.getByRole("button", { name: "＋ 添加行程节点" }).click();
  await page.getByLabel("地点 / 活动", { exact: true }).fill("草稿甲");
  await page.getByRole("button", { name: "＋ 添加行程节点" }).click();
  await page.getByLabel("地点 / 活动", { exact: true }).fill("草稿乙");
  await page.getByRole("button", { name: "向上移动节点" }).click();
  await page.getByLabel("旅行备注").fill("排序后仍编辑乙");
  await save(page);
  await expect(page.getByLabel("地点 / 活动", { exact: true })).toHaveValue(
    "草稿乙",
  );
  expect(f.data().days[0]!.items.map((n) => n.itemName)).toEqual([
    "公园",
    "午餐",
    "草稿乙",
    "草稿甲",
  ]);
  const id = f.data().days[0]!.items[2]!.id;
  expect(f.data().days[0]!.items[2]!.aiTips).toBe("排序后仍编辑乙");
  await page.getByLabel("移动节点到另一天").selectOption("1");
  await page.getByRole("button", { name: /第 2 天 ·/ }).click();
  await select(page, "草稿乙");
  await save(page);
  expect(f.data().days[1]!.items.at(-1)!.id).toBe(id);
  await page.getByRole("button", { name: /第 1 天 ·/ }).click();
  await select(page, "草稿甲");
  await page.getByRole("button", { name: "删除", exact: true }).click();
  await save(page);
  expect(f.data().days[0]!.items.map((n) => n.itemName)).toEqual([
    "公园",
    "午餐",
  ]);
  expect(f.data().days[0]!.items[0]).toMatchObject({
    id: 101,
    startTime: "09:30",
    costEstimate: 12.34,
    costTimeHours: 1.3,
    poiId: "B00001",
    scheduleLocked: true,
    aiTips: "保留备注",
    transportTips: "保留交通提示",
  });
  await page.reload();
  await select(page, "公园");
  await expect(page.getByLabel("开始时间", { exact: true })).toHaveValue(
    "09:30",
  );
});

test("conflict preserves editable draft and explicit retry uses the same save workflow", async ({
  page,
}) => {
  const f = await fixture(page, false, true);
  await page.goto("/trip/edit/1");
  await select(page, "公园");
  await page.getByLabel("旅行备注").fill("未保存的修改");
  await page.getByRole("button", { name: "保存路书", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("路书已被修改");
  await expect(page.getByLabel("旅行备注")).toHaveValue("未保存的修改");
  await expect(
    page.getByRole("button", { name: "保存路书", exact: true }),
  ).toBeEnabled();
  await save(page);
  expect(f.attempts()).toBe(2);
});

test("viewer can inspect nodes without edit, save, add or provider requests", async ({
  page,
}) => {
  const f = await fixture(page, true);
  await page.goto("/trip/edit/1");
  await select(page, "公园");
  await expect(
    page.getByLabel("地点 / 活动", { exact: true }),
  ).not.toBeEditable();
  await expect(page.getByLabel("智能调整时固定此安排")).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "保存路书", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "＋ 添加行程节点" }),
  ).toHaveCount(0);
  await expect(page.getByRole("button", { name: "＋ 添加一天" })).toHaveCount(
    0,
  );
  expect(f.providerCalls).toEqual([]);
});

test("mobile drawer, node Escape and history dialog restore focus without fabricated providers", async ({
  page,
}) => {
  const f = await fixture(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/trip/edit/1");
  await page.getByRole("button", { name: "收起时间轴" }).click();
  await expect(
    page.getByRole("button", { name: "选择安排：公园", exact: true }),
  ).not.toBeVisible();
  await expect(page.getByRole("img", { name: /非地理地图/ })).toBeVisible();
  await page.getByRole("button", { name: "展开时间轴" }).click();
  await select(page, "公园");
  await page.getByLabel("旅行备注").focus();
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("button", { name: "选择安排：公园", exact: true }),
  ).toBeFocused();
  await page.getByRole("button", { name: "历史版本", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  expect(
    await page.evaluate(() =>
      document.querySelector("dialog")!.contains(document.activeElement),
    ),
  ).toBe(true);
  expect(await page.evaluate(() => document.body.style.overflow)).toBe(
    "hidden",
  );
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("button", { name: "历史版本", exact: true }),
  ).toBeFocused();
  expect(await page.evaluate(() => document.body.style.overflow)).toBe("");
  await expect(
    page.getByRole("button", { name: "AI 调整预览", exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "路线与天气", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "查询路线", exact: true }),
  ).toBeDisabled();
  await expect(
    page.locator(".weather-temp,.map-container,.map-pin,.map-route"),
  ).toHaveCount(0);
  expect(f.providerCalls).toEqual([]);
  for (const width of [350, 390, 700, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
  }
});

test("editor CSS stays isolated on other routes after client navigation", async ({
  page,
}) => {
  await fixture(page);
  const ready: Record<string, string> = {
    "/": ".v3-home",
    "/square": ".v3-explore h1",
    "/login": ".v3-entry",
    "/profile": ".v3-traveler",
    "/ai-generate": ".v3-builder",
    "/my-trips": ".v3-library h1",
  };
  async function waitReady(path: string) {
    await page.locator(ready[path]!).waitFor();
    await page.evaluate(() => document.fonts.ready);
  }
  async function clientRoute(path: string) {
    await page.evaluate((path) => {
      history.pushState({}, "", path);
      window.dispatchEvent(new PopStateEvent("popstate"));
    }, path);
    await expect(page).toHaveURL(new RegExp(path.replaceAll("/", "\\/") + "$"));
    await expect(page.locator(".workbench-v3-shell")).toHaveCount(0);
    await waitReady(path);
  }
  const paths = [
    "/",
    "/square",
    "/login",
    "/profile",
    "/ai-generate",
    "/my-trips",
  ];
  const baseline: Record<string, string[]> = {};
  for (const path of paths) {
    await page.goto(path);
    await waitReady(path);
    baseline[path] = await page.evaluate(() => [
      getComputedStyle(document.querySelector("main")!).padding,
      getComputedStyle(document.querySelector("header")!).height,
      getComputedStyle(document.querySelector("header")!).backgroundColor,
    ]);
  }
  await page.goto("/trip/edit/1");
  await page.locator(".wb-timeline-panel").waitFor();
  for (const path of paths) {
    await clientRoute(path);
    expect(
      await page.evaluate(() => [
        getComputedStyle(document.querySelector("main")!).padding,
        getComputedStyle(document.querySelector("header")!).height,
        getComputedStyle(document.querySelector("header")!).backgroundColor,
      ]),
    ).toEqual(baseline[path]);
  }
});
