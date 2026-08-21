// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import type { VehicleAlarmEventHandlers } from "../api/alarmEvents";
import type { VehicleAlarmView } from "../api/vehicleAlarms";

const dispatchMap = vi.hoisted(() => ({ receivedProps: [] as Array<Record<string, unknown>> }));
const alarmBoard = vi.hoisted(() => ({ receivedProps: [] as Array<Record<string, unknown>> }));
const alarmApi = vi.hoisted(() => ({ listVehicleAlarms: vi.fn(), getVehicleAlarm: vi.fn(), submitVehicleAlarmAction: vi.fn() }));
const alarmStream = vi.hoisted(() => ({ handlers: undefined as VehicleAlarmEventHandlers | undefined, close: vi.fn(), subscribeVehicleAlarmEvents: vi.fn() }));
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
      props: ["serviceArea", "stops", "locations", "eventChain", "selectedTask", "selectedVehicleId", "vehicleFocusRequest", "alarmVehicleIds"],
      emits: ["selectVehicle"],
      setup(props, { emit }) {
        dispatchMap.receivedProps.push(props as Record<string, unknown>);
        return () => h("section", { "aria-label": "调度地图" }, [
          "开放瓦片调度地图",
          h("button", { type: "button", onClick: () => emit("selectVehicle", "vehicle-1") }, "选择地图车辆")
        ]);
      }
    })
  };
});
vi.mock("../components/AlarmBoard.vue", async () => {
  const { defineComponent, h } = await import("vue");
  return {
    default: defineComponent({
      name: "AlarmBoardStub",
      props: ["alarms", "canHandle"],
      emits: ["selectAlarm", "action"],
      setup(props, { emit }) {
        alarmBoard.receivedProps.push(props as Record<string, unknown>);
        return () => h("section", { "aria-label": "主动安全报警看板" }, [
          h("button", { type: "button", onClick: () => emit("selectAlarm", alarm("alarm-trusted")) }, "选择可信报警"),
          h("button", { type: "button", onClick: () => emit("selectAlarm", alarm("alarm-quarantined")) }, "选择可疑报警"),
          h("button", { type: "button", onClick: () => emit("selectAlarm", alarm("alarm-single-null", { longitude: null })) }, "选择单边空坐标报警"),
          h("button", { type: "button", onClick: () => emit("selectAlarm", alarm("alarm-rejected", { locationQualityStatus: "REJECTED", vehicleId: "vehicle-3" })) }, "选择拒绝位置报警"),
          h("button", { type: "button", onClick: () => emit("action", { publicId: "alarm-trusted", action: "ACKNOWLEDGE", expectedVersion: 4, reason: "已核实", confirmed: true }) }, "提交报警处理")
        ]);
      }
    })
  };
});
vi.mock("../api/orders", () => orderApi);
vi.mock("../api/tasks", () => taskApi);
vi.mock("../api/manualReviews", () => manualReviewApi);
vi.mock("../api/vehicleLocations", () => vehicleLocationApi);
vi.mock("../api/resources", () => resourceApi);
vi.mock("../api/vehicleAlarms", () => alarmApi);
vi.mock("../api/alarmEvents", () => ({ subscribeVehicleAlarmEvents: alarmStream.subscribeVehicleAlarmEvents }));

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
  alarmApi.listVehicleAlarms.mockResolvedValue([alarm("alarm-trusted"), alarm("alarm-quarantined")]);
  alarmApi.getVehicleAlarm.mockResolvedValue(alarm("alarm-streamed"));
  alarmApi.submitVehicleAlarmAction.mockResolvedValue(alarm("alarm-trusted", { status: "ACKNOWLEDGED", version: 5 }));
  alarmStream.subscribeVehicleAlarmEvents.mockImplementation((handlers: VehicleAlarmEventHandlers) => { alarmStream.handlers = handlers; return { close: alarmStream.close }; });
  dispatchMap.receivedProps.length = 0;
  alarmBoard.receivedProps.length = 0;
  alarmStream.handlers = undefined;
  authStore.setSessionForTest({ accessToken: "dispatch-token", user: { id: "dispatcher-1", username: "dispatcher", roles: ["DISPATCHER"], mustChangePassword: false } });
});

afterEach(() => { cleanup(); authStore.clearSessionForTest(); vi.clearAllMocks(); vi.useRealTimers(); vi.unstubAllEnvs(); });

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

  it("keeps the vehicle sidebar and map marker selection in sync", async () => {
    render(DispatchWorkbenchPage);

    const vehicleButton = await screen.findByRole("button", { name: "定位车辆 甘G-T001" });
    await fireEvent.click(vehicleButton);
    await waitFor(() => expect(dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1].selectedVehicleId).toBe("vehicle-1"));
    expect(dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1].vehicleFocusRequest).toBe(1);
    await fireEvent.click(vehicleButton);
    await waitFor(() => expect(dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1].vehicleFocusRequest).toBe(2));

    await fireEvent.click(screen.getByRole("button", { name: "选择地图车辆" }));
    expect(vehicleButton).toHaveAttribute("aria-pressed", "true");
  });

  it("keeps the last location snapshot after a polling failure", async () => {
    vi.useFakeTimers();
    vehicleLocationApi.listLatestVehicleLocations.mockResolvedValueOnce([latestLocation()]).mockRejectedValueOnce(new Error("位置服务暂不可用"));
    render(DispatchWorkbenchPage);
    await vi.runAllTicks();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1]).toEqual(expect.objectContaining({ locations: [latestLocation()] }));
    expect(screen.getByText(/已保留上次快照/)).toBeInTheDocument();
  });

  it("clears the ten-second polling timer after unmount", async () => {
    vi.useFakeTimers();
    const clearIntervalSpy = vi.spyOn(window, "clearInterval");
    const { unmount } = render(DispatchWorkbenchPage);
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(10_000);
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

  it("links authorized alarm selection to the vehicle map, avoids untrusted focus, and reports SSE degradation", async () => {
    render(DispatchWorkbenchPage);
    await waitFor(() => expect(alarmApi.listVehicleAlarms).toHaveBeenCalledTimes(1));
    expect(screen.getByLabelText("主动安全报警看板")).toBeInTheDocument();
    await waitFor(() => expect(dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1].alarmVehicleIds).toEqual(["vehicle-1", "vehicle-2"]));

    await fireEvent.click(screen.getByRole("button", { name: "选择可信报警" }));
    await waitFor(() => expect(dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1]).toEqual(expect.objectContaining({ selectedVehicleId: "vehicle-1", vehicleFocusRequest: 1 })));
    await fireEvent.click(screen.getByRole("button", { name: "选择可疑报警" }));
    await waitFor(() => expect(dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1]).toEqual(expect.objectContaining({ selectedVehicleId: "vehicle-2", vehicleFocusRequest: 1 })));

    alarmStream.handlers?.onDegradedChange?.(true);
    expect(await screen.findByText("实时推送已降级")).toBeInTheDocument();
    await fireEvent.click(screen.getByRole("button", { name: "提交报警处理" }));
    await waitFor(() => expect(alarmApi.submitVehicleAlarmAction).toHaveBeenCalledWith("alarm-trusted", {
      action: "ACKNOWLEDGE", expectedVersion: 4, reason: "已核实", confirmed: true
    }));
  });

  it("selects alarms with one-sided, quarantined, or rejected positions without requesting map focus", async () => {
    render(DispatchWorkbenchPage);
    await waitFor(() => expect(alarmApi.listVehicleAlarms).toHaveBeenCalledTimes(1));

    await fireEvent.click(screen.getByRole("button", { name: "选择单边空坐标报警" }));
    await waitFor(() => expect(dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1]).toEqual(expect.objectContaining({ selectedVehicleId: "vehicle-1", vehicleFocusRequest: 0 })));
    await fireEvent.click(screen.getByRole("button", { name: "选择可疑报警" }));
    await waitFor(() => expect(dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1]).toEqual(expect.objectContaining({ selectedVehicleId: "vehicle-2", vehicleFocusRequest: 0 })));
    await fireEvent.click(screen.getByRole("button", { name: "选择拒绝位置报警" }));
    await waitFor(() => expect(dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1]).toEqual(expect.objectContaining({ selectedVehicleId: "vehicle-3", vehicleFocusRequest: 0 })));
  });

  it("does not request focus when a trusted alarm is associated with a vehicle snapshot at zero-zero", async () => {
    vehicleLocationApi.listLatestVehicleLocations.mockResolvedValue([latestLocation({ longitude: 0, latitude: 0 })]);
    render(DispatchWorkbenchPage);
    await waitFor(() => expect(alarmApi.listVehicleAlarms).toHaveBeenCalledTimes(1));

    await fireEvent.click(screen.getByRole("button", { name: "选择可信报警" }));
    await waitFor(() => expect(dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1]).toEqual(expect.objectContaining({ selectedVehicleId: "vehicle-1", vehicleFocusRequest: 0 })));
  });

  it("keeps orders, tasks, and vehicle positions available when the initial alarm list fails", async () => {
    alarmApi.listVehicleAlarms.mockRejectedValue(new Error("alarm read unavailable"));
    render(DispatchWorkbenchPage);

    await waitFor(() => expect(dispatchMap.receivedProps[dispatchMap.receivedProps.length - 1].locations).toEqual([latestLocation()]));
    expect(await screen.findByText("order-1")).toBeInTheDocument();
    expect(taskApi.listTasks).toHaveBeenCalledTimes(1);
  });

  it("merges a streamed alarm when an older initial alarm list resolves afterwards", async () => {
    const initialList = deferred<VehicleAlarmView[]>();
    const streamed = alarm("alarm-streamed");
    alarmApi.listVehicleAlarms.mockReturnValueOnce(initialList.promise);
    alarmApi.getVehicleAlarm.mockResolvedValueOnce(streamed);
    render(DispatchWorkbenchPage);
    await waitFor(() => expect(alarmStream.handlers).toBeDefined());

    alarmStream.handlers?.onVehicleAlarm?.({ publicId: streamed.publicId, type: "ALARM_CREATED", status: "NEW", level: 3, module: "ADAS", occurredAt: streamed.occurredAt });
    await waitFor(() => expect(latestBoardAlarms()).toEqual([streamed]));
    initialList.resolve([alarm("alarm-trusted")]);

    await waitFor(() => expect(latestBoardAlarms().map((item) => item.publicId)).toEqual(expect.arrayContaining(["alarm-trusted", "alarm-streamed"])));
  });

  it("merges each refreshed alarm by public id and version while retaining newer streamed alarms", async () => {
    const olderA = alarm("alarm-a", { status: "NEW", version: 1 });
    const olderB = alarm("alarm-b", { status: "NEW", version: 1 });
    const refreshed = deferred<VehicleAlarmView[]>();
    const newerA = alarm("alarm-a", { status: "ACKNOWLEDGED", version: 2 });
    const newerB = alarm("alarm-b", { status: "TAKEN_OVER", version: 2 });
    const streamedC = alarm("alarm-c", { status: "NEW", version: 1 });
    alarmApi.listVehicleAlarms.mockResolvedValueOnce([olderA, olderB]).mockReturnValueOnce(refreshed.promise);
    alarmApi.getVehicleAlarm.mockResolvedValueOnce(newerB).mockResolvedValueOnce(streamedC);
    render(DispatchWorkbenchPage);
    await waitFor(() => expect(latestBoardAlarms()).toEqual([olderA, olderB]));

    alarmStream.handlers?.onResyncRequired?.();
    await waitFor(() => expect(alarmApi.listVehicleAlarms).toHaveBeenCalledTimes(2));
    alarmStream.handlers?.onVehicleAlarm?.({ publicId: newerB.publicId, type: "ALARM_UPDATED", status: newerB.status, level: newerB.level, module: newerB.module, occurredAt: newerB.occurredAt });
    await waitFor(() => expect(latestBoardAlarms()).toEqual(expect.arrayContaining([newerB])));
    alarmStream.handlers?.onVehicleAlarm?.({ publicId: streamedC.publicId, type: "ALARM_CREATED", status: streamedC.status, level: streamedC.level, module: streamedC.module, occurredAt: streamedC.occurredAt });
    await waitFor(() => expect(latestBoardAlarms()).toEqual(expect.arrayContaining([streamedC])));

    refreshed.resolve([newerA, olderB]);

    await waitFor(() => expect(latestBoardAlarms()).toEqual(expect.arrayContaining([newerA, newerB, streamedC])));
  });
});

function latestLocation(overrides: { currentStatus?: string; driverReportedAt?: string; longitude?: number; latitude?: number } = {}) {
  return { vehicleId: "vehicle-1", plateNumber: "甘G-T001", currentStatus: overrides.currentStatus ?? "IN_SERVICE", latestLocation: { longitude: overrides.longitude ?? 104.6378, latitude: overrides.latitude ?? 35.2109, standardizedAddress: "通渭县客运中心", source: "MANUAL_DISPATCHER", coordinateSystem: "GCJ02", driverReportedAt: overrides.driverReportedAt ?? "2026-07-13T00:33:00Z", recordedAt: "2026-07-13T00:35:00Z", eventId: "loc-1", vehicleTaskId: "task-1" } };
}

function alarm(publicId: string, overrides: Partial<Pick<VehicleAlarmView, "status" | "version" | "locationQualityStatus" | "longitude" | "latitude" | "vehicleId">> = {}): VehicleAlarmView {
  const quarantined = publicId === "alarm-quarantined";
  return {
    publicId, vehicleId: quarantined ? "vehicle-2" : "vehicle-1", plateNumber: quarantined ? "甘G·A1002" : "甘G·A1001",
    standard: "T/JSATL12-2017", module: "ADAS", alarmTypeCode: 1, alarmType: "前向碰撞", level: 3,
    status: overrides.status ?? "NEW", occurredAt: "2026-08-15T02:00:00Z", endedAt: null,
    locationQualityStatus: quarantined ? "QUARANTINED" : "GOOD", hasAttachment: false, version: overrides.version ?? 4,
    longitude: quarantined ? 0 : 118, latitude: quarantined ? 0 : 32, speedKph: 60,
    ...overrides
  };
}

function latestBoardAlarms(): VehicleAlarmView[] {
  return (alarmBoard.receivedProps[alarmBoard.receivedProps.length - 1]?.alarms ?? []) as VehicleAlarmView[];
}

function deferred<T>(): { promise: Promise<T>; resolve(value: T): void } {
  let resolve: ((value: T) => void) | undefined;
  const promise = new Promise<T>((nextResolve) => { resolve = nextResolve; });
  return { promise, resolve(value) { resolve?.(value); } };
}
