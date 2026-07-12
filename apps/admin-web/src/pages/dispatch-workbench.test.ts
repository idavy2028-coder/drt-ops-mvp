// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import DispatchWorkbenchPage from "./DispatchWorkbenchPage.vue";

const mapConstructor = vi.hoisted(() => vi.fn(() => ({ remove: vi.fn() })));
const orderApi = vi.hoisted(() => ({
  listOrders: vi.fn()
}));
const taskApi = vi.hoisted(() => ({
  listTasks: vi.fn()
}));
const manualReviewApi = vi.hoisted(() => ({
  listManualReviews: vi.fn(),
  approveManualReview: vi.fn(),
  rejectManualReview: vi.fn()
}));

vi.mock("maplibre-gl", () => ({
  default: {
    Map: mapConstructor
  }
}));

vi.mock("../api/orders", () => orderApi);
vi.mock("../api/tasks", () => taskApi);
vi.mock("../api/manualReviews", () => manualReviewApi);

const review = {
  decisionId: "decision-1",
  orderId: "order-1",
  passengerName: "Manual review rider",
  passengerCount: 2,
  requestedDepartureAt: "2026-07-08T02:30:00Z",
  bestVehicleId: "vehicle-1",
  candidateCount: 3
};

beforeEach(() => {
  orderApi.listOrders.mockResolvedValue([
    {
      id: "order-1",
      passengerName: "Manual review rider",
      passengerPhone: "13800000000",
      passengerCount: 2,
      requestType: "IMMEDIATE",
      originLng: 116.312,
      originLat: 39.94,
      destinationLng: 116.325,
      destinationLat: 39.936,
      requestedDepartureAt: "2026-07-08T02:30:00Z",
      status: "PENDING_MANUAL_REVIEW"
    }
  ]);
  taskApi.listTasks.mockResolvedValue([]);
  manualReviewApi.listManualReviews.mockResolvedValue([review]);
  manualReviewApi.approveManualReview.mockResolvedValue({ vehicleTaskId: "task-1" });
  manualReviewApi.rejectManualReview.mockResolvedValue({ vehicleTaskId: undefined });
  mapConstructor.mockClear();
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("DispatchWorkbenchPage", () => {
  it("renders dispatch workbench operational regions", async () => {
    render(DispatchWorkbenchPage);

    expect(await screen.findByText("实时订单")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "车辆任务" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "人工复核队列" })).toBeInTheDocument();
    expect(screen.getByLabelText("调度地图")).toBeInTheDocument();
    await new Promise((resolve) => window.setTimeout(resolve, 0));
    expect(mapConstructor).not.toHaveBeenCalled();
  });

  it("approves manual review and reloads workbench data", async () => {
    render(DispatchWorkbenchPage);

    expect(await screen.findByText("Manual review rider")).toBeInTheDocument();
    await fireEvent.click(screen.getByRole("button", { name: "确认派单" }));

    expect(manualReviewApi.approveManualReview).toHaveBeenCalledWith(review.decisionId);
    await waitFor(() => expect(taskApi.listTasks).toHaveBeenCalledTimes(2));
    expect(manualReviewApi.listManualReviews).toHaveBeenCalledTimes(2);
  });

  it("shows reject error and keeps queue item visible", async () => {
    manualReviewApi.rejectManualReview.mockRejectedValue(new Error("人工拒绝失败"));
    render(DispatchWorkbenchPage);

    expect(await screen.findByText("Manual review rider")).toBeInTheDocument();
    await fireEvent.click(screen.getByRole("button", { name: "拒绝" }));
    await fireEvent.update(screen.getByLabelText("拒绝原因"), "车辆临时不可用");
    await fireEvent.click(screen.getByRole("button", { name: "确认拒绝" }));

    expect(manualReviewApi.rejectManualReview).toHaveBeenCalledWith(review.decisionId, "车辆临时不可用");
    expect(await screen.findByText("人工拒绝失败")).toBeInTheDocument();
    expect(screen.getByText("Manual review rider")).toBeInTheDocument();
  });
});
