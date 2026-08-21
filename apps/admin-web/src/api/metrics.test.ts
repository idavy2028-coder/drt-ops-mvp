import { afterEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import type { OperationsDashboard } from "./types";
import { getOperationsDashboard } from "./metrics";

describe("operations dashboard API", () => {
  afterEach(() => {
    authStore.clearSessionForTest();
    vi.unstubAllGlobals();
  });

  it("requests the seven-day dashboard ending on the selected operating day", async () => {
    const dashboard = dashboardFixture();
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(dashboard));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getOperationsDashboard("2026-08-13")).resolves.toEqual(dashboard);

    const requestedUrl = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost");
    expect(requestedUrl.pathname).toBe("/api/metrics/operations-dashboard");
    expect(requestedUrl.searchParams.get("endDate")).toBe("2026-08-13");
    expect(requestedUrl.searchParams.get("days")).toBe("7");
  });
});

function jsonResponse(data: OperationsDashboard): Response {
  return new Response(JSON.stringify({ data }), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });
}

function dashboardFixture(): OperationsDashboard {
  return {
    operatingDate: "2026-08-13",
    rangeStart: "2026-08-07",
    rangeEnd: "2026-08-13",
    coreMetrics: {
      orderVolume: { count: 2, baseline: "1.14", changeRate: "0.7544", status: "HIGH" },
      taskCompletion: { completed: 1, total: 2, rate: "0.5000", baselineRate: "1.0000", status: "LOW" },
      averageWait: { minutes: "8.00", sampleCount: 1, baselineMinutes: "10.00", changeRate: "-0.2000", status: "LOW" },
      vehicleUtilization: { utilized: 2, available: 2, rate: "1.0000", baselineRate: "0.0714", status: "HIGH" }
    },
    trend: [
      trendPoint("2026-08-07"),
      trendPoint("2026-08-08"),
      trendPoint("2026-08-09"),
      trendPoint("2026-08-10"),
      trendPoint("2026-08-11"),
      trendPoint("2026-08-12"),
      {
        date: "2026-08-13",
        orderCount: 2,
        completedTasks: 1,
        totalTasks: 2,
        taskCompletionRate: "0.5000",
        averageWaitMinutes: "8.00",
        waitSampleCount: 1,
        utilizedVehicles: 2,
        availableVehicles: 2,
        vehicleUtilizationRate: "1.0000"
      }
    ],
    distributions: {
      orders: [
        { key: "PENDING", label: "待处理", count: 0, rate: "0.0000" },
        { key: "IN_PROGRESS", label: "执行中", count: 0, rate: "0.0000" },
        { key: "COMPLETED", label: "已完成", count: 1, rate: "0.5000" },
        { key: "EXCEPTION_CANCELLED", label: "异常 / 取消", count: 1, rate: "0.5000" }
      ],
      tasks: [
        { key: "PENDING_DEPARTURE", label: "待发车", count: 0, rate: "0.0000" },
        { key: "IN_PROGRESS", label: "执行中", count: 1, rate: "0.5000" },
        { key: "COMPLETED", label: "已完成", count: 1, rate: "0.5000" },
        { key: "EXCEPTION_CANCELLED", label: "异常 / 取消", count: 0, rate: "0.0000" }
      ],
      vehicles: [
        { key: "IN_SERVICE", label: "执行中", count: 0, rate: "0.0000" },
        { key: "IDLE", label: "空闲", count: 2, rate: "1.0000" },
        { key: "UNAVAILABLE", label: "异常 / 不可用", count: 0, rate: "0.0000" }
      ]
    },
    generatedAt: "2026-08-13T09:32:00+08:00"
  };
}

function trendPoint(date: string): OperationsDashboard["trend"][number] {
  return {
    date,
    orderCount: 0,
    completedTasks: 0,
    totalTasks: 0,
    taskCompletionRate: null,
    averageWaitMinutes: null,
    waitSampleCount: 0,
    utilizedVehicles: 0,
    availableVehicles: 2,
    vehicleUtilizationRate: "0.0000"
  };
}
