<script setup lang="ts">
import { computed, ref } from "vue";
import type { DashboardTrendPoint } from "../api/types";
import {
  formatNullablePercentage,
  metricNumber,
  shortDate,
  shortDateRange
} from "../presentation/dashboardMetrics";

type TrendMode = "combined" | "wait" | "utilization";

const props = defineProps<{
  points: readonly DashboardTrendPoint[];
  rangeStart: string;
  rangeEnd: string;
}>();

const mode = ref<TrendMode>("combined");
const plot = { left: 48, right: 724, top: 22, bottom: 194 };
const plotHeight = plot.bottom - plot.top;

const effectiveStart = computed(() => props.points[0]?.date ?? props.rangeStart);
const effectiveEnd = computed(() => props.points[props.points.length - 1]?.date ?? props.rangeEnd);
const rangeLabel = computed(() => shortDateRange(effectiveStart.value, effectiveEnd.value));
const chartTitle = computed(() => {
  if (mode.value === "wait") {
    return `近 7 天平均等待时间趋势（${rangeLabel.value}）`;
  }
  if (mode.value === "utilization") {
    return `近 7 天车辆利用率趋势（${rangeLabel.value}）`;
  }
  return `近 7 天订单量与任务完成率趋势（${rangeLabel.value}）`;
});

const maxOrderCount = computed(() => {
  const maximum = Math.max(0, ...props.points.map((point) => point.orderCount));
  return niceMaximum(maximum);
});

const maxWaitMinutes = computed(() => {
  const values = props.points
    .map((point) => metricNumber(point.averageWaitMinutes))
    .filter((value): value is number => value !== null);
  return niceMaximum(Math.max(0, ...values));
});

const bars = computed(() => props.points.map((point, index) => {
  const x = pointX(index);
  const y = scaleLinear(point.orderCount, maxOrderCount.value);
  return {
    x: x - 20,
    y,
    width: 40,
    height: plot.bottom - y,
    tooltip: `${point.date}：订单 ${point.orderCount} 单`
  };
}));

const lineDots = computed(() => props.points.flatMap((point, index) => {
  const value = lineValue(point);
  if (value === null) {
    return [];
  }
  return [{
    x: pointX(index),
    y: lineY(value),
    tooltip: pointSummary(point)
  }];
}));

const linePaths = computed(() => {
  const paths: string[] = [];
  let current: string[] = [];
  props.points.forEach((point, index) => {
    const value = lineValue(point);
    if (value === null) {
      if (current.length > 0) {
        paths.push(current.join(" "));
        current = [];
      }
      return;
    }
    const command = current.length === 0 ? "M" : "L";
    current.push(`${command} ${pointX(index).toFixed(1)} ${lineY(value).toFixed(1)}`);
  });
  if (current.length > 0) {
    paths.push(current.join(" "));
  }
  return paths;
});

const gridLines = [0, 0.25, 0.5, 0.75, 1].map((ratio) => plot.bottom - ratio * plotHeight);

function selectMode(nextMode: TrendMode): void {
  mode.value = nextMode;
}

function pointX(index: number): number {
  if (props.points.length <= 1) {
    return (plot.left + plot.right) / 2;
  }
  return plot.left + index * ((plot.right - plot.left) / (props.points.length - 1));
}

function scaleLinear(value: number, maximum: number): number {
  if (maximum <= 0) {
    return plot.bottom;
  }
  return plot.bottom - Math.max(0, Math.min(1, value / maximum)) * plotHeight;
}

function lineValue(point: DashboardTrendPoint): number | null {
  if (mode.value === "wait") {
    return metricNumber(point.averageWaitMinutes);
  }
  if (mode.value === "utilization") {
    return metricNumber(point.vehicleUtilizationRate);
  }
  return metricNumber(point.taskCompletionRate);
}

function lineY(value: number): number {
  if (mode.value === "wait") {
    return scaleLinear(value, maxWaitMinutes.value);
  }
  return plot.bottom - Math.max(0, Math.min(1, value)) * plotHeight;
}

function niceMaximum(value: number): number {
  if (value <= 0) {
    return 1;
  }
  const magnitude = 10 ** Math.floor(Math.log10(value));
  return Math.ceil(value / magnitude) * magnitude;
}

function pointSummary(point: DashboardTrendPoint): string {
  const date = shortDate(point.date);
  if (mode.value === "wait") {
    const minutes = metricNumber(point.averageWaitMinutes);
    return minutes === null
      ? `${date}：平均等待无有效样本`
      : `${date}：平均等待 ${minutes.toFixed(1)} 分钟；样本 ${point.waitSampleCount} 单`;
  }
  if (mode.value === "utilization") {
    const rate = formatNullablePercentage(point.vehicleUtilizationRate);
    return point.vehicleUtilizationRate === null
      ? `${date}：车辆利用率无有效样本`
      : `${date}：车辆利用 ${point.utilizedVehicles}/${point.availableVehicles}（${rate}）`;
  }
  if (point.taskCompletionRate === null) {
    return `${date}：订单 ${point.orderCount} 单；任务完成率无有效样本`;
  }
  return `${date}：订单 ${point.orderCount} 单；任务完成 ${point.completedTasks}/${point.totalTasks}（${formatNullablePercentage(point.taskCompletionRate)}）`;
}
</script>

<template>
  <section class="trend-panel">
    <header class="trend-header">
      <div>
        <p class="trend-kicker">OPERATING TREND</p>
        <h3>{{ chartTitle }}</h3>
      </div>
      <div class="trend-mode-switch" aria-label="趋势指标">
        <button type="button" :aria-pressed="mode === 'combined'" @click="selectMode('combined')">订单量 + 完成率</button>
        <button type="button" :aria-pressed="mode === 'wait'" @click="selectMode('wait')">平均等待</button>
        <button type="button" :aria-pressed="mode === 'utilization'" @click="selectMode('utilization')">车辆利用率</button>
      </div>
    </header>

    <div class="trend-legend" aria-label="图表图例">
      <span v-if="mode === 'combined'"><i class="legend-bar" aria-hidden="true"></i>订单量（单）</span>
      <span v-if="mode === 'combined'"><i class="legend-line" aria-hidden="true"></i>任务完成率（%）</span>
      <span v-if="mode === 'wait'"><i class="legend-line" aria-hidden="true"></i>平均等待（分钟）</span>
      <span v-if="mode === 'utilization'"><i class="legend-line" aria-hidden="true"></i>车辆利用率（%）</span>
    </div>

    <div class="trend-scroll">
      <div class="trend-canvas">
        <svg viewBox="0 0 772 222" role="img" :aria-label="chartTitle">
          <defs>
            <linearGradient id="operations-order-bars" x1="0" x2="0" y1="0" y2="1">
              <stop offset="0" stop-color="#64d3ca" />
              <stop offset="1" stop-color="#c8efeb" />
            </linearGradient>
          </defs>
          <g class="trend-grid" aria-hidden="true">
            <line v-for="line in gridLines" :key="line" :x1="plot.left" :x2="plot.right" :y1="line" :y2="line" />
          </g>
          <g v-if="mode === 'combined'" class="trend-bars">
            <rect
              v-for="(bar, index) in bars"
              :key="props.points[index].date"
              :x="bar.x"
              :y="bar.y"
              :width="bar.width"
              :height="bar.height"
              rx="4"
              tabindex="0"
            >
              <title>{{ bar.tooltip }}</title>
            </rect>
          </g>
          <g class="trend-line-series">
            <path v-for="path in linePaths" :key="path" :d="path" />
            <circle v-for="dot in lineDots" :key="`${dot.x}-${dot.y}`" :cx="dot.x" :cy="dot.y" r="4.5" tabindex="0">
              <title>{{ dot.tooltip }}</title>
            </circle>
          </g>
        </svg>
        <div class="trend-axis-dates" :style="{ gridTemplateColumns: `repeat(${Math.max(points.length, 1)}, minmax(0, 1fr))` }">
          <span v-for="point in points" :key="point.date" data-testid="trend-date">{{ shortDate(point.date) }}</span>
        </div>
      </div>
    </div>

    <ul class="sr-only" aria-label="趋势数据摘要">
      <li v-for="point in points" :key="`${mode}-${point.date}`">{{ pointSummary(point) }}</li>
    </ul>
  </section>
</template>

<style scoped>
.trend-panel {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--ops-line, #d8e3e9);
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 10px 25px rgba(26, 47, 61, 0.05);
}

.trend-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 54px;
  border-bottom: 1px solid var(--ops-line, #d8e3e9);
  padding: 9px 14px;
}

.trend-kicker {
  margin: 0 0 3px;
  color: #78909a;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: 0.1em;
}

.trend-header h3 {
  margin: 0;
  color: #1b2b36;
  font-size: 16px;
  line-height: 1.35;
}

.trend-mode-switch {
  display: flex;
  flex: 0 0 auto;
  gap: 2px;
  border-radius: 7px;
  background: #edf3f5;
  padding: 3px;
}

.trend-mode-switch button {
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: #667985;
  padding: 6px 9px;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
}

.trend-mode-switch button[aria-pressed="true"] {
  background: #ffffff;
  box-shadow: 0 2px 7px rgba(27, 48, 62, 0.12);
  color: #087f75;
}

.trend-legend {
  display: flex;
  justify-content: center;
  gap: 20px;
  min-height: 31px;
  padding: 9px 14px 0;
  color: #60737e;
  font-size: 12px;
  font-weight: 700;
}

.trend-legend span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.legend-bar {
  width: 13px;
  height: 8px;
  border-radius: 2px;
  background: #64d3ca;
}

.legend-line {
  position: relative;
  width: 16px;
  height: 8px;
}

.legend-line::before,
.legend-line::after {
  position: absolute;
  content: "";
}

.legend-line::before {
  top: 3px;
  right: 0;
  left: 0;
  height: 2px;
  background: var(--ops-blue, #2e7be6);
}

.legend-line::after {
  top: 1px;
  left: 6px;
  width: 5px;
  height: 5px;
  border: 1.5px solid var(--ops-blue, #2e7be6);
  border-radius: 50%;
  background: #ffffff;
}

.trend-scroll {
  overflow-x: auto;
  padding: 0 12px 10px;
}

.trend-canvas {
  min-width: 720px;
}

.trend-canvas svg {
  display: block;
  width: 100%;
  height: 228px;
}

.trend-grid line {
  stroke: #e5ecef;
  stroke-width: 1;
}

.trend-bars rect {
  fill: url(#operations-order-bars);
  stroke: #77d9d1;
  stroke-width: 0.5;
}

.trend-bars rect:focus,
.trend-line-series circle:focus {
  outline: none;
  filter: drop-shadow(0 0 3px rgba(46, 123, 230, 0.7));
}

.trend-line-series path {
  fill: none;
  stroke: var(--ops-blue, #2e7be6);
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 3;
}

.trend-line-series circle {
  fill: #ffffff;
  stroke: var(--ops-blue, #2e7be6);
  stroke-width: 2.5;
}

.trend-axis-dates {
  display: grid;
  margin: -5px 26px 0 46px;
  color: #71828d;
  font-family: "Bahnschrift", "Microsoft YaHei UI", sans-serif;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  clip-path: inset(50%);
}

@media (max-width: 900px) {
  .trend-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .trend-mode-switch {
    width: 100%;
  }

  .trend-mode-switch button {
    flex: 1;
  }
}
</style>
