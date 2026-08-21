// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it } from "vitest";
import DashboardMetricCard from "./DashboardMetricCard.vue";

describe("DashboardMetricCard", () => {
  afterEach(cleanup);

  it("shows absolute values, ratio, status, comparison and baseline together", () => {
    render(DashboardMetricCard, {
      props: {
        label: "任务完成率",
        primary: "110/119",
        secondary: "92.4%",
        status: "NORMAL",
        comparison: "较基线 +2.1 个百分点",
        baseline: "基线 90.3%",
        icon: "✓",
        accent: "blue"
      }
    });

    const card = screen.getByRole("article", { name: "任务完成率" });
    expect(card).toHaveTextContent("110/119");
    expect(card).toHaveTextContent("92.4%");
    expect(card).toHaveTextContent("正常");
    expect(card).toHaveTextContent("较基线 +2.1 个百分点");
    expect(card).toHaveTextContent("基线 90.3%");
  });

  it("labels missing comparison data as no baseline", () => {
    render(DashboardMetricCard, {
      props: {
        label: "当日订单量",
        primary: "0",
        secondary: "单",
        status: "NO_BASELINE",
        comparison: "较基线 --",
        baseline: "暂无历史基线",
        icon: "单",
        accent: "teal"
      }
    });

    expect(screen.getByRole("article", { name: "当日订单量" })).toHaveTextContent("暂无基线");
  });
});
