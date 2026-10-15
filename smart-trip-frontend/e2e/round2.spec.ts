import { test, expect, type Page, type Route } from "@playwright/test";

const trip = (id: number) => ({
  id,
  userId: 1,
  version: 0,
  title: "测试路书" + id,
  destination: "成都",
  totalBudget: 50,
  isPublic: 0,
  days: [
    {
      id: 10 + id,
      dayTheme: "第一天",
      items: [
        {
          id: 100 + id,
          itemType: "SCENIC",
          itemName: "测试地点",
          costEstimate: 50,
        },
      ],
    },
  ],
});
async function fixture(
  page: Page,
  handle?: (route: Route, path: string) => Promise<boolean>,
) {
  await page.addInitScript(() =>
    localStorage.setItem("AUTH_TOKEN", "browser-fixture"),
  );
  await page.route("**/api/**", async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (
      pathname === "/api/community/posts" &&
      new URL(route.request().url()).searchParams.get("sort") === "popular"
    )
      return route.fulfill({
        json: { code: 200, data: { items: [], hasMore: false } },
      });
    if (handle && (await handle(route, pathname))) return;
    let data: unknown = {};
    if (pathname === "/api/user/info") data = { id: 1, nickname: "测试游客" };
    else if (pathname === "/api/capabilities")
      data = { ai: false, geo: false, map: false, notice: "未启用" };
    else if (pathname.startsWith("/api/membership/trips/"))
      data = [{ userId: 1, role: "OWNER" }];
    else if (pathname.startsWith("/api/workbench/"))
      data = trip(Number(pathname.split("/").at(-1)));
    else if (pathname === "/api/community/posts")
      data = { items: [], hasMore: false };
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ code: 200, data }),
    });
  });
}

test("save locks the draft until persistence completes", async ({ page }) => {
  let release!: () => void;
  const gate = new Promise<void>((done) => {
    release = done;
  });
  await fixture(page, async (route, path) => {
    if (path === "/api/workbench/1" && route.request().method() === "PUT") {
      const body = route.request().postDataJSON();
      await gate;
      await route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ code: 200, data: { ...body, version: 1 } }),
      });
      return true;
    }
    return false;
  });
  await page.goto("/trip/edit/1");
  await page
    .getByRole("button", { name: /^选择安排：/ })
    .first()
    .click();
  const title = page.getByLabel("路书标题");
  await title.fill("保存中的标题");
  await page.getByRole("button", { name: "保存路书", exact: true }).click();
  await expect(title).not.toBeEditable();
  await expect(page.getByLabel("地点 / 活动", { exact: true })).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "＋ 添加一天" }),
  ).toBeDisabled();
  release();
  await expect(title).toBeEditable();
  await expect(title).toHaveValue("保存中的标题");
  await expect(page.getByText("版本 1", { exact: false })).toBeVisible();
});

test("late route response cannot overwrite the next itinerary", async ({
  page,
}) => {
  let release!: () => void;
  const gate = new Promise<void>((done) => {
    release = done;
  });
  let started = false;
  await fixture(page, async (route, path) => {
    if (path === "/api/workbench/1") {
      started = true;
      await gate;
      await route
        .fulfill({
          contentType: "application/json",
          body: JSON.stringify({ code: 200, data: trip(1) }),
        })
        .catch(() => {});
      return true;
    }
    return false;
  });
  await page.goto("/trip/edit/1");
  await expect.poll(() => started).toBe(true);
  // Same Vue route component, new route params via browser history navigation.
  await page.evaluate(() => {
    history.pushState(
      { ...history.state, current: "/trip/edit/2" },
      "",
      "/trip/edit/2",
    );
    dispatchEvent(new PopStateEvent("popstate", { state: history.state }));
  });
  await expect(page.getByLabel("路书标题")).toHaveValue("测试路书2");
  release();
  await expect(page.getByLabel("路书标题")).toHaveValue("测试路书2");
});

test("failed load-more retries the same cursor and deduplicates cards", async ({
  page,
}) => {
  const cursors: string[] = [];
  const post = (id: number) => ({
    id,
    authorId: 1,
    authorName: "游客",
    title: "公开路书" + id,
    destination: "成都",
    totalDays: 1,
    totalBudget: 0,
    likeCount: 0,
    favoriteCount: 0,
    commentCount: 0,
    cloneCount: 0,
  });
  await fixture(page, async (route, path) => {
    if (path !== "/api/community/posts") return false;
    const cursor = new URL(route.request().url()).searchParams.get("cursor");
    if (cursor) {
      cursors.push(cursor);
      if (cursors.length === 1) {
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({ code: 503, message: "翻页暂时失败" }),
        });
        return true;
      }
    }
    const data = cursor
      ? { items: [post(20), post(21)], hasMore: false }
      : {
          items: Array.from({ length: 20 }, (_, i) => post(i + 1)),
          nextCursor: "same-cursor",
          hasMore: true,
        };
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ code: 200, data }),
    });
    return true;
  });
  await page.goto("/square");
  await page
    .getByRole("button", { name: "查看更多最新路书", exact: true })
    .click();
  await page.getByRole("button", { name: "查看更多" }).click();
  await expect(page.getByText("翻页暂时失败")).toBeVisible();
  await page.getByRole("button", { name: "查看更多" }).click();
  await expect(page.locator('a[href^="/posts/"]')).toHaveCount(21);
  expect(cursors).toEqual(["same-cursor", "same-cursor"]);
  await expect(page.getByRole("button", { name: "查看更多" })).toHaveCount(0);
});
