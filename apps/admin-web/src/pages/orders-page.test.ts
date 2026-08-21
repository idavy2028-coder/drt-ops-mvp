// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { defineComponent } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { RideOrder } from "../api/types";
import { authStore } from "../auth/authStore";
import AppFeedback from "../components/AppFeedback.vue";
import { feedbackStore } from "../stores/feedbackStore";
import OrdersPage from "./OrdersPage.vue";

function orderFixture(overrides: Partial<RideOrder> = {}): RideOrder {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    passengerName: "张敏",
    passengerPhone: "13800001201",
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
    status: "PENDING_DISPATCH",
    canMarkNoShow: false,
    noShowWaitedSeconds: 0,
    ...overrides
  };
}

function installOrdersFetch(orderList: RideOrder[]): void {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ data: orderList }), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  }));
}

function renderOrdersPage(): void {
  authStore.setSessionForTest({
    accessToken: "operator-token",
    user: { id: "operator-1", username: "operator01", roles: ["OPERATOR"], mustChangePassword: false }
  });
  render(OrdersPage);
}

function renderOrdersPageWithFeedback(): void {
  authStore.setSessionForTest({
    accessToken: "dispatcher-token",
    user: { id: "dispatcher-1", username: "dispatcher01", roles: ["DISPATCHER"], mustChangePassword: false }
  });
  const PageWithFeedback = defineComponent({
    components: { AppFeedback, OrdersPage },
    template: "<OrdersPage /><AppFeedback />"
  });
  render(PageWithFeedback);
}

function orderPaginationFixture(): RideOrder[] {
  return [
    orderFixture({ id: "today-09", passengerName: "今日乘客 09", createdAt: "2026-08-13T09:00:00.000Z" }),
    orderFixture({ id: "today-08", passengerName: "今日乘客 08", createdAt: "2026-08-13T08:00:00.000Z" }),
    orderFixture({ id: "today-07", passengerName: "今日乘客 07", createdAt: "2026-08-13T07:00:00.000Z" }),
    orderFixture({ id: "today-06", passengerName: "今日乘客 06", createdAt: "2026-08-13T06:00:00.000Z" }),
    orderFixture({ id: "today-05", passengerName: "今日乘客 05", createdAt: "2026-08-13T05:00:00.000Z" }),
    orderFixture({ id: "today-04", passengerName: "今日乘客 04", createdAt: "2026-08-13T04:00:00.000Z" }),
    orderFixture({ id: "today-03", passengerName: "今日乘客 03", createdAt: "2026-08-13T03:00:00.000Z" }),
    orderFixture({ id: "today-02", passengerName: "今日乘客 02", createdAt: "2026-08-13T02:00:00.000Z" }),
    orderFixture({ id: "today-01", passengerName: "今日乘客 01", createdAt: "2026-08-13T01:00:00.000Z" }),
    orderFixture({ id: "history-01", passengerName: "历史乘客 01", createdAt: "2026-08-11T01:00:00.000Z" })
  ];
}

describe("OrdersPage", () => {
  afterEach(() => {
    cleanup();
    authStore.clearSessionForTest();
    for (const item of [...feedbackStore.items]) {
      feedbackStore.dismiss(item.id);
    }
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("在当前订单表中展示可拨打电话和完整行程", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-08-13T04:00:00.000Z"));
    installOrdersFetch([orderFixture()]);

    renderOrdersPage();

    expect(await screen.findByRole("link", { name: "拨打 13800001201" })).toHaveAttribute("href", "tel:13800001201");
    expect(screen.getByText("通渭县汽车站")).toBeInTheDocument();
    expect(screen.getByText(/通渭县人民医院/)).toBeInTheDocument();
  });

  it("每页仅显示八条订单并分别保留两个分区的页码", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-08-13T04:00:00.000Z"));
    installOrdersFetch(orderPaginationFixture());

    renderOrdersPage();

    expect(await screen.findByText("第 1 / 2 页 · 共 9 条")).toBeInTheDocument();
    expect(screen.getAllByRole("row")).toHaveLength(9);
    await fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    expect(screen.getByText("第 2 / 2 页 · 共 9 条")).toBeInTheDocument();
    expect(screen.getByText("今日乘客 01")).toBeInTheDocument();

    await fireEvent.click(screen.getByRole("button", { name: "历史订单 1" }));
    expect(screen.getByText("第 1 / 1 页 · 共 1 条")).toBeInTheDocument();
    expect(screen.getByText("历史乘客 01")).toBeInTheDocument();

    await fireEvent.click(screen.getByRole("button", { name: "今日新增 9" }));
    expect(screen.getByText("第 2 / 2 页 · 共 9 条")).toBeInTheDocument();
  });

  it("按上海自然日而不是运行环境时区统计今日订单", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-08-13T04:00:00.000Z"));
    installOrdersFetch([
      orderFixture({ id: "shanghai-midnight", createdAt: "2026-08-12T16:30:00.000Z" }),
      orderFixture({ id: "previous-day", createdAt: "2026-08-12T15:59:59.000Z" })
    ]);

    renderOrdersPage();

    expect(await screen.findByRole("button", { name: "今日新增 1" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "历史订单 1" })).toBeInTheDocument();
  });

  it("打开并关闭订单详情后把焦点还给同一行按钮", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-08-13T04:00:00.000Z"));
    installOrdersFetch([orderFixture()]);
    renderOrdersPage();

    const detailButton = await screen.findByRole("button", { name: "查看详情" });
    detailButton.focus();
    await fireEvent.click(detailButton);
    expect(screen.getByRole("dialog", { name: "订单详情" })).toBeInTheDocument();

    await fireEvent.click(screen.getByRole("button", { name: "关闭订单详情" }));
    await waitFor(() => expect(detailButton).toHaveFocus());
  });

  it("确认前不取消订单，并提交填写的原因后显示成功 Toast", async () => {
    const order = orderFixture({ createdAt: new Date().toISOString() });
    const postedRequests: Array<{ url: string; body: string }> = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, options) => {
      const url = String(input);
      if (url.endsWith(`/api/orders/${order.id}/cancel`) && options?.method === "POST") {
        postedRequests.push({ url, body: String(options.body) });
        return new Response(JSON.stringify({ data: { ...order, status: "CANCELLED" } }), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }
      return new Response(JSON.stringify({ data: [order] }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    });
    renderOrdersPageWithFeedback();

    await fireEvent.click(await screen.findByRole("button", { name: "取消订单" }));
    expect(screen.getByRole("dialog", { name: "取消订单" })).toBeInTheDocument();
    expect(postedRequests).toHaveLength(0);

    await fireEvent.update(screen.getByRole("textbox", { name: "取消原因" }), "乘客临时调整行程");
    await fireEvent.click(screen.getByRole("button", { name: "确认取消" }));

    await waitFor(() => expect(postedRequests).toHaveLength(1));
    expect(JSON.parse(postedRequests[0]!.body)).toEqual({ reason: "乘客临时调整行程" });
    expect(await screen.findByText("订单已取消")).toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "取消订单" })).not.toBeInTheDocument();
  });

  it("取消失败时保留对话框、输入内容并显示失败 Toast", async () => {
    const order = orderFixture({ createdAt: new Date().toISOString() });
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, options) => {
      const url = String(input);
      if (url.endsWith(`/api/orders/${order.id}/cancel`) && options?.method === "POST") {
        return new Response(JSON.stringify({ data: { message: "订单状态已变化，请刷新" } }), {
          status: 409,
          headers: { "Content-Type": "application/json" }
        });
      }
      return new Response(JSON.stringify({ data: [order] }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    });
    renderOrdersPageWithFeedback();

    await fireEvent.click(await screen.findByRole("button", { name: "取消订单" }));
    const reason = screen.getByRole("textbox", { name: "取消原因" });
    await fireEvent.update(reason, "道路临时封闭");
    await fireEvent.click(screen.getByRole("button", { name: "确认取消" }));

    await waitFor(() => expect(screen.getAllByText("订单状态已变化，请刷新").length).toBeGreaterThanOrEqual(2));
    expect(screen.getByRole("dialog", { name: "取消订单" })).toBeInTheDocument();
    expect(reason).toHaveValue("道路临时封闭");
  });

  it("终态订单保留禁用的取消按钮", async () => {
    const completed = orderFixture({ status: "COMPLETED", createdAt: new Date().toISOString() });
    installOrdersFetch([completed]);
    renderOrdersPageWithFeedback();

    expect(await screen.findByRole("button", { name: "取消订单" })).toBeDisabled();
    expect(screen.getByText("当前订单状态不可取消")).toBeInTheDocument();
  });

  it("shows create order action and order status columns", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ data: [] }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    authStore.setSessionForTest({ accessToken: "operator-token", user: { id: "operator-1", username: "operator01", roles: ["OPERATOR"], mustChangePassword: false } });
    render(OrdersPage);

    expect(await screen.findByRole("button", { name: "录入需求" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "联系电话" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "状态" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "时间" })).toBeInTheDocument();
  });

  it("separates today's orders from history by createdAt instead of departure time", async () => {
    const today = new Date();
    const yesterday = new Date(today.getTime() - 24 * 60 * 60 * 1000);
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ data: [
      {
        id: "today-order",
        passengerName: "今日乘客",
        passengerCount: 1,
        requestedDepartureAt: yesterday.toISOString(),
        createdAt: today.toISOString(),
        status: "PENDING_DISPATCH",
        canMarkNoShow: false,
        noShowWaitedSeconds: 0
      },
      {
        id: "history-order",
        passengerName: "历史乘客",
        passengerCount: 1,
        requestedDepartureAt: today.toISOString(),
        createdAt: yesterday.toISOString(),
        status: "COMPLETED",
        canMarkNoShow: false,
        noShowWaitedSeconds: 0
      }
    ] }), { status: 200, headers: { "Content-Type": "application/json" } }));
    authStore.setSessionForTest({ accessToken: "operator-token", user: { id: "operator-1", username: "operator01", roles: ["OPERATOR"], mustChangePassword: false } });
    render(OrdersPage);

    const todayButton = await screen.findByRole("button", { name: "今日新增 1" });
    expect(todayButton).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("今日乘客")).toBeInTheDocument();
    expect(screen.queryByText("历史乘客")).not.toBeInTheDocument();

    await fireEvent.click(screen.getByRole("button", { name: "历史订单 1" }));
    expect(screen.getByText("历史乘客")).toBeInTheDocument();
    expect(screen.queryByText("今日乘客")).not.toBeInTheDocument();
  });

  it("在详情抽屉展示不可服务订单的完整诊断", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ data: [{
      id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      passengerName: "罗老师",
      passengerPhone: "13800000000",
      passengerCount: 1,
      requestType: "IMMEDIATE",
      originLng: 105.258224,
      originLat: 35.197636,
      destinationLng: 105.327705,
      destinationLat: 35.283669,
      originAddress: "高铁站",
      destinationAddress: "陇阳镇",
      coordinateSystem: "GCJ02",
      originAddressSource: "VIRTUAL_STOP",
      destinationAddressSource: "VIRTUAL_STOP",
      requestedDepartureAt: "2026-08-03T06:57:00Z",
      createdAt: new Date().toISOString(),
      status: "UNSERVICEABLE",
      dispatchFailure: {
        code: "ALL_CANDIDATES_REJECTED",
        summary: "所有候选方案均未满足调度约束",
        candidateCount: 4,
        rejectedReasons: ["WAIT_TIME_EXCEEDED"],
        maxWaitMinutes: 5,
        maxDetourMinutes: 8,
        mapProvider: "AMAP",
        mapDegraded: false,
        pickupToDestinationDistanceMeters: 13523,
        pickupToDestinationDurationSeconds: 1391
      }
    }] }), { status: 200, headers: { "Content-Type": "application/json" } }));
    authStore.setSessionForTest({
      accessToken: "dispatcher-token",
      user: { id: "dispatcher-1", username: "dispatcher01", roles: ["DISPATCHER"], mustChangePassword: false }
    });

    render(OrdersPage);

    await screen.findByText("不可服务");
    expect(screen.queryByText("所有候选方案均未满足调度约束")).not.toBeInTheDocument();
    await fireEvent.click(screen.getByRole("button", { name: "查看详情" }));
    expect(screen.getByRole("dialog", { name: "订单详情" })).toBeInTheDocument();
    expect(screen.getByText("所有候选方案均未满足调度约束")).toBeInTheDocument();
    expect(screen.getByText("候选方案数：4")).toBeInTheDocument();
    expect(screen.getByText("拒绝原因：WAIT_TIME_EXCEEDED")).toBeInTheDocument();
    expect(screen.getByText("最大等候：5 分钟")).toBeInTheDocument();
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
          createdAt: new Date().toISOString(),
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
        createdAt: new Date().toISOString(),
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
    expect(screen.getByText("剩余 3 分 0 秒")).toBeInTheDocument();
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
          createdAt: new Date().toISOString(),
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

  it("confirms a cancelled order as passenger cancelled without cancelling it again", async () => {
    const orderId = "66666666-6666-6666-6666-666666666666";
    const postedBodies: string[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, options) => {
      const url = String(input);
      if (url.endsWith(`/api/orders/${orderId}/cancellation-reason-confirmation`) && options?.method === "POST") {
        postedBodies.push(String(options.body));
        return new Response(JSON.stringify({ data: { id: orderId, status: "CANCELLED" } }), {
          status: 200,
          headers: { "Content-Type": "application/json" }
        });
      }
      return new Response(JSON.stringify({
        data: postedBodies.length === 0 ? [{
          id: orderId,
          passengerName: "已取消乘客",
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
          createdAt: new Date().toISOString(),
          status: "CANCELLED"
        }] : []
      }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    });
    authStore.setSessionForTest({
      accessToken: "dispatcher-token",
      user: { id: "dispatcher-1", username: "dispatcher01", roles: ["DISPATCHER"], mustChangePassword: false }
    });

    render(OrdersPage);

    await fireEvent.click(await screen.findByRole("button", { name: "确认乘客取消" }));

    await waitFor(() => expect(postedBodies).toHaveLength(1));
    expect(JSON.parse(postedBodies[0]!)).toEqual({ reason: "乘客取消" });
  });
});
