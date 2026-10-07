import { test, expect } from "@playwright/test";

const trip = {
  id: 701,
  userId: 17,
  title: "真实接口保存的湖边旅行",
  destination: "杭州",
  totalDays: 2,
  days: [
    {
      dayIndex: 1,
      dayTheme: "湖边散步",
      items: [
        {
          id: 801,
          itemName: "西湖散步",
          itemType: "SCENIC",
          startTime: "09:30",
          costTimeHours: 2,
          costEstimate: 0,
        },
      ],
    },
    { dayIndex: 2, dayTheme: "老城慢行", items: [] },
  ],
};
const posts = Array.from({ length: 4 }, (_, i) => ({
  id: 90 + i,
  title: "公开旅行" + i,
  destination: "杭州",
  totalDays: 2,
  totalBudget: 30,
  authorName: "旅行者",
  likeCount: 3,
  favoriteCount: 1,
}));

async function fixtures(
  page: import("@playwright/test").Page,
  authenticated = false,
) {
  if (authenticated)
    await page.addInitScript(() =>
      localStorage.setItem("AUTH_TOKEN", "home-fixture"),
    );
  await page.route("**/api/**", (route) => {
    const url = new URL(route.request().url());
    let data: unknown;
    switch (url.pathname) {
      case "/api/capabilities":
        data = { ai: false, geo: false, map: false, notice: "" };
        break;
      case "/api/user/info":
        data = { id: 17, nickname: "旅行者" };
        break;
      case "/api/community/posts":
        data = { items: posts, hasMore: false };
        break;
      case "/api/community/posts/90":
        data = { ...posts[0], trip };
        break;
      case "/api/workbench":
        data = [{ ...trip, days: null }];
        break;
      case "/api/workbench/701":
        data = trip;
        break;
      default:
        return route.fulfill({
          status: 404,
          json: { code: 404, message: "Unexpected endpoint" },
        });
    }
    return route.fulfill({ json: { code: 200, data } });
  });
}

test("public Home uses public snapshot data and existing protected planning redirect", async ({
  page,
}) => {
  await fixtures(page);
  const providerCalls: string[] = [];
  page.on("request", (r) => {
    if (/\/weather|\/route\?|amap|\/ai\/tasks/.test(r.url()))
      providerCalls.push(r.url());
  });
  await page.goto("/");
  await expect(page.locator(".hero-copy p")).toContainText("杭州 · 2 天");
  await expect(page.locator(".home-post")).toHaveCount(3);
  await expect(page.locator(".graph-row").first()).toContainText("西湖散步");
  await expect(page.locator(".hero-stop").first()).toHaveAttribute(
    "href",
    "/posts/90",
  );
  await expect(page.locator(".hero-weather")).toContainText("1 个已保存安排");
  expect(providerCalls).toEqual([]);
  await page.locator(".quick-plan .primary").click();
  await expect(page).toHaveURL(/\/login\?redirect=\/ai-generate/);
});

test("signed-in Home loads summary then detail and opens the actual recent trip", async ({
  page,
}) => {
  await fixtures(page, true);
  await page.goto("/");
  await expect(page.locator(".continue-copy")).toContainText(trip.title);
  await expect(
    page.locator(".trip-continuation .outline-button"),
  ).toHaveAttribute("href", "/trip/edit/701");
  await expect(page.locator(".hero-stop").first()).toHaveAttribute(
    "href",
    "/trip/edit/701",
  );
  await expect(page.locator(".graphic-itinerary")).toContainText(
    "本日已填活动时长 2 小时",
  );
});

test("community failure retries; empty data never fills the page with prototype values", async ({
  page,
}) => {
  await fixtures(page);
  let fail = true;
  await page.route("**/api/community/posts", (route) =>
    fail
      ? route.fulfill({
          status: 503,
          json: { code: 503, message: "暂时无法读取公开路书" },
        })
      : route.fulfill({
          json: { code: 200, data: { items: [], hasMore: false } },
        }),
  );
  await page.goto("/");
  await expect(page.locator(".home-inspiration")).toContainText(
    "暂时无法读取公开路书",
  );
  fail = false;
  await page.getByRole("button", { name: "重新加载", exact: true }).click();
  await expect(page.locator(".home-inspiration")).toContainText(
    "广场还没有公开路书",
  );
  await expect(page.locator(".hero-copy")).not.toContainText("大理");
  await expect(page.locator(".v3-home")).not.toContainText("22°");
  await expect(page.locator(".v3-home")).not.toContainText("18 分钟");
  await expect(page.locator(".hero-stop")).toHaveCount(4);
  await expect(page.locator("a.hero-stop")).toHaveCount(0);
});

test("logout clears private journey and ignores a delayed private response", async ({
  page,
}) => {
  await fixtures(page, true);
  let release!: () => void;
  let requested!: () => void;
  const started = new Promise<void>((r) => {
    requested = r;
  });
  const gate = new Promise<void>((r) => {
    release = r;
  });
  await page.route("**/api/workbench/701", async (route) => {
    requested();
    await gate;
    await route
      .fulfill({
        json: {
          code: 200,
          data: {
            ...trip,
            title: "不得泄露的私密标题",
            destination: "私密目的地",
          },
        },
      })
      .catch(() => {});
  });
  await page.goto("/");
  await started;
  await page.getByRole("button", { name: "退出", exact: true }).click();
  release();
  await expect(page.locator(".hero-copy p")).toContainText("杭州");
  await expect(page.locator(".v3-home")).not.toContainText("私密目的地");
  await expect(page.locator(".v3-home")).not.toContainText("不得泄露");
  expect(
    await page.evaluate(() => localStorage.getItem("AUTH_TOKEN")),
  ).toBeNull();
});

test("missing times, unknown duration, long names and photo failure remain usable at five widths", async ({
  page,
}) => {
  await fixtures(page);
  const long = "沿着城市街巷慢慢行走并停下来观察风景".repeat(5);
  await page.route("**/api/community/posts/90", (route) =>
    route.fulfill({
      json: {
        code: 200,
        data: {
          ...posts[0],
          trip: {
            ...trip,
            destination: long,
            days: [
              {
                dayTheme: long,
                items: [
                  { itemName: long, itemType: "SCENIC", costEstimate: 0 },
                ],
              },
            ],
          },
        },
      },
    }),
  );
  await page.route("**/design-v3/lake-journey.png", (r) => r.abort());
  await page.goto("/");
  await expect(page.locator(".graphic-itinerary")).toContainText("时间待定");
  await expect(page.locator(".graphic-itinerary")).toContainText(
    "本日活动时长尚未填全",
  );
  await expect(page.locator(".hero-footnote")).toContainText("暂不可用");
  for (const width of [320, 390, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await expect(page.locator(".quick-plan .primary")).toBeVisible();
  }
  await page.emulateMedia({ reducedMotion: "reduce" });
  expect(
    await page
      .locator(".route-trace")
      .first()
      .evaluate((e) => getComputedStyle(e).animationName),
  ).toBe("none");
});

test("real demo backend recent trip persists and Home opens it", async ({
  page,
  request,
}) => {
  const username = "home3_" + Date.now();
  const password = "HomeV3Acceptance2026!";
  expect(
    (
      await request.post("/api/auth/register", {
        data: { username, password, nickname: "首页验收" },
      })
    ).ok(),
  ).toBe(true);
  const login = (
    await (
      await request.post("/api/auth/login", { data: { username, password } })
    ).json()
  ).data;
  const headers = { Authorization: "Bearer " + login.token };
  const created = (
    await (
      await request.post("/api/workbench", {
        headers,
        data: {
          title: "首页真实持久化验收",
          destination: "苏州",
          days: [
            {
              dayTheme: "园林漫步",
              items: [
                {
                  itemName: "拙政园",
                  itemType: "SCENIC",
                  startTime: "10:00",
                  costTimeHours: 2,
                  costEstimate: 70,
                },
              ],
            },
          ],
        },
      })
    ).json()
  ).data;
  try {
    await page.addInitScript(
      (t) => localStorage.setItem("AUTH_TOKEN", t),
      login.token,
    );
    await page.goto("/");
    await expect(page.locator(".continue-copy")).toContainText(
      "首页真实持久化验收",
    );
    await expect(page.locator(".graph-row").first()).toContainText("拙政园");
    await page.reload();
    await expect(page.locator(".continue-copy")).toContainText(
      "首页真实持久化验收",
    );
    await page.locator(".trip-continuation .outline-button").click();
    await expect(page).toHaveURL(new RegExp("/trip/edit/" + created.id + "$"));
    await page
      .getByRole("button", { name: "选择安排：拙政园", exact: true })
      .click();
    await expect(
      page.getByLabel("地点 / 活动", { exact: true }).first(),
    ).toHaveValue("拙政园");
  } finally {
    await request.delete("/api/workbench/" + created.id, { headers });
  }
});

test("signed-in empty library falls back to a public snapshot without pretending it is personal", async ({
  page,
}) => {
  await fixtures(page, true);
  await page.route("**/api/workbench", (r) =>
    r.fulfill({ json: { code: 200, data: [] } }),
  );
  await page.goto("/");
  await expect(page.locator(".hero-copy p")).toContainText("杭州");
  await expect(page.locator(".continue-copy")).toContainText("还没有路书");
  await expect(
    page.locator(".trip-continuation .outline-button"),
  ).toHaveAttribute("href", "/ai-generate?mode=manual");
  await expect(page.locator(".hero-stop").first()).toHaveAttribute(
    "href",
    "/posts/90",
  );
});

test("private journey errors can retry independently of the public community feed", async ({
  page,
}) => {
  await fixtures(page, true);
  let fail = true;
  await page.route("**/api/workbench/701", (r) =>
    fail
      ? r.fulfill({
          status: 503,
          json: { code: 503, message: "暂时无法读取我的旅行" },
        })
      : r.fulfill({ json: { code: 200, data: trip } }),
  );
  await page.goto("/");
  await expect(page.locator(".continue-copy")).toContainText(
    "暂时无法读取我的旅行",
  );
  await expect(page.locator(".home-post")).toHaveCount(3);
  fail = false;
  await page
    .locator(".continue-copy")
    .getByRole("button", { name: "重新读取旅行" })
    .click();
  await expect(page.locator(".continue-copy")).toContainText(trip.title);
});

test("Home CSS does not change the Square after client-side navigation", async ({
  page,
}) => {
  await fixtures(page);
  await page.goto("/square");
  const appearance = () =>
    page.evaluate(() => {
      const heading = getComputedStyle(document.querySelector("h1")!);
      const main = getComputedStyle(document.querySelector("main")!);
      const nav = getComputedStyle(document.querySelector("header")!);
      return {
        color: heading.color,
        font: heading.fontFamily,
        size: heading.fontSize,
        mainWidth: main.maxWidth,
        navHeight: nav.height,
      };
    });
  await expect(
    page.getByRole("heading", { name: "旅行灵感广场" }),
  ).toBeVisible();
  const before = await appearance();
  await page.locator(".brand").click();
  await expect(page.locator(".v3-home")).toBeVisible();
  await page.getByRole("link", { name: "旅行灵感", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "旅行灵感广场" }),
  ).toBeVisible();
  expect(await appearance()).toEqual(before);
  await expect(page.locator(".v3-shell")).toHaveCount(0);
});

test("expired session retains the original login redirect and clears its token", async ({
  page,
}) => {
  await fixtures(page, true);
  await page.route("**/api/workbench", (r) =>
    r.fulfill({ status: 401, json: { code: 401, message: "登录已过期" } }),
  );
  await page.goto("/");
  await expect(page).toHaveURL(/\/login\?redirect=\//);
  expect(
    await page.evaluate(() => localStorage.getItem("AUTH_TOKEN")),
  ).toBeNull();
  await expect(page.locator(".v3-home")).toHaveCount(0);
});
