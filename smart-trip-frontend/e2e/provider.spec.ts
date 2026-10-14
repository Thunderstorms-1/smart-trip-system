import { test, expect } from "@playwright/test";

test("mixed capability payload enables routes and keeps weather and the SDK disabled (mock responses)", async ({
  page,
}) => {
  const trip = {
    id: 1,
    userId: 1,
    version: 1,
    title: "分项能力验收",
    destination: "成都",
    startDate: "2026-10-05",
    days: [
      {
        id: 11,
        dayIndex: 1,
        dayDate: "2026-10-05",
        items: [
          { id: 101, itemType: "SCENIC", itemName: "公园", poiId: "B00001" },
          { id: 102, itemType: "SCENIC", itemName: "博物馆", poiId: "B00002" },
        ],
      },
    ],
  };
  let routeCalls = 0,
    weatherCalls = 0,
    sdkCalls = 0;
  await page.addInitScript(() =>
    localStorage.setItem("AUTH_TOKEN", "provider-fixture"),
  );
  page.on("request", (r) => {
    if (r.url().includes("webapi.amap.com")) sdkCalls++;
  });
  await page.route("**/api/**", async (route) => {
    const p = new URL(route.request().url()).pathname;
    let data: unknown = {};
    if (p === "/api/user/info") data = { id: 1, nickname: "测试游客" };
    else if (p === "/api/capabilities")
      data = {
        ai: false,
        geo: true,
        map: false,
        poi: false,
        route: true,
        weather: false,
        status: {
          ai: "DISABLED",
          geo: "READY",
          map: "BROWSER_KEY_MISSING",
          poi: "DISABLED",
          route: "READY",
          weather: "QUOTA_UNVERIFIED",
        },
        notice: "固定响应",
      };
    else if (p.startsWith("/api/membership"))
      data = [{ userId: 1, role: "OWNER" }];
    else if (p === "/api/workbench/1") data = trip;
    else if (p.includes("/route")) {
      routeCalls++;
      data = {
        tripVersion: 1,
        mode: "walking",
        retrievedAt: "2026-10-05T10:00:00Z",
        points: [],
        legs: [
          {
            from: 101,
            to: 102,
            available: true,
            distanceMeters: "1000",
            durationSeconds: "600",
            polylines: ["104,30;105,31"],
          },
        ],
      };
    } else if (p.includes("/weather")) weatherCalls++;
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ code: 200, data }),
    });
  });
  await page.goto("/trip/edit/1");
  await page.getByRole("button", { name: "路线与天气", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "查询路线", exact: true }),
  ).toBeEnabled();
  await expect(page.getByRole("button", { name: /查看.*天气/ })).toBeDisabled();
  await expect(
    page.getByText("天气免费额度或接口权限尚未确认，当前不会调用外部服务。", {
      exact: true,
    }),
  ).toBeVisible();
  await page.getByRole("button", { name: "查询路线", exact: true }).click();
  await expect(page.getByText(/约 1.0 km/)).toBeVisible();
  await page.getByRole("button", { name: "查询路线", exact: true }).click();
  expect(routeCalls).toBe(1);
  await page.getByRole("button", { name: "重新查询", exact: true }).click();
  await expect.poll(() => routeCalls).toBe(2);
  expect(weatherCalls).toBe(0);
  expect(sdkCalls).toBe(0);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
  if (process.env.SMARTTRIP_EVIDENCE_DIR)
    await page.screenshot({
      path: process.env.SMARTTRIP_EVIDENCE_DIR + "/provider-mixed-mobile.png",
      fullPage: true,
    });
});

test("mock POI selection persists only a reference through the real backend and reload", async ({
  page,
}) => {
  await page.route("**/api/capabilities", (r) =>
    r.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        code: 200,
        data: {
          ai: false,
          geo: true,
          map: false,
          poi: true,
          route: false,
          weather: false,
          status: {
            ai: "DISABLED",
            geo: "READY",
            map: "DISABLED",
            poi: "READY",
            route: "DISABLED",
            weather: "DISABLED",
          },
          notice: "POI search fixture only",
        },
      }),
    }),
  );
  let searches = 0;
  await page.route("**/api/geo/search?**", (r) => {
    searches++;
    return r.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        code: 200,
        data: [
          {
            poiId: "B000TEST",
            name: "成都博物馆",
            address: "成都市青羊区",
            location: "104.06,30.66",
            adcode: "510100",
            provider: "AMAP",
            coordinateSystem: "GCJ02",
          },
        ],
      }),
    });
  });
  await page.goto("/login");
  await page.getByRole("button", { name: "创建账号" }).click();
  await page.getByLabel("用户名").fill("provider_e2e_" + Date.now());
  await page.getByLabel("昵称", { exact: true }).fill("地点验收游客");
  await page.getByLabel("密码", { exact: true }).fill("ProviderE2e2026!");
  await page.getByRole("button", { name: "注册并登录" }).click();
  await page.waitForURL("**/my-trips");
  await page.locator(".v3-page-heading").getByRole("button", { name: "新建路书" }).click();
  await page.getByLabel("目的地", { exact: true }).fill("成都");
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await page.getByRole("button", { name: "创建私密路书", exact: true }).click();
  await page.waitForURL("**/trip/edit/*");
  const tripId = page.url().split("/").pop()!;
  try {
    await page.getByRole("button", { name: "＋ 添加行程节点" }).click();
    await page.getByLabel("地点 / 活动", { exact: true }).fill("博物馆");
    await page
      .getByRole("button", { name: "关联真实地点", exact: true })
      .click();
    expect(searches).toBe(0);
    await page.getByRole("button", { name: "搜索", exact: true }).click();
    await page
      .getByRole("button", { name: /成都博物馆.*成都市青羊区/ })
      .click();
    await page.getByRole("button", { name: "保存路书", exact: true }).click();
    await expect(
      page.getByRole("button", { name: "保存路书", exact: true }),
    ).toBeDisabled();
    await page.reload();
    await page
      .getByRole("button", { name: "选择安排：成都博物馆", exact: true })
      .click();
    await expect(page.getByLabel("地点 / 活动", { exact: true })).toHaveValue(
      "成都博物馆",
    );
    const t = await page.evaluate(async (id) => {
      const r = await fetch("/api/workbench/" + id, {
        headers: {
          Authorization: "Bearer " + localStorage.getItem("AUTH_TOKEN"),
        },
      });
      return (await r.json()).data;
    }, tripId);
    const item = t.days[0].items[0];
    expect(item.poiId).toBe("B000TEST");
    expect(item.longitude == null).toBe(true);
    expect(item.latitude == null).toBe(true);
    expect(item.address == null).toBe(true);
    expect(searches).toBe(1);
    if (process.env.SMARTTRIP_EVIDENCE_DIR)
      await page.screenshot({
        path:
          process.env.SMARTTRIP_EVIDENCE_DIR +
          "/provider-reference-desktop.png",
        fullPage: true,
      });
  } finally {
    await page.evaluate(async (id) => {
      await fetch("/api/workbench/" + id, {
        method: "DELETE",
        headers: {
          Authorization: "Bearer " + localStorage.getItem("AUTH_TOKEN"),
        },
      });
    }, tripId);
  }
});
