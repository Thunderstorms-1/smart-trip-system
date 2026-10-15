import { test, expect, type Page, type Route } from "@playwright/test";
import type { Post } from "../src/features/types";

function post(id: number, likes = id): Post {
  return {
    id,
    authorId: 1,
    authorName: "测试旅行者",
    title: `验收路书 ${id}`,
    destination: "验收目的地",
    totalDays: 2,
    totalBudget: 0,
    likeCount: likes,
    favoriteCount: 0,
    commentCount: 0,
    cloneCount: 0,
  };
}
const latest = Array.from({ length: 20 }, (_, i) => post(i + 1));
const hot = (page: Page) => page.locator("#square-popular");
const latestCards = (page: Page) =>
  page.locator("#square-stories").getByTestId("square-story");
const hotCards = (page: Page) => hot(page).getByTestId("square-story");
async function reply(route: Route, items: Post[], more = false) {
  return route.fulfill({
    json: {
      code: 200,
      data: {
        items,
        hasMore: more,
        nextCursor: more ? "latest-next" : undefined,
      },
    },
  });
}
async function fixture(page: Page, feed: (r: Route, u: URL) => Promise<void>) {
  await page.route("**/api/**", async (route) => {
    const u = new URL(route.request().url());
    if (u.pathname === "/api/community/posts") return feed(route, u);
    if (u.pathname === "/api/capabilities")
      return route.fulfill({
        json: { code: 200, data: { ai: false, map: false, geo: false } },
      });
    return route.fulfill({
      status: 404,
      json: { code: 404, message: "Unexpected test endpoint" },
    });
  });
}

test("Popular is independent of the latest page; full latest list retains pagination and return", async ({
  page,
}) => {
  const ranked = [post(99, 30), post(1, 20), post(77, 10), post(66, 5)];
  await fixture(page, (r, u) =>
    u.searchParams.get("sort") === "popular"
      ? reply(r, ranked)
      : u.searchParams.has("cursor")
        ? reply(r, [post(20), post(21)])
        : reply(r, latest, true),
  );
  await page.goto("/square");
  await expect(latestCards(page)).toHaveCount(3);
  await expect(hotCards(page).getByRole("heading")).toHaveText(
    ranked.map((p) => p.title),
  );
  await expect(hot(page)).toContainText("近30天发布 · 按点赞数排序");
  await expect(hotCards(page).first()).toContainText("点赞 30");
  await expect(
    page.getByRole("button", { name: "查看更多", exact: true }),
  ).toHaveCount(0);
  await page
    .getByRole("button", { name: "查看更多最新路书", exact: true })
    .click();
  await expect(hot(page)).toHaveCount(0);
  await expect(page.getByTestId("square-story")).toHaveCount(20);
  await page.getByRole("button", { name: "查看更多", exact: true }).click();
  await expect(page.getByTestId("square-story")).toHaveCount(21);
  await page.getByRole("button", { name: "返回广场推荐", exact: true }).click();
  await expect(latestCards(page)).toHaveCount(3);
  await expect(hotCards(page)).toHaveCount(4);
});

test("Popular loading/error/retry is independent of latest and does not reload it", async ({
  page,
}) => {
  let hotRequests = 0,
    latestRequests = 0;
  let release!: () => void;
  const pending = new Promise<void>((done) => {
    release = done;
  });
  await fixture(page, async (r, u) => {
    if (u.searchParams.get("sort") !== "popular") {
      latestRequests++;
      return reply(r, latest);
    }
    if (++hotRequests === 1) {
      await pending;
      return r.fulfill({
        status: 503,
        json: { code: 503, message: "热门榜暂不可用" },
      });
    }
    return reply(r, [post(90, 9)]);
  });
  try {
    await page.goto("/square");
    await expect(latestCards(page)).toHaveCount(3);
    await expect(hot(page).getByRole("status")).toHaveText("正在加载热门路书…");
    release();
    await expect(hot(page).getByRole("alert")).toContainText("热门榜暂不可用");
    await page
      .getByRole("button", { name: "重新加载热门路书", exact: true })
      .click();
    await expect(hotCards(page)).toHaveCount(1);
    expect(latestRequests).toBe(1);
    expect(hotRequests).toBe(2);
  } finally {
    release();
  }
});

test("Popular shows real zero counts, short and empty lists without filling mobile slots", async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 740 });
  let ranked = [post(31, 0), post(32, 0)];
  await fixture(page, (r, u) =>
    reply(r, u.searchParams.get("sort") === "popular" ? ranked : latest),
  );
  await page.goto("/square");
  await expect(hotCards(page)).toHaveCount(2);
  await expect(hot(page)).toContainText("近期路书暂未收到点赞。");
  await expect(hotCards(page).first()).toContainText("点赞 0");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  ranked = [];
  await page.reload();
  await expect(
    hot(page).getByRole("heading", {
      name: "近30天还没有公开路书",
      exact: true,
    }),
  ).toBeVisible();
  await expect(hotCards(page)).toHaveCount(0);
  await expect(latestCards(page)).toHaveCount(3);
});

test("Search hides popular and a late cancelled hot response cannot replace the fresh return", async ({
  page,
}) => {
  await page.addInitScript(() => {
    const fetchOriginal = window.fetch.bind(window);
    window.fetch = (input, init) =>
      typeof input === "string" && input.includes("sort=popular")
        ? fetchOriginal(input, { ...init, signal: undefined })
        : fetchOriginal(input, init);
  });
  let release!: () => void;
  const pending = new Promise<void>((done) => {
    release = done;
  });
  let calls = 0;
  await fixture(page, async (r, u) => {
    if (u.searchParams.get("sort") === "popular") {
      if (++calls === 1) {
        await pending;
        return reply(r, [post(91)]);
      }
      return reply(r, [post(92)]);
    }
    return reply(r, u.searchParams.get("q") ? [post(7)] : latest);
  });
  try {
    await page.goto("/square");
    await expect(latestCards(page)).toHaveCount(3);
    await expect.poll(() => calls).toBe(1);
    await page.getByLabel("搜索城市或路书").fill("搜索词");
    await page.getByRole("button", { name: "搜索", exact: true }).click();
    await expect(hot(page)).toHaveCount(0);
    await expect(latestCards(page).getByRole("heading")).toHaveText(
      post(7).title,
    );
    await page
      .getByRole("button", { name: "返回广场推荐", exact: true })
      .click();
    await expect(hotCards(page).getByRole("heading")).toHaveText(
      post(92).title,
    );
    const oldResponse = page.waitForResponse((r) =>
      r.url().includes("sort=popular"),
    );
    release();
    await oldResponse;
    await expect(hotCards(page).getByRole("heading")).toHaveText(
      post(92).title,
    );
  } finally {
    release();
  }
});

test("Latest error and retry do not suppress or refetch a working popular list", async ({
  page,
}) => {
  let requests = 0,
    hotRequests = 0;
  await fixture(page, (r, u) => {
    if (u.searchParams.get("sort") === "popular") {
      hotRequests++;
      return reply(r, [post(99)]);
    }
    if (++requests === 1)
      return r.fulfill({
        status: 503,
        json: { code: 503, message: "最新列表暂不可用" },
      });
    return reply(r, latest);
  });
  await page.goto("/square");
  await expect(hotCards(page)).toHaveCount(1);
  await expect(
    page.locator("#square-stories").getByRole("alert"),
  ).toContainText("最新列表暂不可用");
  await page.getByRole("button", { name: "重新加载", exact: true }).click();
  await expect(latestCards(page)).toHaveCount(3);
  expect(hotRequests).toBe(1);
});
