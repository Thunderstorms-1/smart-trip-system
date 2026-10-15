import { test, expect, type Page, type Route } from "@playwright/test";
import type { Post, PostPage } from "../src/features/types";

const title = "把世界变成你的下一段旅程";
const cards = (page: Page) => page.getByTestId("square-story");

function post(id: number): Post {
  return {
    id,
    authorId: 100 + id,
    authorName: `旅行者 ${id}`,
    title: `真实路书 ${id} · 沿着海岸慢慢走`,
    destination: `目的地 ${id}`,
    totalDays: id + 1,
    totalBudget: 1200 + id * 100,
    summary: `第 ${id} 段公开行程的真实摘要。`,
    likeCount: id,
    favoriteCount: id + 2,
    commentCount: id + 3,
    cloneCount: id + 4,
  };
}

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

async function respond(route: Route, data: PostPage) {
  await route.fulfill({ json: { code: 200, data } });
}

async function fixture(
  page: Page,
  feed: (route: Route, url: URL) => Promise<void>,
  authenticated = false,
) {
  await page.addInitScript((auth) => {
    if (sessionStorage.getItem("square-v3-session-seeded")) return;
    sessionStorage.setItem("square-v3-session-seeded", "1");
    localStorage.removeItem("USER_INFO");
    if (auth) localStorage.setItem("AUTH_TOKEN", "square-v3-test");
    else localStorage.removeItem("AUTH_TOKEN");
  }, authenticated);
  // Every API call is fulfilled locally; these tests never reach a backend/provider.
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/community/posts") {
      if (url.searchParams.get("sort") === "popular")
        return respond(route, { items: [], hasMore: false });
      return feed(route, url);
    }
    if (url.pathname === "/api/capabilities")
      return route.fulfill({
        json: { code: 200, data: { ai: false, geo: false, map: false } },
      });
    if (url.pathname === "/api/user/info")
      return route.fulfill({
        json: {
          code: 200,
          data: { id: 17, nickname: "旅人", username: "traveler_17" },
        },
      });
    if (url.pathname === "/api/workbench")
      return route.fulfill({ json: { code: 200, data: [] } });
    return route.fulfill({
      status: 404,
      json: { code: 404, message: "Unexpected test endpoint" },
    });
  });
}

async function noOverflow(page: Page) {
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
}

test("Square shows loading and honest empty state, then recovers from an API error", async ({
  page,
}) => {
  const pending = deferred();
  const requests: URL[] = [];
  await fixture(page, async (route, url) => {
    requests.push(url);
    if (requests.length === 1) {
      await pending.promise;
      return respond(route, { items: [], hasMore: false });
    }
    if (requests.length === 2)
      return route.fulfill({
        status: 503,
        json: { code: 503, message: "公开路书暂不可用，请重试" },
      });
    return respond(route, { items: [post(1)], hasMore: false });
  });
  try {
    await page.goto("/square");
    await expect(
      page.getByRole("heading", { name: title, exact: true }),
    ).toBeVisible();
    await expect(
      page.locator("#square-stories").getByRole("status"),
    ).toHaveText("正在加载路书…");
    await expect(cards(page)).toHaveCount(0);
    pending.resolve();
    await expect(
      page.getByRole("heading", { name: "等待下一段真实旅程", exact: true }),
    ).toBeVisible();
    await expect(
      page.locator("#square-stories").getByRole("status"),
    ).toHaveCount(0);
    await expect(cards(page)).toHaveCount(0);

    await page.getByLabel("搜索城市或路书", { exact: true }).fill("杭州");
    await page.getByRole("button", { name: "搜索", exact: true }).click();
    await expect(page.getByRole("alert")).toContainText(
      "公开路书暂不可用，请重试",
    );
    await expect(cards(page)).toHaveCount(0);
    await page.getByRole("button", { name: "重新加载", exact: true }).click();
    await expect(cards(page)).toHaveCount(1);
    await expect(page.getByRole("alert")).toHaveCount(0);
    expect(requests.map((url) => url.searchParams.get("q"))).toEqual([
      "",
      "杭州",
      "杭州",
    ]);
  } finally {
    pending.resolve();
  }
});

for (const count of [1, 2, 7]) {
  test(`Square renders ${count} real posts in the correct groups without invented covers`, async ({
    page,
  }) => {
    const items = Array.from({ length: count }, (_, index) => post(index + 1));
    await fixture(page, (route) => respond(route, { items, hasMore: false }));
    await page.goto("/square");
    if (count > 3)
      await page
        .getByRole("button", { name: "查看更多最新路书", exact: true })
        .click();
    await expect(cards(page)).toHaveCount(count);
    await expect(
      page.locator(".square-featured-stories").getByTestId("square-story"),
    ).toHaveCount(Math.min(count, 3));
    await expect(
      page.locator(".square-compact-stories").getByTestId("square-story"),
    ).toHaveCount(Math.max(count - 3, 0));
    for (const [index, item] of items.entries()) {
      const card = cards(page).nth(index);
      await expect(card.getByRole("heading")).toHaveText(item.title);
      await expect(card).toContainText(item.destination);
      await expect(card).toContainText(item.summary!);
      await expect(card).toContainText(item.authorName);
      await expect(card).toContainText(new RegExp(`${item.totalDays}\\s*天`));
      await expect(card.locator(".open-story")).toHaveAttribute(
        "href",
        `/posts/${item.id}`,
      );
      // Post has no cover field: scenic assets must never masquerade as its photo.
      await expect(card.locator("img")).toHaveCount(0);
    }
    expect(
      await cards(page).evaluateAll((elements) =>
        elements.flatMap((card) =>
          [card, ...card.querySelectorAll("*")].flatMap((element) =>
            [null, "::before", "::after"]
              .map(
                (pseudo) => getComputedStyle(element, pseudo).backgroundImage,
              )
              .filter((background) => /url\(/.test(background)),
          ),
        ),
      ),
    ).toEqual([]);
    await expect(
      page.getByRole("button", { name: "查看更多", exact: true }),
    ).toHaveCount(0);
    await expect(page.getByRole("main")).toHaveCount(1);
  });
}

test("Square submits only on explicit search and encodes the trimmed query", async ({
  page,
}) => {
  const requests: URL[] = [];
  await fixture(page, async (route, url) => {
    requests.push(url);
    return respond(route, {
      items: [post(requests.length)],
      hasMore: false,
    });
  });
  await page.goto("/square");
  await expect(cards(page)).toHaveCount(1);
  const input = page.getByLabel("搜索城市或路书", { exact: true });
  const query = "杭州 & 海边/慢游";
  await input.fill(`  ${query}  `);
  await input.press("Tab");
  await expect(cards(page).getByRole("heading")).toHaveText(post(1).title);
  expect(requests).toHaveLength(1);
  await input.press("Enter");
  await expect(cards(page).getByRole("heading")).toHaveText(post(2).title);
  expect(requests).toHaveLength(2);
  expect(requests[1]!.searchParams.get("q")).toBe(query);
  expect(requests[1]!.search).toBe(`?q=${encodeURIComponent(query)}`);
  expect(requests[1]!.searchParams.has("cursor")).toBe(false);

  await input.fill("   ");
  await page.getByRole("button", { name: "搜索", exact: true }).click();
  await expect(cards(page).getByRole("heading")).toHaveText(post(3).title);
  expect(requests[2]!.search).toBe("?q=");
});

test("Square retries the failed cursor with its submitted query and deduplicates posts", async ({
  page,
}) => {
  const pending = deferred();
  const requests: URL[] = [];
  const cursor = "next +/=& 中文";
  const submittedQuery = "厦门 & 海岸";
  let cursorAttempts = 0;
  const updated = { ...post(2), title: "路书 2 · 服务端更新后的标题" };
  await fixture(page, async (route, url) => {
    requests.push(url);
    if (!url.searchParams.has("cursor"))
      return respond(route, {
        items: [post(1), post(2), post(3)],
        nextCursor: cursor,
        hasMore: true,
      });
    if (++cursorAttempts === 1) {
      await pending.promise;
      return route.fulfill({
        status: 503,
        json: { code: 503, message: "下一页路书暂不可用，请重试" },
      });
    }
    return respond(route, {
      items: [updated, post(4), post(5), post(6), post(7)],
      hasMore: false,
    });
  });
  try {
    await page.goto("/square");
    await expect(cards(page)).toHaveCount(3);
    const input = page.getByLabel("搜索城市或路书", { exact: true });
    await input.fill(`  ${submittedQuery}  `);
    await input.press("Enter");
    await expect.poll(() => requests.length).toBe(2);
    await expect(cards(page)).toHaveCount(3);
    await input.fill("未提交的新城市");
    await page.getByRole("button", { name: "查看更多", exact: true }).click();
    await expect(
      page.getByRole("button", { name: "加载中…", exact: true }),
    ).toBeDisabled();
    await expect(cards(page)).toHaveCount(3);
    expect(requests).toHaveLength(3);
    expect(requests[2]!.searchParams.get("q")).toBe(submittedQuery);
    expect(requests[2]!.searchParams.get("cursor")).toBe(cursor);
    expect(requests[2]!.search).toBe(
      `?q=${encodeURIComponent(submittedQuery)}&cursor=${encodeURIComponent(cursor)}`,
    );
    pending.resolve();
    await expect(page.getByRole("alert")).toContainText(
      "下一页路书暂不可用，请重试",
    );
    await expect(cards(page)).toHaveCount(3);
    await expect(cards(page).getByRole("heading")).toHaveText(
      [post(1), post(2), post(3)].map((item) => item.title),
    );
    await input.fill("出错后仍未提交的新城市");
    await page.getByRole("button", { name: "重新加载", exact: true }).click();
    await expect(cards(page)).toHaveCount(7);
    expect(requests).toHaveLength(4);
    expect(requests[3]!.search).toBe(requests[2]!.search);
    expect(cursorAttempts).toBe(2);
    await expect(page.getByRole("alert")).toHaveCount(0);
    await expect(cards(page).getByRole("heading")).toHaveText(
      [post(1), updated, post(3), post(4), post(5), post(6), post(7)].map(
        (item) => item.title,
      ),
    );
    await expect(
      page.locator(".square-featured-stories").getByTestId("square-story"),
    ).toHaveCount(3);
    await expect(
      page.locator(".square-compact-stories").getByTestId("square-story"),
    ).toHaveCount(4);
    await expect(
      page.getByRole("button", { name: "查看更多", exact: true }),
    ).toHaveCount(0);
  } finally {
    pending.resolve();
  }
});

test("Square ignores a superseded response even when transport cancellation is unavailable", async ({
  page,
}) => {
  // Keep the old HTTP response in flight to exercise the stale-result guard itself.
  await page.addInitScript(() => {
    const originalFetch = window.fetch.bind(window);
    window.fetch = (input, init) => {
      if (typeof input === "string" && input.startsWith("/api/community/posts"))
        return originalFetch(input, { ...init, signal: undefined });
      return originalFetch(input, init);
    };
  });
  const pending = deferred();
  const oldStarted = deferred();
  const newer = { ...post(3), title: "新查询的有效结果" };
  const older = { ...post(2), title: "旧查询的过期结果" };
  await fixture(page, async (route, url) => {
    const q = url.searchParams.get("q");
    if (q === "旧查询") {
      oldStarted.resolve();
      await pending.promise;
      return respond(route, { items: [older], hasMore: false });
    }
    return respond(route, {
      items: [q === "新查询" ? newer : post(1)],
      hasMore: false,
    });
  });
  try {
    await page.goto("/square");
    await expect(cards(page)).toHaveCount(1);
    const input = page.getByLabel("搜索城市或路书", { exact: true });
    await input.fill("旧查询");
    await input.press("Enter");
    await oldStarted.promise;
    await input.fill("新查询");
    await input.press("Enter");
    await expect(cards(page).getByRole("heading")).toHaveText(newer.title);
    const oldResponse = page.waitForResponse(
      (response) => new URL(response.url()).searchParams.get("q") === "旧查询",
    );
    pending.resolve();
    await (await oldResponse).finished();
    await page.evaluate(
      () =>
        new Promise<void>((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
        ),
    );
    await expect(cards(page).getByRole("heading")).toHaveText(newer.title);
    await expect(page.getByText(older.title, { exact: true })).toHaveCount(0);
    await expect(page.getByRole("alert")).toHaveCount(0);
    await expect(
      page.locator("#square-stories").getByRole("status"),
    ).toHaveCount(0);
  } finally {
    pending.resolve();
  }
});

test("Square mobile keeps auth and navigation usable and restores other route shells", async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await fixture(
    page,
    (route) =>
      respond(route, {
        items: [post(1), post(2), post(3), post(4), post(5), post(6), post(7)],
        hasMore: false,
      }),
    true,
  );
  await page.goto("/square");
  await expect(cards(page)).toHaveCount(3);
  await expect(page.getByRole("main")).toHaveCount(1);
  const nav = page.getByRole("navigation", { name: "主导航", exact: true });
  for (const [name, href] of [
    ["旅行灵感", "/square"],
    ["规划旅行", "/ai-generate"],
    ["我的路书", "/my-trips"],
  ]) {
    await expect(nav.getByRole("link", { name, exact: true })).toBeVisible();
    await expect(nav.getByRole("link", { name, exact: true })).toHaveAttribute(
      "href",
      href!,
    );
  }
  await expect(
    page.getByRole("link", { name: "个人中心", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "退出", exact: true }),
  ).toBeVisible();
  await noOverflow(page);

  await page.getByRole("link", { name: "个人中心", exact: true }).click();
  await expect(page).toHaveURL(/\/profile$/);
  await expect(page.locator(".square-page")).toHaveCount(0);
  await expect(page.locator(".site-header")).toBeVisible();
  await expect(page.locator("main.site-main")).toBeVisible();
  await expect(page.locator(".site-footer")).toBeVisible();
  await noOverflow(page);
  await page.goBack();
  await expect(page.locator(".square-page")).toBeVisible();
  await nav.getByRole("link", { name: "我的路书", exact: true }).click();
  await expect(page).toHaveURL(/\/my-trips$/);
  await expect(
    page.getByRole("heading", { name: "我的旅行库", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".site-header")).toBeVisible();
  await expect(page.locator(".square-page")).toHaveCount(0);
  await page.goBack();
  await expect(page.locator(".square-page")).toBeVisible();
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect(page).toHaveURL(/\/$/);
  expect(
    await page.evaluate(() => localStorage.getItem("AUTH_TOKEN")),
  ).toBeNull();

  await page.goto("/square");
  const login = page.getByRole("link", { name: "登录 / 注册", exact: true });
  await expect(login).toBeVisible();
  const loginURL = new URL((await login.getAttribute("href"))!, page.url());
  expect(loginURL.pathname).toBe("/login");
  expect(loginURL.searchParams.get("redirect")).toBe("/square");
  await noOverflow(page);
  await login.click();
  await expect(page).toHaveURL(
    (url) =>
      url.pathname === "/login" &&
      url.searchParams.get("redirect") === "/square",
  );
  await expect(page.locator(".login-v3")).toBeVisible();
  await expect(page.locator(".square-page")).toHaveCount(0);
  await expect(page.locator("main.login-main")).toBeVisible();
  await expect(page.getByRole("main")).toHaveCount(1);
  await noOverflow(page);
});
