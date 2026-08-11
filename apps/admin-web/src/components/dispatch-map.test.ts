// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ServiceArea, VehicleLocationEventView, VehicleLocationSnapshotItem, VehicleTask, VirtualStop } from "../api/types";

const tileMapRuntime = vi.hoisted(() => {
  let baseLayerErrorListener: (() => void) | undefined;
  const unsubscribeBaseLayerError = vi.fn();
  const handle = {
    map: {},
    baseLayerFailed: false,
    destroy: vi.fn(),
    fitLayers: vi.fn(),
    focusPoint: vi.fn(),
    invalidateSize: vi.fn(),
    onBaseLayerError: vi.fn((listener: () => void) => {
      baseLayerErrorListener = listener;
      return unsubscribeBaseLayerError;
    }),
    onClick: vi.fn(() => vi.fn())
  };

  return {
    handle,
    createTileMap: vi.fn(() => handle),
    unsubscribeBaseLayerError,
    triggerBaseLayerError: () => baseLayerErrorListener?.()
  };
});

const leaflet = vi.hoisted(() => {
  type Layer = {
    addTo: ReturnType<typeof vi.fn>;
    bindPopup: ReturnType<typeof vi.fn>;
    bindTooltip: ReturnType<typeof vi.fn>;
    handlers: Map<string, () => void>;
    on: ReturnType<typeof vi.fn>;
    openPopup: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
  };
  const layers: Layer[] = [];
  const markerLayers: Layer[] = [];
  function createLayer(kind: "marker" | "polygon" | "polyline") {
    const handlers = new Map<string, () => void>();
    const layer = {
      addTo: vi.fn(),
      bindPopup: vi.fn(),
      bindTooltip: vi.fn(),
      handlers,
      on: vi.fn((event: string, handler: () => void) => {
        handlers.set(event, handler);
        return layer;
      }),
      openPopup: vi.fn(),
      remove: vi.fn()
    };
    layer.addTo.mockReturnValue(layer);
    layer.bindPopup.mockReturnValue(layer);
    layer.bindTooltip.mockReturnValue(layer);
    layers.push(layer);
    if (kind === "marker") markerLayers.push(layer);
    return layer;
  }

  return {
    layers,
    markerLayers,
    divIcon: vi.fn((options: Record<string, unknown>) => options),
    marker: vi.fn((..._args: unknown[]) => createLayer("marker")),
    polygon: vi.fn((..._args: unknown[]) => createLayer("polygon")),
    polyline: vi.fn((..._args: unknown[]) => createLayer("polyline"))
  };
});

const resizeObserver = vi.hoisted(() => {
  let callback: (() => void) | undefined;
  const observe = vi.fn();
  const disconnect = vi.fn();
  class ResizeObserverMock {
    constructor(listener: () => void) {
      callback = listener;
    }
    observe = observe;
    disconnect = disconnect;
  }
  return { ResizeObserverMock, observe, disconnect, trigger: () => callback?.() };
});

vi.mock("../maps/tileMapRuntime", () => ({ createTileMap: tileMapRuntime.createTileMap }));
vi.mock("../maps/coordinateTransform", () => ({
  toLeafletLatLng: vi.fn((point: { longitude: number; latitude: number }) => [point.latitude - 0.02, point.longitude - 0.01])
}));
vi.mock("leaflet", () => leaflet);

import DispatchMap from "./DispatchMap.vue";

describe("DispatchMap", () => {
  beforeEach(() => {
    vi.stubGlobal("ResizeObserver", resizeObserver.ResizeObserverMock);
    vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
      callback(0);
      return 1;
    });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    leaflet.layers.length = 0;
    leaflet.markerLayers.length = 0;
    vi.unstubAllGlobals();
  });

  it("renders the four open-tile operational layers with a manual event chain ordered by driver feedback time", async () => {
    renderMap();

    await waitFor(() => expect(tileMapRuntime.createTileMap).toHaveBeenCalled());

    expect(leaflet.polygon).toHaveBeenCalledTimes(1);
    expect(leaflet.marker).toHaveBeenCalledTimes(3);
    expect(leaflet.polyline).toHaveBeenCalledTimes(2);
    expect(leaflet.marker).toHaveBeenLastCalledWith(
      [expect.closeTo(35.1909), expect.closeTo(104.6278)],
      expect.objectContaining({ icon: expect.anything(), title: "甘G-T001" })
    );
    expect(leaflet.divIcon).toHaveBeenCalledWith(expect.objectContaining({ className: expect.stringContaining("is-active") }));
    const manualChainPoints = leaflet.polyline.mock.calls[1]?.[0] as unknown[];
    expect(manualChainPoints).toEqual([
      [expect.closeTo(35.192), expect.closeTo(104.628)],
      [expect.closeTo(35.191), expect.closeTo(104.627)]
    ]);
    expect(screen.queryByLabelText("车辆位置 甘G-T001")).not.toBeInTheDocument();
    expect(screen.getByText("离散节点链，仅表示人工上报节点，不是实际行驶轨迹")).toBeInTheDocument();
  });

  it("keeps operational layers available without rendering fake vehicle cards when the base tile layer fails", async () => {
    renderMap();
    await waitFor(() => expect(tileMapRuntime.handle.onBaseLayerError).toHaveBeenCalled());

    tileMapRuntime.triggerBaseLayerError();

    await waitFor(() => expect(screen.getByText("开放底图暂不可用")).toBeInTheDocument());
    expect(screen.queryByLabelText("车辆位置 甘G-T001")).not.toBeInTheDocument();
    expect(leaflet.polygon).toHaveBeenCalledTimes(1);
    expect(leaflet.marker).toHaveBeenCalledTimes(3);
  });

  it("allows each map layer to be toggled independently and removes its old Leaflet layers", async () => {
    renderMap();
    await waitFor(() => expect(leaflet.marker).toHaveBeenCalledTimes(3));
    const firstStopLayer = leaflet.layers[1];
    expect(tileMapRuntime.handle.fitLayers).toHaveBeenCalledTimes(1);

    await fireEvent.click(screen.getByLabelText("虚拟站点图层"));

    expect(screen.getByLabelText("虚拟站点图层")).not.toBeChecked();
    expect(firstStopLayer.remove).toHaveBeenCalled();
    expect(leaflet.polygon).toHaveBeenCalledTimes(2);
    expect(leaflet.marker).toHaveBeenCalledTimes(4);
    expect(leaflet.polyline).toHaveBeenCalledTimes(4);
    expect(screen.getByLabelText("服务区图层")).toBeChecked();
    expect(screen.getByLabelText("车辆位置图层")).toBeChecked();
    expect(screen.queryByLabelText("任务路线图层")).not.toBeInTheDocument();
    expect(tileMapRuntime.handle.fitLayers).toHaveBeenCalledTimes(1);
  });

  it("renders a static fallback summary only when tile map initialization fails", async () => {
    tileMapRuntime.createTileMap.mockImplementationOnce(() => {
      throw new Error("map init failed");
    });
    renderMap();

    expect(await screen.findByText("开放底图暂不可用")).toBeInTheDocument();
    expect(screen.getByText("通渭县试点服务区")).toBeInTheDocument();
    expect(screen.queryByLabelText("车辆位置 甘G-T001")).not.toBeInTheDocument();
  });

  it("shows a short task number in the vehicle popup and emits marker selection", async () => {
    const view = renderMap();
    await waitFor(() => expect(leaflet.markerLayers).toHaveLength(3));
    const vehicleMarker = leaflet.markerLayers[2];
    const popup = vehicleMarker.bindPopup.mock.calls[0]?.[0] as string;

    expect(popup).toContain("任务 12345678");
    expect(popup).not.toContain("12345678-1234-4234-8234-123456789abc");
    vehicleMarker.handlers.get("click")?.();
    expect(view.emitted("selectVehicle")).toEqual([["vehicle-1"]]);
  });

  it("keeps the initial overview and focuses again for every explicit vehicle request", async () => {
    const view = renderMap({ selectedVehicleId: "vehicle-1", vehicleFocusRequest: 0 });
    await waitFor(() => expect(leaflet.markerLayers).toHaveLength(3));

    expect(tileMapRuntime.handle.focusPoint).not.toHaveBeenCalled();

    await view.rerender(mapProps({ selectedVehicleId: "vehicle-1", vehicleFocusRequest: 1 }));
    await waitFor(() => expect(tileMapRuntime.handle.focusPoint).toHaveBeenCalledWith({ longitude: 104.6378, latitude: 35.2109 }));
    expect(tileMapRuntime.handle.focusPoint).toHaveBeenCalledWith({ longitude: 104.6378, latitude: 35.2109 });
    expect(leaflet.markerLayers[leaflet.markerLayers.length - 1].openPopup).toHaveBeenCalled();

    tileMapRuntime.handle.focusPoint.mockClear();
    const markerCountAfterFocus = leaflet.markerLayers.length;
    const movedLocation = latestLocation();
    movedLocation.latestLocation.longitude = 104.6401;
    await view.rerender(mapProps({ selectedVehicleId: "vehicle-1", vehicleFocusRequest: 1, locations: [movedLocation] }));
    await waitFor(() => expect(leaflet.markerLayers.length).toBeGreaterThan(markerCountAfterFocus));
    expect(tileMapRuntime.handle.focusPoint).not.toHaveBeenCalled();

    await view.rerender(mapProps({ selectedVehicleId: "vehicle-1", vehicleFocusRequest: 2, locations: [movedLocation] }));
    await waitFor(() => expect(tileMapRuntime.handle.focusPoint).toHaveBeenCalledWith({ longitude: 104.6401, latitude: 35.2109 }));
  });

  it("invalidates the Leaflet canvas when the map container changes size", async () => {
    renderMap();
    await waitFor(() => expect(tileMapRuntime.createTileMap).toHaveBeenCalled());

    resizeObserver.trigger();

    expect(tileMapRuntime.handle.invalidateSize).toHaveBeenCalled();
  });

  it("releases tile subscriptions, layers and the map when unmounted", async () => {
    const view = renderMap();
    await waitFor(() => expect(leaflet.marker).toHaveBeenCalledTimes(3));

    view.unmount();

    expect(tileMapRuntime.unsubscribeBaseLayerError).toHaveBeenCalled();
    expect(resizeObserver.disconnect).toHaveBeenCalled();
    expect(tileMapRuntime.handle.destroy).toHaveBeenCalled();
    expect(leaflet.layers.every((layer) => layer.remove.mock.calls.length > 0)).toBe(true);
  });
});

function renderMap(overrides: { selectedVehicleId?: string; vehicleFocusRequest?: number } = {}) {
  return render(DispatchMap, {
    props: mapProps(overrides)
  });
}

function mapProps(overrides: { selectedVehicleId?: string; vehicleFocusRequest?: number; locations?: VehicleLocationSnapshotItem[] } = {}) {
  return {
    serviceArea,
    stops,
    locations: overrides.locations ?? [latestLocation()],
    selectedVehicleId: overrides.selectedVehicleId,
    vehicleFocusRequest: overrides.vehicleFocusRequest,
    selectedTask,
    eventChain: [
      locationEvent("PASSENGER_BOARDED", "2026-07-13T00:42:00Z", 104.637, 35.212),
      locationEvent("TASK_STARTED", "2026-07-13T00:32:00Z", 104.638, 35.211)
    ]
  };
}

const serviceArea: ServiceArea = {
  id: "area-1", name: "通渭县试点服务区", boundary: "POLYGON((105.2 35.2,105.3 35.2,105.3 35.3,105.2 35.2))",
  boundarySource: "MANUAL", boundaryVersion: 1, draftBoundary: null, draftBoundarySource: null, draftBoundaryVersion: 0,
  publishedAt: "2026-07-18T00:00:00Z", updatedAt: "2026-07-18T00:00:00Z", coordinateSystem: "GCJ02", serviceStart: "06:30", serviceEnd: "19:00", ruleSetId: "rule-1", enabled: true
};

const stops: VirtualStop[] = [
  { id: "stop-1", serviceAreaId: "area-1", name: "县医院北门", location: "POINT(105.241 35.211)", longitude: 105.241, latitude: 35.211, serviceRadiusMeters: 500, boardingEnabled: true, alightingEnabled: true, safetyNote: "安全", enabled: true, coordinateSystem: "GCJ-02", source: "MANUAL" },
  { id: "stop-2", serviceAreaId: "area-1", name: "客运站", location: "POINT(105.251 35.221)", longitude: 105.251, latitude: 35.221, serviceRadiusMeters: 500, boardingEnabled: true, alightingEnabled: true, safetyNote: "安全", enabled: true, coordinateSystem: "GCJ-02", source: "MANUAL" }
];

const selectedTask: VehicleTask = {
  id: "task-1", vehicleId: "vehicle-1", driverId: "driver-1", status: "IN_SERVICE", plannedStartAt: "2026-07-13T00:30:00Z",
  stops: [
    { id: "task-stop-1", virtualStopId: "stop-1", sequenceNumber: 1, stopType: "BOARDING", plannedArrivalAt: "2026-07-13T00:35:00Z", status: "PLANNED" },
    { id: "task-stop-2", virtualStopId: "stop-2", sequenceNumber: 2, stopType: "ALIGHTING", plannedArrivalAt: "2026-07-13T00:45:00Z", status: "PLANNED" }
  ]
};

function latestLocation(): VehicleLocationSnapshotItem {
  return {
    vehicleId: "vehicle-1", plateNumber: "甘G-T001", currentStatus: "IN_SERVICE",
    latestLocation: { longitude: 104.6378, latitude: 35.2109, standardizedAddress: "通渭县客运中心", source: "MANUAL_DISPATCHER", coordinateSystem: "GCJ02", driverReportedAt: "2026-07-13T00:33:00Z", recordedAt: "2026-07-13T00:35:00Z", eventId: "event-1", vehicleTaskId: "12345678-1234-4234-8234-123456789abc" }
  };
}

function locationEvent(eventType: string, driverReportedAt: string, longitude: number, latitude: number): VehicleLocationEventView {
  return {
    id: `event-${eventType}`, vehicleId: "vehicle-1", vehicleTaskId: "task-1", eventType, longitude, latitude,
    standardizedAddress: "通渭县客运中心", source: "MANUAL_DISPATCHER", coordinateSystem: "GCJ02", driverReportedAt, recordedAt: driverReportedAt, recordedBy: "dispatcher-1", snapshotApplied: true
  };
}
