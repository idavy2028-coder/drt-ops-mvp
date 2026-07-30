// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
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

  it("uses the server eligibility snapshot instead of order status for no-show actions", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      data: [{
        id: "88888888-8888-8888-8888-888888888888",
        passengerName: "试运行乘客",
        passengerPhone: "13800000000",
        passengerCount: 1,
        requestType: "IMMEDIATE",
        originLng: 105.327705,
        originLat: 35.283669,
        destinationLng: 105.258224,
        destinationLat: 35.197636,
        originAddress: "测试起点",
        destinationAddress: "测试终点",
        coordinateSystem: "GCJ02",
        originAddressSource: "VIRTUAL_STOP",
        destinationAddressSource: "VIRTUAL_STOP",
        boardingStopId: "55555555-5555-5555-5555-555555555551",
        alightingStopId: "55555555-5555-5555-5555-555555555552",
        requestedDepartureAt: "2026-07-30T12:00:00+08:00",
        estimatedBoardingAt: "2026-07-30T12:05:00+08:00",
        status: "IN_PROGRESS",
        canMarkNoShow: false,
        noShowEligibleAt: "2026-07-30T12:10:00+08:00",
        noShowWaitedSeconds: 120,
        noShowBlockReason: "乘客等候期尚未结束"
      }]
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
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

    expect(await screen.findByText("乘客等候期尚未结束")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "乘客未到" })).not.toBeInTheDocument();
  });

  it("requires the confirmation dialog and submits one idempotent no-show request", async () => {
    const orderId = "99999999-9999-9999-9999-999999999999";
    const postedBodies: string[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, options) => {
      const url = String(input);
      if (url.endsWith(`/api/orders/${orderId}/no-show`) && options?.method === "POST") {
        postedBodies.push(String(options.body));
        return new Response(JSON.stringify({ data: { id: orderId, status: "EXCEPTION_CLOSED" } }), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }
      return new Response(JSON.stringify({
        data: postedBodies.length === 0 ? [{
          id: orderId,
          passengerName: "试运行乘客",
          passengerPhone: "13800000000",
          passengerCount: 1,
          requestType: "IMMEDIATE",
          originLng: 105.327705,
          originLat: 35.283669,
          destinationLng: 105.258224,
          destinationLat: 35.197636,
          originAddress: "测试起点",
          destinationAddress: "测试终点",
          coordinateSystem: "GCJ02",
          originAddressSource: "VIRTUAL_STOP",
          destinationAddressSource: "VIRTUAL_STOP",
          boardingStopId: "55555555-5555-5555-5555-555555555551",
          alightingStopId: "55555555-5555-5555-5555-555555555552",
          requestedDepartureAt: "2026-07-30T12:00:00+08:00",
          estimatedBoardingAt: "2026-07-30T12:05:00+08:00",
          status: "IN_PROGRESS",
          canMarkNoShow: true,
          noShowEligibleAt: "2026-07-30T12:10:00+08:00",
          noShowWaitedSeconds: 301
        }] : []
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

    await fireEvent.click(await screen.findByRole("button", { name: "乘客未到" }));
    expect(screen.getByRole("dialog", { name: "确认乘客未到" })).toBeInTheDocument();
    expect(postedBodies).toHaveLength(0);

    await fireEvent.update(screen.getByRole("combobox", { name: "爽约原因" }), "WAITING_PERIOD_EXPIRED");
    await fireEvent.click(screen.getByRole("button", { name: "确认乘客未到并关闭订单" }));

    await waitFor(() => expect(postedBodies).toHaveLength(1));
    expect(JSON.parse(postedBodies[0]!)).toMatchObject({
      reason: "乘客在等待期内未出现"
    });
    expect(JSON.parse(postedBodies[0]!).idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });
});
