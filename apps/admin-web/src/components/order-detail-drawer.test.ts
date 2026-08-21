// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import type { RideOrder } from "../api/types";
import OrderDetailDrawer from "./OrderDetailDrawer.vue";

function unserviceableOrderFixture(): RideOrder {
  return {
    id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    passengerName: "罗老师",
    passengerPhone: "13800000000",
    passengerCount: 1,
    requestType: "IMMEDIATE",
    originLng: 105.258224,
    originLat: 35.197636,
    destinationLng: 105.327705,
    destinationLat: 35.283669,
    originAddress: "通渭县汽车站",
    destinationAddress: "通渭县人民医院",
    coordinateSystem: "GCJ02",
    originAddressSource: "VIRTUAL_STOP",
    destinationAddressSource: "VIRTUAL_STOP",
    requestedDepartureAt: "2026-08-13T02:30:00.000Z",
    createdAt: "2026-08-13T01:00:00.000Z",
    status: "UNSERVICEABLE",
    canMarkNoShow: false,
    noShowWaitedSeconds: 0,
    dispatchFailure: {
      code: "ALL_CANDIDATES_REJECTED",
      summary: "候选车辆均不满足调度约束",
      candidateCount: 3,
      rejectedReasons: ["WAIT_TIME_EXCEEDED"],
      maxWaitMinutes: 5,
      maxDetourMinutes: 8,
      mapProvider: "AMAP",
      mapDegraded: false
    }
  };
}

describe("OrderDetailDrawer", () => {
  afterEach(cleanup);

  it("展示完整订单与调度失败详情", async () => {
    render(OrderDetailDrawer, { props: { order: unserviceableOrderFixture() } });

    expect(screen.getByRole("dialog", { name: "订单详情" })).toBeInTheDocument();
    expect(screen.getByText("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "拨打 13800000000" })).toHaveAttribute("href", "tel:13800000000");
    expect(screen.getByText("候选车辆均不满足调度约束")).toBeInTheDocument();
    expect(screen.getByText("候选方案数：3")).toBeInTheDocument();
    expect(screen.getByText("诊断代码：ALL_CANDIDATES_REJECTED")).toBeInTheDocument();
  });

  it("按 Escape 时发出关闭事件", async () => {
    const view = render(OrderDetailDrawer, { props: { order: unserviceableOrderFixture() } });

    await fireEvent.keyDown(screen.getByRole("dialog", { name: "订单详情" }), { key: "Escape" });

    expect(view.emitted("close")).toEqual([[]]);
  });
});
