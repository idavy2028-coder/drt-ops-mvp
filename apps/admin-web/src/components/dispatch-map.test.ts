// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { VehicleLocationEventView, VehicleLocationSnapshotItem } from "../api/types";
import DispatchMap from "./DispatchMap.vue";

const amapLoader = vi.hoisted(() => ({ loadAmap: vi.fn() }));
vi.mock("../maps/amapLoader", () => amapLoader);

describe("DispatchMap", () => {
  afterEach(() => cleanup());
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

  it("allows each operational map layer to be toggled independently in degraded mode", async () => {
    render(DispatchMap, {
      props: {
        amapEnabled: false,
        serviceArea: {
          id: "area-1", name: "通渭县试点服务区", boundary: "POLYGON((105.2 35.2,105.3 35.2,105.3 35.3,105.2 35.2))",
          boundarySource: "MANUAL", boundaryVersion: 1, draftBoundary: null, draftBoundarySource: null, draftBoundaryVersion: 0,
          publishedAt: "2026-07-18T00:00:00Z", updatedAt: "2026-07-18T00:00:00Z", coordinateSystem: "GCJ02", serviceStart: "06:30", serviceEnd: "19:00", ruleSetId: "rule-1", enabled: true
        },
        stops: [{ id: "stop-1", serviceAreaId: "area-1", name: "县医院北门", location: "POINT(105.241 35.211)", longitude: 105.241, latitude: 35.211, serviceRadiusMeters: 500, boardingEnabled: true, alightingEnabled: true, safetyNote: "安全", enabled: true, coordinateSystem: "GCJ-02", source: "MANUAL" }]
      }
    });

    expect(screen.getByLabelText("服务区图层")).toBeChecked();
    expect(screen.getByLabelText("虚拟站点图层")).toBeChecked();
    expect(screen.getByText("县医院北门")).toBeInTheDocument();

    await fireEvent.click(screen.getByLabelText("虚拟站点图层"));

    expect(screen.getByLabelText("虚拟站点图层")).not.toBeChecked();
    expect(screen.queryByText("县医院北门")).not.toBeInTheDocument();
    expect(screen.getByLabelText("服务区图层")).toBeChecked();
  });

  it("does not cover an available AMap canvas with the degraded service-area summary", async () => {
    const map = { destroy: vi.fn(), setFitView: vi.fn() };
    const overlay = { setMap: vi.fn() };
    amapLoader.loadAmap.mockResolvedValue({ provider: "AMAP", enabled: true, coordinateSystem: "GCJ-02", AMap: { Map: function () { return map; }, Polygon: function () { return overlay; }, Marker: function () { return overlay; }, Polyline: function () { return overlay; } } });
    render(DispatchMap, {
      props: { amapEnabled: true, serviceArea: { id: "area-1", name: "通渭县试点服务区", boundary: "POLYGON((105.2 35.2,105.3 35.2,105.3 35.3,105.2 35.2))", boundarySource: "MANUAL", boundaryVersion: 1, draftBoundary: null, draftBoundarySource: null, draftBoundaryVersion: 0, publishedAt: null, updatedAt: null, coordinateSystem: "GCJ02", serviceStart: "06:30", serviceEnd: "19:00", ruleSetId: "rule-1", enabled: true } }
    });

    await waitFor(() => expect(amapLoader.loadAmap).toHaveBeenCalled());
    expect(screen.queryByText("通渭县试点服务区")).not.toBeInTheDocument();
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
