// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import DispatchWorkbenchPage from "./DispatchWorkbenchPage.vue";

const mapConstructor = vi.hoisted(() => vi.fn(() => ({ remove: vi.fn() })));
const orderApi = vi.hoisted(() => ({ listOrders: vi.fn() }));
const taskApi = vi.hoisted(() => ({ listTasks: vi.fn() }));
const manualReviewApi = vi.hoisted(() => ({
  listManualReviews: vi.fn(),
  approveManualReview: vi.fn(),
  rejectManualReview: vi.fn()
}));
const vehicleLocationApi = vi.hoisted(() => ({
  listLatestVehicleLocations: vi.fn(),
  listVehicleLocationEvents: vi.fn()
}));
const resourceApi = vi.hoisted(() => ({ listServiceAreas: vi.fn(), listVirtualStops: vi.fn() }));

vi.mock("maplibre-gl", () => ({
  default: {
    Map: mapConstructor
  }
}));

vi.mock("../api/orders", () => orderApi);
vi.mock("../api/tasks", () => taskApi);
vi.mock("../api/manualReviews", () => manualReviewApi);
vi.mock("../api/vehicleLocations", () => vehicleLocationApi);
vi.mock("../api/resources", () => resourceApi);

const review = {
  decisionId: "decision-1",
  orderId: "order-1",
  passengerName: "张三",
  passengerCount: 2,
  requestedDepartureAt: "2026-07-08T02:30:00Z",
  bestVehicleId: "vehicle-1",
  candidateCount: 3
};

beforeEach(() => {
  orderApi.listOrders.mockResolvedValue([
    {
      id: "order-1",
      passengerName: "张三",
      passengerPhone: "13800000000",
      passengerCount: 2,
      requestType: "IMMEDIATE",
      originLng: 116.312,
      originLat: 39.94,
      destinationLng: 116.325,
      destinationLat: 39.936,
      requestedDepartureAt: "2026-07-08T02:30:00Z",
      status: "PENDING_MANUAL_REVIEW"
    }
  ]);
  taskApi.listTasks.mockResolvedValue([]);
  manualReviewApi.listManualReviews.mockResolvedValue([review]);
  manualReviewApi.approveManualReview.mockResolvedValue({ vehicleTaskId: "task-1" });
  manualReviewApi.rejectManualReview.mockResolvedValue({ vehicleTaskId: undefined });
  vehicleLocationApi.listLatestVehicleLocations.mockResolvedValue([latestLocation()]);
  vehicleLocationApi.listVehicleLocationEvents.mockResolvedValue([]);
  resourceApi.listServiceAreas.mockResolvedValue([]);
  resourceApi.listVirtualStops.mockResolvedValue([]);
  mapConstructor.mockClear();
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.useRealTimers();
  vi.unstubAllEnvs();
});

describe("DispatchWorkbenchPage", () => {
  it("renders dispatch workbench operational regions", async () => {
    render(DispatchWorkbenchPage);

    expect(await screen.findByText("实时订单")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "车辆任务" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "人工复核队列" })).toBeInTheDocument();
    expect(screen.getByLabelText("调度地图")).toBeInTheDocument();
    await new Promise((resolve) => window.setTimeout(resolve, 0));
    expect(mapConstructor).not.toHaveBeenCalled();
  });

  it("loads latest vehicle locations when the workbench mounts", async () => {
    render(DispatchWorkbenchPage);

    expect(await screen.findByText("甘E-T001")).toBeInTheDocument();
    expect(screen.getAllByText("人工上报").length).toBeGreaterThan(0);
    expect(vehicleLocationApi.listLatestVehicleLocations).toHaveBeenCalledTimes(1);
  });

  it("loads pilot map resources and requests a selected task location chain", async () => {
    taskApi.listTasks.mockResolvedValue([{ id: "task-1", vehicleId: "vehicle-1", driverId: "driver-1", status: "DISPATCHED", plannedStartAt: "2026-07-18T00:00:00Z", stops: [] }]);
    resourceApi.listServiceAreas.mockResolvedValue([{ id: "area-1", name: "通渭县试点服务区", boundary: null, boundarySource: null, boundaryVersion: 0, draftBoundary: null, draftBoundarySource: null, draftBoundaryVersion: 0, publishedAt: null, updatedAt: null, coordinateSystem: "GCJ02", serviceStart: "06:30", serviceEnd: "19:00", ruleSetId: "rule-1", enabled: true }]);
    resourceApi.listVirtualStops.mockResolvedValue([]);

    render(DispatchWorkbenchPage);

    expect(await screen.findByText("通渭县试点服务区")).toBeInTheDocument();
    expect(resourceApi.listVirtualStops).toHaveBeenCalledTimes(1);
    expect(vehicleLocationApi.listVehicleLocationEvents).toHaveBeenCalledWith({ taskId: "task-1" });
  });

  it("switches the map location chain when the dispatcher selects another task", async () => {
    taskApi.listTasks.mockResolvedValue([
      { id: "task-1", vehicleId: "vehicle-1", driverId: "driver-1", status: "DISPATCHED", plannedStartAt: "2026-07-18T00:00:00Z", stops: [] },
      { id: "task-2", vehicleId: "vehicle-2", driverId: "driver-2", status: "DISPATCHED", plannedStartAt: "2026-07-18T00:10:00Z", stops: [] }
    ]);
    render(DispatchWorkbenchPage);

    await waitFor(() => expect(screen.getAllByRole("button", { name: "查看地图" })).toHaveLength(2));
    await fireEvent.click(screen.getAllByRole("button", { name: "查看地图" })[1]);

    await waitFor(() => expect(vehicleLocationApi.listVehicleLocationEvents).toHaveBeenLastCalledWith({ taskId: "task-2" }));
  });

  it("polls latest vehicle locations every 15 seconds and clears the timer after unmount", async () => {
    vi.useFakeTimers();
    const clearIntervalSpy = vi.spyOn(window, "clearInterval");
    const { unmount } = render(DispatchWorkbenchPage);

    await Promise.resolve();
    await Promise.resolve();
    expect(vehicleLocationApi.listLatestVehicleLocations).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(15_000);
    expect(vehicleLocationApi.listLatestVehicleLocations).toHaveBeenCalledTimes(2);

    unmount();
    expect(clearIntervalSpy).toHaveBeenCalled();
  });

  it("warns when an active vehicle location is stale and allows overriding the stale threshold", async () => {
    vi.setSystemTime(new Date("2026-07-13T01:10:00Z"));
    vi.stubEnv("VITE_MANUAL_LOCATION_STALE_MINUTES", "20");
    vehicleLocationApi.listLatestVehicleLocations.mockResolvedValue([latestLocation({ currentStatus: "IN_SERVICE", driverReportedAt: "2026-07-13T00:33:00Z" })]);

    render(DispatchWorkbenchPage);

    expect(await screen.findByText("位置较久未更新")).toBeInTheDocument();
    expect(screen.getByText("甘E-T001 超过 20 分钟未更新位置")).toBeInTheDocument();
  });

  it("approves manual review and reloads workbench data", async () => {
    render(DispatchWorkbenchPage);

    expect(await screen.findByText("张三")).toBeInTheDocument();
    await fireEvent.click(screen.getByRole("button", { name: "确认派单" }));

    expect(manualReviewApi.approveManualReview).toHaveBeenCalledWith(review.decisionId);
    await waitFor(() => expect(taskApi.listTasks).toHaveBeenCalledTimes(2));
    expect(manualReviewApi.listManualReviews).toHaveBeenCalledTimes(2);
  });

  it("shows reject error and keeps queue item visible", async () => {
    manualReviewApi.rejectManualReview.mockRejectedValue(new Error("人工拒绝失败"));
    render(DispatchWorkbenchPage);

    expect(await screen.findByText("张三")).toBeInTheDocument();
    await fireEvent.click(screen.getByRole("button", { name: "拒绝" }));
    await fireEvent.update(screen.getByLabelText("拒绝原因"), "车辆临时不可用");
    await fireEvent.click(screen.getByRole("button", { name: "确认拒绝" }));

    expect(manualReviewApi.rejectManualReview).toHaveBeenCalledWith(review.decisionId, "车辆临时不可用");
    expect(await screen.findByText("人工拒绝失败")).toBeInTheDocument();
    expect(screen.getByText("张三")).toBeInTheDocument();
  });
});

function latestLocation(overrides: { currentStatus?: string; driverReportedAt?: string } = {}) {
  return {
    vehicleId: "vehicle-1",
    plateNumber: "甘E-T001",
    currentStatus: overrides.currentStatus ?? "IN_SERVICE",
    latestLocation: {
      longitude: 104.6378,
      latitude: 35.2109,
      standardizedAddress: "通渭县客运中心",
      source: "MANUAL_DISPATCHER",
      coordinateSystem: "GCJ02",
      driverReportedAt: overrides.driverReportedAt ?? "2026-07-13T00:33:00Z",
      recordedAt: "2026-07-13T00:35:00Z",
      eventId: "loc-1",
      vehicleTaskId: "task-1"
    }
  };
}
