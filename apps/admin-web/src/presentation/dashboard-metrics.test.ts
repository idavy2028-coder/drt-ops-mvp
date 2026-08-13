import { describe, expect, it } from "vitest";
import type { DashboardDistributionItem } from "../api/types";
import {
  distributionPercentages,
  formatNullablePercentage,
  formatSignedPoints,
  formatSignedRate,
  metricStatusLabel,
  shortDateRange
} from "./dashboardMetrics";

describe("dashboard metric presentation", () => {
  it("formats ratios, changes and missing values for operations cards", () => {
    expect(formatNullablePercentage("0.9244")).toBe("92.4%");
    expect(formatNullablePercentage(null)).toBe("--");
    expect(formatSignedRate("0.0940")).toBe("+9.4%");
    expect(formatSignedRate("-0.1400")).toBe("-14.0%");
    expect(formatSignedPoints("0.0214")).toBe("+2.1 个百分点");
  });

  it("uses explicit Chinese labels for every metric status", () => {
    expect(metricStatusLabel("NORMAL")).toBe("正常");
    expect(metricStatusLabel("HIGH")).toBe("偏高");
    expect(metricStatusLabel("LOW")).toBe("偏低");
    expect(metricStatusLabel("NO_BASELINE")).toBe("暂无基线");
  });

  it("corrects displayed distribution percentages to exactly one hundred percent", () => {
    const items: DashboardDistributionItem[] = [
      { key: "a", label: "A", count: 1, rate: "0.3333" },
      { key: "b", label: "B", count: 1, rate: "0.3333" },
      { key: "c", label: "C", count: 1, rate: "0.3333" }
    ];

    const percentages = distributionPercentages(items);

    expect(percentages).toEqual([33.3, 33.3, 33.4]);
    expect(percentages.reduce((sum, value) => sum + value, 0)).toBe(100);
  });

  it("keeps zero-count categories at zero and returns zeroes for an empty distribution", () => {
    expect(distributionPercentages([
      { key: "empty", label: "空", count: 0, rate: null },
      { key: "all", label: "全部", count: 4, rate: "1.0000" }
    ])).toEqual([0, 100]);
    expect(distributionPercentages([])).toEqual([]);
  });

  it("formats the chart range without shifting ISO dates through the browser timezone", () => {
    expect(shortDateRange("2026-08-07", "2026-08-13")).toBe("08-07 至 08-13");
  });
});
