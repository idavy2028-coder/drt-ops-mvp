// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";

const leaflet = vi.hoisted(() => {
  const handlers = new Map<string, Array<(event?: unknown) => void>>();
  const map = {
    on: vi.fn((event: string, handler: (event?: unknown) => void) => {
      handlers.set(event, [...(handlers.get(event) ?? []), handler]);
      return map;
    }),
    off: vi.fn(),
    remove: vi.fn(),
    setView: vi.fn(() => map),
    fitBounds: vi.fn()
  };
  const layer = {
    on: vi.fn((event: string, handler: (event?: unknown) => void) => {
      handlers.set(`layer:${event}`, [...(handlers.get(`layer:${event}`) ?? []), handler]);
      return layer;
    }),
    addTo: vi.fn(() => layer),
    remove: vi.fn()
  };

  return {
    handlers,
    map,
    layer,
    createMap: vi.fn(() => map),
    createTileLayer: vi.fn(() => layer)
  };
});

vi.mock("leaflet", () => ({
  map: leaflet.createMap,
  tileLayer: leaflet.createTileLayer
}));

import { createTileMap } from "./tileMapRuntime";

describe("开放瓦片地图运行时", () => {
  afterEach(() => {
    leaflet.handlers.clear();
    leaflet.createMap.mockClear();
    leaflet.createTileLayer.mockClear();
    leaflet.map.on.mockClear();
    leaflet.map.off.mockClear();
    leaflet.map.remove.mockClear();
    leaflet.map.setView.mockClear();
    leaflet.map.fitBounds.mockClear();
    leaflet.layer.on.mockClear();
    leaflet.layer.addTo.mockClear();
    leaflet.layer.remove.mockClear();
    vi.unstubAllEnvs();
  });

  it("默认使用带可见 OSM 归属的开放瓦片", () => {
    const container = document.createElement("div");
    const handle = createTileMap(container, { longitude: 105.2421, latitude: 35.2103 }, 12);

    expect(leaflet.createTileLayer).toHaveBeenCalledWith(
      "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
      expect.objectContaining({ attribution: expect.stringContaining("OpenStreetMap") })
    );
    handle.destroy();
  });

  it("采用运行时瓦片配置", () => {
    vi.stubEnv("VITE_TILE_URL", "https://tiles.example.test/{z}/{x}/{y}.png");
    vi.stubEnv("VITE_TILE_ATTRIBUTION", "Test tiles");
    vi.stubEnv("VITE_TILE_MAX_ZOOM", "15");
    const container = document.createElement("div");

    const handle = createTileMap(container, { longitude: 105.2421, latitude: 35.2103 }, 12);

    expect(leaflet.createTileLayer).toHaveBeenCalledWith(
      "https://tiles.example.test/{z}/{x}/{y}.png",
      expect.objectContaining({ attribution: "Test tiles", maxZoom: 15 })
    );
    handle.destroy();
  });

  it("在瓦片错误时暴露降级状态，点击位置回传 GCJ-02", () => {
    const container = document.createElement("div");
    const handle = createTileMap(container, { longitude: 105.2421, latitude: 35.2103 }, 12);
    const clickListener = vi.fn();
    handle.onClick(clickListener);

    leaflet.handlers.get("layer:tileerror")?.forEach((handler) => handler());
    leaflet.handlers.get("click")?.forEach((handler) => handler({ latlng: { lat: 35.2103, lng: 105.2421 } }));

    expect(handle.baseLayerFailed).toBe(true);
    const reportedPoint = clickListener.mock.calls[0]?.[0];
    expect(reportedPoint.longitude).not.toBe(105.2421);
    expect(reportedPoint.latitude).not.toBe(35.2103);
    handle.destroy();
  });

  it("在瓦片错误时通知订阅者，取消订阅后不再通知", () => {
    const container = document.createElement("div");
    const handle = createTileMap(container, { longitude: 105.2421, latitude: 35.2103 }, 12);
    const listener = vi.fn();
    const unsubscribe = handle.onBaseLayerError(listener);

    leaflet.handlers.get("layer:tileerror")?.forEach((handler) => handler());
    unsubscribe();
    leaflet.handlers.get("layer:tileerror")?.forEach((handler) => handler());

    expect(listener).toHaveBeenCalledTimes(1);
    handle.destroy();
  });
});
