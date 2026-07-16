import { expect, test, type Page, type Route } from "@playwright/test";

const taskId = "task-control-0000-4000-8000-000000000001";
const vehicleId = "vehicle-control-0000-4000-8000-000000000001";
const boardingStopId = "stop-boarding-0000-4000-8000-000000000001";
const alightingStopId = "stop-alighting-0000-4000-8000-000000000001";
const orderId = "order-control-0000-4000-8000-000000000001";
const boardingVirtualStopId = "virtual-boarding-0000-4000-8000-000000000001";
const alightingVirtualStopId = "virtual-alighting-0000-4000-8000-000000000001";

test("dispatcher completes manual location task chain and admin exports location history", async ({ page }) => {
  const state = createState();
  await installSessionMocks(page);
  await installVehicleLocationMocks(page, state);

  await page.goto("/tasks");
  await login(page, "dispatcher01");

  await expect(page.getByText("task-don")).toBeVisible();
  await page.getByRole("row", { name: new RegExp(taskId.slice(0, 8)) }).getByRole("button", { name: "选择" }).click();
  await submitLocationAction(page, "发车", "2026-07-14T09:00");
  await submitLocationAction(page, "到站", "2026-07-14T09:03");
  await submitLocationAction(page, "上车", "2026-07-14T09:04");
  await submitLocationAction(page, "到站", "2026-07-14T09:17");
  await submitLocationAction(page, "下车", "2026-07-14T09:18");
  await submitLocationAction(page, "完成", "2026-07-14T09:20");

  await expect(page.getByText("已完成").last()).toBeVisible();
  expect(state.events).toHaveLength(6);
  expect(state.submittedReports).toHaveLength(6);
  expect(state.submittedReports.every((report) => report.locationReport)).toBe(true);

  await page.getByRole("button", { name: "退出" }).click();
  await login(page, "admin");
  await page.getByRole("link", { name: "位置历史" }).click();
  await page.getByLabel("任务编号").fill(taskId);
  await page.getByRole("button", { name: "查询" }).click();
  await expect(page.getByText("通渭县任务节点 1")).toBeVisible();
  await expect(page.getByText("人工上报").first()).toBeVisible();

  await page.getByRole("button", { name: "导出 CSV" }).click();
  await expect(page.getByText("位置事件导出已提交")).toBeVisible();
  expect(state.exportRequested).toBe(true);
});

async function login(page: Page, username: string) {
  await page.getByLabel("用户名").fill(username);
  await page.getByLabel("密码").fill("Secret123!");
  await page.getByRole("button", { name: "登录" }).click();
}

async function submitLocationAction(page: Page, actionLabel: string, reportedAt: string) {
  await page.getByRole("button", { name: actionLabel, exact: true }).click();
  await expect(page.getByRole("heading", { name: `确认${actionLabel}位置` })).toBeVisible();
  await page.getByLabel("驾驶员反馈时间").fill(reportedAt);
  await page.getByRole("button", { name: `确认${actionLabel}`, exact: true }).click();
  await expect(page.getByRole("heading", { name: `确认${actionLabel}位置` })).not.toBeVisible();
}

async function installSessionMocks(page: Page) {
  await page.route("**/api/auth/refresh", (route) => route.fulfill({ status: 401 }));
  await page.route("**/api/auth/logout", (route) => route.fulfill({ status: 204 }));
  await page.route("**/api/auth/login", async (route) => {
    const body = await route.request().postDataJSON() as { username: string };
    const admin = body.username === "admin";
    await json(route, {
      accessToken: `${body.username}-token`,
      expiresAt: "2026-07-14T19:00:00+08:00",
      user: {
        id: admin ? "admin-user" : "dispatcher-user",
        username: body.username,
        roles: admin ? ["SYSTEM_ADMIN"] : ["DISPATCHER"],
        mustChangePassword: false
      }
    });
  });
}

async function installVehicleLocationMocks(page: Page, state: FlowState) {
  await page.route("**/api/virtual-stops", async (route) => {
    await json(route, [
      {
        id: boardingVirtualStopId,
        serviceAreaId: "service-area-1",
        name: "通渭县人民医院",
        location: "POINT(105.242000 35.211000)",
        serviceRadiusMeters: 600,
        boardingEnabled: true,
        alightingEnabled: false,
        safetyNote: "医院门口",
        enabled: true
      },
      {
        id: alightingVirtualStopId,
        serviceAreaId: "service-area-1",
        name: "通渭县客运站",
        location: "POINT(105.251000 35.205000)",
        serviceRadiusMeters: 600,
        boardingEnabled: false,
        alightingEnabled: true,
        safetyNote: "客运站落客区",
        enabled: true
      }
    ]);
  });

  await page.route("**/api/vehicles/locations/latest", async (route) => {
    await json(route, [
      {
        vehicleId,
        plateNumber: "甘JDRT-1",
        currentStatus: "DISPATCHED",
        latestLocation: {
          longitude: 105.24,
          latitude: 35.21,
          standardizedAddress: "通渭县调度起点",
          source: "MANUAL_DISPATCHER",
          coordinateSystem: "GCJ02",
          driverReportedAt: "2026-07-14T00:30:00Z",
          recordedAt: "2026-07-14T00:31:00Z",
          eventId: "event-latest",
          vehicleTaskId: taskId
        }
      }
    ]);
  });

  await page.route("**/api/vehicle-tasks", async (route) => {
    await json(route, state.tasks);
  });

  await page.route("**/api/vehicle-tasks/*/start", async (route) => {
    state.submittedReports.push(await route.request().postDataJSON());
    state.tasks = [state.tasks[0], task("IN_PROGRESS", "PLANNED", "PLANNED")];
    await json(route, taskActionResponse(state, "TASK_STARTED"));
  });
  await page.route("**/api/vehicle-tasks/*/stops/*/arrive", async (route) => {
    state.submittedReports.push(await route.request().postDataJSON());
    const current = state.tasks[1];
    const boardingPending = current.stops[0].status === "PLANNED";
    state.tasks = [state.tasks[0], task("IN_PROGRESS", boardingPending ? "ARRIVED" : "BOARDED", boardingPending ? "PLANNED" : "ARRIVED")];
    await json(route, taskActionResponse(state, boardingPending ? "PICKUP_ARRIVED" : "DROPOFF_ARRIVED"));
  });
  await page.route("**/api/vehicle-tasks/*/stops/*/board", async (route) => {
    state.submittedReports.push(await route.request().postDataJSON());
    state.tasks = [state.tasks[0], task("IN_PROGRESS", "BOARDED", "PLANNED")];
    await json(route, taskActionResponse(state, "PASSENGER_BOARDED"));
  });
  await page.route("**/api/vehicle-tasks/*/stops/*/alight", async (route) => {
    state.submittedReports.push(await route.request().postDataJSON());
    state.tasks = [state.tasks[0], task("IN_PROGRESS", "BOARDED", "ALIGHTED")];
    await json(route, taskActionResponse(state, "PASSENGER_ALIGHTED"));
  });
  await page.route("**/api/vehicle-tasks/*/complete", async (route) => {
    state.submittedReports.push(await route.request().postDataJSON());
    state.tasks = [state.tasks[0], task("COMPLETED", "BOARDED", "ALIGHTED")];
    await json(route, taskActionResponse(state, "TASK_COMPLETED"));
  });

  await page.route("**/api/vehicle-tasks/*/location-events**", async (route) => {
    await json(route, state.events);
  });
  await page.route("**/api/vehicle-locations/export.csv**", async (route) => {
    state.exportRequested = true;
    await route.fulfill({
      status: 200,
      contentType: "text/csv;charset=UTF-8",
      body: "event_id,event_type\nlocation-event-1,TASK_STARTED\n"
    });
  });
}

function createState(): FlowState {
  return {
    tasks: [doneTask(), task("DISPATCHED", "PLANNED", "PLANNED")],
    events: [],
    submittedReports: [],
    exportRequested: false
  };
}

function doneTask() {
  return {
    id: "task-done-0000-4000-8000-000000000001",
    vehicleId: "vehicle-done",
    driverId: "driver-done",
    status: "COMPLETED",
    plannedStartAt: "2026-07-14T00:00:00Z",
    stops: []
  };
}

function task(status: string, boardingStatus: string, alightingStatus: string) {
  return {
    id: taskId,
    vehicleId,
    driverId: "driver-control-0000-4000-8000-000000000001",
    status,
    plannedStartAt: "2026-07-14T01:00:00Z",
    stops: [
      {
        id: boardingStopId,
        virtualStopId: boardingVirtualStopId,
        rideOrderId: orderId,
        sequenceNumber: 1,
        stopType: "BOARDING",
        plannedArrivalAt: "2026-07-14T01:03:00Z",
        status: boardingStatus
      },
      {
        id: alightingStopId,
        virtualStopId: alightingVirtualStopId,
        rideOrderId: orderId,
        sequenceNumber: 2,
        stopType: "ALIGHTING",
        plannedArrivalAt: "2026-07-14T01:17:00Z",
        status: alightingStatus
      }
    ]
  };
}

function taskActionResponse(state: FlowState, eventType: string) {
  const event = {
    id: `location-event-${state.events.length + 1}`,
    vehicleId,
    vehicleTaskId: taskId,
    taskStopId: eventType.includes("PICKUP") || eventType === "PASSENGER_BOARDED" ? boardingStopId : eventType.includes("DROPOFF") || eventType === "PASSENGER_ALIGHTED" ? alightingStopId : undefined,
    virtualStopId: eventType.includes("PICKUP") || eventType === "PASSENGER_BOARDED" ? boardingVirtualStopId : eventType.includes("DROPOFF") || eventType === "PASSENGER_ALIGHTED" ? alightingVirtualStopId : undefined,
    eventType,
    longitude: 105.24 + state.events.length / 1000,
    latitude: 35.21,
    standardizedAddress: `通渭县任务节点 ${state.events.length + 1}`,
    source: "MANUAL_DISPATCHER",
    coordinateSystem: "GCJ02",
    driverReportedAt: `2026-07-14T01:0${Math.min(state.events.length, 5)}:00Z`,
    recordedAt: `2026-07-14T01:0${Math.min(state.events.length, 5)}:30Z`,
    recordedBy: "dispatcher-user",
    snapshotApplied: true
  };
  state.events.push(event);
  return { task: state.tasks[1], locationEvent: event, snapshotApplied: true, warnings: [], replayed: false };
}

async function json(route: Route, data: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify({ data })
  });
}

interface FlowState {
  tasks: Array<ReturnType<typeof task> | ReturnType<typeof doneTask>>;
  events: Record<string, unknown>[];
  submittedReports: Array<{ locationReport?: unknown }>;
  exportRequested: boolean;
}
