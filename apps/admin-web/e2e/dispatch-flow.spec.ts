import { expect, test, type Page, type Route } from "@playwright/test";

test("operator can create demand dispatch it and complete the task", async ({ page }) => {
  test.slow();
  await installSessionMocks(page, ["OPERATOR", "DISPATCHER"]);
  await installDispatchFlowMocks(page);
  await installDispatchPageResourceMocks(page);

  await page.goto("/orders");
  await login(page);
  await page.getByRole("button", { name: "录入需求" }).click();
  await page.locator("details.manual-coordinates > summary").nth(0).click();
  await page.locator("details.manual-coordinates > summary").nth(1).click();
  await page.getByLabel("起点经度").fill("120.155");
  await page.getByLabel("起点纬度").fill("30.2741");
  await page.getByLabel("终点经度").fill("120.1688");
  await page.getByLabel("终点纬度").fill("30.2799");
  await page.getByLabel("起点地址").fill("测试起点");
  await page.getByLabel("终点地址").fill("测试终点");
  await page.getByLabel("乘客姓名").fill("张三");
  await page.getByLabel(/乘客(电话|手机号)/).fill("13800000000");
  await page.getByLabel("乘客人数").fill("1");
  await page.getByRole("button", { name: "提交需求" }).click();
  await expect(page.getByText("张三")).toBeVisible();

  await page.getByRole("button", { name: "调度", exact: true }).click();
  await expect(page.getByText("已确认")).toBeVisible();

  await page.getByRole("link", { name: "车辆任务" }).click();
  await expect(page.getByText("DRT-201")).toBeVisible();
  await submitLocationAction(page, "发车", "2026-07-08T10:36");
  await expect(page.getByText("执行中")).toBeVisible();
  await submitLocationAction(page, "到站", "2026-07-08T10:40");
  await submitLocationAction(page, "上车", "2026-07-08T10:41");
  await submitLocationAction(page, "到站", "2026-07-08T10:53");
  await submitLocationAction(page, "下车", "2026-07-08T10:54");
  await submitLocationAction(page, "完成", "2026-07-08T10:55");
  await expect(page.getByText("已完成", { exact: true })).toBeVisible();
});

test("operator can approve manual review from dispatch workbench", async ({ page }) => {
  await installSessionMocks(page, ["DISPATCHER"]);
  await installManualReviewWorkbenchMocks(page);
  await installDispatchPageResourceMocks(page);

  await page.goto("/dispatch");
  await login(page);
  await expect(page.getByRole("heading", { name: "人工复核队列" })).toBeVisible();
  await expect(page.getByText("Manual review rider")).toBeVisible();

  await page.getByRole("button", { name: "确认派单" }).click();

  await expect(page.getByText("暂无待复核订单")).toBeVisible();
  await expect(page.getByText("待发车")).toBeVisible();
});

test("dispatch map fills the workspace and keeps live vehicle layers interactive", async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1366, height: 768 });
  await installSessionMocks(page, ["DISPATCHER"]);
  await installMapWorkbenchMocks(page);

  await page.goto("/dispatch");
  await login(page);
  await expect(page.getByRole("heading", { name: "车辆位置" })).toBeVisible();

  const mapBox = await page.getByLabel("调度地图").boundingBox();
  const canvasBox = await page.locator(".leaflet-container").boundingBox();
  expect(mapBox).not.toBeNull();
  expect(canvasBox).not.toBeNull();
  expect(Math.abs((mapBox?.height ?? 0) - (canvasBox?.height ?? 0))).toBeLessThanOrEqual(1);
  await expect(page.locator(".leaflet-popup")).toHaveCount(0);

  await page.locator(".leaflet-tile").first().evaluate((tile) => tile.dispatchEvent(new Event("error")));
  const mapWarning = page.getByRole("status").filter({ hasText: "开放底图暂不可用" });
  await expect(mapWarning).toBeVisible();
  const warningBox = await mapWarning.boundingBox();
  const metricsBox = await page.getByLabel("调度关键指标").boundingBox();
  expect(warningBox).not.toBeNull();
  expect(metricsBox).not.toBeNull();
  expect(rectanglesOverlap(warningBox!, metricsBox!)).toBe(false);

  await page.getByRole("button", { name: "定位车辆 甘G-T001" }).click();
  await expect(page.getByLabel("调度地图").getByText("任务 12345678")).toBeVisible();
  await expect(page.getByText("12345678-1234-4234-8234-123456789abc", { exact: true })).toHaveCount(0);

  await expect(page.locator(".dispatch-vehicle-marker")).toHaveCount(2);
  await page.getByLabel("车辆位置图层").uncheck();
  await expect(page.locator(".dispatch-vehicle-marker")).toHaveCount(0);
  await page.getByLabel("车辆位置图层").check();
  await expect(page.locator(".dispatch-vehicle-marker")).toHaveCount(2);

  await expect(page.locator(".leaflet-marker-icon:not(.dispatch-vehicle-marker)")).toHaveCount(2);
  await page.getByLabel("虚拟站点图层").uncheck();
  await expect(page.locator(".leaflet-marker-icon:not(.dispatch-vehicle-marker)")).toHaveCount(0);
  await page.getByLabel("虚拟站点图层").check();
  await expect(page.locator(".leaflet-marker-icon:not(.dispatch-vehicle-marker)")).toHaveCount(2);

  await expect(page.locator(".leaflet-overlay-pane path")).toHaveCount(1);
  await page.getByLabel("服务区图层").uncheck();
  await expect(page.locator(".leaflet-overlay-pane path")).toHaveCount(0);
  await page.getByLabel("服务区图层").check();
  await expect(page.locator(".leaflet-overlay-pane path")).toHaveCount(1);

  await page.screenshot({ path: testInfo.outputPath("dispatch-map-1366.png"), fullPage: true });
  await page.setViewportSize({ width: 1920, height: 1080 });
  await expect(page.getByLabel("调度地图")).toBeVisible();
  const wideMapBox = await page.getByLabel("调度地图").boundingBox();
  const wideCanvasBox = await page.locator(".leaflet-container").boundingBox();
  expect(Math.abs((wideMapBox?.height ?? 0) - (wideCanvasBox?.height ?? 0))).toBeLessThanOrEqual(1);
  await page.screenshot({ path: testInfo.outputPath("dispatch-map-1920.png"), fullPage: true });

  await page.setViewportSize({ width: 768, height: 1024 });
  const hasHorizontalOverflow = await page.locator("html").evaluate((element) => element.scrollWidth > element.clientWidth);
  expect(hasHorizontalOverflow).toBe(false);
});

async function installSessionMocks(page: Page, roles: string[]) {
  await page.route("**/api/auth/refresh", (route) => route.fulfill({ status: 401 }));
  await page.route("**/api/auth/login", async (route) => {
    await json(route, {
      accessToken: "workflow-token",
      expiresAt: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(),
      user: { id: "workflow-user", username: "workflow01", roles, mustChangePassword: false }
    });
  });
}

async function login(page: Page) {
  await page.getByLabel("用户名").fill("workflow01");
  await page.getByLabel("密码").fill("Secret123!");
  await page.getByRole("button", { name: "登录" }).click();
}

async function submitLocationAction(page: Page, actionLabel: string, reportedAt: string) {
  await page.getByRole("button", { name: actionLabel, exact: true }).click();
  await expect(page.getByRole("heading", { name: `确认${actionLabel}位置` })).toBeVisible();
  await page.getByLabel("驾驶员反馈时间").fill(reportedAt);
  await page.getByLabel("经度").fill("120.155");
  await page.getByLabel("纬度").fill("30.2741");
  await page.getByLabel("标准化地址").fill("测试任务位置");
  await page.getByRole("button", { name: `确认${actionLabel}`, exact: true }).click();
  await expect(page.getByRole("heading", { name: `确认${actionLabel}位置` })).not.toBeVisible();
}

async function installDispatchPageResourceMocks(page: Page) {
  await page.route("**/api/vehicles/locations/latest", async (route) => json(route, []));
  await page.route("**/api/service-areas", async (route) => json(route, []));
  await page.route("**/api/virtual-stops", async (route) => json(route, []));
  await page.route("**/api/vehicle-tasks/*/location-events**", async (route) => json(route, []));
}

async function installMapWorkbenchMocks(page: Page) {
  await page.route("**/api/orders", async (route) => json(route, []));
  await page.route("**/api/vehicle-tasks", async (route) => json(route, []));
  await page.route("**/api/dispatch-decisions/manual-review", async (route) => json(route, []));
  await page.route("**/api/vehicle-tasks/*/location-events**", async (route) => json(route, []));
  await page.route("**/api/service-areas", async (route) => json(route, [{
    id: "area-1",
    name: "通渭县试点服务区",
    boundary: "POLYGON((104.56 35.14,104.72 35.14,104.72 35.28,104.56 35.28,104.56 35.14))",
    coordinateSystem: "GCJ02",
    serviceStart: "06:30",
    serviceEnd: "19:00",
    ruleSetId: "rule-1",
    enabled: true
  }]));
  await page.route("**/api/virtual-stops", async (route) => json(route, [
    { id: "stop-1", name: "通渭汽车站", longitude: 104.6378, latitude: 35.2109, coordinateSystem: "GCJ02", enabled: true },
    { id: "stop-2", name: "中医院", longitude: 104.662, latitude: 35.225, coordinateSystem: "GCJ02", enabled: true }
  ]));
  await page.route("**/api/vehicles/locations/latest", async (route) => json(route, [
    mapVehicle("vehicle-1", "甘G-T001", "IN_SERVICE", 104.6378, 35.2109, "12345678-1234-4234-8234-123456789abc"),
    mapVehicle("vehicle-2", "甘G-T002", "IDLE", 104.662, 35.225)
  ]));
}

function mapVehicle(vehicleId: string, plateNumber: string, currentStatus: string, longitude: number, latitude: number, vehicleTaskId?: string) {
  return {
    vehicleId,
    plateNumber,
    currentStatus,
    latestLocation: {
      longitude,
      latitude,
      standardizedAddress: "通渭县测试位置",
      source: "MANUAL_DISPATCHER",
      coordinateSystem: "GCJ02",
      driverReportedAt: "2026-08-11T02:33:00Z",
      recordedAt: "2026-08-11T02:33:30Z",
      eventId: `event-${vehicleId}`,
      vehicleTaskId
    }
  };
}

function rectanglesOverlap(left: { x: number; y: number; width: number; height: number }, right: { x: number; y: number; width: number; height: number }): boolean {
  return left.x < right.x + right.width
    && left.x + left.width > right.x
    && left.y < right.y + right.height
    && left.y + left.height > right.y;
}

async function installDispatchFlowMocks(page: Page) {
  let orders: Record<string, unknown>[] = [];
  let tasks: Record<string, unknown>[] = [];

  await page.route("**/api/orders", async (route) => {
    if (route.request().method() === "GET") {
      await json(route, orders);
      return;
    }

    orders = [
      {
        id: "11111111-1111-4111-8111-111111111111",
        passengerName: "张三",
        passengerPhone: "13800000000",
        passengerCount: 1,
        requestType: "IMMEDIATE",
        originLng: 120.155,
        originLat: 30.2741,
        destinationLng: 120.1688,
        destinationLat: 30.2799,
        requestedDepartureAt: "2026-07-08T02:30:00Z",
        estimatedBoardingAt: null,
        estimatedArrivalAt: null,
        status: "PENDING_DISPATCH"
      }
    ];
    await json(route, orders[0], 201);
  });

  await page.route("**/api/orders/*/dispatch", async (route) => {
    orders = orders.map((order) => ({
      ...order,
      estimatedBoardingAt: "2026-07-08T02:36:00Z",
      estimatedArrivalAt: "2026-07-08T02:49:00Z",
      status: "CONFIRMED"
    }));
    tasks = [demoTask("DISPATCHED", "PLANNED", "PLANNED")];
    await json(route, {
      orderId: "11111111-1111-4111-8111-111111111111",
      decision: "AUTO_DISPATCH",
      dispatchDecisionId: "22222222-2222-4222-8222-222222222222",
      vehicleTaskId: "33333333-3333-4333-8333-333333333333"
    });
  });

  await page.route("**/api/vehicle-tasks", async (route) => {
    await json(route, tasks);
  });

  await page.route("**/api/vehicle-tasks/*/start", async (route) => {
    tasks = [demoTask("IN_PROGRESS", "PLANNED", "PLANNED")];
    await json(route, tasks[0]);
  });

  await page.route("**/api/vehicle-tasks/*/stops/*/arrive", async (route) => {
    const task = tasks[0] as { stops: Array<Record<string, unknown>> };
    const nextStop = task.stops.find((stop) => stop.status === "PLANNED");
    if (nextStop) {
      nextStop.status = "ARRIVED";
    }
    await json(route, task);
  });

  await page.route("**/api/vehicle-tasks/*/stops/*/board", async (route) => {
    const task = tasks[0] as { stops: Array<Record<string, unknown>> };
    const stop = task.stops.find((candidate) => candidate.stopType === "BOARDING");
    if (stop) {
      stop.status = "BOARDED";
    }
    await json(route, task);
  });

  await page.route("**/api/vehicle-tasks/*/stops/*/alight", async (route) => {
    const task = tasks[0] as { stops: Array<Record<string, unknown>> };
    const stop = task.stops.find((candidate) => candidate.stopType === "ALIGHTING");
    if (stop) {
      stop.status = "ALIGHTED";
    }
    await json(route, task);
  });

  await page.route("**/api/vehicle-tasks/*/complete", async (route) => {
    tasks = [demoTask("COMPLETED", "BOARDED", "ALIGHTED")];
    await json(route, tasks[0]);
  });
}

async function installManualReviewWorkbenchMocks(page: Page) {
  let orders: Record<string, unknown>[] = [
    {
      id: "11111111-1111-4111-8111-111111111111",
      passengerName: "Manual review rider",
      passengerPhone: "13800000000",
      passengerCount: 2,
      requestType: "IMMEDIATE",
      originLng: 116.312,
      originLat: 39.94,
      destinationLng: 116.325,
      destinationLat: 39.936,
      requestedDepartureAt: "2026-07-08T02:30:00Z",
      estimatedBoardingAt: null,
      estimatedArrivalAt: null,
      status: "PENDING_MANUAL_REVIEW"
    }
  ];
  let reviews: Record<string, unknown>[] = [
    {
      decisionId: "22222222-2222-4222-8222-222222222222",
      orderId: "11111111-1111-4111-8111-111111111111",
      passengerName: "Manual review rider",
      passengerCount: 2,
      requestedDepartureAt: "2026-07-08T02:30:00Z",
      bestVehicleId: "33333333-3333-4333-8333-333333333333",
      candidateCount: 2
    }
  ];
  let tasks: Record<string, unknown>[] = [];

  await page.route("**/api/orders", async (route) => {
    await json(route, orders);
  });

  await page.route("**/api/vehicle-tasks", async (route) => {
    await json(route, tasks);
  });

  await page.route("**/api/dispatch-decisions/manual-review", async (route) => {
    await json(route, reviews);
  });

  await page.route("**/api/dispatch-decisions/*/approve", async (route) => {
    orders = orders.map((order) => ({
      ...order,
      estimatedBoardingAt: "2026-07-08T02:36:00Z",
      estimatedArrivalAt: "2026-07-08T02:49:00Z",
      status: "CONFIRMED"
    }));
    reviews = [];
    tasks = [demoTask("DISPATCHED", "PLANNED", "PLANNED")];
    await json(route, {
      orderId: "11111111-1111-4111-8111-111111111111",
      decision: "MANUAL_REVIEW",
      dispatchDecisionId: "22222222-2222-4222-8222-222222222222",
      vehicleTaskId: "33333333-3333-4333-8333-333333333333"
    });
  });
}

function demoTask(status: string, boardingStatus: string, alightingStatus: string) {
  return {
    id: "33333333-3333-4333-8333-333333333333",
    vehicleId: "DRT-201",
    vehiclePlateNumber: "DRT-201",
    driverId: "王师傅",
    status,
    plannedStartAt: "2026-07-08T02:36:00Z",
    sourceType: "ALGORITHM",
    stops: [
      {
        id: "44444444-4444-4444-8444-444444444441",
        virtualStopId: "上车点",
        rideOrderId: "11111111-1111-4111-8111-111111111111",
        sequenceNumber: 1,
        stopType: "BOARDING",
        plannedArrivalAt: "2026-07-08T02:36:00Z",
        status: boardingStatus
      },
      {
        id: "44444444-4444-4444-8444-444444444442",
        virtualStopId: "下车点",
        rideOrderId: "11111111-1111-4111-8111-111111111111",
        sequenceNumber: 2,
        stopType: "ALIGHTING",
        plannedArrivalAt: "2026-07-08T02:49:00Z",
        status: alightingStatus
      }
    ]
  };
}

async function json(route: Route, data: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify({ data })
  });
}
