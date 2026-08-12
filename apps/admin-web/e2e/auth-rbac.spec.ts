import { expect, test, type Page, type Route } from "@playwright/test";

test("operator can create an order and a direct protected navigation requires session restoration", async ({ page }) => {
  await installAuthMocks(page, ["OPERATOR"]);
  await page.goto("/orders");
  await login(page);

  await expect(page.getByRole("button", { name: "录入需求" })).toBeVisible();
  await page.goto("/dispatch");
  await expect(page).toHaveURL(/\/login\?redirect=\/dispatch$/);
});

test("only a system administrator can enter terminal management and call its API", async ({ page }) => {
  await installAuthMocks(page, ["SYSTEM_ADMIN"]);
  await page.goto("/terminals");
  await login(page);

  await expect(page.getByRole("heading", { name: "终端管理" })).toBeVisible();
  await expect(page.getByRole("link", { name: "终端管理" })).toBeVisible();

  for (const role of ["DISPATCHER", "OPERATOR", "AUDITOR"]) {
    await installAuthMocks(page, [role]);
    await page.goto("/terminals");
    await login(page);
    await expect(page).not.toHaveURL(/\/terminals$/);
    await expect(page.getByRole("link", { name: "终端管理" })).toHaveCount(0);
  }
});

async function installAuthMocks(page: Page, roles: string[]) {
  await page.route("**/api/auth/refresh", (route) => route.fulfill({ status: 401 }));
  await page.route("**/api/auth/login", (route) => json(route, {
    accessToken: "operator-token",
    expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    user: { id: "operator-1", username: "operator01", roles, mustChangePassword: false }
  }));
  await page.route("**/api/orders", (route) => json(route, []));
  await page.route("**/api/terminals", (route) => json(route, []));
  await page.route("**/api/metrics/**", (route) => json(route, {
    orderCount: 0, confirmationRate: 0, autoDispatchRate: 0, manualReviewRate: 0, averageWaitMinutes: 0,
    averageDetourMinutes: 0, taskCompletionRate: 0, exceptionCloseRate: 0, vehicleUtilizationRate: 0
  }));
}

async function login(page: Page) {
  await page.getByLabel("用户名").fill("operator01");
  await page.getByLabel("密码").fill("Secret123!");
  await page.getByRole("button", { name: "登录" }).click();
}

async function json(route: Route, data: unknown, status = 200) {
  await route.fulfill({ status, contentType: "application/json", body: JSON.stringify({ data }) });
}
