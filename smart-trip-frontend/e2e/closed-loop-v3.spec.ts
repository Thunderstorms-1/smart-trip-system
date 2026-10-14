import {
  test,
  request as requestFactory,
  expect,
  type APIRequestContext,
  type BrowserContext,
  type Page,
} from "@playwright/test";
import type {
  Trip,
  Post,
  PostPage,
  Profile,
  AnalysisReport,
} from "../src/features/types";

test.use({ actionTimeout: 15_000 });

const password = "V3ClosedLoop2026!";
const unique = () =>
  Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
type Account = { username: string; token: string; id: number };
async function api<T>(
  request: APIRequestContext,
  method: string,
  path: string,
  data?: unknown,
  token?: string,
  status = 200,
): Promise<T> {
  const response = await request.fetch("/api" + path, {
    method,
    data,
    headers: token ? { Authorization: "Bearer " + token } : {},
  });
  expect(response.status(), `${method} ${path}`).toBe(status);
  const body = await response.json();
  if (status === 200)
    expect(body.code, `${method} ${path} application status`).toBe(200);
  return body.data as T;
}
async function account(request: APIRequestContext): Promise<Account> {
  const username = "v3qa_" + unique();
  await api(request, "POST", "/auth/register", {
    username,
    password,
    nickname: "闭环验收旅人",
  });
  const login = await api<{ token: string }>(request, "POST", "/auth/login", {
    username,
    password,
  });
  const profile = await api<Profile>(
    request,
    "GET",
    "/user/info",
    undefined,
    login.token,
  );
  return { username, token: login.token, id: profile.id };
}
function resources() {
  const trips: Array<{ id: number; token: string }> = [];
  const shares: Array<{ id: number; token: string }> = [];
  return {
    trip(id: number, token: string) {
      trips.push({ id, token });
    },
    share(id: number, token: string) {
      shares.push({ id, token });
    },
    async cleanup() {
      const cleanupRequest = await requestFactory.newContext({
        baseURL: process.env.SMARTTRIP_BASE_URL || "http://127.0.0.1:5173",
        timeout: 10_000,
      });
      const failures: string[] = [];
      try {
        // Delete only resources created by this test. Trip deletion also invalidates its post.
        for (const [kind, entries] of [
          ["/community/shares/", shares],
          ["/workbench/", trips],
        ] as const) {
          for (const entry of [...entries].reverse()) {
            const response = await cleanupRequest.delete(
              "/api" + kind + entry.id,
              {
                headers: { Authorization: "Bearer " + entry.token },
              },
            );
            if (![200, 404].includes(response.status()))
              failures.push(kind + entry.id + ": " + response.status());
          }
        }
      } finally {
        await cleanupRequest.dispose();
      }
      expect(failures, "cleanup of this test's resources").toEqual([]);
    },
  };
}
async function authenticate(context: BrowserContext, owner: Account) {
  await context.addInitScript(
    (token) => localStorage.setItem("AUTH_TOKEN", token),
    owner.token,
  );
}
async function save(page: Page, tripId: number) {
  const response = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === "/api/workbench/" + tripId &&
      r.request().method() === "PUT",
  );
  await page.getByRole("button", { name: "保存路书", exact: true }).click();
  const result = await response;
  expect(result.status()).toBe(200);
  await expect(
    page.getByRole("button", { name: "保存路书", exact: true }),
  ).toBeDisabled();
  return (await result.json()).data as Trip;
}
async function openMore(page: Page) {
  if ((await page.locator(".wb-more-actions").getAttribute("open")) === null)
    await page.getByText("更多操作", { exact: true }).click();
}
async function publish(page: Page, tripId: number) {
  await openMore(page);
  const response = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === `/api/community/trips/${tripId}/publish` &&
      r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "发布到广场", exact: true }).click();
  const result = await response;
  expect(result.status()).toBe(200);
  const id = (await result.json()).data.id as number;
  await expect(page.locator(".wb-visibility")).toHaveText("已发布快照");
  return id;
}
async function noOverflow(page: Page) {
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
}
const savedDraft = (title: string) => ({
  title,
  destination: "成都",
  startDate: "2026-10-20",
  summary: "真实接口闭环验收",
  days: [
    {
      dayTheme: "城市慢行",
      items: [
        {
          itemType: "SCENIC",
          itemName: "手动安排的公园漫步",
          startTime: "09:00",
          costTimeHours: 1,
          costEstimate: 0,
          scheduleLocked: true,
        },
      ],
    },
  ],
});

test.beforeAll(async ({ request }) => {
  const caps = await api<Record<string, unknown>>(
    request,
    "GET",
    "/capabilities",
  );
  for (const flag of ["ai", "geo", "map", "poi", "route", "weather"])
    expect(
      Boolean(caps[flag]),
      flag + " must be disabled for real offline acceptance",
    ).toBe(false);
});

for (const mobile of [false, true]) {
  test(`real ${mobile ? "mobile" : "desktop"} registration to public community and private clone`, async ({
    page,
    browser,
    request,
  }) => {
    test.setTimeout(90000);
    if (mobile) await page.setViewportSize({ width: 390, height: 844 });
    const owned = resources();
    const secondContext = await browser.newContext({
      viewport: mobile
        ? { width: 390, height: 844 }
        : { width: 1280, height: 800 },
    });
    const secondPage = await secondContext.newPage();
    const username = "v3qa_" + unique();
    const title = "闭环旅行_" + unique();
    let ownerToken = "";
    try {
      await page.goto("/login");
      await page
        .getByRole("button", { name: "创建账号", exact: true })
        .click();
      await page.getByLabel("用户名", { exact: true }).fill(username);
      await page.getByLabel("昵称", { exact: true }).fill("闭环旅人");
      await page.getByLabel("密码", { exact: true }).fill(password);
      await page
        .getByRole("button", { name: "注册并登录", exact: true })
        .click();
      await expect(page).toHaveURL(/\/my-trips$/);
      ownerToken = await page.evaluate(
        () => localStorage.getItem("AUTH_TOKEN") || "",
      );
      expect(ownerToken).toBeTruthy();
      await page.goto("/profile");
      await page
        .getByRole("button", { name: "编辑旅行资料", exact: true })
        .click();
      await page
        .getByLabel("个人简介")
        .fill("沿着公园慢慢走，记录真实的旅行计划。");
      await page.getByLabel("常住城市", { exact: true }).fill("成都");
      await page.getByLabel("旅行偏好").fill("私有偏好：每天留出休息时间");
      await page.getByRole("button", { name: "保存资料", exact: true }).click();
      await expect(page.locator(".traveler-form")).toHaveCount(0);
      await page.reload();
      await expect(page.locator(".traveler-bio")).toContainText(
        "记录真实的旅行计划",
      );
      const profile = await api<Profile>(
        request,
        "GET",
        "/user/info",
        undefined,
        ownerToken,
      );
      expect(profile.homeCity).toBe("成都");
      expect(profile.travelPreferences).toContain("私有偏好");
      await page.goto("/my-trips");
      await page
        .locator(".v3-page-heading")
        .getByRole("button", { name: "新建路书", exact: true })
        .click();
      await page.getByLabel("目的地", { exact: true }).fill("成都");
      await page.getByLabel("路书标题", { exact: true }).fill(title);
      await page.getByRole("button", { name: "继续", exact: true }).click();
      await page.getByLabel("旅行天数", { exact: true }).fill("2");
      await page.getByRole("button", { name: "继续", exact: true }).click();
      const createResponse = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === "/api/workbench" &&
          r.request().method() === "POST",
      );
      await page
        .getByRole("button", { name: "创建私密路书", exact: true })
        .click();
      const created = (await (await createResponse).json()).data as Trip;
      owned.trip(created.id!, ownerToken);
      await expect(page).toHaveURL(new RegExp(`/trip/edit/${created.id}$`));
      await page
        .getByRole("button", { name: "＋ 添加行程节点", exact: true })
        .click();
      await page
        .getByLabel("地点 / 活动", { exact: true })
        .fill("手动安排的公园漫步");
      await page.getByRole("button", { name: "收起编辑", exact: true }).click();
      const persisted = await save(page, created.id!);
      await page.reload();
      await expect(
        page.getByRole("button", {
          name: "选择安排：手动安排的公园漫步",
          exact: true,
        }),
      ).toBeVisible();
      const saved = await api<Trip>(
        request,
        "GET",
        "/workbench/" + created.id,
        undefined,
        ownerToken,
      );
      expect(saved.days[0].items[0].itemName).toBe("手动安排的公园漫步");
      expect(saved.version).toBe(persisted.version);
      await page
        .getByRole("button", { name: "行程诊断", exact: true })
        .first()
        .click();
      const analysisResponse = page.waitForResponse(
        (r) =>
          /\/analyses$/.test(new URL(r.url()).pathname) &&
          r.request().method() === "POST",
      );
      await page.getByRole("button", { name: "检查行程", exact: true }).click();
      const analysis = (await (await analysisResponse).json())
        .data as AnalysisReport;
      expect(analysis.baseVersion).toBe(saved.version);
      expect(analysis.quality?.scope).toBe("TRIP");
      expect(analysis.quality?.dayIds).toHaveLength(2);
      await expect(
        page.getByRole("region", { name: "全行程质量报告" }),
      ).toBeVisible();
      await page
        .getByRole("button", { name: "返回行程编辑", exact: true })
        .click();
      const postId = await publish(page, created.id!);
      await page.goto("/square");
      await page.getByLabel("搜索城市或路书", { exact: true }).fill(title);
      await page.getByRole("button", { name: "搜索", exact: true }).click();
      const entry = page.locator(`.explore-entry[href="/posts/${postId}"]`);
      await expect(entry).toContainText(title);
      await entry.click();
      await expect(page.locator(".journal-content")).toContainText(
        "手动安排的公园漫步",
      );
      await noOverflow(page);
      await page.locator(".journal-author").click();
      await expect(page).toHaveURL(new RegExp(`/authors/${profile.id}$`));
      await expect(page.locator(".author-bio")).toContainText(
        "记录真实的旅行计划",
      );
      await expect(page.locator(".author-identity")).not.toContainText(
        "私有偏好",
      );
      await noOverflow(page);
      const second = await account(request);
      await secondPage.goto("/login");
      await secondPage
        .getByLabel("用户名", { exact: true })
        .fill(second.username);
      await secondPage.getByLabel("密码", { exact: true }).fill(password);
      await secondPage
        .getByRole("button", { name: "登录", exact: true })
        .click();
      await expect(secondPage).toHaveURL(/\/my-trips$/);
      await secondPage.goto("/posts/" + postId);
      await secondPage
        .getByRole("button", { name: "点赞 0", exact: true })
        .click();
      await expect(
        secondPage.getByRole("button", { name: "已点赞 1", exact: true }),
      ).toHaveAttribute("aria-pressed", "true");
      await secondPage
        .getByRole("button", { name: "收藏路书 0", exact: true })
        .click();
      await expect(
        secondPage.getByRole("button", { name: "已收藏 1", exact: true }),
      ).toHaveAttribute("aria-pressed", "true");
      await secondPage
        .getByLabel("写下你的想法", { exact: true })
        .fill("真实闭环评论：期待慢慢走。");
      await secondPage
        .getByRole("button", { name: "发表评论", exact: true })
        .click();
      const comment = secondPage
        .locator(".journal-comment")
        .filter({ hasText: "真实闭环评论：期待慢慢走。" });
      await expect(comment).toBeVisible();
      await secondPage.reload();
      await expect(comment).toBeVisible();
      const post = await api<Post>(
        request,
        "GET",
        "/community/posts/" + postId,
        undefined,
        second.token,
      );
      expect(post.liked).toBe(true);
      expect(post.favorited).toBe(true);
      expect(post.commentCount).toBe(1);
      await secondPage.goto("/my-trips");
      for (const label of ["我的收藏", "我的点赞"]) {
        await secondPage
          .getByRole("button", { name: label, exact: true })
          .click();
        await expect(
          secondPage.locator(`.explore-entry[href="/posts/${postId}"]`),
        ).toContainText(title);
      }
      await secondPage.goto("/posts/" + postId);
      const cloneResponse = secondPage.waitForResponse(
        (r) =>
          new URL(r.url()).pathname ===
            `/api/community/posts/${postId}/clone` &&
          r.request().method() === "POST",
      );
      await secondPage
        .getByRole("button", { name: "保存为我的路书", exact: true })
        .click();
      const cloned = (await (await cloneResponse).json()).data as Trip;
      owned.trip(cloned.id!, second.token);
      await expect(secondPage).toHaveURL(
        new RegExp(`/trip/edit/${cloned.id}$`),
      );
      await expect(
        secondPage.getByRole("button", {
          name: "选择安排：手动安排的公园漫步",
          exact: true,
        }),
      ).toBeVisible();
      expect(cloned.id).not.toBe(created.id);
      expect(cloned.isPublic).toBe(0);
      const cloneStored = await api<Trip>(
        request,
        "GET",
        "/workbench/" + cloned.id,
        undefined,
        second.token,
      );
      expect(cloneStored.days[0].items[0].itemName).toBe(
        saved.days[0].items[0].itemName,
      );
      await secondPage.goto("/my-trips");
      await expect(
        secondPage.locator(`.library-recent[href="/trip/edit/${cloned.id}"]`),
      ).toBeVisible();
      await noOverflow(secondPage);
      // Remove this test's comment through its author's real UI before deleting the post.
      await secondPage.goto("/posts/" + postId);
      await secondPage
        .locator(".journal-comment")
        .filter({ hasText: "真实闭环评论：期待慢慢走。" })
        .getByRole("button", { name: "删除", exact: true })
        .click();
      await expect(secondPage.locator(".journal-comment")).toHaveCount(0);
    } finally {
      await secondContext.close();
      await owned.cleanup();
    }
  });
}

test("real published snapshot, private access and independent share revocation", async ({
  page,
  browser,
  request,
}) => {
  test.setTimeout(60000);
  const owned = resources();
  const owner = await account(request),
    other = await account(request);
  const guest = await browser.newContext();
  const guestPage = await guest.newPage();
  try {
    const originalTitle = "公开快照_" + unique();
    const trip = await api<Trip>(
      request,
      "POST",
      "/workbench",
      savedDraft(originalTitle),
      owner.token,
    );
    owned.trip(trip.id!, owner.token);
    await authenticate(page.context(), owner);
    await page.goto("/trip/edit/" + trip.id);
    const postId = await publish(page, trip.id!);
    await openMore(page);
    await page.getByRole("button", { name: "分享", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await dialog
      .getByRole("button", { name: "创建分享链接", exact: true })
      .click();
    const shareLink = dialog.locator("a.share-url");
    await expect(shareLink).toBeVisible();
    const shareUrl = (await shareLink.getAttribute("href"))!;
    const shares = await api<Array<{ id: number }>>(
      request,
      "GET",
      `/community/trips/${trip.id}/shares`,
      undefined,
      owner.token,
    );
    owned.share(shares[0].id, owner.token);
    await page.keyboard.press("Escape");
    await expect(dialog).toHaveCount(0);
    await expect(page.locator(".wb-more-actions")).not.toHaveAttribute("open");
    await expect(page.getByText("更多操作", { exact: true })).toBeFocused();
    await page
      .getByLabel("路书标题", { exact: true })
      .fill("私密原稿修改_" + unique());
    await page
      .getByRole("button", {
        name: "选择安排：手动安排的公园漫步",
        exact: true,
      })
      .click();
    await page
      .getByLabel("地点 / 活动", { exact: true })
      .fill("私密修改后的地点");
    await page.getByRole("button", { name: "收起编辑", exact: true }).click();
    const updated = await save(page, trip.id!);
    expect(updated.days[0].items[0].itemName).toBe("私密修改后的地点");
    await guestPage.goto("/posts/" + postId);
    await expect(guestPage.locator(".journal-cover h1")).toHaveText(
      originalTitle,
    );
    const published = await api<Post>(
      request,
      "GET",
      "/community/posts/" + postId,
    );
    expect(published.trip!.title).toBe(originalTitle);
    expect(published.trip!.title).not.toBe(updated.title);
    await api(
      request,
      "GET",
      "/workbench/" + trip.id,
      undefined,
      other.token,
      403,
    );
    await guestPage.goto("/trip/edit/" + trip.id);
    await expect(guestPage).toHaveURL(/\/login\?redirect=/);
    const publicProfile = await api<Record<string, unknown>>(
      request,
      "GET",
      "/user/public/" + owner.id,
    );
    for (const privateField of [
      "email",
      "username",
      "travelPreferences",
      "password",
      "tokenVersion",
    ])
      expect(publicProfile).not.toHaveProperty(privateField);
    const clone = await api<Trip>(
      request,
      "POST",
      `/community/posts/${postId}/clone`,
      {},
      other.token,
    );
    owned.trip(clone.id!, other.token);
    expect(clone.title).toBe(originalTitle + " · 副本");
    expect(clone.days[0].items[0].itemName).toBe("手动安排的公园漫步");
    expect(clone.isPublic).toBe(0);
    await openMore(page);
    page.once("dialog", (d) => d.accept());
    await page.getByRole("button", { name: "下架", exact: true }).click();
    await expect(page.locator(".wb-visibility")).toHaveText("私密路书");
    await api(
      request,
      "GET",
      "/community/posts/" + postId,
      undefined,
      undefined,
      404,
    );
    const feed = await api<PostPage>(
      request,
      "GET",
      "/community/posts?q=" + encodeURIComponent(originalTitle),
    );
    expect(feed.items.some((p) => p.id === postId)).toBe(false);
    await guestPage.goto(shareUrl);
    await expect(guestPage.locator(".journal-cover h1")).toHaveText(
      originalTitle,
    );
    await expect(guestPage.locator(".journal-actions")).toHaveCount(0);
    await openMore(page);
    await page.getByRole("button", { name: "分享", exact: true }).click();
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "撤销", exact: true })
      .click();
    await expect(
      page.getByRole("dialog").getByText("已撤销", { exact: true }),
    ).toBeVisible();
    await guestPage.reload();
    await expect(
      guestPage.getByRole("heading", { name: "暂时无法打开", exact: true }),
    ).toBeVisible();
    const sharePath = new URL(shareUrl).pathname.replace(
      "/share/",
      "/community/share/",
    );
    await api(request, "GET", sharePath, undefined, undefined, 404);
  } finally {
    await guest.close();
    await owned.cleanup();
  }
});

test("real two-session version conflict retains local input without overwriting persistence", async ({
  page,
  browser,
  request,
}) => {
  const owned = resources();
  const owner = await account(request);
  const second = await browser.newContext();
  const otherPage = await second.newPage();
  try {
    const trip = await api<Trip>(
      request,
      "POST",
      "/workbench",
      savedDraft("版本验收_" + unique()),
      owner.token,
    );
    owned.trip(trip.id!, owner.token);
    await authenticate(page.context(), owner);
    await authenticate(second, owner);
    await page.goto("/trip/edit/" + trip.id);
    await otherPage.goto("/trip/edit/" + trip.id);
    await page.getByLabel("路书标题", { exact: true }).fill("第一端已保存");
    await otherPage
      .getByLabel("路书标题", { exact: true })
      .fill("第二端未保存输入");
    const firstSave = await save(page, trip.id!);
    const conflict = otherPage.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === "/api/workbench/" + trip.id &&
        r.request().method() === "PUT",
    );
    await otherPage
      .getByRole("button", { name: "保存路书", exact: true })
      .click();
    expect((await conflict).status()).toBe(409);
    await expect(otherPage.getByRole("alert")).toContainText("路书已被修改");
    await expect(otherPage.getByLabel("路书标题", { exact: true })).toHaveValue(
      "第二端未保存输入",
    );
    await expect(
      otherPage.getByRole("button", { name: "保存路书", exact: true }),
    ).toBeEnabled();
    const stored = await api<Trip>(
      request,
      "GET",
      "/workbench/" + trip.id,
      undefined,
      owner.token,
    );
    expect(stored.title).toBe("第一端已保存");
    expect(stored.version).toBe(firstSave.version);
    expect(stored.days[0].items[0].scheduleLocked).toBe(true);
    await page.reload();
    await expect(page.getByLabel("路书标题", { exact: true })).toHaveValue(
      stored.title,
    );
  } finally {
    await second.close();
    await owned.cleanup();
  }
});
