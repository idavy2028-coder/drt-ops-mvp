// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import DashboardPage from "./DashboardPage.vue";

vi.mock("../api/metrics", () => ({
  getOperationsSummary: vi.fn().mockResolvedValue({
    orderCount: 1,
    confirmationRate: "1.0000",
    autoDispatchRate: "1.0000",
    manualReviewRate: "0.0000",
    averageWaitMinutes: "3.00",
    averageDetourMinutes: "1.00",
    taskCompletionRate: "1.0000",
    exceptionCloseRate: "1.0000",
    vehicleUtilizationRate: "1.0000"
  })
}));

describe("DashboardPage", () => {
  afterEach(() => {
    cleanup();
  });

  it("renders first-version operations metrics", async () => {
    render(DashboardPage);

    expect(await screen.findByText("订单确认率")).toBeInTheDocument();
    expect(screen.getByText("自动派发率")).toBeInTheDocument();
    expect(screen.getByText("平均等待时间")).toBeInTheDocument();
    expect(screen.getByText("车辆利用率")).toBeInTheDocument();
  });

  it("provides a refresh control for the current operations summary", async () => {
    render(DashboardPage);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "刷新" })).toBeEnabled();
    });
  });
});
