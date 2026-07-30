// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import type { RideOrder } from "../api/types";
import NoShowConfirmDialog from "./NoShowConfirmDialog.vue";

describe("NoShowConfirmDialog", () => {
  afterEach(cleanup);

  it("requires an explicit reason before emitting one confirmation", async () => {
    const order = {
      id: "12345678-1234-1234-1234-123456789012",
      estimatedBoardingAt: "2026-07-30T12:05:00+08:00",
      noShowEligibleAt: "2026-07-30T12:10:00+08:00",
      noShowWaitedSeconds: 301
    } as RideOrder;

    const view = render(NoShowConfirmDialog, {
      props: { order, submitting: false }
    });

    expect(screen.getByRole("dialog", { name: "确认乘客未到" })).toBeInTheDocument();
    expect(screen.getByText("订单 12345678")).toBeInTheDocument();
    expect(screen.getByText(/取消车辆任务、释放车辆和驾驶员/)).toBeInTheDocument();
    const confirm = screen.getByRole("button", { name: "确认乘客未到并关闭订单" });
    expect(confirm).toBeDisabled();

    await fireEvent.update(screen.getByRole("combobox", { name: "爽约原因" }), "WAITING_PERIOD_EXPIRED");
    expect(confirm).toBeEnabled();
    await fireEvent.click(confirm);

    const emitted = view.emitted().confirm as Array<[
      { reason: string; idempotencyKey: string }
    ]> | undefined;
    expect(emitted).toHaveLength(1);
    expect(emitted?.[0]?.[0]).toMatchObject({
      reason: "乘客在等待期内未出现"
    });
    expect(String(emitted?.[0]?.[0].idempotencyKey))
      .toMatch(/^[0-9a-f-]{36}$/);
  });
});
