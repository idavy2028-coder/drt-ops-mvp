// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import type { DashboardTrendPoint } from "../api/types";
import OperationsTrendChart from "./OperationsTrendChart.vue";

describe("OperationsTrendChart", () => {
  afterEach(cleanup);

  it("keeps the combined title, axes and all seven dates aligned", () => {
    render(OperationsTrendChart, {
      props: {
        points: trendPoints(),
        rangeStart: "2026-01-01",
        rangeEnd: "2026-01-07"
      }
    });

    expect(screen.getByRole("heading", {
      name: "近 7 天订单量与任务完成率趋势（08-07 至 08-13）"
    })).toBeInTheDocument();
    expect(screen.getByText("订单量（单）")).toBeInTheDocument();
    expect(screen.getByText("任务完成率（%）")).toBeInTheDocument();
    expect(screen.getAllByTestId("trend-date").map((node) => node.textContent)).toEqual([
      "08-07", "08-08", "08-09", "08-10", "08-11", "08-12", "08-13"
    ]);
    expect(screen.getByRole("img", {
      name: "近 7 天订单量与任务完成率趋势（08-07 至 08-13）"
    })).toBeInTheDocument();
  });

  it("updates title, unit and series when average wait is selected", async () => {
    render(OperationsTrendChart, {
      props: { points: trendPoints(), rangeStart: "2026-08-07", rangeEnd: "2026-08-13" }
    });

    await fireEvent.click(screen.getByRole("button", { name: "平均等待" }));

    expect(screen.getByRole("heading", {
      name: "近 7 天平均等待时间趋势（08-07 至 08-13）"
    })).toBeInTheDocument();
    expect(screen.getByText("平均等待（分钟）")).toBeInTheDocument();
    expect(screen.queryByText("任务完成率（%）")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "平均等待" })).toHaveAttribute("aria-pressed", "true");
  });

  it("updates title and ratio unit when vehicle utilization is selected", async () => {
    render(OperationsTrendChart, {
      props: { points: trendPoints(), rangeStart: "2026-08-07", rangeEnd: "2026-08-13" }
    });

    await fireEvent.click(screen.getByRole("button", { name: "车辆利用率" }));

    expect(screen.getByRole("heading", {
      name: "近 7 天车辆利用率趋势（08-07 至 08-13）"
    })).toBeInTheDocument();
    expect(screen.getByText("车辆利用率（%）")).toBeInTheDocument();
    expect(screen.queryByText("订单量（单）")).not.toBeInTheDocument();
  });

  it("describes null samples as unavailable instead of drawing a zero result", () => {
    const points = trendPoints();
    points[2] = {
      ...points[2],
      taskCompletionRate: null,
      averageWaitMinutes: null,
      waitSampleCount: 0,
      vehicleUtilizationRate: null
    };

    render(OperationsTrendChart, {
      props: { points, rangeStart: "2026-08-07", rangeEnd: "2026-08-13" }
    });

    expect(screen.getByText("08-09：订单 118 单；任务完成率无有效样本")).toBeInTheDocument();
  });
});

function trendPoints(): DashboardTrendPoint[] {
  const dates = ["2026-08-07", "2026-08-08", "2026-08-09", "2026-08-10", "2026-08-11", "2026-08-12", "2026-08-13"];
  const orders = [111, 126, 118, 137, 129, 146, 128];
  return dates.map((date, index) => ({
    date,
    orderCount: orders[index],
    completedTasks: 88 + index,
    totalTasks: 100,
    taskCompletionRate: `${(0.88 + index * 0.007).toFixed(4)}`,
    averageWaitMinutes: `${(10.4 - index * 0.3).toFixed(2)}`,
    waitSampleCount: orders[index] - 4,
    utilizedVehicles: 17 + (index % 3),
    availableVehicles: 25,
    vehicleUtilizationRate: `${((17 + (index % 3)) / 25).toFixed(4)}`
  }));
}
