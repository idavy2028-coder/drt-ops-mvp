// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import type { RideOrder } from "../api/types";
import OrderCancelDialog from "./OrderCancelDialog.vue";

function orderFixture(): RideOrder {
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
    noShowWaitedSeconds: 0
  };
}

describe("OrderCancelDialog", () => {
  afterEach(cleanup);

  it("要求填写 2–100 字取消原因并提交去除首尾空格的内容", async () => {
    const view = render(OrderCancelDialog, {
      props: { order: orderFixture(), submitting: false, submitError: "" }
    });
    const reason = screen.getByRole("textbox", { name: "取消原因" });
    const confirm = screen.getByRole("button", { name: "确认取消" });

    await fireEvent.update(reason, " ");
    await fireEvent.click(confirm);
    expect(screen.getByText("请输入 2–100 字取消原因")).toBeInTheDocument();
    expect(view.emitted("confirm")).toBeUndefined();

    await fireEvent.update(reason, "a".repeat(101));
    await fireEvent.click(confirm);
    expect(view.emitted("confirm")).toBeUndefined();

    await fireEvent.update(reason, " 乘客临时调整行程 ");
    await fireEvent.click(confirm);
    expect(view.emitted("confirm")).toEqual([[{ reason: "乘客临时调整行程" }]]);
  });

  it("提交期间禁用输入和关闭，并保留服务端错误", () => {
    render(OrderCancelDialog, {
      props: { order: orderFixture(), submitting: true, submitError: "订单状态已变化，请刷新" }
    });

    expect(screen.getByRole("textbox", { name: "取消原因" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "正在取消" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "返回" })).toBeDisabled();
    expect(screen.getByText("订单状态已变化，请刷新")).toBeInTheDocument();
  });
});
