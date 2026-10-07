import { test, expect, type Page } from "@playwright/test";
// Provider responses and the SDK are explicit test doubles, never shipped data.
const trip = {
  id: 1,
  userId: 1,
  title: "画布测试桩",
  destination: "成都",
  version: 7,
  days: [
    {
      id: 11,
      dayDate: "2026-10-07",
      items: [
        {
          id: 101,
          itemName: "已确认甲",
          itemType: "SCENIC",
          costEstimate: 0,
          poiId: "fixture-a",
          coordinateSystem: "GCJ02",
          longitude: 104.06,
          latitude: 30.66,
        },
        {
          id: 102,
          itemName: "已确认乙",
          itemType: "SCENIC",
          costEstimate: 0,
          poiId: "fixture-b",
          coordinateSystem: "GCJ02",
          longitude: 104.07,
          latitude: 30.67,
        },
        { id: 103, itemName: "未确认", itemType: "SCENIC", costEstimate: 0 },
      ],
    },
    { id: 12, dayDate: "2026-10-08", items: [] },
  ],
};
const report = {
  tripVersion: 7,
  mode: "walking",
  retrievedAt: "2026-10-07T01:00:00Z",
  points: [
    { itemId: 101, name: "已确认甲", resolved: true, location: "104.06,30.66" },
    { itemId: 102, name: "已确认乙", resolved: true, location: "104.07,30.67" },
    { itemId: 103, name: "未确认", resolved: false },
  ],
  legs: [
    {
      from: 101,
      to: 102,
      available: true,
      distanceMeters: "0",
      polylines: ["104.06,30.66;104.07,30.67", "bad;invalid"],
    },
    { from: 102, to: 103, available: false, reason: "地点待确认" },
  ],
};
const sdk = `window.__canvas={creates:0,destroys:0,paths:[],centers:[]};window.AMap={
  Map:class { constructor(host){ this.host=host;window.__canvas.creates++;host.innerHTML='<p>SDK 测试桩（非真实地图）</p>'; } add(v){ if(v.options.content)this.host.appendChild(v.options.content);else window.__canvas.paths.push(v.options.path); } destroy(){window.__canvas.destroys++;this.host.innerHTML='';} setFitView(){} setCenter(p){window.__canvas.centers.push(p);} getZoom(){return 12;} setZoom(){} },
  Marker:class {constructor(options){this.options=options;}},Polyline:class {constructor(options){this.options=options;}}
};`;
async function setup(
  page: Page,
  options: {
    routeGate?: Promise<void>;
    scriptGate?: Promise<void>;
    weather?: unknown;
    map?: boolean;
    route?: boolean;
  } = {},
) {
  const calls = { route: 0, weather: 0, config: 0, script: 0, poi: 0 };
  await page.addInitScript(() =>
    localStorage.setItem("AUTH_TOKEN", "canvas-fixture"),
  );
  await page.route("https://webapi.amap.com/**", async (r) => {
    calls.script++;
    await options.scriptGate;
    await r.fulfill({ contentType: "text/javascript", body: sdk });
  });
  await page.route("**/api/**", async (r) => {
    const path = new URL(r.request().url()).pathname;
    let data: unknown = {};
    if (path === "/api/user/info") data = { id: 1, nickname: "测试桩" };
    else if (path === "/api/capabilities")
      data = {
        ai: false,
        geo: true,
        map: options.map ?? true,
        route: options.route ?? true,
        weather: true,
        poi: true,
      };
    else if (path.startsWith("/api/membership"))
      data = [{ userId: 1, role: "OWNER" }];
    else if (path === "/api/workbench/1") data = trip;
    else if (path === "/api/geo/config") {
      calls.config++;
      data = { key: "fixture", securityJsCode: "fixture" };
    } else if (path.includes("/route")) {
      calls.route++;
      await options.routeGate;
      data = report;
    } else if (path === "/api/geo/weather") {
      calls.weather++;
      data = options.weather ?? {
        available: true,
        date: "2026-10-07",
        city: "成都",
        low: "-2",
        high: "0",
        dayWeather: "晴",
        issuedAt: "2026-10-07T00:00:00Z",
      };
    } else if (path === "/api/geo/search") calls.poi++;
    await r.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ code: 200, data }),
    });
  });
  await page.goto("/trip/edit/1");
  await page
    .getByRole("button", { name: "选择安排：已确认甲", exact: true })
    .waitFor();
  return calls;
}
test("canvas only queries explicitly, draws verified geometry, links pins to original Inspector and invalidates on edit (mock providers)", async ({
  page,
}) => {
  const calls = await setup(page);
  expect(calls).toEqual({ route: 0, weather: 0, config: 0, script: 0, poi: 0 });
  await page.getByRole("button", { name: "路线与天气", exact: true }).click();
  expect(calls.route).toBe(0);
  await page.getByRole("button", { name: "查询路线", exact: true }).click();
  await expect(page.getByText("约 0.0 km · 耗时未提供")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "地图安排：已确认甲" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "地图安排：未确认" }),
  ).toHaveCount(0);
  expect(await page.evaluate(() => (window as any).__canvas.paths)).toEqual([
    [
      [104.06, 30.66],
      [104.07, 30.67],
    ],
  ]);
  await page.getByRole("button", { name: /查看.*天气/ }).click();
  await expect(page.locator(".wb-canvas-weather")).toContainText("-2–0°");
  await page.getByRole("button", { name: "返回行程编辑", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "地图安排：已确认甲" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "地图安排：已确认甲" }).click();
  await expect(page.getByLabel("地点 / 活动")).toHaveValue("已确认甲");
  await expect(
    page.getByRole("button", { name: "地图安排：已确认甲" }),
  ).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "收起编辑", exact: true }).click();
  await page
    .getByRole("button", { name: "选择安排：已确认乙", exact: true })
    .click();
  expect(
    await page.evaluate(() => (window as any).__canvas.centers.at(-1)),
  ).toEqual([104.07, 30.67]);
  await page.getByLabel("旅行备注").fill("同一草稿修改");
  await expect(page.locator(".wb-map-pin")).toHaveCount(0);
  await expect(page.locator(".wb-canvas-weather")).toHaveCount(0);
  expect(calls).toEqual({ route: 1, weather: 1, config: 1, script: 1, poi: 0 });
});
test("map-only capability uses saved POI coordinates without route or automatic search; mobile controls preserve Inspector (mock SDK)", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const calls = await setup(page, {
    route: false,
    weather: {
      available: true,
      date: "2026-10-08",
      city: "成都",
      low: "10",
      high: "20",
      dayWeather: "晴",
    },
  });
  await page.getByRole("button", { name: "路线与天气", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "查询路线", exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "加载地图", exact: true }).click();
  await page.getByRole("button", { name: /查看.*天气/ }).click();
  await expect(page.getByText("对应日期的完整天气预报暂不可用")).toBeVisible();
  await expect(page.locator(".wb-canvas-weather")).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("button", { name: "行程编辑", exact: true }),
  ).toBeFocused();
  await expect(page.locator(".wb-geo-controls")).toBeHidden();
  await page
    .getByRole("button", { name: "选择安排：已确认甲", exact: true })
    .click();
  await expect(page.locator(".wb-inspector-dialog:modal")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("button", { name: "选择安排：已确认甲", exact: true }),
  ).toBeFocused();
  expect(calls.route).toBe(0);
  expect(calls.poi).toBe(0);
  expect(calls.config).toBe(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(
    390,
  );
});
test("explicit cancellation and day switch reject delayed route and SDK results (mock providers)", async ({
  page,
}) => {
  let release!: () => void;
  const gate = new Promise<void>((r) => (release = r));
  const calls = await setup(page, { routeGate: gate });
  await page.getByRole("button", { name: "路线与天气", exact: true }).click();
  await page.getByRole("button", { name: "查询路线", exact: true }).click();
  await expect.poll(() => calls.route).toBe(1);
  await page.getByRole("button", { name: "取消查询", exact: true }).click();
  release();
  await expect(
    page.getByRole("button", { name: "查询路线", exact: true }),
  ).toBeEnabled();
  expect(calls.config).toBe(0);
  let releaseScript!: () => void;
  const scriptGate = new Promise<void>((r) => (releaseScript = r));
  await page.route("https://webapi.amap.com/**", async (r) => {
    calls.script++;
    await scriptGate;
    await r.fulfill({ contentType: "text/javascript", body: sdk });
  });
  await page.getByRole("button", { name: "查询路线", exact: true }).click();
  await expect.poll(() => calls.script).toBe(1);
  await page
    .getByRole("button", { name: "第 2 天 · 自由探索", exact: true })
    .click();
  releaseScript();
  await page.waitForFunction(() => Boolean((window as any).AMap));
  expect(await page.evaluate(() => (window as any).__canvas.creates)).toBe(0);
  await expect(page.locator(".wb-map-pin,.wb-canvas-weather")).toHaveCount(0);
});
