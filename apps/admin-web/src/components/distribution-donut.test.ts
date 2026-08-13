// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import type { DashboardDistributionItem } from "../api/types";
import DistributionDonut from "./DistributionDonut.vue";

describe("DistributionDonut", () => {
  afterEach(cleanup);

  it("shows count and corrected percentage for every category", () => {
    render(DistributionDonut, {
      props: {
        title: "订单状态分布",
        unit: "单",
        items: orderDistribution()
      }
    });

    expect(screen.getByRole("heading", { name: "订单状态分布" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "订单状态分布，共 128 单" })).toBeInTheDocument();
    expect(screen.getByText("已完成 83 单 · 64.8%")).toBeInTheDocument();
    expect(screen.getByText("执行中 23 单 · 18.0%")).toBeInTheDocument();
    expect(screen.getByText("待处理 14 单 · 10.9%")).toBeInTheDocument();
    expect(screen.getByText("异常 / 取消 8 单 · 6.3%")).toBeInTheDocument();
    const totalPercentage = screen.getAllByTestId("distribution-percentage")
      .map((node) => Number(node.getAttribute("data-percentage")))
      .reduce((sum, value) => sum + value, 0);
    expect(totalPercentage).toBe(100);
  });

  it("shows a friendly empty state instead of a decorative zero donut", () => {
    render(DistributionDonut, {
      props: {
        title: "车辆状态分布",
        unit: "辆",
        items: [
          { key: "IN_SERVICE", label: "执行中", count: 0, rate: null },
          { key: "IDLE", label: "空闲", count: 0, rate: null },
          { key: "UNAVAILABLE", label: "异常 / 不可用", count: 0, rate: null }
        ]
      }
    });

    expect(screen.getByText("当日暂无可统计数据")).toBeInTheDocument();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });
});

function orderDistribution(): DashboardDistributionItem[] {
  return [
    { key: "COMPLETED", label: "已完成", count: 83, rate: "0.6484" },
    { key: "IN_PROGRESS", label: "执行中", count: 23, rate: "0.1797" },
    { key: "PENDING", label: "待处理", count: 14, rate: "0.1094" },
    { key: "EXCEPTION_CANCELLED", label: "异常 / 取消", count: 8, rate: "0.0625" }
  ];
}
