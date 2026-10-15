import {
  test,
  expect,
  type Page,
  type Route,
  type Locator,
} from "@playwright/test";
import type { Trip, Post } from "../src/features/types";

const token = "library-v3-fixture";
const validInvite = "a".repeat(41) + "_-";
const tripData: Trip[] = [
  {
    id: 71,
    title: "Z · 零预算城市漫游",
    destination: "成都",
    startDate: "2026-11-03",
    totalDays: 2,
    totalBudget: 0,
    isPublic: 0,
    summary: "服务端首先返回的路书",
    days: [],
  },
  {
    id: 72,
    title: "A · 预算待定的街巷长途散步与博物馆探访计划",
    destination: "重庆",
    isPublic: 1,
    days: [],
  },
  {
    id: 73,
    title: "M · 年末湖边旅行",
    destination: "杭州",
    startDate: "2026-12-20",
    totalDays: 3,
    totalBudget: 860,
    isPublic: 0,
    days: [],
  },
  {
    id: 74,
    title: "B · 山间慢行",
    destination: "昆明",
    startDate: "2026-10-16",
    totalDays: 4,
    totalBudget: 1200,
    isPublic: 1,
    days: [],
  },
  {
    id: 75,
    title: "F · 水乡记事",
    destination: "苏州",
    totalDays: 1,
    totalBudget: 90,
    isPublic: 0,
    days: [],
  },
  {
    id: 76,
    title: "D · 岭南小憩",
    destination: "广州",
    totalDays: 2,
    totalBudget: 350,
    isPublic: 0,
    days: [],
  },
  {
    id: 77,
    title: "C · 海边的第七份路书",
    destination: "青岛",
    totalDays: 3,
    totalBudget: 500,
    isPublic: 1,
    days: [],
  },
];
function post(
  id: number,
  title: string,
  destination: string,
  likeCount: number,
): Post {
  return {
    id,
    authorId: 17,
    authorName: "有名字的真实作者",
    title,
    destination,
    totalDays: 2,
    totalBudget: 0,
    summary: "接口返回的公开路书摘要",
    likeCount,
    favoriteCount: 0,
    commentCount: 0,
    cloneCount: 0,
    trip: { ...tripData[0]!, id: 9000 + id, title, destination },
  };
}
const favoriteData = [
  post(901, "Z · 收藏的杭州水岸", "杭州", 0),
  post(902, "A · 收藏的杭州小路", "杭州", 9),
  post(903, "M · 收藏的重庆街巷", "重庆", 3),
];
// A null budget must not be formatted as a real zero estimate.
const likedData = [
  post(911, "Z · 点赞的重庆公园", "重庆", 1),
  {
    ...post(912, "A · 点赞的成都街头", "成都", 8),
    totalBudget: null,
  } as unknown as Post,
];
type RequestRecord = { path: string; search: string; method: string };
type Handler = (route: Route, url: URL) => Promise<boolean>;
type FixtureOptions = {
  authenticated?: boolean;
  trips?: Trip[];
  favorites?: Post[];
  likes?: Post[];
  handle?: Handler;
};
async function respond(route: Route, data: unknown) {
  await route.fulfill({ json: { code: 200, data } });
}
function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function fixture(page: Page, options: FixtureOptions = {}) {
  const requests: RequestRecord[] = [];
  const trips = options.trips ?? tripData;
  const favorites = options.favorites ?? favoriteData;
  const likes = options.likes ?? likedData;
  await page.addInitScript(
    ({ authenticated, fixtureToken }) => {
      if (sessionStorage.getItem("library-v3-session-seeded")) return;
      sessionStorage.setItem("library-v3-session-seeded", "1");
      localStorage.removeItem("USER_INFO");
      if (authenticated) localStorage.setItem("AUTH_TOKEN", fixtureToken);
      else localStorage.removeItem("AUTH_TOKEN");
    },
    { authenticated: options.authenticated !== false, fixtureToken: token },
  );
  // Fulfill every API request locally. No backend or paid provider is contacted.
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    requests.push({
      path: url.pathname,
      search: url.search,
      method: route.request().method(),
    });
    if (/^\/api\/(workbench|membership|community\/assets)/.test(url.pathname))
      expect(route.request().headers().authorization).toBe("Bearer " + token);
    if (options.handle && (await options.handle(route, url))) return;
    if (url.pathname === "/api/capabilities")
      return respond(route, {
        ai: false,
        geo: false,
        map: false,
        poi: false,
        route: false,
        weather: false,
        notice: "",
      });
    if (url.pathname === "/api/user/info")
      return respond(route, {
        id: 17,
        nickname: "旅行库旅人",
        username: "library-traveler",
        homeCity: "成都",
        bio: "沿着水岸慢慢走",
        travelPreferences: "步行",
      });
    if (url.pathname === "/api/workbench") return respond(route, trips);
    if (url.pathname === "/api/community/assets")
      return respond(
        route,
        url.searchParams.get("type") === "LIKE" ? likes : favorites,
      );
    if (/^\/api\/membership\/trips\/\d+$/.test(url.pathname))
      return respond(route, [{ userId: 17, role: "OWNER" }]);
    if (/^\/api\/workbench\/\d+$/.test(url.pathname)) {
      const id = Number(url.pathname.split("/").at(-1));
      return respond(route, {
        ...(trips.find((trip) => trip.id === id) ?? tripData[0]),
        id,
        userId: 17,
        version: 0,
      });
    }
    if (/^\/api\/community\/posts\/\d+\/comments$/.test(url.pathname))
      return respond(route, []);
    if (/^\/api\/community\/posts\/\d+$/.test(url.pathname)) {
      const id = Number(url.pathname.split("/").at(-1));
      return respond(
        route,
        [...favorites, ...likes].find((entry) => entry.id === id),
      );
    }
    if (url.pathname === "/api/user/public/17")
      return respond(route, {
        id: 17,
        nickname: "有名字的真实作者",
        bio: "公开的作者介绍",
        homeCity: "杭州",
      });
    if (url.pathname === "/api/community/posts")
      return respond(route, {
        items: url.searchParams.get("author") === "17" ? favorites : [],
        hasMore: false,
      });
    await route.fulfill({
      status: 404,
      json: {
        code: 404,
        message: "Unexpected fixture endpoint: " + url.pathname,
      },
    });
  });
  return requests;
}
const library = (page: Page) => page.locator(".v3-library");
const tripCards = (page: Page) =>
  library(page).locator(".library-recent, .library-row");
const storyCards = (page: Page) => library(page).locator(".explore-entry");
const tab = (page: Page, name: string) =>
  library(page).getByRole("button", { name, exact: true });
const search = (page: Page, name: string) =>
  library(page).getByLabel(name, { exact: true });
async function hrefs(cards: Locator) {
  return cards.evaluateAll((entries) =>
    entries.map((entry) => entry.getAttribute("href")),
  );
}
async function noInventedCovers(cards: Locator) {
  await expect(cards.locator("img")).toHaveCount(0);
  expect(
    await cards.evaluateAll((entries) =>
      entries.flatMap((card) =>
        [card, ...card.querySelectorAll("*")].flatMap((element) =>
          [null, "::before", "::after"]
            .map((pseudo) => getComputedStyle(element, pseudo).backgroundImage)
            .filter((background) => /url\(/.test(background)),
        ),
      ),
    ),
  ).toEqual([]);
}
async function noOverflow(page: Page) {
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
}
function libraryRequests(requests: RequestRecord[]) {
  return requests.filter(
    ({ path }) => path === "/api/workbench" || path === "/api/community/assets",
  );
}

test("Library desktop renders all seven server-ordered trips and honest missing fields", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  const requests = await fixture(page);
  await page.goto("/my-trips");
  await expect(
    library(page).getByRole("heading", { level: 1 }),
  ).toHaveAccessibleName("我的路书");
  await expect(tripCards(page)).toHaveCount(7);
  expect(await hrefs(tripCards(page))).toEqual(
    tripData.map((trip) => "/trip/edit/" + trip.id),
  );
  await expect(library(page).locator(".library-recent")).toContainText(
    "预估 ¥0",
  );
  const unknown = tripCards(page).filter({ hasText: tripData[1]!.title });
  await expect(unknown).toContainText("预算待定");
  await expect(unknown).toContainText("日期待定");
  await expect(unknown).toContainText("天数待定");
  await expect(unknown).toContainText("已发布");
  await noInventedCovers(tripCards(page));
  await noOverflow(page);
  await library(page)
    .locator(".v3-page-heading")
    .getByRole("button", { name: "新建路书", exact: true })
    .click();
  await expect(page).toHaveURL(/\/ai-generate\?mode=manual$/);
  expect(
    requests.filter(
      ({ path, method }) => path === "/api/workbench" && method === "POST",
    ),
  ).toEqual([]);
  expect(
    requests.filter(({ path }) => /^\/api\/(ai|geo)\//.test(path)),
  ).toEqual([]);
});

test("Library applies search explicitly and sorts locally without refetching or dropping records", async ({
  page,
}) => {
  const requests = await fixture(page);
  await page.goto("/my-trips");
  await expect(tripCards(page)).toHaveCount(7);
  const input = search(page, "搜索我的路书");
  const count = libraryRequests(requests).length;
  await input.fill("  成都  ");
  await expect(tripCards(page)).toHaveCount(7);
  expect(libraryRequests(requests)).toHaveLength(count);
  await input.press("Enter");
  await expect(tripCards(page)).toHaveCount(1);
  await expect(tripCards(page)).toHaveAttribute("href", "/trip/edit/71");
  await input.fill("");
  await input.press("Enter");
  const sort = library(page).getByLabel("路书排序", { exact: true });
  await sort.selectOption("title");
  await expect(tripCards(page).first()).toHaveAttribute(
    "href",
    "/trip/edit/72",
  );
  await sort.selectOption("date");
  await expect(tripCards(page).first()).toHaveAttribute(
    "href",
    "/trip/edit/73",
  );
  await sort.selectOption({ label: "最近更新" });
  expect(await hrefs(tripCards(page))).toEqual(
    tripData.map((trip) => "/trip/edit/" + trip.id),
  );
  await tab(page, "列表展示").click();
  await expect(tab(page, "列表展示")).toHaveAttribute("aria-pressed", "true");
  await expect(tripCards(page)).toHaveCount(7);
  expect(libraryRequests(requests)).toHaveLength(count);
});

test("Library deep links and browser history restore each tab's search, sort and layout", async ({
  page,
}) => {
  await fixture(page);
  await page.goto("/my-trips?view=favorites");
  await expect(
    library(page).getByRole("heading", { level: 1 }),
  ).toHaveAccessibleName("我的收藏");
  await expect(tab(page, "我的收藏")).toHaveAttribute("aria-pressed", "true");
  await expect(storyCards(page)).toHaveCount(3);
  expect(await hrefs(storyCards(page))).toEqual(
    favoriteData.map((entry) => "/posts/" + entry.id),
  );
  await expect(storyCards(page).first()).toContainText("有名字的真实作者");
  await expect(storyCards(page).first()).toContainText("预估 ¥0");
  await noInventedCovers(storyCards(page));
  await search(page, "搜索我的收藏").fill("杭州");
  await search(page, "搜索我的收藏").press("Enter");
  await library(page).getByLabel("路书排序").selectOption("likes");
  await tab(page, "列表展示").click();
  await expect(storyCards(page).first()).toHaveAttribute("href", "/posts/902");
  await tab(page, "我的点赞").click();
  await expect(page).toHaveURL(/\/my-trips\?view=likes$/);
  await expect(
    library(page).getByRole("heading", { level: 1 }),
  ).toHaveAccessibleName("我的点赞");
  await expect(
    storyCards(page).filter({ hasText: likedData[1]!.title }),
  ).toContainText("预算待定");
  await search(page, "搜索我的点赞").fill("重庆");
  await search(page, "搜索我的点赞").press("Enter");
  await expect(storyCards(page)).toHaveCount(1);
  await tab(page, "我的路书").click();
  await expect(tripCards(page)).toHaveCount(7);
  await page.goBack();
  await expect(
    library(page).getByRole("heading", { level: 1 }),
  ).toHaveAccessibleName("我的点赞");
  await expect(search(page, "搜索我的点赞")).toHaveValue("重庆");
  await expect(storyCards(page)).toHaveAttribute("href", "/posts/911");
  await page.goBack();
  await expect(
    library(page).getByRole("heading", { level: 1 }),
  ).toHaveAccessibleName("我的收藏");
  await expect(search(page, "搜索我的收藏")).toHaveValue("杭州");
  await expect(library(page).getByLabel("路书排序")).toHaveValue("likes");
  await expect(tab(page, "列表展示")).toHaveAttribute("aria-pressed", "true");
  expect(await hrefs(storyCards(page))).toEqual(["/posts/902", "/posts/901"]);
});

test("Library rapid tab changes ignore a late response even when transport ignores abort", async ({
  page,
}) => {
  await page.addInitScript(() => {
    const originalFetch = window.fetch.bind(window);
    window.fetch = (input, init) =>
      typeof input === "string" && input.startsWith("/api/community/assets")
        ? originalFetch(input, { ...init, signal: undefined })
        : originalFetch(input, init);
  });
  const pending = deferred();
  const started = deferred();
  await fixture(page, {
    handle: async (route, url) => {
      if (
        url.pathname !== "/api/community/assets" ||
        url.searchParams.get("type") !== "FAVORITE"
      )
        return false;
      started.resolve();
      await pending.promise;
      await respond(route, [
        { ...favoriteData[0], title: "不可覆盖当前点赞的旧收藏响应" },
      ]);
      return true;
    },
  });
  try {
    await page.goto("/my-trips?view=favorites");
    await started.promise;
    await tab(page, "我的点赞").click();
    await expect(storyCards(page)).toHaveCount(2);
    const oldResponse = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return (
        url.pathname === "/api/community/assets" &&
        url.searchParams.get("type") === "FAVORITE"
      );
    });
    pending.resolve();
    await (await oldResponse).finished();
    await page.evaluate(
      () =>
        new Promise<void>((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
        ),
    );
    await expect(
      library(page).getByRole("heading", { level: 1 }),
    ).toHaveAccessibleName("我的点赞");
    expect(await hrefs(storyCards(page))).toEqual(
      likedData.map((entry) => "/posts/" + entry.id),
    );
    await expect(
      library(page).getByText("不可覆盖当前点赞的旧收藏响应", { exact: true }),
    ).toHaveCount(0);
    await expect(library(page).getByRole("alert")).toHaveCount(0);
  } finally {
    pending.resolve();
  }
});

test("Library loading and API failure can retry into genuine empty collections", async ({
  page,
}) => {
  const pending = deferred();
  const started = deferred();
  let attempts = 0;
  await fixture(page, {
    trips: [],
    favorites: [],
    likes: [],
    handle: async (route, url) => {
      if (url.pathname !== "/api/workbench" || ++attempts > 1) return false;
      started.resolve();
      await pending.promise;
      await route.fulfill({
        status: 503,
        json: { code: 503, message: "旅行库暂时不可用，请重试" },
      });
      return true;
    },
  });
  try {
    await page.goto("/my-trips");
    await started.promise;
    await expect(
      library(page)
        .getByRole("status")
        .filter({ hasText: /正在|加载/ })
        .first(),
    ).toBeVisible();
    await expect(tripCards(page)).toHaveCount(0);
    pending.resolve();
    await expect(library(page).getByRole("alert")).toContainText(
      "旅行库暂时不可用，请重试",
    );
    await library(page)
      .getByRole("button", { name: /重新加载|重试/ })
      .click();
    await expect.poll(() => attempts).toBe(2);
    await expect(library(page).getByRole("alert")).toHaveCount(0);
    await expect(tripCards(page)).toHaveCount(0);
    await expect(
      library(page)
        .locator(".v3-page-heading")
        .getByRole("button", { name: "新建路书", exact: true }),
    ).toBeEnabled();
    for (const name of ["我的收藏", "我的点赞"]) {
      await tab(page, name).click();
      await expect(
        library(page).getByRole("heading", { level: 1 }),
      ).toHaveAccessibleName(name);
      await expect(storyCards(page)).toHaveCount(0);
      await expect(
        library(page).locator('a[href="/square"]').first(),
      ).toBeVisible();
    }
  } finally {
    pending.resolve();
  }
});

test("Library invitation validates locally, restores focus, preserves failures and prevents duplicate joins", async ({
  page,
}) => {
  const pending = deferred();
  const started = deferred();
  const joined: unknown[] = [];
  await fixture(page, {
    handle: async (route, url) => {
      if (url.pathname !== "/api/membership/join") return false;
      expect(route.request().method()).toBe("POST");
      joined.push(route.request().postDataJSON());
      if (joined.length === 1) {
        await route.fulfill({
          status: 400,
          json: { code: 400, message: "邀请口令已失效" },
        });
        return true;
      }
      started.resolve();
      await pending.promise;
      await respond(route, { tripId: 71 });
      return true;
    },
  });
  try {
    await page.goto("/my-trips");
    await expect(tripCards(page)).toHaveCount(7);
    const toggle = tab(page, "加入同行");
    await expect(library(page).getByLabel("同行邀请口令")).not.toBeVisible();
    await toggle.click();
    const input = library(page).getByLabel("同行邀请口令", { exact: true });
    const submit = library(page).getByRole("button", {
      name: /^(确认加入|正在加入…)$/,
    });
    await expect(input).toHaveAttribute("maxlength", "43");
    for (const invalid of ["short", "a".repeat(42) + "+"]) {
      await input.fill(invalid);
      await submit.click();
      expect(
        await input.evaluate((element: HTMLInputElement) =>
          element.checkValidity(),
        ),
      ).toBe(false);
      expect(joined).toEqual([]);
    }
    await page.keyboard.press("Escape");
    await expect(input).not.toBeVisible();
    await expect(toggle).toBeFocused();
    await toggle.click();
    await input.fill(validInvite);
    await submit.click();
    await expect(library(page).getByRole("alert")).toContainText(
      "邀请口令已失效",
    );
    await expect(input).toHaveValue(validInvite);
    await expect(tripCards(page)).toHaveCount(7);
    await submit.click();
    await started.promise;
    await expect(submit).toBeDisabled();
    await input.evaluate((element: HTMLInputElement) =>
      element.form?.requestSubmit(),
    );
    await page.evaluate(
      () =>
        new Promise<void>((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
        ),
    );
    expect(joined).toEqual([
      { inviteCode: validInvite },
      { inviteCode: validInvite },
    ]);
    pending.resolve();
    await expect(page).toHaveURL(/\/trip\/edit\/71$/);
  } finally {
    pending.resolve();
  }
});

test("Library protects deep links and preserves the original view when a session expires", async ({
  page,
}) => {
  const requests = await fixture(page, {
    authenticated: false,
    handle: async (route, url) => {
      if (url.pathname !== "/api/community/assets") return false;
      await route.fulfill({
        status: 401,
        json: { code: 401, message: "登录已过期" },
      });
      return true;
    },
  });
  await page.goto("/my-trips?view=likes");
  await expect(page).toHaveURL(
    (url) =>
      url.pathname === "/login" &&
      url.searchParams.get("redirect") === "/my-trips?view=likes",
  );
  expect(libraryRequests(requests)).toEqual([]);
  await page.evaluate(
    (fixtureToken) => localStorage.setItem("AUTH_TOKEN", fixtureToken),
    token,
  );
  await page.goto("/my-trips?view=favorites");
  await expect(page).toHaveURL(
    (url) =>
      url.pathname === "/login" &&
      url.searchParams.get("redirect") === "/my-trips?view=favorites",
  );
  expect(
    await page.evaluate(() => localStorage.getItem("AUTH_TOKEN")),
  ).toBeNull();
});

test("Library mobile honors reduced motion, keeps public post and author routes and restores other shells", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  const requests = await fixture(page);
  await page.goto("/profile");
  await expect(page.locator(".traveler-identity")).toBeVisible();
  const shellStyle = () =>
    page.evaluate(() => [
      getComputedStyle(document.querySelector("main")!).padding,
      getComputedStyle(document.querySelector(".site-header")!).height,
      getComputedStyle(document.querySelector(".site-header")!).backgroundColor,
    ]);
  const baseline = await shellStyle();
  await page
    .getByRole("navigation", { name: "主导航", exact: true })
    .getByRole("link", { name: "我的路书", exact: true })
    .click();
  await expect(tripCards(page)).toHaveCount(7);
  for (const width of [320, 390, 768]) {
    await page.setViewportSize({ width, height: 844 });
    for (const name of ["我的路书", "我的收藏", "我的点赞"]) {
      await tab(page, name).click();
      await expect(
        library(page).getByRole("heading", { level: 1 }),
      ).toHaveAccessibleName(name);
      await expect(tab(page, name)).toHaveAttribute("aria-pressed", "true");
      await expect(
        name === "我的路书" ? tripCards(page) : storyCards(page),
      ).toHaveCount(name === "我的路书" ? 7 : name === "我的收藏" ? 3 : 2);
      if (name === "我的路书") {
        for (const trip of tripData.filter((entry) => entry.startDate)) {
          await expect(
            library(page)
              .locator(`a[href="/trip/edit/${trip.id}"] .trip-facts`)
              .getByText(trip.startDate!, { exact: true }),
          ).toBeVisible();
        }
      }
      await noOverflow(page);
    }
  }
  await page.evaluate(() =>
    window.dispatchEvent(
      new PageTransitionEvent("pageshow", { persisted: true }),
    ),
  );
  await expect(
    library(page).getByRole("heading", { level: 1 }),
  ).toHaveAccessibleName("我的点赞");
  await noInventedCovers(storyCards(page));
  await storyCards(page).first().click();
  await expect(page).toHaveURL(/\/posts\/911$/);
  await expect(page.locator(".journal-author")).toHaveAttribute(
    "href",
    "/authors/17",
  );
  await page.locator(".journal-author").click();
  await expect(page).toHaveURL(/\/authors\/17$/);
  await expect(page.locator(".author-identity")).toContainText(
    "有名字的真实作者",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("link", { name: "个人中心", exact: true }).click();
  await expect(page.locator(".traveler-identity")).toBeVisible();
  await expect(library(page)).toHaveCount(0);
  expect(await shellStyle()).toEqual(baseline);
  expect(
    requests.filter(({ path }) => /^\/api\/(ai|geo)\//.test(path)),
  ).toEqual([]);
});
