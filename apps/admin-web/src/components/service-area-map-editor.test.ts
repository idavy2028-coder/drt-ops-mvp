// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import ServiceAreaMapEditor from "./ServiceAreaMapEditor.vue";
import type { ServiceAreaBoundaryView } from "../api/types";

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

describe("ServiceAreaMapEditor", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("degrades to boundary text input when AMap is unavailable", () => {
    render(ServiceAreaMapEditor, { props: { serviceArea, readonly: false, amapEnabled: false } });

    expect(screen.getByText("地图不可用，可粘贴边界数据或稍后配置高德 Key。")).toBeInTheDocument();
    expect(screen.getByLabelText("服务区边界草稿")).toBeInTheDocument();
    expect(screen.getByText("坐标系：GCJ-02")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "发布并启用" })).toBeDisabled();
  });

  it("emits a district import request", async () => {
    const { emitted } = render(ServiceAreaMapEditor, { props: { serviceArea, readonly: false, amapEnabled: false } });

    await fireEvent.click(screen.getByRole("button", { name: "导入通渭县边界" }));

    expect(emitted()["import-district"]).toEqual([["甘肃省定西市通渭县"]]);
  });

  it("emits the WKT draft to save", async () => {
    const { emitted } = render(ServiceAreaMapEditor, { props: { serviceArea, readonly: false, amapEnabled: false } });
    await fireEvent.update(screen.getByLabelText("服务区边界草稿"), "POLYGON((105 35, 106 35, 105 36, 105 35))");

    await fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));

    expect(emitted()["save-boundary"]).toEqual([[{ boundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))" }]]);
  });

  it("requires explicit confirmation before publishing", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const { emitted } = render(ServiceAreaMapEditor, {
      props: {
        serviceArea: {
          ...serviceArea,
          draftBoundaryWkt: "POLYGON((105 35, 106 35, 105 36, 105 35))",
          draftBoundaryVersion: 1
        },
        readonly: false,
        amapEnabled: false
      }
    });

    await fireEvent.click(screen.getByRole("button", { name: "发布并启用" }));

    expect(window.confirm).toHaveBeenCalled();
    expect(emitted().publish).toEqual([[]]);
  });
});
