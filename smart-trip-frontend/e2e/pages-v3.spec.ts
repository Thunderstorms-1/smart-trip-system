import { test, expect, type Page, type Route } from "@playwright/test";

const posts = Array.from({ length: 3 }, (_, i) => ({
  id: 90 + i,
  authorId: 17,
  title: `公开路书 ${i}`,
  destination: "杭州",
  totalDays: 2,
  totalBudget: 0,
  authorName: "旅行者",
  likeCount: 0,
  favoriteCount: 1,
  commentCount: 0,
  cloneCount: 0,
}));
const trips = [
  {
    id: 71,
    title: "最近更新的旅程",
    destination: "成都",
    totalDays: 2,
    totalBudget: 0,
    isPublic: 0,
    days: [],
  },
  { id: 72, title: "较早的旅程", destination: "杭州", totalDays: 3, days: [] },
];
const profile = {
  id: 17,
  nickname: "旅人",
  homeCity: "成都",
  bio: "沿着河岸慢慢走",
  travelPreferences: "喜欢博物馆",
};
async function fixture(
  page: Page,
  handle?: (route: Route, url: URL) => Promise<boolean>,
) {
  await page.addInitScript(() =>
    localStorage.setItem("AUTH_TOKEN", "pages-fixture"),
  );
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    if (
      url.pathname === "/api/community/posts" &&
      url.searchParams.get("sort") === "popular"
    )
      return route.fulfill({
        json: { code: 200, data: { items: [], hasMore: false } },
      });
    if (handle && (await handle(route, url))) return;
    let data: unknown;
    switch (url.pathname) {
      case "/api/capabilities":
        data = { ai: false, geo: false, map: false, notice: "" };
        break;
      case "/api/user/info":
        data = profile;
        break;
      case "/api/community/posts":
        data = { items: posts, hasMore: false };
        break;
      case "/api/workbench":
        data = trips;
        break;
      case "/api/community/assets":
        data = [posts[url.searchParams.get("type") === "LIKE" ? 1 : 0]];
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

test("Explore preserves explicit search, pagination and post destinations", async ({
  page,
}) => {
  const queries: string[] = [];
  await fixture(page, async (route, url) => {
    if (url.pathname !== "/api/community/posts") return false;
    queries.push(url.search);
    const cursor = url.searchParams.get("cursor");
    await route.fulfill({
      json: {
        code: 200,
        data: {
          items: cursor ? [posts[0], posts[1]] : [posts[0]],
          hasMore: !cursor,
          nextCursor: cursor ? undefined : "next-token",
        },
      },
    });
    return true;
  });
  await page.goto("/square");
  await expect(page.locator(".explore-entry")).toHaveCount(1);
  await expect(page.locator(".explore-lead")).toHaveAttribute(
    "href",
    "/posts/90",
  );
  await page.getByLabel("搜索城市或路书").fill("  杭州  ");
  expect(queries).toHaveLength(1);
  await page.getByRole("button", { name: "搜索", exact: true }).click();
  await expect.poll(() => queries.length).toBe(2);
  expect(new URLSearchParams(queries[1]).get("q")).toBe("杭州");
  await page.getByRole("button", { name: "查看更多", exact: true }).click();
  await expect(page.locator(".explore-entry")).toHaveCount(2);
  expect(new URLSearchParams(queries[2]).get("cursor")).toBe("next-token");
  await expect(
    page.getByRole("button", { name: "查看更多", exact: true }),
  ).toHaveCount(0);
  await expect(page.locator(".explore-meta").first()).toContainText("点赞 0");
});

test("mobile Travel Library keeps recent order, unknown budget, assets and join", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  let joined: unknown;
  await fixture(page, async (route, url) => {
    if (url.pathname !== "/api/membership/join") return false;
    joined = route.request().postDataJSON();
    await route.fulfill({ json: { code: 200, data: { tripId: 71 } } });
    return true;
  });
  await page.goto("/my-trips");
  await expect(page.locator(".library-recent")).toHaveAttribute(
    "href",
    "/trip/edit/71",
  );
  await expect(page.locator(".library-recent")).toContainText("预估 ¥0");
  await expect(page.locator(".library-row")).toHaveCount(1);
  await expect(page.locator(".library-row")).toContainText("预算待定");
  await page.getByRole("button", { name: "我的收藏", exact: true }).click();
  await expect(page.locator(".explore-entry")).toHaveAttribute(
    "href",
    "/posts/90",
  );
  await page.getByRole("button", { name: "我的点赞", exact: true }).click();
  await expect(page.locator(".explore-entry")).toHaveAttribute(
    "href",
    "/posts/91",
  );
  await page.getByRole("button", { name: "我的路书", exact: true }).click();
  await expect(page.locator(".library-recent")).toBeVisible();
  await page.getByRole("button", { name: "加入同行", exact: true }).click();
  const inviteCode = "a".repeat(41) + "_-";
  await page.getByLabel("同行邀请口令").fill(inviteCode);
  await page.getByRole("button", { name: "确认加入", exact: true }).click();
  await expect(page).toHaveURL(/\/trip\/edit\/71$/);
  expect(joined).toEqual({ inviteCode });
});

test("Traveler Dashboard shows identity, cancels edits and persists original profile fields", async ({
  page,
}) => {
  let saved: unknown;
  await fixture(page, async (route, url) => {
    if (url.pathname !== "/api/user/profile") return false;
    expect(route.request().method()).toBe("PUT");
    saved = route.request().postDataJSON();
    await route.fulfill({
      json: { code: 200, data: { ...profile, ...(saved as object) } },
    });
    return true;
  });
  await page.goto("/profile");
  await expect(page.locator(".traveler-bio")).toHaveText(profile.bio);
  await expect(page.locator(".traveler-form")).toHaveCount(0);
  await page.getByRole("button", { name: "编辑旅行资料", exact: true }).click();
  await page.getByLabel("昵称", { exact: true }).fill("未保存昵称");
  await page.getByRole("button", { name: "取消编辑", exact: true }).click();
  await expect(page.locator(".traveler-identity h2")).toHaveText("旅人");
  expect(saved).toBeUndefined();
  await page.getByRole("button", { name: "编辑旅行资料", exact: true }).click();
  await expect(page.getByLabel("昵称", { exact: true })).toHaveValue("旅人");
  await page.getByLabel("昵称", { exact: true }).fill("新旅人");
  await page.getByRole("button", { name: "保存资料", exact: true }).click();
  await expect(page.locator(".traveler-form")).toHaveCount(0);
  await expect(page.locator(".traveler-identity h2")).toHaveText("新旅人");
  expect(saved).toEqual({
    nickname: "新旅人",
    homeCity: "成都",
    bio: profile.bio,
    travelPreferences: profile.travelPreferences,
  });
  await expect(
    page.getByRole("link", { name: "查看公开主页" }),
  ).toHaveAttribute("href", "/authors/17");
});

test("mobile profile failed save keeps draft; password change logs out", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  let password: unknown;
  await fixture(page, async (route, url) => {
    if (url.pathname === "/api/user/profile") {
      await route.fulfill({
        status: 400,
        json: { code: 400, message: "昵称暂不可用" },
      });
      return true;
    }
    if (url.pathname === "/api/user/password") {
      expect(route.request().method()).toBe("PUT");
      password = route.request().postDataJSON();
      await route.fulfill({ json: { code: 200, data: null } });
      return true;
    }
    return false;
  });
  await page.goto("/profile");
  await page.getByRole("button", { name: "编辑旅行资料", exact: true }).click();
  await page.getByLabel("昵称", { exact: true }).fill("失败时保留输入");
  await page.getByRole("button", { name: "保存资料", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("昵称暂不可用");
  await expect(page.getByLabel("昵称", { exact: true })).toHaveValue(
    "失败时保留输入",
  );
  await page.keyboard.press("Escape");
  await expect(page.locator(".traveler-form")).toHaveCount(0);
  await page.getByRole("button", { name: "账号安全", exact: true }).click();
  await page.getByLabel("当前密码", { exact: true }).fill("old-password");
  await page.getByLabel("新密码", { exact: true }).fill("new-password");
  await page.getByRole("button", { name: "修改密码", exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect(password).toEqual({
    currentPassword: "old-password",
    newPassword: "new-password",
  });
  expect(
    await page.evaluate(() => localStorage.getItem("AUTH_TOKEN")),
  ).toBeNull();
});
