import { test, expect, type Page, type Route } from "@playwright/test";

const restoredTrip = {
  id: 71,
  title: "成都慢旅行",
  destination: "成都",
  days: [{ id: 11, dayTheme: "自由探索", items: [] }],
};

type Handler = (route: Route, url: URL) => Promise<boolean>;
async function fixture(page: Page, authenticated = true, handle?: Handler) {
  if (authenticated)
    await page.addInitScript(() =>
      localStorage.setItem("AUTH_TOKEN", "generate-v3-fixture"),
    );
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    if (handle && (await handle(route, url))) return;
    let data: unknown;
    switch (url.pathname) {
      case "/api/capabilities":
        data = { ai: true, geo: false, map: false, notice: "" };
        break;
      case "/api/user/info":
        data = { id: 17, nickname: "旅人", travelPreferences: "喜欢博物馆" };
        break;
      case "/api/workbench":
        data = [];
        break;
      case "/api/workbench/71":
        data = restoredTrip;
        break;
      case "/api/membership/trips/71":
        data = [{ userId: 17, role: "OWNER" }];
        break;
      case "/api/community/posts":
        data = { items: [], hasMore: false };
        break;
      default:
        return route.fulfill({
          status: 404,
          json: { code: 404, message: "Unexpected fixture endpoint" },
        });
    }
    await route.fulfill({ json: { code: 200, data } });
  });
}

async function fillReview(page: Page) {
  await page.getByLabel("目的地", { exact: true }).fill("成都");
  await page.getByLabel("路书标题", { exact: true }).fill("成都慢旅行");
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await page.getByLabel("出发日期", { exact: true }).fill("2026-11-01");
  await page.getByLabel("旅行天数", { exact: true }).fill("4");
  await page
    .getByRole("combobox", { name: "旅行风格", exact: true })
    .selectOption("文化博物馆");
  await page
    .getByRole("combobox", { name: "同行人群", exact: true })
    .selectOption("独自旅行");
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await page
    .getByLabel("这次旅行的小期待", { exact: true })
    .fill("下午留出休息时间");
}

test("planning title enters once per route visit and respects reduced motion", async ({
  page,
}) => {
  await page.addInitScript(() => {
    const events: { started: number; ended: number } = { started: 0, ended: 0 };
    (window as unknown as { planningMotion: typeof events }).planningMotion =
      events;
    for (const name of ["animationstart", "animationend"])
      document.addEventListener(name, (event) => {
        if (
          (event as AnimationEvent).animationName === "planning-arrive" &&
          (event.target as Element).matches(".planning-invitation")
        ) {
          if (name === "animationstart") events.started++;
          else events.ended++;
        }
      });
  });
  const motion = () =>
    page.evaluate(
      () =>
        (
          window as unknown as {
            planningMotion: { started: number; ended: number };
          }
        ).planningMotion,
    );
  await fixture(page);
  await page.goto("/ai-generate?mode=manual");
  const invitation = page.locator(".generate-v3 .planning-invitation");
  await expect(invitation).toContainText("从一个想法");
  await expect(invitation).toHaveCSS("animation-duration", "0.68s");
  await expect(invitation).toHaveCSS("animation-iteration-count", "1");
  await expect.poll(motion).toEqual({ started: 1, ended: 1 });
  await fillReview(page);
  await page.getByRole("button", { name: "上一步", exact: true }).click();
  await expect(page.getByLabel("旅行天数", { exact: true })).toBeVisible();
  expect(await motion()).toEqual({ started: 1, ended: 1 });

  const nav = page.getByRole("navigation", { name: "主导航", exact: true });
  await nav.getByRole("link", { name: "我的路书", exact: true }).click();
  await expect(page).toHaveURL(/\/my-trips$/);
  await nav.getByRole("link", { name: "规划旅行", exact: true }).click();
  await expect(invitation).toBeVisible();
  await expect.poll(motion).toEqual({ started: 2, ended: 2 });

  await page.emulateMedia({ reducedMotion: "reduce" });
  await nav.getByRole("link", { name: "我的路书", exact: true }).click();
  await nav.getByRole("link", { name: "规划旅行", exact: true }).click();
  await expect(invitation).toHaveCSS("animation-name", "none");
  await expect(invitation).toHaveCSS("opacity", "1");
  await expect(invitation).toHaveCSS("transform", "none");
  expect(await motion()).toEqual({ started: 2, ended: 2 });
});

test("planning uses the real session, navigation, logout and protected return path", async ({
  page,
}) => {
  let logins = 0;
  await fixture(page, false, async (route, url) => {
    if (url.pathname !== "/api/auth/login") return false;
    logins++;
    expect(route.request().postDataJSON()).toEqual({
      username: "traveler_17",
      password: "password123",
    });
    await route.fulfill({
      json: { code: 200, data: { token: "planning-session" } },
    });
    return true;
  });
  await page.goto("/ai-generate?mode=manual");
  await expect.poll(() => new URL(page.url()).pathname).toBe("/login");
  expect(new URL(page.url()).searchParams.get("redirect")).toBe(
    "/ai-generate?mode=manual",
  );
  await page.getByLabel("用户名", { exact: true }).fill("traveler_17");
  await page.getByLabel("密码", { exact: true }).fill("password123");
  await page.locator("#auth-submit").click();
  await expect(page).toHaveURL(/\/ai-generate\?mode=manual$/);
  await expect(
    page.getByRole("link", { name: "个人中心", exact: true }),
  ).toContainText("旅人");
  await expect(
    page.getByRole("button", { name: "手动创建路书", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  expect(logins).toBe(1);

  const nav = page.getByRole("navigation", { name: "主导航", exact: true });
  await nav.getByRole("link", { name: "我的路书", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "我的旅行库", exact: true }),
  ).toBeVisible();
  await nav.getByRole("link", { name: "规划旅行", exact: true }).click();
  await page.getByRole("button", { name: "退出", exact: true }).click();
  await expect.poll(() => new URL(page.url()).pathname).toBe("/");
  expect(
    await page.evaluate(() => localStorage.getItem("AUTH_TOKEN")),
  ).toBeNull();
  await page
    .getByRole("navigation", { name: "主导航", exact: true })
    .getByRole("link", { name: "规划旅行", exact: true })
    .click();
  await expect.poll(() => new URL(page.url()).pathname).toBe("/login");
  expect(new URL(page.url()).searchParams.get("redirect")).toBe("/ai-generate");
});

test("planning preserves mode and step input and rechecks hidden fields before creating", async ({
  page,
}) => {
  let creates = 0;
  await fixture(page, true, async (route, url) => {
    if (
      ["/api/workbench", "/api/ai/tasks"].includes(url.pathname) &&
      route.request().method() === "POST"
    )
      creates++;
    return false;
  });
  await page.goto("/ai-generate?mode=manual");
  const steps = page.getByRole("navigation", {
    name: "旅程创建步骤",
    exact: true,
  });
  await expect(
    steps.getByRole("button", { name: /安排旅行节奏/ }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await expect(page.getByLabel("目的地", { exact: true })).toBeVisible();
  await page.getByLabel("目的地", { exact: true }).fill("成都");
  await page.getByLabel("路书标题", { exact: true }).fill("成都慢旅行");
  await page.getByRole("button", { name: "继续", exact: true }).click();
  const days = page.getByLabel("旅行天数", { exact: true });
  for (const invalid of ["", "0", "31", "1.5"]) {
    await days.fill(invalid);
    await page.getByRole("button", { name: "继续", exact: true }).click();
    await expect(days).toBeVisible();
    await expect(
      steps.getByRole("button", { name: /确认并创建/ }),
    ).toBeDisabled();
  }
  await days.fill("4");
  await page.getByLabel("出发日期", { exact: true }).fill("2026-11-01");
  await page
    .getByRole("combobox", { name: "旅行风格", exact: true })
    .selectOption("文化博物馆");
  await page
    .getByRole("combobox", { name: "同行人群", exact: true })
    .selectOption("独自旅行");
  await page.getByRole("button", { name: "继续", exact: true }).click();
  const requirements = page.getByLabel("这次旅行的小期待", { exact: true });
  await requirements.fill("下午留出休息时间");
  await page.getByRole("button", { name: "AI 生成草稿", exact: true }).click();
  await expect(requirements).toHaveAttribute("maxlength", "1500");
  await expect(
    page.getByRole("button", { name: "生成 AI 草稿", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "手动创建路书", exact: true }).click();
  await expect(requirements).toHaveValue("下午留出休息时间");
  await expect(requirements).toHaveAttribute("maxlength", "500");
  await page.getByRole("button", { name: "上一步", exact: true }).click();
  await expect(days).toHaveValue("4");
  await expect(page.getByLabel("出发日期", { exact: true })).toHaveValue(
    "2026-11-01",
  );
  await expect(
    page.getByRole("combobox", { name: "旅行风格", exact: true }),
  ).toHaveValue("文化博物馆");
  await expect(
    page.getByRole("combobox", { name: "同行人群", exact: true }),
  ).toHaveValue("独自旅行");
  await steps.getByRole("button", { name: /选择目的地/ }).click();
  await expect(page.getByLabel("路书标题", { exact: true })).toHaveValue(
    "成都慢旅行",
  );
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await expect(page.locator(".builder-review")).toContainText("4 天");

  // A final submit must validate earlier stages even if their inputs change while hidden.
  await page.getByLabel("目的地", { exact: true }).evaluate((element) => {
    (element as HTMLInputElement).value = "";
    element.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await page.getByRole("button", { name: "创建私密路书", exact: true }).click();
  await expect(page.getByLabel("目的地", { exact: true })).toBeVisible();
  await page.getByLabel("目的地", { exact: true }).fill("成都");
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await days.evaluate((element) => {
    (element as HTMLInputElement).value = "31";
    element.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await page.getByRole("button", { name: "创建私密路书", exact: true }).click();
  await expect(days).toBeVisible();
  expect(creates).toBe(0);
});

test("all AI unavailable states show the actual reason and keep manual planning usable", async ({
  page,
}) => {
  let status = "KEY_MISSING";
  await fixture(page, true, async (route, url) => {
    if (url.pathname !== "/api/capabilities") return false;
    await route.fulfill({
      json: {
        code: 200,
        data: {
          ai: false,
          geo: false,
          map: false,
          status: { ai: status },
          notice: "",
        },
      },
    });
    return true;
  });
  for (const [value, reason] of [
    ["KEY_MISSING", "AI Key 尚未配置。"],
    [
      "QUOTA_UNVERIFIED",
      "AI 免费额度或模型权限尚未确认。确认后再启用，避免意外计费。",
    ],
    ["DISABLED", "AI 服务当前处于关闭状态。"],
  ]) {
    status = value;
    await page.goto("/ai-generate");
    await expect(
      page.getByRole("button", { name: "AI 生成草稿", exact: true }),
    ).toBeDisabled();
    await expect(
      page.getByRole("button", { name: "手动创建路书", exact: true }),
    ).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator("#ai-unavailable")).toHaveText(
      reason + "你可以先手动创建路书，填写地点和每天的安排。",
    );
    await page.getByLabel("目的地", { exact: true }).fill("成都");
    await page.getByRole("button", { name: "继续", exact: true }).click();
    await expect(page.getByLabel("旅行天数", { exact: true })).toBeEnabled();
  }
});

test("a slow manual create locks submission and failures retain input for a successful retry", async ({
  page,
}) => {
  let release!: () => void;
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  const submissions: Array<Record<string, unknown>> = [];
  await fixture(page, true, async (route, url) => {
    if (
      url.pathname !== "/api/workbench" ||
      route.request().method() !== "POST"
    )
      return false;
    expect(route.request().headers().authorization).toBe(
      "Bearer generate-v3-fixture",
    );
    submissions.push(route.request().postDataJSON());
    if (submissions.length === 1) {
      await pending;
      await route.fulfill({
        status: 503,
        json: { code: 503, message: "暂时无法创建，请稍后重试" },
      });
    } else if (submissions.length === 2) await route.abort("failed");
    else
      await route.fulfill({
        json: { code: 200, data: { ...restoredTrip, ...submissions[2] } },
      });
    return true;
  });
  await page.goto("/ai-generate?mode=manual");
  await fillReview(page);
  await page.getByRole("button", { name: "创建私密路书", exact: true }).click();
  await expect.poll(() => submissions.length).toBe(1);
  await expect(
    page.getByRole("button", { name: "请稍候…", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByLabel("这次旅行的小期待", { exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "AI 生成草稿", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "上一步", exact: true }),
  ).toBeDisabled();
  release();
  await expect(page.getByRole("alert")).toContainText(
    "暂时无法创建，请稍后重试",
  );
  await expect(
    page.getByLabel("这次旅行的小期待", { exact: true }),
  ).toHaveValue("下午留出休息时间");
  const submit = page.getByRole("button", {
    name: "创建私密路书",
    exact: true,
  });
  await expect(submit).toBeEnabled();
  await submit.click();
  await expect(page.getByRole("alert")).toContainText(
    "连接失败，请检查网络后重试",
  );
  await expect(submit).toBeEnabled();
  await expect(page.locator(".builder-review")).toContainText("成都");
  await submit.click();
  await expect(page).toHaveURL(/\/trip\/edit\/71$/);
  expect(submissions).toHaveLength(3);
  expect(submissions[1]).toEqual(submissions[0]);
  expect(submissions[2]).toEqual(submissions[0]);
  expect(submissions[2]).toMatchObject({
    title: "成都慢旅行",
    destination: "成都",
    startDate: "2026-11-01",
    summary: "下午留出休息时间",
  });
  expect(submissions[2]?.days).toHaveLength(4);
});

test("an AI submission rejection unlocks the same draft and can retry with a real task", async ({
  page,
}) => {
  const submissions: Array<{
    requestKey: string;
    type: string;
    prompt: string;
  }> = [];
  await fixture(page, true, async (route, url) => {
    if (url.pathname === "/api/ai/tasks") {
      submissions.push(route.request().postDataJSON());
      if (submissions.length === 1)
        await route.fulfill({
          status: 503,
          json: { code: 503, message: "AI 任务提交失败，请重试" },
        });
      else
        await route.fulfill({
          json: {
            code: 200,
            data: { id: "retry-task", type: "GENERATE", status: "QUEUED" },
          },
        });
      return true;
    }
    if (url.pathname === "/api/ai/tasks/retry-task/events") {
      await route.fulfill({
        contentType: "text/event-stream",
        body: 'event: task\nid: retry-task\ndata: {"id":"retry-task","type":"GENERATE","status":"RUNNING"}\n\n',
      });
      return true;
    }
    return false;
  });
  await page.goto("/ai-generate");
  await expect(
    page.getByRole("button", { name: "AI 生成草稿", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await fillReview(page);
  const submit = page.getByRole("button", {
    name: "生成 AI 草稿",
    exact: true,
  });
  await submit.click();
  await expect(page.getByRole("alert")).toContainText(
    "AI 任务提交失败，请重试",
  );
  await expect(submit).toBeEnabled();
  await expect(
    page.getByLabel("这次旅行的小期待", { exact: true }),
  ).toHaveValue("下午留出休息时间");
  expect(
    await page.evaluate(() => localStorage.getItem("SMARTTRIP_TASK")),
  ).toBeNull();
  await submit.click();
  await expect(page.locator(".builder-task")).toContainText("正在构建旅行草稿");
  await expect(
    page.getByLabel("这次旅行的小期待", { exact: true }),
  ).toBeDisabled();
  expect(submissions).toHaveLength(2);
  expect(submissions[1]?.prompt).toBe(submissions[0]?.prompt);
  expect(submissions[1]?.requestKey).toBeTruthy();
  expect(submissions[1]?.requestKey).not.toBe(submissions[0]?.requestKey);
  expect(
    await page.evaluate(() => localStorage.getItem("SMARTTRIP_TASK")),
  ).toBe("retry-task");
});

test("a stored AI task resumes across refresh, locks controls and opens its completed trip", async ({
  page,
}) => {
  let completed = false;
  let resumed = false;
  let reads = 0;
  let creates = 0;
  await page.addInitScript(() =>
    localStorage.setItem("SMARTTRIP_TASK", "restored-task"),
  );
  await fixture(page, true, async (route, url) => {
    if (url.pathname === "/api/ai/tasks" && route.request().method() === "POST")
      creates++;
    if (url.pathname !== "/api/ai/tasks/restored-task") return false;
    reads++;
    await route.fulfill({
      json: {
        code: 200,
        data: {
          id: "restored-task",
          type: "GENERATE",
          status: completed ? "SUCCEEDED" : resumed ? "RUNNING" : "QUEUED",
          ...(completed ? { tripId: 71 } : {}),
        },
      },
    });
    return true;
  });
  await page.goto("/ai-generate?mode=manual");
  await expect(page.locator(".builder-task")).toContainText("任务已排队");
  await expect(page.getByLabel("目的地", { exact: true })).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "手动创建路书", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "AI 生成草稿", exact: true }),
  ).toBeDisabled();
  resumed = true;
  await page.reload();
  await expect(page.locator(".builder-task")).toContainText("正在构建旅行草稿");
  await expect(
    page.getByRole("button", { name: "请稍候…", exact: true }),
  ).toBeDisabled();
  await expect(
    page
      .getByRole("navigation", { name: "旅程创建步骤", exact: true })
      .getByRole("button")
      .first(),
  ).toBeDisabled();
  expect(
    await page.evaluate(() => localStorage.getItem("SMARTTRIP_TASK")),
  ).toBe("restored-task");
  completed = true;
  await expect(page).toHaveURL(/\/trip\/edit\/71$/, { timeout: 6000 });
  expect(reads).toBeGreaterThanOrEqual(3);
  expect(creates).toBe(0);
  expect(
    await page.evaluate(() => localStorage.getItem("SMARTTRIP_TASK")),
  ).toBeNull();
});
