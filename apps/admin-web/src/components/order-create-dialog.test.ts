// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import OrderCreateDialog from "./OrderCreateDialog.vue";

const tileMapRuntime = vi.hoisted(() => {
  const clickListeners: Array<(point: { longitude: number; latitude: number }) => void> = [];
  return {
    clickListeners,
    createTileMap: vi.fn(() => ({
      map: {},
      baseLayerFailed: false,
      destroy: vi.fn(),
      fitLayers: vi.fn(),
      onBaseLayerError: vi.fn(() => vi.fn()),
      onClick: vi.fn((listener: (point: { longitude: number; latitude: number }) => void) => {
        clickListeners.push(listener);
        return vi.fn();
      })
    }))
  };
});

vi.mock("../api/resources", () => ({
  listServiceAreas: vi.fn().mockResolvedValue([]),
  listVirtualStops: vi.fn().mockResolvedValue([])
}));

vi.mock("../api/map", () => ({ checkServiceAreaContainment: vi.fn() }));
vi.mock("../maps/tileMapRuntime", () => ({ createTileMap: tileMapRuntime.createTileMap }));

describe("OrderCreateDialog", () => {
  afterEach(() => {
    cleanup();
    tileMapRuntime.clickListeners.length = 0;
    vi.clearAllMocks();
    vi.useRealTimers();
  });

  it("keeps address text and manual coordinates available without address lookup", () => {
    render(OrderCreateDialog);

    expect(screen.getByLabelText("起点地址")).toBeInTheDocument();
    expect(screen.getByLabelText("终点地址")).toBeInTheDocument();
    expect(screen.getAllByText("手工输入经纬度")).toHaveLength(2);
  });

  it("defaults the departure field to fifteen minutes later in Shanghai local time", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-23T06:45:00Z"));

    render(OrderCreateDialog);

    expect(screen.getByLabelText("预计出发时间")).toHaveValue("2026-07-23T15:00");
  });

  it("submits manually entered GCJ-02 coordinates for both endpoints", async () => {
    const { emitted } = render(OrderCreateDialog);
    await fireEvent.update(screen.getByLabelText("乘客姓名"), "张三");
    await fireEvent.update(screen.getByLabelText("乘客电话"), "13800000000");
    await fireEvent.update(screen.getByLabelText("起点地址"), "通渭县人民医院");
    await fireEvent.update(screen.getByLabelText("终点地址"), "通渭县文化广场");
    await fireEvent.update(screen.getByLabelText("起点经度"), "105.22");
    await fireEvent.update(screen.getByLabelText("起点纬度"), "35.22");
    await fireEvent.update(screen.getByLabelText("终点经度"), "105.23");
    await fireEvent.update(screen.getByLabelText("终点纬度"), "35.23");
    await fireEvent.click(screen.getByRole("button", { name: "提交需求" }));

    const creates = emitted().create as unknown[][] | undefined;
    expect(creates?.[0]?.[0]).toMatchObject({
      originAddress: "通渭县人民医院",
      destinationAddress: "通渭县文化广场",
      originLng: 105.22,
      destinationLng: 105.23,
      coordinateSystem: "GCJ02"
    });
  });

  it("shows coordinate validation feedback instead of silently blocking the form", async () => {
    const { emitted } = render(OrderCreateDialog);
    await fireEvent.update(screen.getByLabelText("乘客姓名"), "张三");
    await fireEvent.update(screen.getByLabelText("乘客电话"), "13800000000");
    await fireEvent.update(screen.getByLabelText("起点地址"), "高铁站");
    await fireEvent.update(screen.getByLabelText("终点地址"), "陇阳");
    await fireEvent.click(screen.getByRole("button", { name: "提交需求" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("请通过地图点选、虚拟站点或手工经纬度补全起点和终点。");
    expect(emitted().create).toBeUndefined();
  });

  it("submits after both endpoints are selected on the map", async () => {
    const { emitted } = render(OrderCreateDialog);
    await fireEvent.update(screen.getByLabelText("乘客姓名"), "张三");
    await fireEvent.update(screen.getByLabelText("乘客电话"), "13800000000");
    await fireEvent.update(screen.getByLabelText("起点地址"), "高铁站");
    await fireEvent.update(screen.getByLabelText("终点地址"), "陇阳");

    const mapButtons = screen.getAllByRole("button", { name: "地图点选" });
    await fireEvent.click(mapButtons[0]);
    await vi.waitFor(() => expect(tileMapRuntime.clickListeners).toHaveLength(1));
    tileMapRuntime.clickListeners[0]({ longitude: 105.258224, latitude: 35.197636 });
    await fireEvent.click(mapButtons[1]);
    await vi.waitFor(() => expect(tileMapRuntime.clickListeners).toHaveLength(2));
    tileMapRuntime.clickListeners[1]({ longitude: 105.267975, latitude: 35.211521 });

    await fireEvent.click(screen.getByRole("button", { name: "提交需求" }));

    const creates = emitted().create as unknown[][] | undefined;
    expect(creates?.[0]?.[0]).toMatchObject({
      originLng: 105.258224,
      originLat: 35.197636,
      destinationLng: 105.267975,
      destinationLat: 35.211521
    });
  });

  it("clears stale coordinate feedback as soon as both endpoints become complete", async () => {
    render(OrderCreateDialog);
    await fireEvent.update(screen.getByLabelText("乘客姓名"), "张三");
    await fireEvent.update(screen.getByLabelText("乘客电话"), "13800000000");
    await fireEvent.update(screen.getByLabelText("起点地址"), "高铁站");
    await fireEvent.update(screen.getByLabelText("终点地址"), "陇阳");
    await fireEvent.click(screen.getByRole("button", { name: "提交需求" }));
    expect(screen.getByRole("alert")).toHaveTextContent("请通过地图点选、虚拟站点或手工经纬度补全起点和终点。");

    await fireEvent.update(screen.getByLabelText("起点经度"), "105.258224");
    await fireEvent.update(screen.getByLabelText("起点纬度"), "35.197636");
    await fireEvent.update(screen.getByLabelText("终点经度"), "105.267975");
    await fireEvent.update(screen.getByLabelText("终点纬度"), "35.211521");

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows API errors inside the open demand form", () => {
    render(OrderCreateDialog, { props: { submitError: "所选下车虚拟站点不在服务半径内" } });

    expect(screen.getByRole("alert")).toHaveTextContent("所选下车虚拟站点不在服务半径内");
  });
});
