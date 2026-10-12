import { test, expect } from "@playwright/test";
test("visitor can browse mobile without overflow", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "下一站，从这里开始" }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
  await page.getByRole("link", { name: "旅行灵感", exact: true }).click();
  await expect(
    page.locator(".v3-explore h1"),
  ).toBeVisible();
});
test("register, create, save and delete a private itinerary", async ({
  page,
}) => {
  await page.goto("/login");
  await page.getByRole("button", { name: "还没有账号？创建一个" }).click();
  await page.getByLabel("用户名").fill("e2e_" + Date.now());
  await page.getByLabel("昵称", { exact: true }).fill("路书验收游客");
  await page.getByLabel("密码", { exact: true }).fill("E2eTravel2026!");
  await page.getByRole("button", { name: "注册并开始旅行" }).click();
  await page.waitForURL("**/my-trips");
  await page.locator(".v3-page-heading").getByRole("button", { name: "新建路书" }).click();
  await page.getByLabel("目的地", { exact: true }).fill("杭州");
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await page.getByRole("button", { name: "继续", exact: true }).click();
  await page.getByRole("button", { name: "创建私密路书", exact: true }).click();
  await page.waitForURL("**/trip/edit/*");
  await page.getByRole("button", { name: "＋ 添加行程节点" }).click();
  await page.getByLabel("地点 / 活动", { exact: true }).fill("西湖散步");
  await page.getByRole("button", { name: "保存路书", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "保存路书", exact: true }),
  ).toBeDisabled();
  await page.reload();
  await page
    .getByRole("button", { name: "选择安排：西湖散步", exact: true })
    .click();
  await expect(page.getByLabel("地点 / 活动", { exact: true })).toHaveValue(
    "西湖散步",
  );
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByText("更多操作", { exact: true }).click();
  await page.getByRole("button", { name: "删除这份路书" }).click();
  await page.waitForURL("**/my-trips");
});
