// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { render, screen } from "@testing-library/vue";
import { describe, expect, it, vi } from "vitest";
import DispatchWorkbenchPage from "./DispatchWorkbenchPage.vue";

const mapConstructor = vi.hoisted(() => vi.fn(() => ({ remove: vi.fn() })));

vi.mock("maplibre-gl", () => ({
  default: {
    Map: mapConstructor
  }
}));

describe("DispatchWorkbenchPage", () => {
  it("renders dispatch workbench operational regions", async () => {
    render(DispatchWorkbenchPage);

    expect(await screen.findByText("实时订单")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "车辆任务" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "算法解释" })).toBeInTheDocument();
    expect(screen.getByLabelText("调度地图")).toBeInTheDocument();
    await new Promise((resolve) => window.setTimeout(resolve, 0));
    expect(mapConstructor).not.toHaveBeenCalled();
  });
});
