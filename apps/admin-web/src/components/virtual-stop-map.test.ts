// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import VirtualStopMap from "./VirtualStopMap.vue";

describe("VirtualStopMap", () => {
  afterEach(() => cleanup());

  it("keeps the station workflow available when AMap is disabled", () => {
    render(VirtualStopMap, { props: { stops: [], amapEnabled: false, readonly: false } });
    expect(screen.getByText("地图不可用")).toBeInTheDocument();
    expect(screen.getByText("配置高德地图 Key 后可在此显示站点并点击取点。")).toBeInTheDocument();
  });
});
