import type {
  DashboardDistributionItem,
  DashboardMetricStatus,
  DecimalValue
} from "../api/types";

const statusLabels: Record<DashboardMetricStatus, string> = {
  NORMAL: "正常",
  HIGH: "偏高",
  LOW: "偏低",
  NO_BASELINE: "暂无基线"
};

export function formatNullablePercentage(value: DecimalValue | null): string {
  const parsed = metricNumber(value);
  return parsed === null ? "--" : `${(parsed * 100).toFixed(1)}%`;
}

export function formatSignedRate(value: DecimalValue | null): string {
  return formatSigned(value, 100, "%");
}

export function formatSignedPoints(value: DecimalValue | null): string {
  return formatSigned(value, 100, " 个百分点");
}

export function metricStatusLabel(status: DashboardMetricStatus): string {
  return statusLabels[status];
}

export function distributionPercentages(items: DashboardDistributionItem[]): number[] {
  const total = items.reduce((sum, item) => sum + Math.max(0, item.count), 0);
  if (total === 0) {
    return items.map(() => 0);
  }

  const nonZeroIndexes = items
    .map((item, index) => item.count > 0 ? index : -1)
    .filter((index) => index >= 0);
  const lastNonZeroIndex = nonZeroIndexes[nonZeroIndexes.length - 1];
  const tenths = items.map((item, index) => {
    if (item.count <= 0 || index === lastNonZeroIndex) {
      return 0;
    }
    return Math.round((item.count / total) * 1000);
  });
  tenths[lastNonZeroIndex] = 1000 - tenths.reduce((sum, value) => sum + value, 0);
  return tenths.map((value) => value / 10);
}

export function shortDate(value: string): string {
  const matched = /^\d{4}-(\d{2})-(\d{2})$/.exec(value);
  return matched ? `${matched[1]}-${matched[2]}` : value;
}

export function shortDateRange(start: string, end: string): string {
  return `${shortDate(start)} 至 ${shortDate(end)}`;
}

export function metricNumber(value: DecimalValue | null): number | null {
  if (value === null || (typeof value === "string" && value.trim() === "")) {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function formatSigned(value: DecimalValue | null, multiplier: number, suffix: string): string {
  const parsed = metricNumber(value);
  if (parsed === null) {
    return "--";
  }
  const scaled = parsed * multiplier;
  const normalized = Math.abs(scaled) < 0.05 ? 0 : scaled;
  const prefix = normalized > 0 ? "+" : "";
  return `${prefix}${normalized.toFixed(1)}${suffix}`;
}
