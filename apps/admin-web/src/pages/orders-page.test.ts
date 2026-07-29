// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import OrdersPage from "./OrdersPage.vue";

describe("OrdersPage", () => {
  afterEach(() => {
    cleanup();
    authStore.clearSessionForTest();
    vi.restoreAllMocks();
  });

  it("shows create order action and order status columns", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ data: [] }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    authStore.setSessionForTest({ accessToken: "operator-token", user: { id: "operator-1", username: "operator01", roles: ["OPERATOR"], mustChangePassword: false } });
    render(OrdersPage);

    expect(await screen.findByRole("button", { name: "录入需求" })).toBeInTheDocument();
    expect(screen.getByText("订单状态")).toBeInTheDocument();
    expect(screen.getByText("预计上车时间")).toBeInTheDocument();
  });

  it("shows algorithm unavailable instead of login expired when dispatch returns the tagged 503", async () => {
    const orderId = "77777777-7777-7777-7777-777777777777";
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, options) => {
      const url = String(input);
      if (url.endsWith(`/api/orders/${orderId}/dispatch`) && options?.method === "POST") {
        return new Response(JSON.stringify({
          data: {
            code: "ALGORITHM_UNAVAILABLE",
            message: "算法服务不可用"
          }
        }), {
          status: 503,
          headers: { "Content-Type": "application/json" }
        });
      }
      return new Response(JSON.stringify({
        data: [{
          id: orderId,
          passengerName: "P1-4 测试乘客",
          passengerPhone: "13800000000",
          passengerCount: 1,
          requestType: "IMMEDIATE",
          originLng: 105.327705,
          originLat: 35.283669,
          destinationLng: 105.258224,
          destinationLat: 35.197636,
          originAddress: "P1-4 测试起点",
          destinationAddress: "P1-4 测试终点",
          coordinateSystem: "GCJ-02",
          originAddressSource: "VIRTUAL_STOP",
          destinationAddressSource: "VIRTUAL_STOP",
          boardingStopId: "55555555-5555-5555-5555-555555555551",
          alightingStopId: "55555555-5555-5555-5555-555555555552",
          requestedDepartureAt: "2026-07-29T17:00:00+08:00",
          status: "PENDING_DISPATCH"
        }]
      }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    });
    authStore.setSessionForTest({
      accessToken: "dispatcher-token",
      user: {
        id: "dispatcher-1",
        username: "dispatcher01",
        roles: ["DISPATCHER"],
        mustChangePassword: false
      }
    });
    render(OrdersPage);

    await fireEvent.click(await screen.findByRole("button", { name: "调度" }));

    expect(await screen.findByText("算法服务不可用")).toBeInTheDocument();
    expect(screen.queryByText("登录状态已失效，请重新登录")).not.toBeInTheDocument();
  });
});
