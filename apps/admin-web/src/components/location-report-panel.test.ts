// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import LocationReportPanel from "./LocationReportPanel.vue";
import type { LocationCandidate, LocationPickerProvider, LocationReportInput, VirtualStop } from "../api/types";

describe("LocationReportPanel", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
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
    expect(screen.getByText("地图服务不可用，已切换为经纬度录入。")).toBeInTheDocument();
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
