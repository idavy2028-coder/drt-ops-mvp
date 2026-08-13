<script setup lang="ts">
import { computed, onMounted } from "vue";
import type { DecimalValue } from "../api/types";
import DashboardMetricCard from "../components/DashboardMetricCard.vue";
import DistributionDonut from "../components/DistributionDonut.vue";
import OperationsTrendChart from "../components/OperationsTrendChart.vue";
import {
  formatNullablePercentage,
  formatSignedPoints,
  formatSignedRate,
  metricNumber,
  shortDateRange
} from "../presentation/dashboardMetrics";
import { feedbackStore } from "../stores/feedbackStore";
import { useOperationsStore } from "../stores/operationsStore";

const { state, loadDashboard } = useOperationsStore();
const dashboard = computed(() => state.dashboard);

const orderMetric = computed(() => dashboard.value?.coreMetrics.orderVolume ?? null);
const completionMetric = computed(() => dashboard.value?.coreMetrics.taskCompletion ?? null);
const waitMetric = computed(() => dashboard.value?.coreMetrics.averageWait ?? null);
const utilizationMetric = computed(() => dashboard.value?.coreMetrics.vehicleUtilization ?? null);

const trendSummary = computed(() => {
  const points = dashboard.value?.trend ?? [];
  if (points.length === 0) {
    return null;
  }
  const totalOrders = points.reduce((sum, point) => sum + point.orderCount, 0);
  const peak = points.reduce((current, point) => point.orderCount > current.orderCount ? point : current, points[0]);
  const waitSamples = points.filter((point) => metricNumber(point.averageWaitMinutes) !== null);
  const weightedWait = waitSamples.reduce(
    (sum, point) => sum + (metricNumber(point.averageWaitMinutes) ?? 0) * point.waitSampleCount,
    0
  );
  const waitSampleCount = waitSamples.reduce((sum, point) => sum + point.waitSampleCount, 0);
  return {
    dailyAverage: totalOrders / points.length,
    peakDate: peak.date,
    peakOrders: peak.orderCount,
    averageWait: waitSampleCount === 0 ? null : weightedWait / waitSampleCount
  };
});

async function refreshDashboard(announce = true): Promise<void> {
  const success = await loadDashboard(currentOperatingDay());
  if (!announce) {
    return;
  }
  if (success) {
    feedbackStore.success("运营看板已更新");
  } else {
    feedbackStore.error(state.error || "运营数据加载失败，请稍后重试");
  }
}

function currentOperatingDay(): string {
  const dateParts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(new Date());
  const values = Object.fromEntries(dateParts.map(({ type, value }) => [type, value]));
  return `${values.year}-${values.month}-${values.day}`;
}

function metricDecimal(value: DecimalValue | null, digits = 1): string {
  const parsed = metricNumber(value);
  return parsed === null ? "--" : parsed.toFixed(digits);
}

function baselineNumber(value: DecimalValue | null, unit: string): string {
  const parsed = metricNumber(value);
  return parsed === null ? "近 7 日暂无有效基线" : `基线 ${parsed.toFixed(1)} ${unit}`;
}

function baselinePercentage(value: DecimalValue | null): string {
  return value === null ? "近 7 日暂无有效基线" : `基线 ${formatNullablePercentage(value)}`;
}

function pointsFromBaseline(value: DecimalValue | null, baseline: DecimalValue | null): DecimalValue | null {
  const parsed = metricNumber(value);
  const parsedBaseline = metricNumber(baseline);
  return parsed === null || parsedBaseline === null ? null : parsed - parsedBaseline;
}

function formatGeneratedAt(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  }).format(date);
}

onMounted(() => {
  void refreshDashboard(false);
});
</script>

<template>
  <section class="operations-dashboard">
    <header class="dashboard-header">
      <div class="dashboard-heading">
        <div class="dashboard-title-row">
          <p class="dashboard-kicker">OPERATIONS CONTROL</p>
          <span class="dashboard-live"><i aria-hidden="true"></i>运营快照</span>
        </div>
        <h2>运营看板</h2>
        <p>聚焦订单履约、乘客等待与车辆供给，快速识别当日运营偏差。</p>
      </div>
      <div class="dashboard-actions">
        <div v-if="dashboard" class="dashboard-date-block">
          <span>运营日</span>
          <strong>{{ dashboard.operatingDate }}</strong>
          <small>更新于 {{ formatGeneratedAt(dashboard.generatedAt) }}</small>
        </div>
        <button
          class="dashboard-refresh"
          type="button"
          aria-label="刷新数据"
          :disabled="state.loading || state.refreshing"
          @click="refreshDashboard(true)"
        >
          <span aria-hidden="true" :class="{ 'is-spinning': state.refreshing }">↻</span>
          {{ state.refreshing ? "刷新中" : "刷新" }}
        </button>
      </div>
    </header>

    <div v-if="state.loading && !dashboard" class="dashboard-metric-grid">
      <article v-for="index in 4" :key="index" class="metric-skeleton" aria-label="运营指标加载中">
        <i></i><strong></strong><span></span><small></small>
      </article>
    </div>

    <section v-else-if="!dashboard" class="dashboard-empty" aria-live="polite">
      <span aria-hidden="true">!</span>
      <div>
        <h3>运营数据暂未就绪</h3>
        <p>{{ state.error || "请稍后重新加载当日运营快照。" }}</p>
      </div>
      <button type="button" @click="refreshDashboard(true)">重新加载</button>
    </section>

    <template v-else>
      <p v-if="state.error" class="dashboard-stale-notice" role="status">
        当前保留上次成功快照；最新数据同步失败。
      </p>

      <section class="dashboard-metric-grid" aria-label="当日核心运营指标">
        <DashboardMetricCard
          v-if="orderMetric"
          label="订单量"
          :primary="String(orderMetric.count)"
          secondary="单"
          :status="orderMetric.status"
          :comparison="`较基线 ${formatSignedRate(orderMetric.changeRate)}`"
          :baseline="baselineNumber(orderMetric.baseline, '单/日')"
          icon="单"
          accent="teal"
        />
        <DashboardMetricCard
          v-if="completionMetric"
          label="任务完成率"
          :primary="`${completionMetric.completed} / ${completionMetric.total}`"
          :secondary="`完成 · ${formatNullablePercentage(completionMetric.rate)}`"
          :status="completionMetric.status"
          :comparison="`较基线 ${formatSignedPoints(pointsFromBaseline(completionMetric.rate, completionMetric.baselineRate))}`"
          :baseline="baselinePercentage(completionMetric.baselineRate)"
          icon="率"
          accent="blue"
        />
        <DashboardMetricCard
          v-if="waitMetric"
          label="平均等待"
          :primary="metricDecimal(waitMetric.minutes)"
          :secondary="`分钟 · ${waitMetric.sampleCount} 单样本`"
          :status="waitMetric.status"
          :comparison="`较基线 ${formatSignedRate(waitMetric.changeRate)}`"
          :baseline="baselineNumber(waitMetric.baselineMinutes, '分钟')"
          icon="候"
          accent="amber"
        />
        <DashboardMetricCard
          v-if="utilizationMetric"
          label="车辆利用率"
          :primary="`${utilizationMetric.utilized} / ${utilizationMetric.available}`"
          :secondary="`辆 · ${formatNullablePercentage(utilizationMetric.rate)}`"
          :status="utilizationMetric.status"
          :comparison="`较基线 ${formatSignedPoints(pointsFromBaseline(utilizationMetric.rate, utilizationMetric.baselineRate))}`"
          :baseline="`${baselinePercentage(utilizationMetric.baselineRate)} · 按当前可调度车辆口径`"
          icon="车"
          accent="violet"
        />
      </section>

      <section v-if="trendSummary" class="trend-summary-strip" aria-label="七天趋势摘要">
        <p><span>统计周期</span><strong>{{ shortDateRange(dashboard.rangeStart, dashboard.rangeEnd) }}</strong></p>
        <p><span>日均订单</span><strong>{{ trendSummary.dailyAverage.toFixed(1) }} 单</strong></p>
        <p><span>峰值订单</span><strong>{{ trendSummary.peakOrders }} 单 · {{ trendSummary.peakDate.slice(5) }}</strong></p>
        <p><span>周期平均等待</span><strong>{{ trendSummary.averageWait === null ? "--" : `${trendSummary.averageWait.toFixed(1)} 分钟` }}</strong></p>
      </section>

      <OperationsTrendChart
        :points="dashboard.trend"
        :range-start="dashboard.rangeStart"
        :range-end="dashboard.rangeEnd"
      />

      <section class="distribution-section" aria-labelledby="distribution-title">
        <header class="distribution-section-heading">
          <div>
            <p>DISTRIBUTION</p>
            <h3 id="distribution-title">当日分类统计</h3>
          </div>
          <span>数量与百分比同步展示</span>
        </header>
        <div class="distribution-grid">
          <DistributionDonut title="订单状态" unit="单" :items="dashboard.distributions.orders" />
          <DistributionDonut title="任务状态" unit="项" :items="dashboard.distributions.tasks" />
          <DistributionDonut title="车辆状态" unit="辆" :items="dashboard.distributions.vehicles" />
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.operations-dashboard {
  --ops-teal: #08a99d;
  --ops-blue: #2e7be6;
  --ops-warning: #d98a22;
  --ops-line: #d8e3e9;
  display: grid;
  gap: 12px;
  color: #1a2b36;
}

.dashboard-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  border-bottom: 1px solid var(--ops-line);
  padding: 2px 0 12px;
}

.dashboard-title-row,
.dashboard-actions {
  display: flex;
  align-items: center;
}

.dashboard-title-row {
  gap: 10px;
}

.dashboard-kicker {
  margin: 0;
  color: #768a95;
  font-size: 11px;
  font-weight: 900;
  letter-spacing: 0.12em;
}

.dashboard-live {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border-radius: 999px;
  background: #e6f6f3;
  color: #087e73;
  padding: 3px 8px;
  font-size: 11px;
  font-weight: 900;
}

.dashboard-live i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ops-teal);
  box-shadow: 0 0 0 3px rgba(8, 169, 157, 0.13);
}

.dashboard-heading h2 {
  margin: 7px 0 0;
  color: #182934;
  font-size: clamp(24px, 2.3vw, 31px);
  line-height: 1.05;
}

.dashboard-heading > p:last-child {
  margin: 7px 0 0;
  color: #6b7e89;
  font-size: 13px;
}

.dashboard-actions {
  flex: 0 0 auto;
  gap: 10px;
}

.dashboard-date-block {
  display: grid;
  grid-template-columns: auto auto;
  align-items: baseline;
  column-gap: 7px;
  border-right: 1px solid var(--ops-line);
  padding-right: 12px;
  text-align: right;
}

.dashboard-date-block span,
.dashboard-date-block small {
  color: #81919a;
  font-size: 11px;
  font-weight: 700;
}

.dashboard-date-block strong {
  color: #2d414d;
  font-family: "Bahnschrift", "Microsoft YaHei UI", sans-serif;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}

.dashboard-date-block small {
  grid-column: 1 / -1;
  margin-top: 2px;
}

.dashboard-refresh,
.dashboard-empty button {
  border: 1px solid #b8c9d2;
  border-radius: 7px;
  background: #ffffff;
  color: #29404d;
  min-height: 36px;
  padding: 7px 12px;
  font-size: 13px;
  font-weight: 900;
  cursor: pointer;
}

.dashboard-refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.dashboard-refresh:disabled {
  cursor: wait;
  opacity: 0.58;
}

.dashboard-refresh .is-spinning {
  animation: dashboard-spin 900ms linear infinite;
}

.dashboard-metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.metric-skeleton {
  display: grid;
  gap: 9px;
  min-height: 151px;
  border: 1px solid var(--ops-line);
  border-radius: 8px;
  background: #ffffff;
  padding: 16px;
}

.metric-skeleton > * {
  display: block;
  border-radius: 5px;
  background: linear-gradient(90deg, #edf2f4 25%, #f8fafb 42%, #edf2f4 60%);
  background-size: 300% 100%;
  animation: dashboard-shimmer 1.4s ease infinite;
}

.metric-skeleton i { width: 42%; height: 13px; }
.metric-skeleton strong { width: 60%; height: 31px; }
.metric-skeleton span { width: 74%; height: 19px; }
.metric-skeleton small { width: 52%; height: 11px; }

.dashboard-empty {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  border: 1px dashed #bdcbd2;
  border-radius: 8px;
  background: #fbfcfc;
  padding: 22px;
}

.dashboard-empty > span {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 8px;
  background: #fff1df;
  color: #a9600f;
  font-weight: 900;
}

.dashboard-empty h3,
.dashboard-empty p {
  margin: 0;
}

.dashboard-empty h3 { font-size: 15px; }
.dashboard-empty p { margin-top: 4px; color: #70818b; font-size: 13px; }

.dashboard-stale-notice {
  margin: 0;
  border: 1px solid #f0d4aa;
  border-radius: 7px;
  background: #fff8ec;
  color: #8f5b17;
  padding: 8px 11px;
  font-size: 12px;
  font-weight: 700;
}

.trend-summary-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--ops-line);
  border-radius: 8px;
  background: #ffffff;
}

.trend-summary-strip p {
  display: grid;
  gap: 3px;
  margin: 0;
  border-right: 1px solid #e4ebee;
  padding: 9px 12px;
}

.trend-summary-strip p:last-child { border-right: 0; }
.trend-summary-strip span { color: #7b8d97; font-size: 11px; font-weight: 700; }
.trend-summary-strip strong { color: #263b47; font-size: 13px; font-variant-numeric: tabular-nums; }

.distribution-section {
  display: grid;
  gap: 8px;
}

.distribution-section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 1px 0;
}

.distribution-section-heading p {
  margin: 0 0 2px;
  color: #80919a;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: 0.1em;
}

.distribution-section-heading h3 {
  margin: 0;
  font-size: 16px;
}

.distribution-section-heading > span {
  color: #7c8d96;
  font-size: 11px;
}

.distribution-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

@keyframes dashboard-spin {
  to { transform: rotate(360deg); }
}

@keyframes dashboard-shimmer {
  0% { background-position: 100% 0; }
  100% { background-position: 0 0; }
}

@media (max-width: 1120px) {
  .dashboard-metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .distribution-grid { grid-template-columns: 1fr 1fr; }
  .distribution-grid > :last-child { grid-column: 1 / -1; }
}

@media (max-width: 760px) {
  .dashboard-header { align-items: flex-start; flex-direction: column; }
  .dashboard-actions { width: 100%; justify-content: space-between; }
  .trend-summary-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .trend-summary-strip p:nth-child(2) { border-right: 0; }
  .trend-summary-strip p:nth-child(-n + 2) { border-bottom: 1px solid #e4ebee; }
  .distribution-grid { grid-template-columns: 1fr; }
  .distribution-grid > :last-child { grid-column: auto; }
}

@media (max-width: 520px) {
  .dashboard-metric-grid { grid-template-columns: 1fr; }
  .dashboard-date-block { text-align: left; }
  .distribution-section-heading > span { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .dashboard-refresh .is-spinning,
  .metric-skeleton > * { animation: none; }
}
</style>
