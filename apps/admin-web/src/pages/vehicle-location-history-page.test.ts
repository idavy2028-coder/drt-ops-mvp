// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import VehicleLocationHistoryPage from "./VehicleLocationHistoryPage.vue";

const vehicleLocationApi = vi.hoisted(() => ({
  listVehicleLocationEvents: vi.fn(),
  exportVehicleLocationEvents: vi.fn()
}));

vi.mock("../api/vehicleLocations", () => vehicleLocationApi);

describe("VehicleLocationHistoryPage", () => {
  beforeEach(() => {
    authStore.setSessionForTest({
      accessToken: "admin-token",
      user: { id: "admin-1", username: "admin", roles: ["SYSTEM_ADMIN"], mustChangePassword: false }
    });
    vehicleLocationApi.listVehicleLocationEvents.mockResolvedValue([locationEvent()]);
    vehicleLocationApi.exportVehicleLocationEvents.mockResolvedValue(undefined);
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
});

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
