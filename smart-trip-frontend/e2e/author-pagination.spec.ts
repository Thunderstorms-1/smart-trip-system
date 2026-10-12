import {
  test,
  expect,
  request as requestFactory,
  type Page,
} from "@playwright/test";
const post = (id: number, author = 17) => ({
  id,
  authorId: author,
  authorName: "旅行者" + author,
  title: "作者路书" + id,
  destination: "成都",
  totalDays: 1,
  totalBudget: 0,
  likeCount: 0,
  favoriteCount: 0,
  commentCount: 0,
  cloneCount: 0,
});
async function shell(page: Page) {
  await page.route("**/api/capabilities", (r) =>
    r.fulfill({
      json: { code: 200, data: { ai: false, geo: false, map: false } },
    }),
  );
  await page.route("**/api/user/public/*", (r) => {
    const id = Number(new URL(r.request().url()).pathname.split("/").at(-1));
    return r.fulfill({
      json: { code: 200, data: { id, nickname: "旅行者" + id } },
    });
  });
}

test("author pagination keeps first page on failure, retries same scoped cursor and deduplicates", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await shell(page);
  const cursors: string[] = [];
  await page.route("**/api/community/posts?*", async (r) => {
    const query = new URL(r.request().url()).searchParams;
    expect(query.get("author")).toBe("17");
    const cursor = query.get("cursor");
    if (cursor) {
      cursors.push(cursor);
      if (cursors.length === 1) {
        await r.fulfill({
          status: 503,
          json: { code: 503, message: "分页暂不可用" },
        });
        return;
      }
    }
    await r.fulfill({
      json: {
        code: 200,
        data: cursor
          ? { items: [post(20), post(21), post(22), post(23)], hasMore: false }
          : {
              items: Array.from({ length: 20 }, (_, i) => post(i + 1)),
              hasMore: true,
              nextCursor: "author-17-next",
            },
      },
    });
  });
  await page.goto("/authors/17");
  await expect(page.locator(".explore-entry")).toHaveCount(20);
  await page.getByRole("button", { name: "查看更多作品", exact: true }).click();
  await expect(page.getByRole("alert")).toHaveText("分页暂不可用");
  await expect(page.locator(".explore-entry")).toHaveCount(20);
  await expect(page.locator(".author-identity")).toBeVisible();
  await page.getByRole("button", { name: "重试加载更多", exact: true }).click();
  await expect(page.locator(".explore-entry")).toHaveCount(23);
  expect(cursors).toEqual(["author-17-next", "author-17-next"]);
  await expect(page.locator(".v3-load-more")).toHaveCount(0);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
});

test("author first-page failure can retry without showing an empty collection", async ({
  page,
}) => {
  await shell(page);
  let attempts = 0;
  await page.route("**/api/community/posts?*", async (r) => {
    attempts++;
    await r.fulfill(
      attempts === 1
        ? { status: 503, json: { code: 503, message: "首次读取失败" } }
        : { json: { code: 200, data: { items: [post(1)], hasMore: false } } },
    );
  });
  await page.goto("/authors/17");
  await expect(page.getByRole("alert")).toContainText("首次读取失败");
  await expect(
    page.getByText("这位旅行者还没有发布路书。", { exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "重新加载", exact: true }).click();
  await expect(page.locator(".explore-entry")).toHaveCount(1);
});

test("changing author cancels pending pagination and resets its cursor", async ({
  page,
}) => {
  await shell(page);
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let waiting = false;
  const queries: Array<{ author: string | null; cursor: string | null }> = [];
  await page.route("**/api/community/posts?*", async (r) => {
    const q = new URL(r.request().url()).searchParams;
    queries.push({ author: q.get("author"), cursor: q.get("cursor") });
    if (q.get("cursor")) {
      waiting = true;
      await gate;
    }
    await r.fulfill({
      json: {
        code: 200,
        data:
          q.get("author") === "18"
            ? { items: [post(80, 18)], hasMore: false }
            : q.get("cursor")
              ? { items: [post(21)], hasMore: false }
              : {
                  items: [post(1)],
                  hasMore: true,
                  nextCursor: "old-author-cursor",
                },
      },
    });
  });
  try {
    await page.goto("/authors/17");
    await page
      .getByRole("button", { name: "查看更多作品", exact: true })
      .click();
    await expect.poll(() => waiting).toBe(true);
    await page.evaluate(() => {
      history.pushState({}, "", "/authors/18");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    await expect(page.locator(".author-scene h1")).toContainText("旅行者18");
    release();
    await expect(page.locator(".explore-entry")).toHaveCount(1);
    await expect(page.locator(".explore-entry")).toHaveAttribute(
      "href",
      "/posts/80",
    );
    expect(queries.at(-1)).toEqual({ author: "18", cursor: null });
    await expect(page.locator(".v3-load-more")).toHaveCount(0);
  } finally {
    release();
  }
});

test("real author can reach every published work beyond the first twenty", async ({
  page,
  request,
}) => {
  test.setTimeout(60000);
  const username = "authorqa_" + Date.now().toString(36),
    password = "AuthorPaging2026!";
  const ids: number[] = [];
  let token = "";
  async function api(path: string, method = "GET", data?: unknown) {
    const r = await request.fetch("/api" + path, {
      method,
      data,
      headers: token ? { Authorization: "Bearer " + token } : {},
    });
    expect(r.status()).toBe(200);
    const v = await r.json();
    expect(v.code).toBe(200);
    return v.data;
  }
  try {
    const caps = await api("/capabilities");
    expect(caps.ai).toBe(false);
    expect(caps.geo).toBe(false);
    await api("/auth/register", "POST", {
      username,
      password,
      nickname: "真实作者分页验收",
    });
    token = (await api("/auth/login", "POST", { username, password })).token;
    const owner = await api("/user/info");
    const postIds: number[] = [];
    for (let i = 0; i < 23; i++) {
      const t = await api("/workbench", "POST", {
        title: `作者分页验收 ${i}`,
        destination: "成都",
        days: [{ items: [] }],
      });
      ids.push(t.id);
      postIds.push(
        (await api(`/community/trips/${t.id}/publish`, "POST", {})).id,
      );
    }
    await page.goto("/authors/" + owner.id);
    await expect(page.locator(".explore-entry")).toHaveCount(20);
    await page
      .getByRole("button", { name: "查看更多作品", exact: true })
      .click();
    await expect(page.locator(".explore-entry")).toHaveCount(23);
    const shown = await page
      .locator(".explore-entry")
      .evaluateAll((links) =>
        links.map((link) =>
          Number(link.getAttribute("href")!.split("/").at(-1)),
        ),
      );
    expect(new Set(shown).size).toBe(23);
    expect([...shown].sort((a, b) => a - b)).toEqual(
      postIds.sort((a, b) => a - b),
    );
    await expect(page.locator(".v3-load-more")).toHaveCount(0);
  } finally {
    const cleaner = await requestFactory.newContext({
      baseURL: process.env.SMARTTRIP_BASE_URL || "http://127.0.0.1:5173",
    });
    try {
      for (const id of ids) {
        const r = await cleaner.delete("/api/workbench/" + id, {
          headers: { Authorization: "Bearer " + token },
        });
        expect(r.status()).toBe(200);
      }
    } finally {
      await cleaner.dispose();
    }
  }
});
