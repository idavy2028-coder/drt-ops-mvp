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

  it("refreshes the current operations summary with today's date", async () => {
    const getSummary = vi.mocked(getOperationsSummary);
    getSummary.mockClear();
    const today = new Date().toISOString().slice(0, 10);
    let resolveInitial!: (value: Awaited<ReturnType<typeof getOperationsSummary>>) => void;
    const initialRequest = new Promise<Awaited<ReturnType<typeof getOperationsSummary>>>((resolve) => {
      resolveInitial = resolve;
    });
    getSummary.mockImplementationOnce(() => initialRequest);

    render(DashboardPage);

    await waitFor(() => {
      expect(getSummary).toHaveBeenCalledTimes(1);
    });
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

    const refreshButton = screen.getByRole("button", { name: "刷新" });
    await refreshButton.click();

    await waitFor(() => {
      expect(getSummary).toHaveBeenCalledTimes(2);
    });
    expect(getSummary).toHaveBeenNthCalledWith(1, today);
    expect(getSummary).toHaveBeenNthCalledWith(2, today);
  });
});
