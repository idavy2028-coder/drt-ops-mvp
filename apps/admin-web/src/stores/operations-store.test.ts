import { describe, expect, it, vi } from "vitest";
import type { OperationsDashboard } from "../api/types";

const getOperationsDashboard = vi.hoisted(() => vi.fn());
vi.mock("../api/metrics", () => ({
  getOperationsDashboard,
  getOperationsSummary: vi.fn()
}));

import { useOperationsStore } from "./operationsStore";

describe("operations dashboard store", () => {
  it("distinguishes first load from refresh and keeps the last successful snapshot on failure", async () => {
    const dashboard = dashboardFixture();
    getOperationsDashboard
      .mockResolvedValueOnce(dashboard)
      .mockRejectedValueOnce(new Error("offline"));
    const store = useOperationsStore();

    const firstLoad = store.loadDashboard("2026-08-13");
    expect(store.state.loading).toBe(true);
    expect(store.state.refreshing).toBe(false);
    await expect(firstLoad).resolves.toBe(true);
    expect(store.state.dashboard).toEqual(dashboard);
    expect(store.state.loading).toBe(false);

    const refresh = store.loadDashboard("2026-08-13");
    expect(store.state.loading).toBe(false);
    expect(store.state.refreshing).toBe(true);
    expect(store.state.dashboard).toEqual(dashboard);
    await expect(refresh).resolves.toBe(false);

    expect(store.state.dashboard).toEqual(dashboard);
    expect(store.state.refreshing).toBe(false);
    expect(store.state.error).toBe("运营数据加载失败");
  });
});

function dashboardFixture(): OperationsDashboard {
  return {
    operatingDate: "2026-08-13",
    rangeStart: "2026-08-07",
    rangeEnd: "2026-08-13",
    coreMetrics: {
      orderVolume: { count: 128, baseline: "117.00", changeRate: "0.0940", status: "NORMAL" },
      taskCompletion: { completed: 110, total: 119, rate: "0.9244", baselineRate: "0.9030", status: "NORMAL" },
      averageWait: { minutes: "8.60", sampleCount: 121, baselineMinutes: "10.00", changeRate: "-0.1400", status: "LOW" },
      vehicleUtilization: { utilized: 19, available: 25, rate: "0.7600", baselineRate: "0.7200", status: "NORMAL" }
    },
    trend: [
      trendPoint("2026-08-07"),
      trendPoint("2026-08-08"),
      trendPoint("2026-08-09"),
      trendPoint("2026-08-10"),
      trendPoint("2026-08-11"),
      trendPoint("2026-08-12"),
      trendPoint("2026-08-13")
    ],
    distributions: {
      orders: [
        { key: "PENDING", label: "待处理", count: 14, rate: "0.1094" },
        { key: "IN_PROGRESS", label: "执行中", count: 23, rate: "0.1797" },
        { key: "COMPLETED", label: "已完成", count: 83, rate: "0.6484" },
        { key: "EXCEPTION_CANCELLED", label: "异常 / 取消", count: 8, rate: "0.0625" }
      ],
      tasks: [
        { key: "PENDING_DEPARTURE", label: "待发车", count: 5, rate: "0.1613" },
        { key: "IN_PROGRESS", label: "执行中", count: 6, rate: "0.1935" },
        { key: "COMPLETED", label: "已完成", count: 16, rate: "0.5161" },
        { key: "EXCEPTION_CANCELLED", label: "异常 / 取消", count: 4, rate: "0.1291" }
      ],
      vehicles: [
        { key: "IN_SERVICE", label: "执行中", count: 19, rate: "0.7600" },
        { key: "IDLE", label: "空闲", count: 4, rate: "0.1600" },
        { key: "UNAVAILABLE", label: "异常 / 不可用", count: 2, rate: "0.0800" }
      ]
    },
    generatedAt: "2026-08-13T09:32:00+08:00"
  };
}

function trendPoint(date: string): OperationsDashboard["trend"][number] {
  return {
    date,
    orderCount: 128,
    completedTasks: 110,
    totalTasks: 119,
    taskCompletionRate: "0.9244",
    averageWaitMinutes: "8.60",
    waitSampleCount: 121,
    utilizedVehicles: 19,
    availableVehicles: 25,
    vehicleUtilizationRate: "0.7600"
  };
}
