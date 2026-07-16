// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { render, screen, within } from "@testing-library/vue";
import { describe, expect, it } from "vitest";
import type { VehicleLocationEventView, VehicleLocationSnapshotItem } from "../api/types";
import DispatchMap from "./DispatchMap.vue";

describe("DispatchMap", () => {
  it("shows manual vehicle location markers and identifies the node chain as not an actual track", () => {
    render(DispatchMap, {
      props: {
        locations: [latestLocation()],
        eventChain: [locationEvent("TASK_STARTED", "2026-07-13T00:32:00Z"), locationEvent("PASSENGER_BOARDED", "2026-07-13T00:42:00Z")]
      }
    });

    const marker = screen.getByLabelText("车辆位置 甘E-T001");
    expect(within(marker).getByText("甘E-T001")).toBeInTheDocument();
    expect(within(marker).getByText("执行中")).toBeInTheDocument();
    expect(within(marker).getByText("任务 task-1")).toBeInTheDocument();
    expect(within(marker).getByText("最后反馈 07/13 08:33")).toBeInTheDocument();
    expect(within(marker).getByText("人工上报")).toBeInTheDocument();
    expect(screen.getByText("离散节点链，仅表示人工上报节点，不是实际行驶轨迹")).toBeInTheDocument();
    expect(screen.queryByText(/GPS 在线|实时轨迹/)).not.toBeInTheDocument();
  });
});

function latestLocation(): VehicleLocationSnapshotItem {
  return {
    vehicleId: "vehicle-1",
    plateNumber: "甘E-T001",
    currentStatus: "IN_SERVICE",
    latestLocation: {
      longitude: 104.6378,
      latitude: 35.2109,
      standardizedAddress: "通渭县客运中心",
      source: "MANUAL_DISPATCHER",
      coordinateSystem: "GCJ02",
      driverReportedAt: "2026-07-13T00:33:00Z",
      recordedAt: "2026-07-13T00:35:00Z",
      eventId: "event-1",
      vehicleTaskId: "task-1"
    }
  };
}

function locationEvent(eventType: string, driverReportedAt: string): VehicleLocationEventView {
  return {
    id: `event-${eventType}`,
    vehicleId: "vehicle-1",
    vehicleTaskId: "task-1",
    eventType,
    longitude: 104.6378,
    latitude: 35.2109,
    standardizedAddress: "通渭县客运中心",
    source: "MANUAL_DISPATCHER",
    coordinateSystem: "GCJ02",
    driverReportedAt,
    recordedAt: driverReportedAt,
    recordedBy: "dispatcher-1",
    snapshotApplied: true
  };
}
