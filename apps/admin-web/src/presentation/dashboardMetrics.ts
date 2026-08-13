import type {
  DashboardDistributionItem,
  DashboardMetricStatus,
  DecimalValue
} from "../api/types";

const statusLabels: Record<DashboardMetricStatus, string> = {
  NORMAL: "正常",
  HIGH: "偏高",
  LOW: "偏低",
  NO_BASELINE: "暂无基线",
  NO_DATA: "暂无当日数据"
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

export function distributionPercentages(items: readonly DashboardDistributionItem[]): number[] {
  const counts = items.map((item) => Math.max(0, item.count));
  const total = counts.reduce((sum, count) => sum + count, 0);
  if (total === 0) {
    return items.map(() => 0);
  }

  const exactTenths = counts.map((count) => (count / total) * 1000);
  const tenths = exactTenths.map((value) => Math.floor(value));
  const remainderCount = 1000 - tenths.reduce((sum, value) => sum + value, 0);
  const remainderOrder = exactTenths
    .map((value, index) => ({ index, remainder: value - tenths[index] }))
    .filter(({ index }) => counts[index] > 0)
    .sort((left, right) => right.remainder - left.remainder || right.index - left.index);

  for (let offset = 0; offset < remainderCount; offset += 1) {
    tenths[remainderOrder[offset].index] += 1;
  }

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
