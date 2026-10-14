import { test, expect, type Page, type Route } from "@playwright/test";

async function fixture(page: Page, auth: (route: Route) => Promise<void>) {
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/auth/login") return auth(route);
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
    if (url.pathname === "/api/community/posts")
      return route.fulfill({
        json: { code: 200, data: { items: [], hasMore: false } },
      });
    return route.fulfill({
      status: 404,
      json: { code: 404, message: "Unexpected test endpoint" },
    });
  });
}

async function credentials(page: Page) {
  await page.getByLabel("用户名", { exact: true }).fill("traveler_17");
  await page.getByLabel("密码", { exact: true }).fill("password123");
}

test("Login rejects external redirect and keeps session after Enter submit", async ({
  page,
}) => {
  const calls: unknown[] = [];
  await fixture(page, async (route) => {
    calls.push(route.request().postDataJSON());
    await route.fulfill({
      json: { code: 200, data: { token: "login-v3-test" } },
    });
  });
  await page.goto("/login?redirect=%2F%2Fmalicious.invalid");
  await credentials(page);
  await page.getByLabel("密码", { exact: true }).press("Enter");
  await expect(page).toHaveURL(/\/my-trips$/);
  expect(calls).toEqual([{ username: "traveler_17", password: "password123" }]);
  expect(await page.evaluate(() => localStorage.getItem("AUTH_TOKEN"))).toBe(
    "login-v3-test",
  );
});

for (const [status, message] of [
  [429, "请求过于频繁，请等待30秒后重试"],
  [503, "认证保护暂不可用，请稍后重试"],
] as const) {
  test(`Login ${status} keeps credentials and permits retry`, async ({
    page,
  }) => {
    let calls = 0;
    await fixture(page, async (route) => {
      calls++;
      await route.fulfill({
        status,
        headers: { "Retry-After": "30" },
        json: { code: status, message },
      });
    });
    await page.goto("/login");
    await credentials(page);
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await expect(page.getByRole("alert")).toHaveText(message);
    await expect(
      page.getByRole("button", { name: "登录", exact: true }),
    ).toBeEnabled();
    await expect(page.getByLabel("用户名", { exact: true })).toHaveValue(
      "traveler_17",
    );
    await expect(page.getByLabel("密码", { exact: true })).toHaveValue(
      "password123",
    );
    expect(
      await page.evaluate(() => localStorage.getItem("AUTH_TOKEN")),
    ).toBeNull();
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await expect.poll(() => calls).toBe(2);
  });
}

test("Login guards duplicate submission and disables mode switch while pending", async ({
  page,
}) => {
  let calls = 0;
  let release!: () => void;
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  await fixture(page, async (route) => {
    calls++;
    await pending;
    await route.fulfill({
      status: 401,
      json: { code: 401, message: "用户名或密码不正确" },
    });
  });
  await page.goto("/login");
  await credentials(page);
  await page.getByRole("button", { name: "登录", exact: true }).dblclick();
  await expect(page.locator("#auth-submit")).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "创建账号", exact: true }),
  ).toBeDisabled();
  expect(calls).toBe(1);
  release();
  await expect(page.getByRole("alert")).toHaveText("用户名或密码不正确");
  await expect(
    page.getByRole("button", { name: "创建账号", exact: true }),
  ).toBeEnabled();
});

test("Login mobile preserves values and focus; page styles disappear on navigation", async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await fixture(page, async () => {
    throw new Error("Unexpected login request");
  });
  await page.goto("/login");
  await expect(page.getByRole("main")).toHaveCount(1);
  await credentials(page);
  await page.getByRole("button", { name: "创建账号", exact: true }).click();
  await expect(page.getByLabel("密码", { exact: true })).toHaveAttribute(
    "autocomplete",
    "new-password",
  );
  await page.getByRole("button", { name: "返回登录", exact: true }).click();
  await expect(page.getByLabel("用户名", { exact: true })).toHaveValue(
    "traveler_17",
  );
  await page.getByRole("button", { name: "显示密码", exact: true }).click();
  await expect(page.getByLabel("密码", { exact: true })).toHaveAttribute(
    "type",
    "text",
  );
  await page.getByRole("button", { name: "隐藏密码", exact: true }).click();
  await expect(page.getByLabel("密码", { exact: true })).toHaveAttribute(
    "type",
    "password",
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.getByRole("link", { name: "跳到登录", exact: true }).focus();
  await page.keyboard.press("Enter");
  await expect(page.locator("#auth-panel")).toBeFocused();
  await page.locator(".mobile-explore").click();
  await expect(page).toHaveURL(/\/square$/);
  await expect(page.locator(".site-header")).toBeVisible();
  await expect(page.locator(".login-v3")).toHaveCount(0);
  await expect(page.locator(".pages-v3-shell")).toBeVisible();
});
