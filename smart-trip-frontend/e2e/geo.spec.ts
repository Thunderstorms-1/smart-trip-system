import { test, expect, type Page } from "@playwright/test";
const sdk =
  "window.__mapCreates=0;window.AMap={Map:class{constructor(){window.__mapCreates++}destroy(){}add(){}setFitView(){}},Marker:class{},Polyline:class{}};";
async function setup(page: Page, onScript?: () => Promise<void>) {
  let count = 0,
    scripts = 0;
  await page.addInitScript(() => localStorage.setItem("AUTH_TOKEN", "fixture"));
  await page.route("https://webapi.amap.com/**", async (r) => {
    scripts++;
    if (onScript) await onScript();
    await r.fulfill({ contentType: "text/javascript", body: sdk });
  });
  await page.route("**/api/**", async (r) => {
    const path = new URL(r.request().url()).pathname;
    let data: unknown = {};
    if (path === "/api/capabilities")
      data = { ai: false, geo: true, map: true };
    else if (path === "/api/user/info") data = { id: 1, nickname: "fixture" };
    else if (path === "/api/workbench/1")
      data = {
        id: 1,
        userId: 1,
        title: "测试地图",
        destination: "成都",
        version: 7,
        totalBudget: 0,
        days: [
          {
            id: 2,
            dayDate: "2026-10-10",
            items: [
              {
                id: 101,
                itemName: "地点一",
                itemType: "SCENIC",
                costEstimate: 0,
              },
              {
                id: 102,
                itemName: "地点二",
                itemType: "SCENIC",
                costEstimate: 0,
              },
            ],
          },
        ],
      };
    else if (path.includes("/membership/"))
      data = [{ userId: 1, role: "OWNER" }];
    else if (path === "/api/geo/config")
      data = { key: "fixture", securityJsCode: "fixture" };
    else if (path.includes("/route")) {
      count++;
      data = {
        tripVersion: 7,
        mode: "walking",
        points: [
          { itemId: 101, name: "地点一", resolved: true, location: "104,30" },
          { itemId: 102, name: "地点二", resolved: true, location: "105,31" },
        ],
        legs: [],
        retrievedAt: "2026-10-04T12:00:00Z",
      };
    } else if (path === "/api/geo/weather")
      data = { available: false, date: "2026-10-10", reason: "暂无预报" };
    await r.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ code: 200, data }),
    });
  });
  await page.goto("/trip/edit/1");
  await page.getByRole("button", { name: "路线与天气", exact: true }).click();
  return { calls: () => count, scripts: () => scripts };
}
test("map page reuses current route, explicitly refreshes and shows query time", async ({
  page,
}) => {
  const fixture = await setup(page);
  await page.getByRole("button", { name: "查询路线", exact: true }).click();
  await expect(page.getByText(/查询时间.*2026-10-04/)).toBeVisible();
  await expect
    .poll(() =>
      page.evaluate(
        () => (window as unknown as { __mapCreates: number }).__mapCreates,
      ),
    )
    .toBe(1);
  await page.getByRole("button", { name: "查询路线", exact: true }).click();
  expect(fixture.calls()).toBe(1);
  await page.getByRole("button", { name: "重新查询", exact: true }).click();
  await expect.poll(fixture.calls).toBe(2);
  expect(fixture.scripts()).toBe(1);
  await page.getByRole("button", { name: /查看.*天气/ }).click();
  await expect(page.getByText("暂无预报", { exact: true })).toBeVisible();
});
test("leaving a map view before SDK finishes cannot create a stale map", async ({
  page,
}) => {
  let release!: () => void;
  const gate = new Promise<void>((r) => (release = r));
  const fixture = await setup(page, () => gate);
  await page.getByRole("button", { name: "查询路线", exact: true }).click();
  await expect.poll(fixture.scripts).toBe(1);
  await page.getByRole("button", { name: "记账与行李", exact: true }).click();
  release();
  await page.waitForFunction(
    () => !!(window as unknown as { AMap?: unknown }).AMap,
  );
  expect(
    await page.evaluate(
      () => (window as unknown as { __mapCreates: number }).__mapCreates,
    ),
  ).toBe(0);
});
test("real disabled capabilities keep geo actions unavailable without external calls", async ({
  page,
  request,
}) => {
  const login = await request.post("http://127.0.0.1:8080/api/auth/login", {
    data: { username: "demo_traveler", password: "DemoTrip2026!" },
  });
  expect(login.ok()).toBeTruthy();
  const auth = (await login.json()).data;
  const created = await request.post("http://127.0.0.1:8080/api/workbench", {
    headers: { Authorization: "Bearer " + auth.token },
    data: {
      title: "地图不可用验收",
      destination: "成都",
      startDate: "2026-10-10",
      days: [{ items: [] }],
    },
  });
  expect(created.ok()).toBeTruthy();
  const trip = (await created.json()).data;
  let external = 0;
  await page.route(/https:\/\/(?:restapi|webapi)\.amap\.com\//, async (r) => {
    external++;
    await r.abort();
  });
  try {
    await page.addInitScript(
      (token) => localStorage.setItem("AUTH_TOKEN", token),
      auth.token,
    );
    await page.goto("/trip/edit/" + trip.id);
    await page.getByRole("button", { name: "路线与天气", exact: true }).click();
    await expect(page.getByText(/路线与天气查询尚未启用/)).toBeVisible();
    await expect(
      page.getByRole("button", { name: "查询路线", exact: true }),
    ).toBeDisabled();
    await expect(
      page.getByRole("button", { name: /查看.*天气/ }),
    ).toBeDisabled();
    expect(external).toBe(0);
  } finally {
    await request.delete("http://127.0.0.1:8080/api/workbench/" + trip.id, {
      headers: { Authorization: "Bearer " + auth.token },
    });
  }
});
