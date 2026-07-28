// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { LocationCandidate, LocationPickerProvider, LocationReportInput, ServiceAreaBoundaryView, VirtualStop } from "../api/types";

const mapApi = vi.hoisted(() => ({ checkServiceAreaContainment: vi.fn() }));
const tileMapRuntime = vi.hoisted(() => {
  let clickListener: ((point: { longitude: number; latitude: number }) => void) | undefined;
  let baseLayerErrorListener: (() => void) | undefined;
  const handle = {
    map: {},
    destroy: vi.fn(),
    fitLayers: vi.fn(),
    onBaseLayerError: vi.fn((listener: () => void) => {
      baseLayerErrorListener = listener;
      return vi.fn();
    }),
    onClick: vi.fn((listener: (point: { longitude: number; latitude: number }) => void) => {
      clickListener = listener;
      return vi.fn();
    })
  };
  return {
    createTileMap: vi.fn(() => handle),
    handle,
    triggerBaseLayerError: () => baseLayerErrorListener?.(),
    triggerClick: (point: { longitude: number; latitude: number }) => clickListener?.(point)
  };
});
const leaflet = vi.hoisted(() => {
  const layer = () => {
    const value = { addTo: vi.fn(), bindTooltip: vi.fn(), remove: vi.fn() };
    value.addTo.mockReturnValue(value);
    value.bindTooltip.mockReturnValue(value);
    return value;
  };
  return { marker: vi.fn(layer), polygon: vi.fn(layer) };
});

vi.mock("../api/map", () => mapApi);
vi.mock("../maps/tileMapRuntime", () => ({ createTileMap: tileMapRuntime.createTileMap }));
vi.mock("../maps/coordinateTransform", () => ({
  toLeafletLatLng: vi.fn((point: { longitude: number; latitude: number }) => [point.latitude, point.longitude])
}));
vi.mock("leaflet", () => ({ marker: leaflet.marker, polygon: leaflet.polygon }));

import LocationReportPanel from "./LocationReportPanel.vue";

function firstSubmitPayload(emitted: unknown): LocationReportInput {
  if (!Array.isArray(emitted) || !Array.isArray(emitted[0])) {
    throw new Error("Expected LocationReportPanel to emit a submit payload");
  }
  return emitted[0][0] as LocationReportInput;
}

describe("LocationReportPanel", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.clearAllMocks();
    tileMapRuntime.createTileMap.mockImplementation(() => tileMapRuntime.handle);
  });

  it("shows the initial location and degraded map hint", () => {
    render(LocationReportPanel, {
      props: {
        actionLabel: "发车",
        initialLocation: candidate(104.6378, 35.2109, "通渭县客运中心"),
        virtualStops: []
      }
    });

    expect(screen.getByLabelText("经度")).toHaveValue(104.6378);
    expect(screen.getByLabelText("纬度")).toHaveValue(35.2109);
    expect(screen.getByLabelText("标准化地址")).toHaveValue("通渭县客运中心");
    expect(screen.getByText("地址搜索服务未配置；可通过虚拟站点、地图点选或经纬度录入位置。")).toBeInTheDocument();
  });

  it("requires driver reported time", async () => {
    render(LocationReportPanel, {
      props: {
        actionLabel: "到站",
        initialLocation: candidate(104.63, 35.21, "通渭县人民医院"),
        virtualStops: []
      }
    });

    await fireEvent.update(screen.getByLabelText("驾驶员反馈时间"), "");
    await fireEvent.click(screen.getByRole("button", { name: "确认到站" }));

    expect(screen.getByText("请填写驾驶员反馈时间")).toBeInTheDocument();
  });

  it("validates longitude and latitude ranges", async () => {
    render(LocationReportPanel, {
      props: {
        actionLabel: "上车",
        initialLocation: candidate(104.63, 35.21, "通渭县一中"),
        virtualStops: []
      }
    });

    await fireEvent.update(screen.getByLabelText("经度"), "200");
    await fireEvent.click(screen.getByRole("button", { name: "确认上车" }));

    expect(screen.getByText("经度必须在 -180 到 180 之间")).toBeInTheDocument();
  });

  it("does not submit when only address is filled without coordinates", async () => {
    const view = render(LocationReportPanel, {
      props: {
        actionLabel: "发车",
        virtualStops: []
      }
    });

    await fireEvent.update(screen.getByLabelText("标准化地址"), "通渭县客运中心");
    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));

    expect(screen.getByText("请填写有效经纬度")).toBeInTheDocument();
    expect(view.emitted("submit")).toBeUndefined();
  });

  it("fills coordinates and address from a virtual stop", async () => {
    render(LocationReportPanel, {
      props: {
        actionLabel: "下车",
        virtualStops: [
          virtualStop("stop-hospital", "通渭县人民医院", "POINT (104.6412 35.2134)")
        ]
      }
    });

    await fireEvent.update(screen.getByLabelText("虚拟站点"), "stop-hospital");

    expect(screen.getByLabelText("经度")).toHaveValue(104.6412);
    expect(screen.getByLabelText("纬度")).toHaveValue(35.2134);
    expect(screen.getByLabelText("标准化地址")).toHaveValue("通渭县人民医院");
  });

  it("submits the selected virtual stop id, coordinates, and name", async () => {
    const view = render(LocationReportPanel, {
      props: {
        actionLabel: "下车",
        virtualStops: [
          virtualStop("stop-hospital", "通渭县人民医院", "POINT (104.6412 35.2134)")
        ]
      }
    });

    await fireEvent.update(screen.getByLabelText("虚拟站点"), "stop-hospital");
    await fireEvent.click(screen.getByRole("button", { name: "确认下车" }));

    const emitted = (view.emitted("submit") ?? []) as Array<[LocationReportInput]>;
    expect(emitted[0][0]).toMatchObject({
      virtualStopId: "stop-hospital",
      longitude: 104.6412,
      latitude: 35.2134,
      standardizedAddress: "通渭县人民医院"
    });
  });

  it("writes a service-area map pick into the form and submits it after containment passes", async () => {
    mapApi.checkServiceAreaContainment.mockResolvedValueOnce(containment(true));
    const view = render(LocationReportPanel, {
      props: { actionLabel: "发车", serviceArea, virtualStops: [] }
    });

    await waitFor(() => expect(tileMapRuntime.handle.onClick).toHaveBeenCalled());
    tileMapRuntime.triggerClick({ longitude: 105.245, latitude: 35.215 });

    await waitFor(() => expect(screen.getByLabelText("经度")).toHaveValue(105.245));
    expect(screen.getByLabelText("纬度")).toHaveValue(35.215);
    expect(screen.getByLabelText("标准化地址")).toHaveValue("地图点选位置");
    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));

    await waitFor(() => expect(mapApi.checkServiceAreaContainment).toHaveBeenCalledWith("area-1", 105.245, 35.215));
    expect(firstSubmitPayload(view.emitted("submit"))).toMatchObject({
      longitude: 105.245,
      latitude: 35.215,
      standardizedAddress: "地图点选位置",
      virtualStopId: undefined
    });
  });

  it("provides an explicit map-pick entry and clears a stale snapshot before the operator clicks the map", async () => {
    render(LocationReportPanel, {
      props: {
        actionLabel: "待命",
        initialLocation: candidate(105.2421, 35.2103, "旧待命位置"),
        serviceArea,
        virtualStops: [virtualStop("stop-1", "高铁站", "POINT (105.258224 35.197636)")]
      }
    });

    await fireEvent.click(screen.getByRole("button", { name: "地图点选位置" }));

    expect(screen.getByText("请在上方地图空白处点击位置，系统会自动填写经纬度。")) .toBeInTheDocument();
    expect(screen.getByLabelText("虚拟站点")).toHaveValue("");
    expect(screen.getByLabelText("经度")).toHaveValue(null);
    expect(screen.getByLabelText("纬度")).toHaveValue(null);
    expect(screen.getByLabelText("标准化地址")).toHaveValue("");

  });

  it("accepts a high-precision service-area map pick without native step validation blocking submission", async () => {
    mapApi.checkServiceAreaContainment.mockResolvedValueOnce(containment(true));
    const view = render(LocationReportPanel, {
      props: { actionLabel: "待命", serviceArea, virtualStops: [] }
    });

    await waitFor(() => expect(tileMapRuntime.handle.onClick).toHaveBeenCalled());
    tileMapRuntime.triggerClick({ longitude: 105.26586720834847, latitude: 35.19207770795434 });

    const longitude = screen.getByLabelText("经度") as HTMLInputElement;
    const latitude = screen.getByLabelText("纬度") as HTMLInputElement;
    expect(longitude.step).toBe("any");
    expect(latitude.step).toBe("any");
    expect(longitude).toBeValid();
    expect(latitude).toBeValid();

    await fireEvent.click(screen.getByRole("button", { name: "确认待命" }));

    await waitFor(() => expect(view.emitted("submit")).toHaveLength(1));
    expect(firstSubmitPayload(view.emitted("submit"))).toMatchObject({
      longitude: 105.26586720834847,
      latitude: 35.19207770795434,
      standardizedAddress: "地图点选位置"
    });
  });

  it("requires confirmation before submitting a point outside the supplied service area", async () => {
    mapApi.checkServiceAreaContainment.mockResolvedValue(containment(false));
    const view = render(LocationReportPanel, {
      props: { actionLabel: "发车", initialLocation: candidate(105.9, 36.8, "服务区外临时点"), serviceArea, virtualStops: [] }
    });

    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));

    await waitFor(() => expect(mapApi.checkServiceAreaContainment).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("当前位置可能在服务区外，请确认后再保存。")).toBeInTheDocument();
    expect(view.emitted("submit")).toBeUndefined();

    await fireEvent.click(screen.getByLabelText("确认服务区外位置仍需保存"));
    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));

    expect(view.emitted("submit")).toHaveLength(1);
  });

  it("invalidates an outside-area confirmation when the candidate changes", async () => {
    mapApi.checkServiceAreaContainment.mockResolvedValue(containment(false));
    const view = render(LocationReportPanel, {
      props: { actionLabel: "发车", initialLocation: candidate(105.9, 36.8, "位置 A"), serviceArea, virtualStops: [] }
    });

    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));
    await fireEvent.click(screen.getByLabelText("确认服务区外位置仍需保存"));
    await fireEvent.update(screen.getByLabelText("标准化地址"), "位置 B");
    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));

    expect(screen.getByLabelText("确认服务区外位置仍需保存")).not.toBeChecked();
    expect(view.emitted("submit")).toBeUndefined();
  });

  it("requires confirmation again after an outside virtual stop is changed to manual entry", async () => {
    mapApi.checkServiceAreaContainment.mockResolvedValue(containment(false));
    const view = render(LocationReportPanel, {
      props: {
        actionLabel: "发车",
        serviceArea,
        virtualStops: [virtualStop("stop-outside", "服务区外站点", "POINT (105.9 36.8)")]
      }
    });

    await fireEvent.update(screen.getByLabelText("虚拟站点"), "stop-outside");
    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));
    await screen.findByText("当前位置可能在服务区外，请确认后再保存。");
    await fireEvent.click(screen.getByLabelText("确认服务区外位置仍需保存"));
    await fireEvent.update(screen.getByLabelText("虚拟站点"), "");
    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));

    await waitFor(() => expect(mapApi.checkServiceAreaContainment).toHaveBeenCalledTimes(2));
    expect(screen.getByLabelText("确认服务区外位置仍需保存")).not.toBeChecked();
    expect(view.emitted("submit")).toBeUndefined();
  });

  it("rejects a malformed containment response without submitting", async () => {
    mapApi.checkServiceAreaContainment.mockResolvedValue({ ...containment(true), inside: undefined });
    const view = render(LocationReportPanel, {
      props: { actionLabel: "发车", initialLocation: candidate(105.24, 35.21, "县医院"), serviceArea, virtualStops: [] }
    });

    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));

    expect(await screen.findByText("服务区范围校验失败，请稍后重试。")).toBeInTheDocument();
    expect(view.emitted("submit")).toBeUndefined();
  });

  it("keeps the form and does not submit when containment rejects", async () => {
    mapApi.checkServiceAreaContainment.mockRejectedValueOnce(new Error("network unavailable"));
    const view = render(LocationReportPanel, {
      props: { actionLabel: "发车", initialLocation: candidate(105.24, 35.21, "县医院"), serviceArea, virtualStops: [] }
    });

    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));

    expect(await screen.findByText("服务区范围校验失败，请稍后重试。")).toBeInTheDocument();
    expect(screen.getByLabelText("标准化地址")).toHaveValue("县医院");
    expect(view.emitted("submit")).toBeUndefined();
  });

  it("submits only once while containment validation is pending and disables close", async () => {
    const pending = deferred<ReturnType<typeof containment>>();
    mapApi.checkServiceAreaContainment.mockReturnValueOnce(pending.promise);
    const view = render(LocationReportPanel, {
      props: { actionLabel: "发车", initialLocation: candidate(105.24, 35.21, "县医院"), serviceArea, virtualStops: [] }
    });

    const submitButton = screen.getByRole("button", { name: "确认发车" });
    await fireEvent.click(submitButton);
    await fireEvent.click(submitButton);

    expect(mapApi.checkServiceAreaContainment).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "关闭" })).toBeDisabled();
    await fireEvent.click(screen.getByRole("button", { name: "关闭" }));
    expect(view.emitted("close")).toBeUndefined();

    pending.resolve(containment(true));
    await waitFor(() => expect(view.emitted("submit")).toHaveLength(1));
  });

  it("clears a selected virtual stop after a service-area map pick", async () => {
    render(LocationReportPanel, {
      props: {
        actionLabel: "发车",
        serviceArea,
        virtualStops: [virtualStop("stop-hospital", "通渭县人民医院", "POINT (104.6412 35.2134)")]
      }
    });

    await fireEvent.update(screen.getByLabelText("虚拟站点"), "stop-hospital");
    await waitFor(() => expect(tileMapRuntime.handle.onClick).toHaveBeenCalled());
    tileMapRuntime.triggerClick({ longitude: 105.245, latitude: 35.215 });

    await waitFor(() => expect(screen.getByLabelText("虚拟站点")).toHaveValue(""));
    expect(screen.getByLabelText("标准化地址")).toHaveValue("地图点选位置");
  });

  it("keeps manual coordinates available after the real service-area map fails to initialize", async () => {
    tileMapRuntime.createTileMap.mockImplementationOnce(() => { throw new Error("map unavailable"); });
    mapApi.checkServiceAreaContainment.mockResolvedValueOnce(containment(true));
    const view = render(LocationReportPanel, {
      props: {
        actionLabel: "发车",
        serviceArea,
        virtualStops: []
      }
    });

    expect(await screen.findByText(/开放底图暂不可用/)).toBeInTheDocument();
    await fireEvent.update(screen.getByLabelText("经度"), "104.6412");
    await fireEvent.update(screen.getByLabelText("纬度"), "35.2134");
    await fireEvent.update(screen.getByLabelText("标准化地址"), "手工位置");
    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));

    expect(firstSubmitPayload(view.emitted("submit"))).toMatchObject({
      longitude: 104.6412,
      latitude: 35.2134,
      standardizedAddress: "手工位置"
    });
  });

  it("keeps virtual-stop submission available after the real service-area map base layer fails", async () => {
    mapApi.checkServiceAreaContainment.mockResolvedValueOnce(containment(true));
    const view = render(LocationReportPanel, {
      props: {
        actionLabel: "发车",
        serviceArea,
        virtualStops: [virtualStop("stop-hospital", "通渭县人民医院", "POINT (104.6412 35.2134)")]
      }
    });

    await waitFor(() => expect(tileMapRuntime.handle.onBaseLayerError).toHaveBeenCalled());
    tileMapRuntime.triggerBaseLayerError();
    expect(await screen.findByText(/开放底图暂不可用/)).toBeInTheDocument();
    await fireEvent.update(screen.getByLabelText("虚拟站点"), "stop-hospital");
    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));

    expect(firstSubmitPayload(view.emitted("submit"))).toMatchObject({
      virtualStopId: "stop-hospital",
      longitude: 104.6412,
      latitude: 35.2134
    });
  });

  it("keeps input and reuses the same idempotency key when submission is retried", async () => {
    vi.spyOn(crypto, "randomUUID").mockReturnValue("11111111-1111-4111-8111-111111111111");
    const view = render(LocationReportPanel, {
      props: {
        actionLabel: "完成",
        initialLocation: candidate(104.64, 35.22, "通渭县体育馆"),
        virtualStops: []
      }
    });

    await fireEvent.update(screen.getByLabelText("备注"), "司机微信反馈已到达");
    await fireEvent.click(screen.getByRole("button", { name: "确认完成" }));
    await fireEvent.click(screen.getByRole("button", { name: "确认完成" }));

    expect(screen.getByLabelText("备注")).toHaveValue("司机微信反馈已到达");
    const emitted = (view.emitted("submit") ?? []) as Array<[LocationReportInput]>;
    expect(emitted).toHaveLength(2);
    expect(emitted[0][0]).toMatchObject({ idempotencyKey: "11111111-1111-4111-8111-111111111111" });
    expect(emitted[1][0]).toMatchObject({ idempotencyKey: "11111111-1111-4111-8111-111111111111" });
  });

  it("requires a second confirmation for outside service area locations", async () => {
    const view = render(LocationReportPanel, {
      props: {
        actionLabel: "发车",
        initialLocation: candidate(105.9, 36.8, "服务区外临时点"),
        virtualStops: [],
        isOutsideServiceArea: () => true
      }
    });

    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));

    expect(screen.getByText("当前位置可能在服务区外，请确认后再保存。")).toBeInTheDocument();
    expect(view.emitted("submit")).toBeUndefined();

    await fireEvent.click(screen.getByLabelText("确认服务区外位置仍需保存"));
    await fireEvent.click(screen.getByRole("button", { name: "确认发车" }));

    expect(view.emitted("submit")).toHaveLength(1);
    expect(mapApi.checkServiceAreaContainment).not.toHaveBeenCalled();
  });

  it("uses the injected provider for address search", async () => {
    const provider: LocationPickerProvider = {
      search: vi.fn().mockResolvedValue([candidate(104.65, 35.23, "通渭县中医院")]),
      pickOnMap: vi.fn()
    };
    render(LocationReportPanel, {
      props: {
        actionLabel: "发车",
        virtualStops: [],
        provider
      }
    });

    await fireEvent.update(screen.getByLabelText("地址搜索"), "中医院");
    await fireEvent.click(screen.getByRole("button", { name: "搜索" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "通渭县中医院" })).toBeInTheDocument());
    await fireEvent.click(screen.getByRole("button", { name: "通渭县中医院" }));

    expect(screen.getByLabelText("经度")).toHaveValue(104.65);
    expect(screen.getByLabelText("纬度")).toHaveValue(35.23);
    expect(screen.getByLabelText("标准化地址")).toHaveValue("通渭县中医院");
  });

  it("shows a recoverable provider error and keeps manual input when search or map pick fails", async () => {
    const provider: LocationPickerProvider = {
      search: vi.fn().mockRejectedValue(new Error("provider unavailable")),
      pickOnMap: vi.fn().mockRejectedValue(new Error("provider unavailable"))
    };
    render(LocationReportPanel, {
      props: {
        actionLabel: "发车",
        initialLocation: candidate(104.63, 35.21, "通渭县客运中心"),
        virtualStops: [],
        provider
      }
    });

    await fireEvent.update(screen.getByLabelText("标准化地址"), "手工保留地址");
    await fireEvent.update(screen.getByLabelText("地址搜索"), "中医院");
    await fireEvent.click(screen.getByRole("button", { name: "搜索" }));

    expect(await screen.findByText("地图交互失败，请稍后重试或手工录入。")).toBeInTheDocument();
    expect(screen.getByLabelText("标准化地址")).toHaveValue("手工保留地址");

    await fireEvent.click(screen.getByRole("button", { name: "地图选点" }));

    expect(await screen.findByText("地图交互失败，请稍后重试或手工录入。")).toBeInTheDocument();
    expect(screen.getByLabelText("标准化地址")).toHaveValue("手工保留地址");
  });
});

function candidate(longitude: number, latitude: number, standardizedAddress: string): LocationCandidate {
  return { longitude, latitude, standardizedAddress };
}

function virtualStop(id: string, name: string, location: string): VirtualStop {
  return {
    id,
    name,
    location,
    serviceAreaId: "area-1",
    serviceRadiusMeters: 300,
    boardingEnabled: true,
    alightingEnabled: true,
    safetyNote: "",
    enabled: true
  };
}

const serviceArea: ServiceAreaBoundaryView = {
  id: "area-1",
  name: "通渭县服务区",
  boundaryWkt: "POLYGON((105.2 35.2, 105.3 35.2, 105.3 35.3, 105.2 35.2))",
  boundarySource: "MANUAL",
  boundaryVersion: 1,
  draftBoundaryWkt: null,
  draftBoundarySource: null,
  draftBoundaryVersion: 1,
  publishedAt: null,
  updatedAt: null,
  coordinateSystem: "GCJ-02"
};

function containment(inside: boolean) {
  return {
    inside,
    serviceAreaId: "area-1",
    distanceToBoundaryMeters: inside ? 50 : 100,
    coordinateSystem: "GCJ-02" as const
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => { resolve = next; });
  return { promise, resolve };
}
