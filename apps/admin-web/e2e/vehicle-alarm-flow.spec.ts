import { expect, test, type Page, type Route } from "@playwright/test";

const publicId = "alarm-public-0000-4000-8000-000000000001";
const vehicleId = "vehicle-internal-0000-4000-8000-000000000001";
const terminalId = "terminal-internal-0000-4000-8000-000000000001";

test("dispatcher handles an active-safety alarm without exposing internal identities or requesting attachments", async ({ page }) => {
  const state = { actions: [] as Array<Record<string, unknown>>, attachmentRequests: [] as string[] };
  page.on("request", (request) => {
    if (request.url().includes("attachment")) state.attachmentRequests.push(request.url());
  });
  await installSessionMocks(page, ["DISPATCHER"]);
  await installDispatchMocks(page);
  await installAlarmMocks(page, state);

  await page.goto("/dispatch");
  await login(page);

  await expect(page.getByLabel("主动安全报警看板")).toBeVisible();
  await expect(page.getByText("甘G·A1001", { exact: true })).toBeVisible();
  await expect(page.locator(".dispatch-vehicle-marker.has-safety-alarm")).toHaveCount(1);
  await expect(page.getByText(publicId, { exact: true })).toHaveCount(0);
  await expect(page.getByText(vehicleId, { exact: true })).toHaveCount(0);
  await expect(page.getByText(terminalId, { exact: true })).toHaveCount(0);

  await page.getByRole("button", { name: /查看报警 甘G·A1001/ }).click();
  await expect(page.getByLabel("报警详情").getByText("附件暂不可用", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "确认报警", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "确认报警处理" })).toBeVisible();
  await page.getByLabel("处理原因（同时作为备注）").fill("已联系驾驶员并完成核实");
  await page.getByLabel("我已核实并确认执行该处理").check();
  await page.getByRole("button", { name: "确认执行", exact: true }).click();

  await expect.poll(() => state.actions.length).toBe(1);
  expect(state.actions[0]).toEqual({ action: "ACKNOWLEDGE", expectedVersion: 4, reason: "已联系驾驶员并完成核实", confirmed: true });
  expect(state.attachmentRequests).toEqual([]);
});

async function login(page: Page): Promise<void> {
  await page.getByLabel("用户名").fill("dispatcher01");
  await page.getByLabel("密码").fill("Secret123!");
  await page.getByRole("button", { name: "登录" }).click();
}

async function installSessionMocks(page: Page, roles: string[]): Promise<void> {
  await page.route("**/api/auth/refresh", (route) => route.fulfill({ status: 401 }));
  await page.route("**/api/auth/login", async (route) => {
    await json(route, {
      accessToken: "dispatcher-token",
      expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
      user: { id: "dispatcher-user", username: "dispatcher01", roles, mustChangePassword: false }
    });
  });
}

async function installDispatchMocks(page: Page): Promise<void> {
  await page.route("**/api/orders", (route) => json(route, []));
  await page.route("**/api/vehicle-tasks", (route) => json(route, []));
  await page.route("**/api/dispatch-decisions/manual-review", (route) => json(route, []));
  await page.route("**/api/vehicles/locations/latest", (route) => json(route, [{
    vehicleId,
    plateNumber: "甘G·A1001",
    currentStatus: "IN_SERVICE",
    latestLocation: {
      longitude: 118, latitude: 32, standardizedAddress: "调度服务区",
      source: "MANUAL_DISPATCHER", coordinateSystem: "GCJ02",
      driverReportedAt: "2026-08-15T02:00:00Z", recordedAt: "2026-08-15T02:00:01Z", eventId: "location-event-1", vehicleTaskId: "task-1"
    }
  }]));
  await page.route("**/api/vehicle-tasks/*/location-events**", (route) => json(route, []));
  await page.route("**/api/service-areas", (route) => json(route, []));
  await page.route("**/api/virtual-stops", (route) => json(route, []));
}

async function installAlarmMocks(page: Page, state: { actions: Array<Record<string, unknown>> }): Promise<void> {
  const alarm = () => ({
    publicId,
    vehicleId,
    plateNumber: "甘G·A1001",
    standard: "T/JSATL12-2017",
    module: "ADAS",
    alarmTypeCode: 1,
    alarmType: "前向碰撞预警",
    level: 3,
    status: "NEW",
    occurredAt: "2026-08-15T02:00:00Z",
    endedAt: null,
    locationQualityStatus: "GOOD",
    hasAttachment: true,
    version: 4,
    longitude: 118,
    latitude: 32,
    speedKph: 60
  });
  await page.route("**/api/vehicle-alarms**", async (route) => {
    const request = route.request();
    if (request.method() === "POST") {
      state.actions.push(await request.postDataJSON() as Record<string, unknown>);
      await json(route, { ...alarm(), status: "ACKNOWLEDGED", version: 5 });
      return;
    }
    await json(route, [alarm()]);
  });
  await page.route("**/api/vehicle-alarms/events", (route) => route.fulfill({
    status: 200,
    contentType: "text/event-stream",
    body: "event: heartbeat\ndata: {}\n\n"
  }));
}

async function json(route: Route, data: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: "application/json", body: JSON.stringify({ data }) });
}
