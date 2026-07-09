import { expect, test, type Page, type Route } from "@playwright/test";

test("operator can create demand dispatch it and complete the task", async ({ page }) => {
  await installDispatchFlowMocks(page);

  await page.goto("/orders");
  await page.getByRole("button", { name: "录入需求" }).click();
  await page.getByLabel("乘客姓名").fill("张三");
  await page.getByLabel(/乘客(电话|手机号)/).fill("13800000000");
  await page.getByLabel("乘客人数").fill("1");
  await page.getByRole("button", { name: "提交需求" }).click();
  await expect(page.getByText("张三")).toBeVisible();

  await page.getByRole("button", { name: "调度" }).click();
  await expect(page.getByText("CONFIRMED")).toBeVisible();

  await page.goto("/tasks");
  await expect(page.getByText("DRT-201")).toBeVisible();
  await page.getByRole("button", { name: "发车" }).click();
  await expect(page.getByText("IN_PROGRESS")).toBeVisible();
  await page.getByRole("button", { name: "到站" }).click();
  await page.getByRole("button", { name: "上车" }).click();
  await page.getByRole("button", { name: "到站" }).click();
  await page.getByRole("button", { name: "下车" }).click();
  await page.getByRole("button", { name: "完成" }).click();
  await expect(page.getByText("COMPLETED")).toBeVisible();
});

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

function demoTask(status: string, boardingStatus: string, alightingStatus: string) {
  return {
    id: "33333333-3333-4333-8333-333333333333",
    vehicleId: "DRT-201",
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
