// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import AddressCoordinateField from "./AddressCoordinateField.vue";

const mapApi = vi.hoisted(() => ({
  searchAddressSuggestions: vi.fn(),
  checkServiceAreaContainment: vi.fn()
}));

vi.mock("../api/map", () => mapApi);

describe("AddressCoordinateField", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("selects an address suggestion and emits its GCJ-02 coordinate", async () => {
    mapApi.searchAddressSuggestions.mockResolvedValueOnce([{
      id: "amap-1", name: "通渭县人民医院", address: "平襄镇文化街", district: "通渭县",
      location: { longitude: 105.2421, latitude: 35.2103, coordinateSystem: "GCJ-02" }
    }]);
    const { emitted } = render(AddressCoordinateField, {
      props: { label: "起点", purpose: "BOARDING", modelValue: { address: "" } }
    });

    await fireEvent.update(screen.getByLabelText("起点地址"), "人民医院");
    await fireEvent.click(await screen.findByRole("button", { name: /通渭县人民医院/ }));

    const changes = emitted()["update:modelValue"] as unknown[][] | undefined;
    expect(changes?.[changes.length - 1]?.[0]).toMatchObject({
      address: "通渭县人民医院 平襄镇文化街",
      longitude: 105.2421,
      latitude: 35.2103
    });
  });

  it("shows a Chinese fallback message when the address service fails", async () => {
    mapApi.searchAddressSuggestions.mockRejectedValueOnce(new Error("offline"));
    render(AddressCoordinateField, {
      props: { label: "终点", purpose: "ALIGHTING", modelValue: { address: "" } }
    });

    await fireEvent.update(screen.getByLabelText("终点地址"), "文化广场");

    expect(await screen.findByText("地图服务暂不可用，可手工输入经纬度或选择虚拟站点。")).toBeInTheDocument();
  });
});
