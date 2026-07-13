// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import { getOperationsSummary } from "../api/metrics";
import DashboardPage from "./DashboardPage.vue";

vi.mock("../api/metrics", () => ({
  getOperationsSummary: vi.fn().mockResolvedValue({
    orderCount: 1,
    confirmationRate: "0.875",
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

  it("renders dashboard rates and durations in operational formats", async () => {
    render(DashboardPage);

    expect((await screen.findAllByText("88%")).length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText("3.0 分钟").length).toBeGreaterThanOrEqual(2);
  });

  it("refreshes the operations summary using the current Shanghai operating day", async () => {
    const getSummary = vi.mocked(getOperationsSummary);
    getSummary.mockClear();
    let resolveInitial!: (value: Awaited<ReturnType<typeof getOperationsSummary>>) => void;
    const initialRequest = new Promise<Awaited<ReturnType<typeof getOperationsSummary>>>((resolve) => {
      resolveInitial = resolve;
    });
    getSummary.mockImplementationOnce(() => initialRequest);

    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-13T16:30:00.000Z"));
    try {
      render(DashboardPage);

      await waitFor(() => {
        expect(getSummary).toHaveBeenCalledTimes(1);
      });
      expect(getSummary).toHaveBeenNthCalledWith(1, "2026-07-14");
      resolveInitial({
        orderCount: 1,
        confirmationRate: "0.875",
        autoDispatchRate: "1.0000",
        manualReviewRate: "0.0000",
        averageWaitMinutes: "3.00",
        averageDetourMinutes: "1.00",
        taskCompletionRate: "1.0000",
        exceptionCloseRate: "1.0000",
        vehicleUtilizationRate: "1.0000"
      });

      await waitFor(() => {
        expect(screen.getByRole("button", { name: "刷新" })).toBeEnabled();
      });

      vi.advanceTimersByTime(24 * 60 * 60 * 1000);
      await screen.getByRole("button", { name: "刷新" }).click();

      await waitFor(() => {
        expect(getSummary).toHaveBeenCalledTimes(2);
      });
      expect(getSummary).toHaveBeenNthCalledWith(2, "2026-07-15");
    } finally {
      vi.useRealTimers();
    }
  });
});
