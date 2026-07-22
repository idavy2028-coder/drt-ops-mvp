// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { DispatchRuleSet, ServiceAreaBoundaryView } from "../api/types";

const tileMapRuntime = vi.hoisted(() => {
  const mapHandlers = new Map<string, Array<(event: unknown) => void>>();
  const map = {
    on: vi.fn((event: string, listener: (payload: unknown) => void) => {
      mapHandlers.set(event, [...(mapHandlers.get(event) ?? []), listener]);
      return map;
    }),
    off: vi.fn(),
    remove: vi.fn(),
    pm: {
      disableDraw: vi.fn(),
      globalDrawModeEnabled: vi.fn(() => false)
    }
  };
  const unsubscribe = vi.fn();
  let baseLayerErrorListener: (() => void) | undefined;
  const handle = {
    map,
    baseLayerFailed: false,
    destroy: vi.fn(),
    fitLayers: vi.fn(),
    onBaseLayerError: vi.fn((listener: () => void) => {
      baseLayerErrorListener = listener;
      return unsubscribe;
    }),
    onClick: vi.fn()
  };
  const createTileMap = vi.fn((_container: HTMLElement, _center: unknown, _zoom: number) => handle);

  return {
    mapHandlers,
    map,
    handle,
    createTileMap,
    unsubscribe,
    triggerBaseLayerError: () => baseLayerErrorListener?.()
  };
});

const leaflet = vi.hoisted(() => {
  const layers: Array<{
    addTo: ReturnType<typeof vi.fn>;
    getLatLngs: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
    on: ReturnType<typeof vi.fn>;
    off: ReturnType<typeof vi.fn>;
    pm: { enable: ReturnType<typeof vi.fn>; disable: ReturnType<typeof vi.fn> };
    handlers: Map<string, Array<(event: unknown) => void>>;
  }> = [];
  const polygon = vi.fn((points: unknown) => {
    const handlers = new Map<string, Array<(event: unknown) => void>>();
    const layer = {
      addTo: vi.fn(),
      getLatLngs: vi.fn(() => [points]),
      remove: vi.fn(),
      on: vi.fn((event: string, listener: (payload: unknown) => void) => {
        handlers.set(event, [...(handlers.get(event) ?? []), listener]);
        return layer;
      }),
      off: vi.fn(),
      pm: { enable: vi.fn(), disable: vi.fn() }
    };
    layer.addTo.mockReturnValue(layer);
    layers.push({ ...layer, handlers });
    return layer;
  });

  return { polygon, layers };
});

vi.mock("../maps/tileMapRuntime", () => ({ createTileMap: tileMapRuntime.createTileMap }));
vi.mock("../maps/coordinateTransform", () => ({
  gcj02ToWgs84: vi.fn((point: { longitude: number; latitude: number }) => ({
    longitude: point.longitude - 0.01,
    latitude: point.latitude - 0.02
  })),
  wgs84ToGcj02: vi.fn((point: { longitude: number; latitude: number }) => ({
    longitude: point.longitude + 0.01,
    latitude: point.latitude + 0.02
  }))
}));
vi.mock("leaflet", () => ({ polygon: leaflet.polygon }));
vi.mock("@geoman-io/leaflet-geoman-free", () => ({}));

import ServiceAreaMapEditor from "./ServiceAreaMapEditor.vue";

const serviceArea: ServiceAreaBoundaryView = {
  id: "area-1",
  name: "通渭县试点服务区",
  boundaryWkt: null,
  boundarySource: null,
  boundaryVersion: 0,
  draftBoundaryWkt: null,
  draftBoundarySource: null,
  draftBoundaryVersion: 0,
  publishedAt: null,
  updatedAt: null,
  coordinateSystem: "GCJ02"
};

const ruleSet = {
  id: "rule-1",
  name: "通渭县试点规则组"
} as DispatchRuleSet;

function renderEditor(overrides: Partial<ServiceAreaBoundaryView> = {}) {
  return render(ServiceAreaMapEditor, {
    props: { serviceArea: { ...serviceArea, ...overrides }, readonly: false }
  });
}

function renderBootstrapEditor(ruleSets: DispatchRuleSet[] = [ruleSet]) {
  return render(ServiceAreaMapEditor, {
    props: { serviceArea: undefined, ruleSets, readonly: false }
  });
}

describe("ServiceAreaMapEditor", () => {
  afterEach(() => {
    cleanup();
    tileMapRuntime.mapHandlers.clear();
    tileMapRuntime.createTileMap.mockClear();
    tileMapRuntime.handle.destroy.mockClear();
    tileMapRuntime.handle.fitLayers.mockClear();
    tileMapRuntime.handle.onBaseLayerError.mockClear();
    tileMapRuntime.map.on.mockClear();
    tileMapRuntime.map.off.mockClear();
    tileMapRuntime.map.pm.disableDraw.mockClear();
    tileMapRuntime.map.pm.globalDrawModeEnabled.mockClear();
    leaflet.polygon.mockClear();
    leaflet.layers.length = 0;
    vi.restoreAllMocks();
  });

  it("creates an open tile map and renders an existing GCJ-02 boundary as WGS84", async () => {
    renderEditor({ draftBoundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))" });

    await vi.waitFor(() => expect(tileMapRuntime.createTileMap).toHaveBeenCalled());

    expect(tileMapRuntime.createTileMap.mock.calls[0]?.[0]).toBe(screen.getByLabelText("服务区电子围栏地图"));
    expect(leaflet.polygon).toHaveBeenCalledWith(
      [[34.98, 104.99], [34.98, 105.99], [35.98, 104.99], [34.98, 104.99]],
      expect.any(Object)
    );
  });

  it("places the service area map in a dedicated full-width panel above the editor controls", () => {
    const { container } = renderBootstrapEditor();

    const mapPanel = container.querySelector(".editor-map-panel");
    const controls = container.querySelector(".editor-controls");

    expect(mapPanel).toContainElement(screen.getByLabelText("服务区电子围栏地图"));
    expect(mapPanel?.compareDocumentPosition(controls!)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  it("converts a Geoman-drawn WGS84 polygon to a closed GCJ-02 WKT draft", async () => {
    const { emitted } = renderEditor();
    await vi.waitFor(() => expect(tileMapRuntime.createTileMap).toHaveBeenCalled());

    const drawnLayer = {
      getLatLngs: () => [[
        { lat: 34.98, lng: 104.99 },
        { lat: 34.98, lng: 105.99 },
        { lat: 35.98, lng: 104.99 }
      ]],
      on: vi.fn(),
      off: vi.fn(),
      remove: vi.fn(),
      pm: { enable: vi.fn(), disable: vi.fn() }
    };
    tileMapRuntime.mapHandlers.get("pm:create")?.forEach((listener) => listener({ layer: drawnLayer }));

    await fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));

    expect(emitted()["save-boundary"]).toEqual([[
      { boundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))" }
    ]]);
  });

  it("keeps the current Geoman layer on edit and saves its updated GCJ-02 WKT", async () => {
    const { emitted } = renderEditor();
    await vi.waitFor(() => expect(tileMapRuntime.createTileMap).toHaveBeenCalled());

    let path = [
      { lat: 34.98, lng: 104.99 },
      { lat: 34.98, lng: 105.99 },
      { lat: 35.98, lng: 104.99 }
    ];
    const handlers = new Map<string, Array<(event: unknown) => void>>();
    const drawnLayer = {
      getLatLngs: () => [path],
      on: vi.fn((event: string, listener: (payload: unknown) => void) => {
        handlers.set(event, [...(handlers.get(event) ?? []), listener]);
      }),
      off: vi.fn(),
      remove: vi.fn(),
      pm: { enable: vi.fn(), disable: vi.fn() }
    };
    tileMapRuntime.mapHandlers.get("pm:create")?.forEach((listener) => listener({ layer: drawnLayer }));

    path = [
      { lat: 35.98, lng: 105.99 },
      { lat: 35.98, lng: 106.99 },
      { lat: 36.98, lng: 105.99 }
    ];
    handlers.get("pm:change")?.forEach((listener) => listener({ layer: drawnLayer }));
    await fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));

    expect(drawnLayer.remove).not.toHaveBeenCalled();
    expect(emitted()["save-boundary"]).toEqual([[
      { boundaryWkt: "POLYGON((106 36, 107 36, 106 37, 106 36))" }
    ]]);
  });

  it("shows the open tile warning without disabling boundary text save", async () => {
    const { emitted } = renderEditor();
    await vi.waitFor(() => expect(tileMapRuntime.createTileMap).toHaveBeenCalled());

    tileMapRuntime.triggerBaseLayerError();
    await fireEvent.update(screen.getByLabelText("服务区边界草稿"), "POLYGON((105 35, 106 35, 105 36, 105 35))");
    await fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));

    expect(screen.getByText("开放底图暂不可用")).toBeInTheDocument();
    expect(emitted()["save-boundary"]).toEqual([[
      { boundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))" }
    ]]);
  });

  it("emits a district import request", async () => {
    const { emitted } = renderEditor();

    await fireEvent.click(screen.getByRole("button", { name: "导入通渭县边界" }));

    expect(emitted()["import-district"]).toEqual([["甘肃省定西市通渭县"]]);
  });

  it("requires explicit confirmation before publishing", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const { emitted } = renderEditor({
      draftBoundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
      draftBoundaryVersion: 1
    });

    await fireEvent.click(screen.getByRole("button", { name: "发布并启用" }));

    expect(window.confirm).toHaveBeenCalled();
    expect(emitted().publish).toEqual([[]]);
  });

  it("shows the published state and prevents repeat publishing when the draft matches the published boundary", () => {
    renderEditor({
      boundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
      boundaryVersion: 1,
      draftBoundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
      draftBoundaryVersion: 1,
      publishedAt: "2026-07-22T01:00:00Z"
    });

    expect(screen.getByText("已发布")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "已发布并启用" })).toBeDisabled();
  });

  it("marks a newer draft as pending and allows it to be published", () => {
    renderEditor({
      boundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
      boundaryVersion: 1,
      draftBoundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
      draftBoundaryVersion: 2,
      publishedAt: "2026-07-22T01:00:00Z"
    });

    expect(screen.getByText("草稿待发布")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "发布并启用" })).toBeEnabled();
  });

  it("requires confirmation before editing an already published boundary", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    renderEditor({
      boundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
      boundaryVersion: 1,
      draftBoundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
      draftBoundaryVersion: 1,
      publishedAt: "2026-07-22T01:00:00Z"
    });

    await vi.waitFor(() => expect(tileMapRuntime.createTileMap).toHaveBeenCalled());
    await fireEvent.click(screen.getByRole("button", { name: "编辑边界" }));

    expect(confirm).toHaveBeenCalledWith(expect.stringContaining("草稿"));
    expect(screen.queryByRole("button", { name: "取消编辑" })).not.toBeInTheDocument();
  });

  it("shows unsaved draft feedback and restores the published boundary when editing is cancelled", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    renderEditor({
      boundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
      boundaryVersion: 1,
      draftBoundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
      draftBoundaryVersion: 1,
      publishedAt: "2026-07-22T01:00:00Z"
    });

    await vi.waitFor(() => expect(tileMapRuntime.createTileMap).toHaveBeenCalled());
    await fireEvent.click(screen.getByRole("button", { name: "编辑边界" }));
    await fireEvent.update(
      screen.getByLabelText("服务区边界草稿"),
      "POLYGON((105.1 35.1, 106.1 35.1, 105.1 36.1, 105.1 35.1))"
    );

    await vi.waitFor(() => expect(screen.getByText("边界修改尚未保存为草稿，当前已发布服务区仍按已发布边界运行。")).toBeInTheDocument());
    await fireEvent.click(screen.getByRole("button", { name: "取消编辑" }));

    expect(screen.getByLabelText("服务区边界草稿")).toHaveValue("POLYGON((105 35, 106 35, 105 36, 105 35))");
    expect(screen.queryByText("边界修改尚未保存为草稿，当前已发布服务区仍按已发布边界运行。")).not.toBeInTheDocument();
  });

  it("syncs a Geoman layer change to the unsaved draft feedback", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    renderEditor({
      boundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
      boundaryVersion: 1,
      draftBoundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
      draftBoundaryVersion: 1,
      publishedAt: "2026-07-22T01:00:00Z"
    });

    await vi.waitFor(() => expect(tileMapRuntime.createTileMap).toHaveBeenCalled());
    await fireEvent.click(screen.getByRole("button", { name: "编辑边界" }));
    const layer = leaflet.layers[0]!;
    layer.getLatLngs.mockReturnValue([[{ lat: 35.08, lng: 105.08 }, { lat: 35.08, lng: 106.08 }, { lat: 36.08, lng: 105.08 }]]);
    layer.handlers.get("pm:change")?.forEach((listener) => listener({ layer }));

    await vi.waitFor(() => expect(screen.getByText("边界修改尚未保存为草稿，当前已发布服务区仍按已发布边界运行。")).toBeInTheDocument());
  });

  it("ends the local edit session after the parent reports a successful save or publish", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const props = {
      serviceArea: {
        ...serviceArea,
        boundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
        boundaryVersion: 1,
        draftBoundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
        draftBoundaryVersion: 1,
        publishedAt: "2026-07-22T01:00:00Z"
      },
      readonly: false,
      feedback: ""
    };
    const view = render(ServiceAreaMapEditor, { props });

    await vi.waitFor(() => expect(tileMapRuntime.createTileMap).toHaveBeenCalled());
    await fireEvent.click(screen.getByRole("button", { name: "编辑边界" }));
    expect(screen.getByRole("button", { name: "取消编辑" })).toBeInTheDocument();

    await view.rerender({ ...props, feedback: "服务区已发布并启用。" });

    expect(screen.queryByRole("button", { name: "取消编辑" })).not.toBeInTheDocument();
  });

  it("does not stop the global draw tool when saving an existing polygon edit", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const props = {
      serviceArea: {
        ...serviceArea,
        boundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
        boundaryVersion: 1,
        draftBoundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
        draftBoundaryVersion: 1,
        publishedAt: "2026-07-22T01:00:00Z"
      },
      readonly: false,
      feedback: ""
    };
    const view = render(ServiceAreaMapEditor, { props });

    await vi.waitFor(() => expect(tileMapRuntime.createTileMap).toHaveBeenCalled());
    await fireEvent.click(screen.getByRole("button", { name: "编辑边界" }));
    tileMapRuntime.map.pm.disableDraw.mockClear();

    await view.rerender({ ...props, feedback: "服务区草稿已保存。" });

    await vi.waitFor(() => expect(screen.queryByRole("button", { name: "取消编辑" })).not.toBeInTheDocument());
    expect(tileMapRuntime.map.pm.disableDraw).not.toHaveBeenCalled();
  });

  it("shows the bootstrap defaults and disables creation until a boundary is provided", () => {
    renderBootstrapEditor();

    expect(screen.getByLabelText("服务区名称")).toHaveValue("通渭县试点服务区");
    expect(screen.getByLabelText("运营开始时间")).toHaveValue("06:30");
    expect(screen.getByLabelText("运营结束时间")).toHaveValue("19:00");
    expect(screen.getByLabelText("调度规则组")).toHaveValue("rule-1");
    expect(screen.getByRole("button", { name: "创建服务区草稿" })).toBeDisabled();
  });

  it("emits a create request with the WKT bootstrap form values", async () => {
    const { emitted } = renderBootstrapEditor();
    await fireEvent.update(
      screen.getByLabelText("服务区边界草稿"),
      "POLYGON((105.20 35.18,105.30 35.18,105.30 35.26,105.20 35.18))"
    );
    await fireEvent.click(screen.getByRole("button", { name: "创建服务区草稿" }));

    expect(emitted().create).toEqual([[{
      name: "通渭县试点服务区",
      boundaryWkt: "POLYGON((105.20 35.18,105.30 35.18,105.30 35.26,105.20 35.18))",
      serviceStart: "06:30:00",
      serviceEnd: "19:00:00",
      ruleSetId: "rule-1"
    }]]);
  });

  it("blocks bootstrap creation when no dispatch rule set exists", () => {
    renderBootstrapEditor([]);

    expect(screen.getByText("请先创建调度规则组，再创建服务区。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "创建服务区草稿" })).toBeDisabled();
  });

  it("converts a GeoJSON Polygon to closed WKT for bootstrap creation", async () => {
    const { emitted } = renderBootstrapEditor();
    await fireEvent.update(screen.getByLabelText("边界格式"), "geoJson");
    await fireEvent.update(
      screen.getByLabelText("服务区边界草稿"),
      JSON.stringify({
        type: "Polygon",
        coordinates: [[[105.2, 35.18], [105.3, 35.18], [105.3, 35.26]]]
      })
    );
    await fireEvent.click(screen.getByRole("button", { name: "创建服务区草稿" }));

    expect(emitted().create).toEqual([[expect.objectContaining({
      boundaryWkt: "POLYGON((105.2 35.18, 105.3 35.18, 105.3 35.26, 105.2 35.18))"
    })]]);
  });

  it("shows a Chinese error and does not emit create for invalid GeoJSON", async () => {
    const { emitted } = renderBootstrapEditor();
    await fireEvent.update(screen.getByLabelText("边界格式"), "geoJson");
    await fireEvent.update(screen.getByLabelText("服务区边界草稿"), "{\"type\":\"LineString\"}");
    await fireEvent.click(screen.getByRole("button", { name: "创建服务区草稿" }));

    expect(screen.getByText("GeoJSON 必须是包含至少三个坐标点的 Polygon。")).toBeInTheDocument();
    expect(emitted().create).toBeUndefined();
  });
});
