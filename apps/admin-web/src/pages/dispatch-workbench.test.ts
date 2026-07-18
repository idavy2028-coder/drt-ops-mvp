// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const dispatchMap = vi.hoisted(() => ({ receivedProps: [] as Array<Record<string, unknown>> }));
const orderApi = vi.hoisted(() => ({ listOrders: vi.fn() }));
const taskApi = vi.hoisted(() => ({ listTasks: vi.fn() }));
const manualReviewApi = vi.hoisted(() => ({ listManualReviews: vi.fn(), approveManualReview: vi.fn(), rejectManualReview: vi.fn() }));
const vehicleLocationApi = vi.hoisted(() => ({ listLatestVehicleLocations: vi.fn(), listVehicleLocationEvents: vi.fn() }));
const resourceApi = vi.hoisted(() => ({ listServiceAreas: vi.fn(), listVirtualStops: vi.fn() }));

vi.mock("../components/DispatchMap.vue", async () => {
  const { defineComponent, h } = await import("vue");
  return {
    default: defineComponent({
      name: "DispatchMapStub",
      props: ["serviceArea", "stops", "locations", "eventChain", "selectedTask"],
      setup(props) {
        dispatchMap.receivedProps.push(props as Record<string, unknown>);
        return () => h("section", { "aria-label": "调度地图" }, "开放瓦片调度地图");
      }
    })
  };
});
vi.mock("../api/orders", () => orderApi);
vi.mock("../api/tasks", () => taskApi);
vi.mock("../api/manualReviews", () => manualReviewApi);
vi.mock("../api/vehicleLocations", () => vehicleLocationApi);
vi.mock("../api/resources", () => resourceApi);

import DispatchWorkbenchPage from "./DispatchWorkbenchPage.vue";

const review = { decisionId: "decision-1", orderId: "order-1", passengerName: "张三", passengerCount: 2, requestedDepartureAt: "2026-07-08T02:30:00Z", bestVehicleId: "vehicle-1", candidateCount: 3 };

beforeEach(() => {
  orderApi.listOrders.mockResolvedValue([{ id: "order-1", passengerName: "张三", passengerPhone: "13800000000", passengerCount: 2, requestType: "IMMEDIATE", originLng: 116.312, originLat: 39.94, destinationLng: 116.325, destinationLat: 39.936, originAddress: "上车点", destinationAddress: "下车点", coordinateSystem: "GCJ02", originAddressSource: "MANUAL", destinationAddressSource: "MANUAL", requestedDepartureAt: "2026-07-08T02:30:00Z", status: "PENDING_MANUAL_REVIEW" }]);
  taskApi.listTasks.mockResolvedValue([]);
  manualReviewApi.listManualReviews.mockResolvedValue([review]);
  manualReviewApi.approveManualReview.mockResolvedValue({ vehicleTaskId: "task-1" });
  manualReviewApi.rejectManualReview.mockResolvedValue({ vehicleTaskId: undefined });
  vehicleLocationApi.listLatestVehicleLocations.mockResolvedValue([latestLocation()]);
  vehicleLocationApi.listVehicleLocationEvents.mockResolvedValue([]);
  resourceApi.listServiceAreas.mockResolvedValue([]);
  resourceApi.listVirtualStops.mockResolvedValue([]);
  dispatchMap.receivedProps.length = 0;
});

afterEach(() => { cleanup(); vi.clearAllMocks(); vi.useRealTimers(); vi.unstubAllEnvs(); });

describe("DispatchWorkbenchPage", () => {
  it("renders workbench regions and passes operational map data", async () => {
    render(DispatchWorkbenchPage);
    expect(await screen.findByText("实时订单")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "车辆任务" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "人工复核队列" })).toBeInTheDocument();
    expect(screen.getByLabelText("调度地图")).toBeInTheDocument();
    await waitFor(() => expect(dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1].locations).toEqual([latestLocation()]));
    const mapProps = dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1];
    expect(mapProps.locations).toEqual([latestLocation()]);
    expect(mapProps.eventChain).toEqual([]);
    expect(mapProps.selectedTask).toBeUndefined();
  });

  it("loads pilot resources and requests the initially selected task location chain", async () => {
    taskApi.listTasks.mockResolvedValue([{ id: "task-1", vehicleId: "vehicle-1", driverId: "driver-1", status: "DISPATCHED", plannedStartAt: "2026-07-18T00:00:00Z", stops: [] }]);
    resourceApi.listServiceAreas.mockResolvedValue([{ id: "area-1", name: "通渭县试点服务区", boundary: null, boundarySource: null, boundaryVersion: 0, draftBoundary: null, draftBoundarySource: null, draftBoundaryVersion: 0, publishedAt: null, updatedAt: null, coordinateSystem: "GCJ02", serviceStart: "06:30", serviceEnd: "19:00", ruleSetId: "rule-1", enabled: true }]);
    render(DispatchWorkbenchPage);
    expect(await screen.findByText("开放瓦片调度地图")).toBeInTheDocument();
    await waitFor(() => expect(vehicleLocationApi.listVehicleLocationEvents).toHaveBeenCalledWith({ taskId: "task-1" }));
    expect(resourceApi.listVirtualStops).toHaveBeenCalledTimes(1);
  });

  it("switches the location chain when another task is selected", async () => {
    taskApi.listTasks.mockResolvedValue([
      { id: "task-1", vehicleId: "vehicle-1", driverId: "driver-1", status: "DISPATCHED", plannedStartAt: "2026-07-18T00:00:00Z", stops: [] },
      { id: "task-2", vehicleId: "vehicle-2", driverId: "driver-2", status: "DISPATCHED", plannedStartAt: "2026-07-18T00:10:00Z", stops: [] }
    ]);
    render(DispatchWorkbenchPage);
    await waitFor(() => expect(screen.getAllByRole("button", { name: "查看地图" })).toHaveLength(2));
    await fireEvent.click(screen.getAllByRole("button", { name: "查看地图" })[1]);
    await waitFor(() => expect(vehicleLocationApi.listVehicleLocationEvents).toHaveBeenLastCalledWith({ taskId: "task-2" }));
  });

  it("keeps the last location snapshot after a polling failure", async () => {
    vi.useFakeTimers();
    vehicleLocationApi.listLatestVehicleLocations.mockResolvedValueOnce([latestLocation()]).mockRejectedValueOnce(new Error("位置服务暂不可用"));
    render(DispatchWorkbenchPage);
    await vi.runAllTicks();
    await vi.advanceTimersByTimeAsync(15_000);
    expect(dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1]).toEqual(expect.objectContaining({ locations: [latestLocation()] }));
    expect(screen.getByText(/已保留上次快照/)).toBeInTheDocument();
  });

  it("clears the fifteen-second polling timer after unmount", async () => {
    vi.useFakeTimers();
    const clearIntervalSpy = vi.spyOn(window, "clearInterval");
    const { unmount } = render(DispatchWorkbenchPage);
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(15_000);
    expect(vehicleLocationApi.listLatestVehicleLocations).toHaveBeenCalledTimes(2);
    unmount();
    expect(clearIntervalSpy).toHaveBeenCalled();
  });

  it("warns when an active vehicle location is stale", async () => {
    vi.setSystemTime(new Date("2026-07-13T01:10:00Z"));
    vi.stubEnv("VITE_MANUAL_LOCATION_STALE_MINUTES", "20");
    vehicleLocationApi.listLatestVehicleLocations.mockResolvedValue([latestLocation({ currentStatus: "IN_SERVICE", driverReportedAt: "2026-07-13T00:33:00Z" })]);
    render(DispatchWorkbenchPage);
    expect(await screen.findByText("位置较久未更新")).toBeInTheDocument();
    expect(screen.getByText("甘G-T001 超过 20 分钟未更新位置")).toBeInTheDocument();
  });

  it("reloads the workbench after approving a manual review", async () => {
    render(DispatchWorkbenchPage);

    expect(await screen.findByText("张三")).toBeInTheDocument();
    await fireEvent.click(screen.getByRole("button", { name: "确认派单" }));

    expect(manualReviewApi.approveManualReview).toHaveBeenCalledWith(review.decisionId);
    await waitFor(() => expect(taskApi.listTasks).toHaveBeenCalledTimes(2));
    expect(manualReviewApi.listManualReviews).toHaveBeenCalledTimes(2);
  });

  it("keeps the review item visible when rejecting a manual review fails", async () => {
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
  return { vehicleId: "vehicle-1", plateNumber: "甘G-T001", currentStatus: overrides.currentStatus ?? "IN_SERVICE", latestLocation: { longitude: 104.6378, latitude: 35.2109, standardizedAddress: "通渭县客运中心", source: "MANUAL_DISPATCHER", coordinateSystem: "GCJ02", driverReportedAt: overrides.driverReportedAt ?? "2026-07-13T00:33:00Z", recordedAt: "2026-07-13T00:35:00Z", eventId: "loc-1", vehicleTaskId: "task-1" } };
}
