// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { VirtualStop } from "../api/types";

const tileMapRuntime = vi.hoisted(() => {
  let clickListener: ((point: { longitude: number; latitude: number }) => void) | undefined;
  let baseLayerErrorListener: (() => void) | undefined;
  const unsubscribeClick = vi.fn();
  const unsubscribeBaseLayerError = vi.fn();
  const handle = {
    map: {},
    baseLayerFailed: false,
    destroy: vi.fn(),
    fitLayers: vi.fn(),
    onBaseLayerError: vi.fn((listener: () => void) => {
      baseLayerErrorListener = listener;
      return unsubscribeBaseLayerError;
    }),
    onClick: vi.fn((listener: (point: { longitude: number; latitude: number }) => void) => {
      clickListener = listener;
      return unsubscribeClick;
    })
  };

  return {
    handle,
    createTileMap: vi.fn((_container: HTMLElement, _center: unknown, _zoom: number) => handle),
    unsubscribeClick,
    unsubscribeBaseLayerError,
    triggerClick: (point: { longitude: number; latitude: number }) => clickListener?.(point),
    triggerBaseLayerError: () => baseLayerErrorListener?.()
  };
});

const leaflet = vi.hoisted(() => {
  const markers: Array<{ addTo: ReturnType<typeof vi.fn>; bindTooltip: ReturnType<typeof vi.fn>; remove: ReturnType<typeof vi.fn> }> = [];
  const marker = vi.fn(() => {
    const layer = {
      addTo: vi.fn(),
      bindTooltip: vi.fn(),
      remove: vi.fn()
    };
    layer.addTo.mockReturnValue(layer);
    layer.bindTooltip.mockReturnValue(layer);
    markers.push(layer);
    return layer;
  });

  return { marker, markers };
});

vi.mock("../maps/tileMapRuntime", () => ({ createTileMap: tileMapRuntime.createTileMap }));
vi.mock("../maps/coordinateTransform", () => ({
  toLeafletLatLng: vi.fn((point: { longitude: number; latitude: number }) => [point.latitude - 0.02, point.longitude - 0.01])
}));
vi.mock("leaflet", () => ({ marker: leaflet.marker }));

import VirtualStopMap from "./VirtualStopMap.vue";

const stops: VirtualStop[] = [{
  id: "stop-1",
  serviceAreaId: "area-1",
  name: "县医院",
  address: "通渭县医院",
  location: "POINT(105.24 35.21)",
  longitude: 105.24,
  latitude: 35.21,
  serviceRadiusMeters: 500,
  boardingEnabled: true,
  alightingEnabled: true,
  safetyNote: "",
  enabled: true
}];

function renderMap(overrides: Partial<{ stops: VirtualStop[]; readonly: boolean }> = {}) {
  return render(VirtualStopMap, { props: { stops, readonly: false, ...overrides } });
}

describe("VirtualStopMap", () => {
  afterEach(() => {
    cleanup();
    tileMapRuntime.createTileMap.mockClear();
    tileMapRuntime.handle.destroy.mockClear();
    tileMapRuntime.handle.fitLayers.mockClear();
    tileMapRuntime.handle.onBaseLayerError.mockClear();
    tileMapRuntime.handle.onClick.mockClear();
    tileMapRuntime.unsubscribeClick.mockClear();
    tileMapRuntime.unsubscribeBaseLayerError.mockClear();
    leaflet.marker.mockClear();
    leaflet.markers.length = 0;
  });

  it("creates the default open tile map and renders enabled state tooltips", async () => {
    renderMap();

    await vi.waitFor(() => expect(tileMapRuntime.createTileMap).toHaveBeenCalled());

    expect(tileMapRuntime.createTileMap.mock.calls[0]?.[0]).toBe(screen.getByLabelText("虚拟站点地图，点击可选取站点坐标"));
    expect(leaflet.marker).toHaveBeenCalledWith([expect.closeTo(35.19), expect.closeTo(105.23)], expect.objectContaining({ title: "县医院 · 已启用" }));
    expect(leaflet.markers[0]?.bindTooltip).toHaveBeenCalledWith("县医院 · 已启用", expect.objectContaining({ direction: "top" }));
  });

  it("emits GCJ-02 coordinates from the tile map click callback", async () => {
    const { emitted } = renderMap();
    await vi.waitFor(() => expect(tileMapRuntime.handle.onClick).toHaveBeenCalled());

    tileMapRuntime.triggerClick({ longitude: 105.245, latitude: 35.215 });

    expect(emitted().pick).toEqual([[105.245, 35.215]]);
  });

  it("does not emit a picked point in readonly mode", async () => {
    const { emitted } = renderMap({ readonly: true });
    await vi.waitFor(() => expect(tileMapRuntime.handle.onClick).toHaveBeenCalled());

    tileMapRuntime.triggerClick({ longitude: 105.245, latitude: 35.215 });

    expect(emitted().pick).toBeUndefined();
  });

  it("warns when the open tile base layer fails without hiding the map", async () => {
    renderMap();
    await vi.waitFor(() => expect(tileMapRuntime.handle.onBaseLayerError).toHaveBeenCalled());

    tileMapRuntime.triggerBaseLayerError();

    await vi.waitFor(() => expect(screen.getByText(/开放底图暂不可用/)).toBeInTheDocument());
    expect(screen.getByLabelText("虚拟站点地图，点击可选取站点坐标")).toBeInTheDocument();
  });

  it("removes old markers and redraws markers when the stop list changes", async () => {
    const view = renderMap();
    await vi.waitFor(() => expect(leaflet.marker).toHaveBeenCalledTimes(1));
    const firstMarker = leaflet.markers[0];

    await view.rerender({
      stops: [{ ...stops[0], id: "stop-2", name: "客运站", enabled: false, longitude: 105.25, latitude: 35.22 }],
      readonly: false
    });

    await vi.waitFor(() => expect(leaflet.marker).toHaveBeenCalledTimes(2));
    expect(firstMarker?.remove).toHaveBeenCalled();
    expect(leaflet.marker).toHaveBeenLastCalledWith([expect.closeTo(35.2), expect.closeTo(105.24)], expect.objectContaining({ title: "客运站 · 未启用" }));
  });
});
