// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";

const mapApi = vi.hoisted(() => ({ checkServiceAreaContainment: vi.fn() }));
const tileMapRuntime = vi.hoisted(() => {
  let clickListener: ((point: { longitude: number; latitude: number }) => void) | undefined;
  let baseLayerErrorListener: (() => void) | undefined;
  const handle = {
    map: {},
    baseLayerFailed: false,
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
    triggerClick: (point: { longitude: number; latitude: number }) => clickListener?.(point),
    triggerBaseLayerError: () => baseLayerErrorListener?.()
  };
});

vi.mock("../api/map", () => mapApi);
vi.mock("../maps/tileMapRuntime", () => ({ createTileMap: tileMapRuntime.createTileMap }));

import AddressCoordinateField from "./AddressCoordinateField.vue";

const stops = [{
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

describe("AddressCoordinateField", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("keeps address text as local input and clears a selected virtual stop", async () => {
    const { emitted } = render(AddressCoordinateField, {
      props: { label: "起点", purpose: "BOARDING", modelValue: { address: "县医院", virtualStopId: "stop-1" } }
    });

    await fireEvent.update(screen.getByLabelText("起点地址"), "文化广场");

    const changes = emitted()["update:modelValue"] as unknown[][] | undefined;
    expect(changes?.[changes.length - 1]?.[0]).toMatchObject({ address: "文化广场", virtualStopId: undefined });
    expect(tileMapRuntime.createTileMap).not.toHaveBeenCalled();
  });

  it("writes back GCJ-02 coordinates when an open-tile map point is selected", async () => {
    const { emitted } = render(AddressCoordinateField, {
      props: { label: "终点", purpose: "ALIGHTING", modelValue: { address: "", virtualStopId: "stop-1" } }
    });

    await fireEvent.click(screen.getByRole("button", { name: "地图点选" }));
    await vi.waitFor(() => expect(tileMapRuntime.createTileMap).toHaveBeenCalled());
    tileMapRuntime.triggerClick({ longitude: 105.245, latitude: 35.215 });

    const changes = emitted()["update:modelValue"] as unknown[][] | undefined;
    expect(changes?.[changes.length - 1]?.[0]).toMatchObject({
      address: "地图点选位置",
      longitude: 105.245,
      latitude: 35.215,
      virtualStopId: undefined
    });
    await vi.waitFor(() => expect(tileMapRuntime.handle.destroy).toHaveBeenCalled());
  });

  it("keeps manual coordinates and virtual-stop selection available when the tile base layer fails", async () => {
    const { emitted } = render(AddressCoordinateField, {
      props: { label: "起点", purpose: "BOARDING", modelValue: { address: "", longitude: 105.2, latitude: 35.2 }, virtualStops: stops }
    });

    await fireEvent.click(screen.getByRole("button", { name: "地图点选" }));
    await vi.waitFor(() => expect(tileMapRuntime.handle.onBaseLayerError).toHaveBeenCalled());
    tileMapRuntime.triggerBaseLayerError();
    expect(await screen.findByText("开放底图暂不可用")).toBeInTheDocument();

    await fireEvent.update(screen.getByLabelText("起点经度"), "105.24");
    await fireEvent.update(screen.getByLabelText("起点纬度"), "35.21");
    await fireEvent.change(screen.getByLabelText("推荐上车点"), { target: { value: "stop-1" } });

    const changes = emitted()["update:modelValue"] as unknown[][] | undefined;
    expect(changes?.some((change) => (change[0] as { longitude?: number }).longitude === 105.24)).toBe(true);
    expect(changes?.[changes.length - 1]?.[0]).toMatchObject({ virtualStopId: "stop-1" });
  });

  it("continues to validate coordinates against the service area", async () => {
    mapApi.checkServiceAreaContainment.mockResolvedValueOnce({ inside: true, serviceAreaId: "area-1" });
    render(AddressCoordinateField, {
      props: {
        label: "起点",
        purpose: "BOARDING",
        modelValue: { address: "县医院", longitude: 105.24, latitude: 35.21 },
        serviceAreaId: "area-1"
      }
    });

    await vi.waitFor(() => expect(mapApi.checkServiceAreaContainment).toHaveBeenCalledWith("area-1", 105.24, 35.21));
    expect(await screen.findByText("服务区内，可继续录入")).toBeInTheDocument();
  });
});
