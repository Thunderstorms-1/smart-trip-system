import { test, expect, type Page, type Route } from "@playwright/test";
const trip = {
  id: 71,
  title: "沿着湖岸的旅行",
  destination: "杭州",
  totalBudget: 0,
  days: [
    {
      id: 11,
      dayTheme: "湖岸慢行",
      dayDate: "2026-10-08",
      items: [
        {
          id: 21,
          itemType: "SCENIC",
          itemName: "西湖散步",
          startTime: "09:00",
          costTimeHours: 2,
          costEstimate: 0,
          aiTips: "慢慢走",
          scheduleLocked: true,
        },
      ],
    },
    { id: 12, dayTheme: "老城探索", items: [] },
  ],
};
const post = {
  id: 90,
  authorId: 17,
  authorName: "旅人",
  title: trip.title,
  destination: trip.destination,
  totalDays: 2,
  totalBudget: 0,
  liked: false,
  favorited: false,
  likeCount: 0,
  favoriteCount: 0,
  commentCount: 0,
  trip,
};
async function fixture(
  page: Page,
  authenticated = true,
  handle?: (route: Route, url: URL) => Promise<boolean>,
) {
  if (authenticated)
    await page.addInitScript(() =>
      localStorage.setItem("AUTH_TOKEN", "batch-two-fixture"),
    );
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    if (handle && (await handle(route, url))) return;
    let data: unknown;
    switch (url.pathname) {
      case "/api/capabilities":
        data = {
          ai: false,
          geo: false,
          map: false,
          status: { ai: "DISABLED" },
          notice: "",
        };
        break;
      case "/api/user/info":
        data = { id: 17, nickname: "旅人", travelPreferences: "喜欢博物馆" };
        break;
      case "/api/community/posts/90":
        data = post;
        break;
      case "/api/community/posts/90/comments":
        data = [];
        break;
      case "/api/community/share/token":
        data = trip;
        break;
      case "/api/user/public/17":
        data = {
          id: 17,
          nickname: "旅人",
          homeCity: "成都",
          bio: "沿着河岸走",
        };
        break;
      case "/api/community/posts":
        data = { items: [post], hasMore: false };
        break;
      case "/api/workbench":
        data = [];
        break;
      default:
        return route.fulfill({
          status: 404,
          json: { code: 404, message: "Unexpected endpoint" },
        });
    }
    await route.fulfill({ json: { code: 200, data } });
  });
}
async function buildInputs(page: Page) {
  await page.getByLabel("目的地", { exact: true }).fill("成都");
  await page.getByLabel("路书标题", { exact: true }).fill("我的成都旅行");
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await page.getByLabel("旅行天数", { exact: true }).fill("2");
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await page
    .getByLabel("这次旅行的小期待", { exact: true })
    .fill("留出休息时间");
}

test("mobile builder validates steps and creates original private aggregate only on confirmation", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  let created: Record<string, unknown> | undefined;
  await fixture(page, true, async (route, url) => {
    if (
      url.pathname !== "/api/workbench" ||
      route.request().method() !== "POST"
    )
      return false;
    created = route.request().postDataJSON();
    await route.fulfill({ json: { code: 200, data: { ...created, id: 71 } } });
    return true;
  });
  await page.goto("/ai-generate?mode=manual");
  await expect(
    page.getByRole("button", { name: "AI 生成草稿", exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "想去哪里？", exact: true }),
  ).toBeVisible();
  await buildInputs(page);
  expect(created).toBeUndefined();
  await expect(page.locator(".builder-review")).toContainText("2 天");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
  await page.getByRole("button", { name: "创建私密路书", exact: true }).click();
  await expect(page).toHaveURL(/\/trip\/edit\/71$/);
  expect(created).toMatchObject({
    title: "我的成都旅行",
    destination: "成都",
    tripStyle: "城市漫步",
    travelGroup: "朋友同行",
    summary: "留出休息时间",
  });
  expect(created?.days).toEqual([
    { dayTheme: "第1天 · 自由探索", items: [] },
    { dayTheme: "第2天 · 自由探索", items: [] },
  ]);
});

test("AI builder uses existing task request and real task status without synthetic progress", async ({
  page,
}) => {
  let submitted: Record<string, unknown> | undefined;
  await fixture(page, true, async (route, url) => {
    if (url.pathname === "/api/capabilities") {
      await route.fulfill({
        json: {
          code: 200,
          data: { ai: true, geo: false, map: false, notice: "" },
        },
      });
      return true;
    }
    if (url.pathname === "/api/ai/tasks") {
      submitted = route.request().postDataJSON();
      await route.fulfill({
        json: {
          code: 200,
          data: { id: "task-71", type: "GENERATE", status: "QUEUED" },
        },
      });
      return true;
    }
    if (url.pathname === "/api/ai/tasks/task-71/events") {
      await route.fulfill({
        contentType: "text/event-stream",
        body: 'event: task\nid: task-71\ndata: {"id":"task-71","type":"GENERATE","status":"RUNNING"}\n\n',
      });
      return true;
    }
    return false;
  });
  await page.goto("/ai-generate");
  await expect(
    page.getByRole("button", { name: "AI 生成草稿", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await buildInputs(page);
  await page.getByRole("button", { name: "生成 AI 草稿", exact: true }).click();
  await expect(page.locator(".builder-task")).toContainText("正在构建旅行草稿");
  expect(submitted?.type).toBe("GENERATE");
  expect(submitted?.requestKey).toBeTruthy();
  expect(submitted?.prompt).not.toContain("喜欢博物馆");
  expect(submitted?.prompt).not.toContain("旅行偏好");
  expect(submitted?.prompt).toContain("成都");
  expect(
    await page.evaluate(() => localStorage.getItem("SMARTTRIP_TASK")),
  ).toBe("task-71");
  await expect(
    page.getByRole("button", { name: "手动创建路书", exact: true }),
  ).toBeDisabled();
});

test("Login preserves registration, session and protected redirect", async ({
  page,
}) => {
  const calls: string[] = [];
  await fixture(page, false, async (route, url) => {
    if (!["/api/auth/register", "/api/auth/login"].includes(url.pathname))
      return false;
    calls.push(url.pathname);
    const body = route.request().postDataJSON();
    expect(body.username).toBe("traveler_17");
    expect(body.password).toBe("password123");
    if (url.pathname.endsWith("register")) expect(body.nickname).toBe("旅人");
    await route.fulfill({
      json: {
        code: 200,
        data: url.pathname.endsWith("login") ? { token: "new-session" } : null,
      },
    });
    return true;
  });
  await page.goto("/login?redirect=/profile");
  await page
    .getByRole("button", { name: "还没有账号？创建一个", exact: true })
    .click();
  await page.getByLabel("用户名", { exact: true }).fill("traveler_17");
  await page.getByLabel("昵称", { exact: true }).fill("旅人");
  await page.getByLabel("密码", { exact: true }).fill("password123");
  await page
    .getByRole("button", { name: "注册并开始旅行", exact: true })
    .click();
  await expect.poll(() => new URL(page.url()).pathname).toBe("/profile");
  expect(calls).toEqual(["/api/auth/register", "/api/auth/login"]);
  expect(await page.evaluate(() => localStorage.getItem("AUTH_TOKEN"))).toBe(
    "new-session",
  );
});

test("public Post keeps day focus, anonymous login redirect and share isolation", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await fixture(page, false);
  await page.goto("/posts/90");
  await expect(
    page.getByRole("link", { name: "旅人 的旅行故事" }),
  ).toHaveAttribute("href", "/authors/17");
  await expect(page.locator(".stop-content")).toContainText("西湖散步");
  await page.getByRole("button", { name: /第 2 天 老城探索/ }).click();
  await expect(page.locator(".day-block").nth(1)).toBeFocused();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
  await page.getByRole("button", { name: "点赞 0", exact: true }).click();
  await expect(page).toHaveURL(/\/login\?redirect=%2Fposts%2F90$/);
  await page.goto("/share/token");
  await expect(page.locator(".journal-cover")).toContainText("独立的分享快照");
  await expect(page.locator(".journal-actions")).toHaveCount(0);
  await expect(page.locator(".journal-comments")).toHaveCount(0);
  await expect(page.locator(".day-block")).toHaveCount(2);
});

test("Post preserves interaction payload, comment ownership and private clone", async ({
  page,
}) => {
  const mutations: Array<{ path: string; body: unknown }> = [];
  await fixture(page, true, async (route, url) => {
    if (url.pathname === "/api/community/posts/90/interaction") {
      mutations.push({
        path: url.pathname,
        body: route.request().postDataJSON(),
      });
      await route.fulfill({
        json: { code: 200, data: { ...post, liked: true, likeCount: 1 } },
      });
      return true;
    }
    if (url.pathname === "/api/community/posts/90/comments") {
      if (route.request().method() === "POST")
        mutations.push({
          path: url.pathname,
          body: route.request().postDataJSON(),
        });
      await route.fulfill({
        json: {
          code: 200,
          data: [
            {
              id: 1,
              authorId: 17,
              authorName: "旅人",
              content: "自己的评论",
              createdAt: "2026-10-07T12:00:00",
            },
            {
              id: 2,
              authorId: 18,
              authorName: "同行者",
              content: "他人的评论",
              createdAt: "2026-10-07T12:00:00",
            },
          ],
        },
      });
      return true;
    }
    if (url.pathname === "/api/community/posts/90/clone") {
      mutations.push({
        path: url.pathname,
        body: route.request().postDataJSON(),
      });
      await route.fulfill({ json: { code: 200, data: { ...trip, id: 72 } } });
      return true;
    }
    return false;
  });
  await page.goto("/posts/90");
  await page.getByRole("button", { name: "点赞 0", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "已点赞 1", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  expect(mutations[0]?.body).toEqual({ type: "LIKE", enabled: true });
  await expect(
    page
      .locator(".journal-comment")
      .first()
      .getByRole("button", { name: "删除" }),
  ).toBeVisible();
  await expect(
    page
      .locator(".journal-comment")
      .nth(1)
      .getByRole("button", { name: "删除" }),
  ).toHaveCount(0);
  await page.getByLabel("写下你的想法", { exact: true }).fill("想去走走");
  await page.getByRole("button", { name: "发表评论", exact: true }).click();
  await expect(page.getByLabel("写下你的想法", { exact: true })).toHaveValue(
    "",
  );
  expect(mutations[1]?.body).toEqual({ content: "想去走走" });
  await page
    .getByRole("button", { name: "保存为我的路书", exact: true })
    .click();
  await expect(page).toHaveURL(/\/trip\/edit\/72$/);
  expect(mutations[2]?.body).toEqual({});
});

test("Author renders only public identity and original author-filtered posts", async ({
  page,
}) => {
  let authorQuery: string | null = null;
  await fixture(page, false, async (route, url) => {
    if (url.pathname !== "/api/community/posts") return false;
    authorQuery = url.searchParams.get("author");
    await route.fulfill({
      json: { code: 200, data: { items: [post], hasMore: false } },
    });
    return true;
  });
  await page.goto("/authors/17");
  await expect(page.locator(".author-identity")).toContainText("沿着河岸走");
  await expect(page.locator(".author-city")).toContainText("成都");
  await expect(page.locator(".explore-entry")).toHaveAttribute(
    "href",
    "/posts/90",
  );
  expect(authorQuery).toBe("17");
  await expect(page.locator("#main-content form")).toHaveCount(0);
});
