// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import OrderCreateDialog from "./OrderCreateDialog.vue";

vi.mock("../api/resources", () => ({
  listServiceAreas: vi.fn().mockResolvedValue([]),
  listVirtualStops: vi.fn().mockResolvedValue([])
}));

vi.mock("../api/map", () => ({ checkServiceAreaContainment: vi.fn() }));

describe("OrderCreateDialog", () => {
  afterEach(() => cleanup());

  it("keeps address text and manual coordinates available without address lookup", () => {
    render(OrderCreateDialog);

    expect(screen.getByLabelText("起点地址")).toBeInTheDocument();
    expect(screen.getByLabelText("终点地址")).toBeInTheDocument();
    expect(screen.getAllByText("手工输入经纬度")).toHaveLength(2);
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
});
