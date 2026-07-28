// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import VehicleLocationHistoryPage from "./VehicleLocationHistoryPage.vue";

const vehicleLocationApi = vi.hoisted(() => ({
  listVehicleLocationEvents: vi.fn(),
  exportVehicleLocationEvents: vi.fn(),
  listLocationReportVehicles: vi.fn(),
  reportVehicleStandbyLocation: vi.fn()
}));
const resourceApi = vi.hoisted(() => ({ listServiceAreas: vi.fn(), listVirtualStops: vi.fn() }));
const feedbackApi = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }));
const locationPanel = vi.hoisted(() => ({
  receivedProps: [] as Array<Record<string, unknown>>,
  standbyInput: {
    longitude: 105.2421,
    latitude: 35.2103,
    standardizedAddress: "通渭县待命点",
    virtualStopId: "stop-1",
    driverReportedAt: "2026-07-26T01:30:00.000Z",
    idempotencyKey: "request-1"
  }
}));

vi.mock("../api/vehicleLocations", () => vehicleLocationApi);
vi.mock("../api/resources", () => resourceApi);
vi.mock("../stores/feedbackStore", () => ({ feedbackStore: feedbackApi }));
vi.mock("../components/LocationReportPanel.vue", async () => {
  const { defineComponent, h } = await import("vue");
  return {
    default: defineComponent({
      name: "LocationReportPanelStub",
      props: ["actionLabel", "initialLocation", "virtualStops", "serviceArea", "submitting"],
      emits: ["submit", "close"],
      setup(props, { emit }) {
        locationPanel.receivedProps.push(props as Record<string, unknown>);
        return () => h("section", { "aria-label": "待命位置上报面板" }, [
          h("p", `确认${props.actionLabel}位置`),
          h("button", { type: "button", onClick: () => emit("submit", locationPanel.standbyInput) }, "模拟确认待命"),
          h("button", { type: "button", onClick: () => emit("close") }, "关闭待命位置面板")
        ]);
      }
    })
  };
});

describe("VehicleLocationHistoryPage", () => {
  beforeEach(() => {
    authStore.setSessionForTest({
      accessToken: "admin-token",
      user: { id: "admin-1", username: "admin", roles: ["SYSTEM_ADMIN"], mustChangePassword: false }
    });
    vehicleLocationApi.listVehicleLocationEvents.mockResolvedValue([locationEvent()]);
    vehicleLocationApi.exportVehicleLocationEvents.mockResolvedValue(undefined);
    vehicleLocationApi.listLocationReportVehicles.mockResolvedValue(locationReportVehicles());
    vehicleLocationApi.reportVehicleStandbyLocation.mockResolvedValue(locationReportResponse());
    resourceApi.listServiceAreas.mockResolvedValue([serviceArea()]);
    resourceApi.listVirtualStops.mockResolvedValue([virtualStop()]);
    locationPanel.receivedProps.length = 0;
  });

  afterEach(() => {
    cleanup();
    authStore.clearSessionForTest();
    vi.clearAllMocks();
  });

  it("filters history by vehicle, task, Shanghai operation date, and event type", async () => {
    render(VehicleLocationHistoryPage);

    await fireEvent.update(screen.getByLabelText("车辆编号"), "vehicle-1");
    await fireEvent.update(screen.getByLabelText("任务编号"), "task-1");
    await fireEvent.update(screen.getByLabelText("日期"), "2026-07-13");
    await fireEvent.update(screen.getByLabelText("事件类型"), "PASSENGER_BOARDED");
    await fireEvent.click(screen.getByRole("button", { name: "查询" }));

    await waitFor(() => expect(vehicleLocationApi.listVehicleLocationEvents).toHaveBeenLastCalledWith({
      vehicleId: "vehicle-1",
      taskId: "task-1",
      eventType: "PASSENGER_BOARDED",
      from: "2026-07-12T16:00:00.000Z",
      to: "2026-07-13T16:00:00.000Z"
    }));
    expect((await screen.findAllByText("乘客上车")).length).toBeGreaterThan(0);
    expect(screen.getByText("驾驶员反馈 07/13 08:33")).toBeInTheDocument();
    expect(screen.getByText("系统录入 07/13 08:36")).toBeInTheDocument();
    expect(screen.getByText("录入延迟 3 分钟")).toBeInTheDocument();
    expect(screen.getByText("操作人 dispatcher-1")).toBeInTheDocument();
    expect(screen.getByText("修正原事件 event-original")).toBeInTheDocument();
  });

  it("shows export and correction actions only to administrators", async () => {
    const { rerender } = render(VehicleLocationHistoryPage);

    expect(await screen.findByRole("button", { name: "导出 CSV" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "修正位置" })).toBeInTheDocument();

    authStore.setSessionForTest({
      accessToken: "dispatcher-token",
      user: { id: "dispatcher-1", username: "dispatcher01", roles: ["DISPATCHER"], mustChangePassword: false }
    });
    await rerender({});

    expect(screen.queryByRole("button", { name: "导出 CSV" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "修正位置" })).not.toBeInTheDocument();
  });

  it("disables export for vehicle-only filters because the backend export endpoint does not support vehicle scope", async () => {
    render(VehicleLocationHistoryPage);

    await fireEvent.update(screen.getByLabelText("车辆编号"), "vehicle-1");

    expect(screen.getByRole("button", { name: "导出 CSV" })).toBeDisabled();
    expect(screen.getByText("车辆维度导出需后端支持，请改用任务编号或清空车辆筛选")).toBeInTheDocument();
  });

  it("shows the standby location entry and selectable report candidates to a dispatcher, including a vehicle without a snapshot", async () => {
    setDispatcherSession();
    render(VehicleLocationHistoryPage);

    await screen.findByRole("option", { name: "甘G00856D · IDLE · 可调度" });
    expect(resourceApi.listVirtualStops).toHaveBeenCalledTimes(1);
    expect(resourceApi.listVirtualStops).toHaveBeenNthCalledWith(1, { enabled: true });
    expect(screen.getByRole("button", { name: "上报待命位置" })).toBeEnabled();
    expect(screen.getByLabelText("待命车辆")).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "甘G00856D · IDLE · 可调度" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "甘G00857D · IN_SERVICE · 不可调度" })).toBeInTheDocument();
  });

  it("does not open the standby panel before a vehicle is selected", async () => {
    setDispatcherSession();
    render(VehicleLocationHistoryPage);

    await screen.findByRole("option", { name: "甘G00856D · IDLE · 可调度" });
    await fireEvent.click(screen.getByRole("button", { name: "上报待命位置" }));

    expect(screen.getByText("请先选择车辆")).toBeInTheDocument();
    expect(screen.queryByLabelText("待命位置上报面板")).not.toBeInTheDocument();
  });

  it("locks the vehicle selector while a standby panel is open for a vehicle with a location snapshot", async () => {
    setDispatcherSession();
    render(VehicleLocationHistoryPage);

    await screen.findByRole("option", { name: "甘G00857D · IN_SERVICE · 不可调度" });
    await fireEvent.update(screen.getByLabelText("待命车辆"), "vehicle-busy");
    await fireEvent.click(screen.getByRole("button", { name: "上报待命位置" }));

    expect(await screen.findByLabelText("待命位置上报面板")).toBeInTheDocument();
    expect(screen.getByLabelText("待命车辆")).toBeDisabled();
  });

  it("reports a selected standby location, refreshes candidates, and searches that vehicle history", async () => {
    setDispatcherSession();
    render(VehicleLocationHistoryPage);

    await screen.findByRole("option", { name: "甘G00856D · IDLE · 可调度" });
    await fireEvent.update(screen.getByLabelText("待命车辆"), "vehicle-standby");
    await fireEvent.click(screen.getByRole("button", { name: "上报待命位置" }));
    expect(await screen.findByLabelText("待命位置上报面板")).toBeInTheDocument();
    const lastPanelProps = locationPanel.receivedProps[locationPanel.receivedProps.length - 1];
    expect(lastPanelProps).toEqual(expect.objectContaining({
      actionLabel: "待命",
      virtualStops: [virtualStop()],
      serviceArea: expect.objectContaining({ id: "area-1" })
    }));

    await fireEvent.click(screen.getByRole("button", { name: "模拟确认待命" }));

    await waitFor(() => expect(vehicleLocationApi.reportVehicleStandbyLocation).toHaveBeenCalledWith("vehicle-standby", locationPanel.standbyInput));
    await waitFor(() => expect(vehicleLocationApi.listVehicleLocationEvents).toHaveBeenLastCalledWith({ vehicleId: "vehicle-standby" }));
    expect(vehicleLocationApi.listLocationReportVehicles).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(feedbackApi.success).toHaveBeenCalledWith("待命位置已上报"));
    expect(screen.queryByLabelText("待命位置上报面板")).not.toBeInTheDocument();
  });

  it("normalizes finite decimal snapshot coordinates and does not prefill invalid coordinates", async () => {
    setDispatcherSession();
    vehicleLocationApi.listLocationReportVehicles.mockResolvedValue([
      {
        vehicleId: "vehicle-decimal",
        plateNumber: "甘G00857D",
        currentStatus: "IDLE",
        dispatchable: true,
        latestLocation: {
          longitude: "105.25",
          latitude: "35.21",
          standardizedAddress: "通渭县客运中心",
          source: "MANUAL_DISPATCHER",
          coordinateSystem: "GCJ02",
          driverReportedAt: "2026-07-26T01:20:00Z",
          recordedAt: "2026-07-26T01:21:00Z",
          eventId: "event-decimal"
        }
      },
      {
        vehicleId: "vehicle-invalid",
        plateNumber: "甘G00858D",
        currentStatus: "IDLE",
        dispatchable: true,
        latestLocation: {
          longitude: "not-a-number",
          latitude: "35.22",
          standardizedAddress: "无效待命点",
          source: "MANUAL_DISPATCHER",
          coordinateSystem: "GCJ02",
          driverReportedAt: "2026-07-26T01:20:00Z",
          recordedAt: "2026-07-26T01:21:00Z",
          eventId: "event-invalid"
        }
      },
      {
        vehicleId: "vehicle-corrupt",
        plateNumber: "甘G00858D",
        currentStatus: "IDLE",
        dispatchable: true,
        latestLocation: {
          longitude: 105.26,
          latitude: 35.22,
          standardizedAddress: "??????-?G00858D",
          source: "MANUAL_DISPATCHER",
          coordinateSystem: "GCJ02",
          driverReportedAt: "2026-07-26T01:20:00Z",
          recordedAt: "2026-07-26T01:21:00Z",
          eventId: "event-corrupt"
        }
      }
    ]);
    render(VehicleLocationHistoryPage);

    await screen.findByRole("option", { name: "甘G00857D · IDLE · 可调度" });
    await fireEvent.update(screen.getByLabelText("待命车辆"), "vehicle-decimal");
    await fireEvent.click(screen.getByRole("button", { name: "上报待命位置" }));
    expect(locationPanel.receivedProps[locationPanel.receivedProps.length - 1].initialLocation).toEqual(expect.objectContaining({
      longitude: 105.25,
      latitude: 35.21
    }));

    await fireEvent.click(screen.getByRole("button", { name: "关闭待命位置面板" }));
    await fireEvent.update(screen.getByLabelText("待命车辆"), "vehicle-invalid");
    await fireEvent.click(screen.getByRole("button", { name: "上报待命位置" }));
    expect(locationPanel.receivedProps[locationPanel.receivedProps.length - 1].initialLocation).toBeUndefined();

    await fireEvent.click(screen.getByRole("button", { name: "关闭待命位置面板" }));
    await fireEvent.update(screen.getByLabelText("待命车辆"), "vehicle-corrupt");
    await fireEvent.click(screen.getByRole("button", { name: "上报待命位置" }));
    expect(locationPanel.receivedProps[locationPanel.receivedProps.length - 1].initialLocation).toEqual(expect.objectContaining({
      longitude: 105.26,
      latitude: 35.22,
      standardizedAddress: ""
    }));
  });

  it("treats an outside-service-area warning as a successful standby report", async () => {
    setDispatcherSession();
    vehicleLocationApi.reportVehicleStandbyLocation.mockResolvedValue(locationReportResponse({ warnings: ["OUTSIDE_SERVICE_AREA"] }));
    render(VehicleLocationHistoryPage);

    await screen.findByRole("option", { name: "甘G00856D · IDLE · 可调度" });
    await fireEvent.update(screen.getByLabelText("待命车辆"), "vehicle-standby");
    await fireEvent.click(screen.getByRole("button", { name: "上报待命位置" }));
    await fireEvent.click(await screen.findByRole("button", { name: "模拟确认待命" }));

    await waitFor(() => expect(feedbackApi.success).toHaveBeenCalledWith("待命位置已上报，已保存服务区外位置；车辆快照是否推进以接口返回为准"));
    expect(feedbackApi.error).not.toHaveBeenCalled();
    expect(screen.queryByLabelText("待命位置上报面板")).not.toBeInTheDocument();
  });

  it("keeps the standby panel open and reports the user-facing error when reporting fails", async () => {
    setDispatcherSession();
    vehicleLocationApi.reportVehicleStandbyLocation.mockRejectedValue(new Error("unexpected failure"));
    render(VehicleLocationHistoryPage);

    await screen.findByRole("option", { name: "甘G00856D · IDLE · 可调度" });
    await fireEvent.update(screen.getByLabelText("待命车辆"), "vehicle-standby");
    await fireEvent.click(screen.getByRole("button", { name: "上报待命位置" }));
    await fireEvent.click(await screen.findByRole("button", { name: "模拟确认待命" }));

    await waitFor(() => expect(feedbackApi.error).toHaveBeenCalledWith("待命位置上报失败"));
    expect(screen.getByLabelText("待命位置上报面板")).toBeInTheDocument();
  });

  it("identifies a replayed standby report without warnings", async () => {
    setDispatcherSession();
    vehicleLocationApi.reportVehicleStandbyLocation.mockResolvedValue(locationReportResponse({ replayed: true }));
    render(VehicleLocationHistoryPage);

    await screen.findByRole("option", { name: "甘G00856D · IDLE · 可调度" });
    await fireEvent.update(screen.getByLabelText("待命车辆"), "vehicle-standby");
    await fireEvent.click(screen.getByRole("button", { name: "上报待命位置" }));
    await fireEvent.click(await screen.findByRole("button", { name: "模拟确认待命" }));

    await waitFor(() => expect(feedbackApi.info).toHaveBeenCalledWith("待命位置已上报（重复提交已复用）"));
    expect(feedbackApi.success).not.toHaveBeenCalled();
    expect(screen.queryByLabelText("待命位置上报面板")).not.toBeInTheDocument();
  });

  it("does not render standby reporting controls for an operator without LOCATION_REPORT", () => {
    authStore.setSessionForTest({
      accessToken: "operator-token",
      user: { id: "operator-1", username: "operator01", roles: ["OPERATOR"], mustChangePassword: false }
    });

    render(VehicleLocationHistoryPage);

    expect(screen.queryByRole("button", { name: "上报待命位置" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("待命车辆")).not.toBeInTheDocument();
    expect(vehicleLocationApi.listLocationReportVehicles).not.toHaveBeenCalled();
    expect(resourceApi.listServiceAreas).not.toHaveBeenCalled();
    expect(resourceApi.listVirtualStops).not.toHaveBeenCalled();
  });

  it("disables standby reporting when no enabled service area is available", async () => {
    setDispatcherSession();
    resourceApi.listServiceAreas.mockResolvedValue([{ ...serviceArea(), enabled: false }]);
    render(VehicleLocationHistoryPage);

    expect(await screen.findByText("未找到已启用服务区，无法校验待命位置")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "上报待命位置" })).toBeDisabled();
  });
});

function setDispatcherSession() {
  authStore.setSessionForTest({
    accessToken: "dispatcher-token",
    user: { id: "dispatcher-1", username: "dispatcher01", roles: ["DISPATCHER"], mustChangePassword: false }
  });
}

function locationReportVehicles() {
  return [
    { vehicleId: "vehicle-standby", plateNumber: "甘G00856D", currentStatus: "IDLE", dispatchable: true, latestLocation: null },
    { vehicleId: "vehicle-busy", plateNumber: "甘G00857D", currentStatus: "IN_SERVICE", dispatchable: false, latestLocation: { longitude: 105.25, latitude: 35.21, standardizedAddress: "通渭县客运中心", source: "MANUAL_DISPATCHER", coordinateSystem: "GCJ02", driverReportedAt: "2026-07-26T01:20:00Z", recordedAt: "2026-07-26T01:21:00Z", eventId: "event-latest" } },
    { vehicleId: "vehicle-corrupt", plateNumber: "甘G00858D", currentStatus: "IDLE", dispatchable: true, latestLocation: { longitude: 105.26, latitude: 35.22, standardizedAddress: "??????-?G00858D", source: "MANUAL_DISPATCHER", coordinateSystem: "GCJ02", driverReportedAt: "2026-07-26T01:20:00Z", recordedAt: "2026-07-26T01:21:00Z", eventId: "event-corrupt" } }
  ];
}

function serviceArea() {
  return { id: "area-1", name: "通渭县试点服务区", boundary: "POLYGON((105.2 35.2,105.3 35.2,105.3 35.3,105.2 35.2))", boundarySource: "MANUAL", boundaryVersion: 1, draftBoundary: null, draftBoundarySource: null, draftBoundaryVersion: 0, publishedAt: "2026-07-26T00:00:00Z", updatedAt: "2026-07-26T00:00:00Z", coordinateSystem: "GCJ02" as const, serviceStart: "06:30", serviceEnd: "19:00", ruleSetId: "rule-1", enabled: true };
}

function virtualStop() {
  return { id: "stop-1", serviceAreaId: "area-1", name: "通渭县待命点", address: "通渭县待命点", location: "POINT(105.2421 35.2103)", longitude: 105.2421, latitude: 35.2103, serviceRadiusMeters: 500, boardingEnabled: true, alightingEnabled: true, safetyNote: "", enabled: true, coordinateSystem: "GCJ-02" as const };
}

function locationReportResponse(overrides: { warnings?: string[]; replayed?: boolean } = {}) {
  return {
    event: { ...locationEvent(), eventType: "MANUAL_REPORT", id: "event-standby", vehicleId: "vehicle-standby" },
    snapshotApplied: true,
    warnings: overrides.warnings ?? [],
    replayed: overrides.replayed ?? false
  };
}

function locationEvent() {
  return {
    id: "event-1",
    vehicleId: "vehicle-1",
    vehicleTaskId: "task-1",
    eventType: "PASSENGER_BOARDED",
    longitude: 104.6378,
    latitude: 35.2109,
    standardizedAddress: "通渭县客运中心",
    source: "MANUAL_DISPATCHER",
    coordinateSystem: "GCJ02",
    driverReportedAt: "2026-07-13T00:33:00Z",
    recordedAt: "2026-07-13T00:36:00Z",
    recordedBy: "dispatcher-1",
    correctsEventId: "event-original",
    snapshotApplied: true
  };
}
