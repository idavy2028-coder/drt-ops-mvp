// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/vue";
import { defineComponent } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { OperationsDashboard } from "../api/types";
import AppFeedback from "../components/AppFeedback.vue";
import { feedbackStore } from "../stores/feedbackStore";
import DashboardPage from "./DashboardPage.vue";

const operationsMock = vi.hoisted(() => ({
  state: {
    loading: false,
    refreshing: false,
    error: "",
    dashboard: null as OperationsDashboard | null
  },
  loadDashboard: vi.fn()
}));

vi.mock("../stores/operationsStore", () => ({
  useOperationsStore: () => operationsMock
}));

const dashboardFixture: OperationsDashboard = {
  operatingDate: "2026-08-13",
  rangeStart: "2026-08-07",
  rangeEnd: "2026-08-13",
  coreMetrics: {
    orderVolume: { count: 128, baseline: "117.4", changeRate: "0.0903", status: "HIGH" },
    taskCompletion: { completed: 118, total: 128, rate: "0.9219", baselineRate: "0.9070", status: "NORMAL" },
    averageWait: { minutes: "8.6", sampleCount: 124, baselineMinutes: "9.4", changeRate: "-0.0851", status: "LOW" },
    vehicleUtilization: { utilized: 31, available: 40, rate: "0.775", baselineRate: "0.742", status: "NORMAL" }
  },
  trend: [
    trendPoint("2026-08-07", 102, 94, 101, "0.9307", "9.8", 98, 28, 40, "0.7000"),
    trendPoint("2026-08-08", 109, 99, 107, "0.9252", "9.4", 105, 29, 40, "0.7250"),
    trendPoint("2026-08-09", 116, 103, 112, "0.9196", "9.1", 110, 30, 40, "0.7500"),
    trendPoint("2026-08-10", 111, 101, 110, "0.9182", "9.2", 108, 29, 40, "0.7250"),
    trendPoint("2026-08-11", 121, 111, 120, "0.9250", "8.9", 117, 31, 40, "0.7750"),
    trendPoint("2026-08-12", 128, 117, 126, "0.9286", "8.7", 123, 32, 40, "0.8000"),
    trendPoint("2026-08-13", 128, 118, 128, "0.9219", "8.6", 124, 31, 40, "0.7750")
  ],
  distributions: {
    orders: [
      { key: "PENDING", label: "待调度", count: 6, rate: "0.0469" },
      { key: "IN_PROGRESS", label: "执行中", count: 4, rate: "0.0313" },
      { key: "COMPLETED", label: "已完成", count: 113, rate: "0.8828" },
      { key: "EXCEPTION_CANCELLED", label: "异常/取消", count: 5, rate: "0.0390" }
    ],
    tasks: [
      { key: "PENDING_DEPARTURE", label: "待发车", count: 5, rate: "0.0391" },
      { key: "IN_PROGRESS", label: "执行中", count: 3, rate: "0.0234" },
      { key: "COMPLETED", label: "已完成", count: 118, rate: "0.9219" },
      { key: "EXCEPTION_CANCELLED", label: "异常/取消", count: 2, rate: "0.0156" }
    ],
    vehicles: [
      { key: "IN_SERVICE", label: "运营中", count: 31, rate: "0.775" },
      { key: "IDLE", label: "空闲", count: 7, rate: "0.175" },
      { key: "UNAVAILABLE", label: "不可用", count: 2, rate: "0.050" }
    ]
  },
  generatedAt: "2026-08-13T10:28:00+08:00"
};

describe("DashboardPage", () => {
  beforeEach(() => {
    operationsMock.state.loading = false;
    operationsMock.state.refreshing = false;
    operationsMock.state.error = "";
    operationsMock.state.dashboard = dashboardFixture;
    operationsMock.loadDashboard.mockReset().mockResolvedValue(true);
    for (const item of [...feedbackStore.items]) {
      feedbackStore.dismiss(item.id);
    }
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it("renders four core metrics with absolute values, ratios and baselines", () => {
    render(DashboardPage);

    expect(screen.getByRole("article", { name: "订单量" })).toHaveTextContent("128");
    expect(screen.getByRole("article", { name: "任务完成率" })).toHaveTextContent("118 / 128");
    expect(screen.getByRole("article", { name: "任务完成率" })).toHaveTextContent("92.2%");
    expect(screen.getByRole("article", { name: "平均等待" })).toHaveTextContent("8.6");
    expect(screen.getByRole("article", { name: "平均等待" })).toHaveTextContent("124 单样本");
    expect(screen.getByRole("article", { name: "车辆利用率" })).toHaveTextContent("31 / 40");
    expect(screen.getByRole("article", { name: "订单量" })).toHaveTextContent("偏高");
    expect(screen.getByRole("article", { name: "车辆利用率" })).toHaveTextContent("基线 74.2%");
    expect(screen.getByRole("article", { name: "车辆利用率" })).toHaveTextContent("按当前可调度车辆口径");
  });

  it("keeps the seven-day title, x-axis dates and percentage distributions aligned", () => {
    render(DashboardPage);

    expect(screen.getByRole("heading", { name: "近 7 天订单量与任务完成率趋势（08-07 至 08-13）" })).toBeInTheDocument();
    expect(screen.getAllByTestId("trend-date")).toHaveLength(7);
    expect(screen.getByText("待调度 6 单 · 4.7%")).toBeInTheDocument();
    expect(screen.getByText("已完成 118 项 · 92.2%")).toBeInTheDocument();
    expect(screen.getByText("运营中 31 辆 · 77.5%")).toBeInTheDocument();
  });

  it("shows four skeleton cards while the first snapshot is loading", () => {
    operationsMock.state.dashboard = null;
    operationsMock.state.loading = true;

    render(DashboardPage);

    expect(screen.getAllByLabelText("运营指标加载中")).toHaveLength(4);
  });

  it("refreshes by the current Shanghai operating day and reports success with a global toast", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-08-12T16:30:00.000Z"));
    const PageWithFeedback = defineComponent({
      components: { AppFeedback, DashboardPage },
      template: "<DashboardPage /><AppFeedback />"
    });

    render(PageWithFeedback);
    await waitFor(() => expect(operationsMock.loadDashboard).toHaveBeenCalledWith("2026-08-13"));

    vi.advanceTimersByTime(24 * 60 * 60 * 1000);
    await screen.getByRole("button", { name: "刷新数据" }).click();

    await waitFor(() => expect(operationsMock.loadDashboard).toHaveBeenLastCalledWith("2026-08-14"));
    expect(screen.getByText("运营看板已更新")).toBeInTheDocument();
  });

  it("keeps the previous snapshot visible and reports a refresh failure", async () => {
    operationsMock.loadDashboard
      .mockResolvedValueOnce(true)
      .mockImplementationOnce(async () => {
        operationsMock.state.error = "运营数据加载失败，请稍后重试";
        return false;
      });
    const PageWithFeedback = defineComponent({
      components: { AppFeedback, DashboardPage },
      template: "<DashboardPage /><AppFeedback />"
    });

    render(PageWithFeedback);
    await waitFor(() => expect(operationsMock.loadDashboard).toHaveBeenCalledTimes(1));
    await screen.getByRole("button", { name: "刷新数据" }).click();

    expect(await screen.findByText("运营数据加载失败，请稍后重试")).toBeInTheDocument();
    expect(screen.getByRole("article", { name: "订单量" })).toHaveTextContent("128");
  });
});

function trendPoint(
  date: string,
  orderCount: number,
  completedTasks: number,
  totalTasks: number,
  taskCompletionRate: string,
  averageWaitMinutes: string,
  waitSampleCount: number,
  utilizedVehicles: number,
  availableVehicles: number,
  vehicleUtilizationRate: string
) {
  return {
    date,
    orderCount,
    completedTasks,
    totalTasks,
    taskCompletionRate,
    averageWaitMinutes,
    waitSampleCount,
    utilizedVehicles,
    availableVehicles,
    vehicleUtilizationRate
  };
}
