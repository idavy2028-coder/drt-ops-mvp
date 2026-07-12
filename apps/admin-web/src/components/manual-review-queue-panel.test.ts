// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import ManualReviewQueuePanel from "./ManualReviewQueuePanel.vue";
import type { ManualReviewQueueItem } from "../api/types";

const review: ManualReviewQueueItem = {
  decisionId: "decision-1",
  orderId: "order-1",
  passengerName: "Manual review rider",
  passengerCount: 2,
  requestedDepartureAt: "2026-07-08T02:30:00Z",
  bestVehicleId: "vehicle-1",
  candidateCount: 3
};

afterEach(() => {
  cleanup();
});

describe("ManualReviewQueuePanel", () => {
  it("renders queue item fields and emits approve", async () => {
    const { emitted } = render(ManualReviewQueuePanel, {
      props: { items: [review] }
    });

    expect(screen.getByRole("heading", { name: "人工复核队列" })).toBeInTheDocument();
    expect(screen.getByText("Manual review rider")).toBeInTheDocument();
    expect(screen.getByText(/2 人/)).toBeInTheDocument();
    expect(screen.getByText("候选车辆 vehicle-1")).toBeInTheDocument();
    expect(screen.getByText("候选方案 3 个")).toBeInTheDocument();

    await fireEvent.click(screen.getByRole("button", { name: "确认派单" }));

    expect(emitted().approve?.[0]).toEqual([review.decisionId]);
  });

  it("requires a reject reason before emitting reject", async () => {
    const { emitted } = render(ManualReviewQueuePanel, {
      props: { items: [review] }
    });

    await fireEvent.click(screen.getByRole("button", { name: "拒绝" }));
    await fireEvent.click(screen.getByRole("button", { name: "确认拒绝" }));
    expect(emitted().reject).toBeUndefined();
    expect(screen.getByText("请填写拒绝原因")).toBeInTheDocument();

    await fireEvent.update(screen.getByLabelText("拒绝原因"), "车辆临时不可用");
    await fireEvent.click(screen.getByRole("button", { name: "确认拒绝" }));

    expect(emitted().reject?.[0]).toEqual([{ decisionId: review.decisionId, reason: "车辆临时不可用" }]);
  });

  it("renders empty state", () => {
    render(ManualReviewQueuePanel, {
      props: { items: [] }
    });

    expect(screen.getByText("暂无待复核订单")).toBeInTheDocument();
  });
});
