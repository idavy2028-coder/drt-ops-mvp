<script setup lang="ts">
import { computed, onMounted } from "vue";
import type { OperationsSummary } from "../api/types";
import MetricTileGrid from "../components/MetricTileGrid.vue";
import { formatMinutes, formatPercentage } from "../presentation/operations";
import { useOperationsStore } from "../stores/operationsStore";

const { state, loadSummary } = useOperationsStore();
const today = new Date().toISOString().slice(0, 10);

const fallbackSummary: OperationsSummary = {
  orderCount: 0,
  confirmationRate: "0.0000",
  autoDispatchRate: "0.0000",
  manualReviewRate: "0.0000",
  averageWaitMinutes: "0.00",
  averageDetourMinutes: "0.00",
  taskCompletionRate: "0.0000",
  exceptionCloseRate: "0.0000",
  vehicleUtilizationRate: "0.0000"
};

const summary = computed(() => state.summary ?? fallbackSummary);
const metrics = computed(() => [
  { label: "订单量", value: summary.value.orderCount, tone: "当日需求总量" },
  { label: "订单确认率", value: formatPercentage(summary.value.confirmationRate), tone: "已确认与可执行需求" },
  { label: "自动派发率", value: formatPercentage(summary.value.autoDispatchRate), tone: "算法直接落单" },
  { label: "人工复核率", value: formatPercentage(summary.value.manualReviewRate), tone: "需要调度员介入" },
  { label: "平均等待时间", value: formatMinutes(summary.value.averageWaitMinutes), tone: "服务等待时长" },
  { label: "平均绕行时间", value: formatMinutes(summary.value.averageDetourMinutes), tone: "服务绕行时长" },
  { label: "任务完成率", value: formatPercentage(summary.value.taskCompletionRate), tone: "执行闭环" },
  { label: "车辆利用率", value: formatPercentage(summary.value.vehicleUtilizationRate), tone: "有任务车辆占比" }
]);

function refreshSummary() {
  void loadSummary(today);
}

onMounted(() => {
  refreshSummary();
});
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="page-kicker">OPERATIONS</p>
        <h2 class="page-title">运营看板</h2>
        <p class="page-subtitle">按运营日聚合订单确认、自动派发、人工复核、任务完成与异常闭环。</p>
      </div>
      <div class="page-actions">
        <button class="secondary-button" type="button" :disabled="state.loading" @click="refreshSummary">
          {{ state.loading ? "同步中" : "刷新" }}
        </button>
        <span class="status-pill">今日</span>
      </div>
    </header>

    <MetricTileGrid :metrics="metrics" />

    <p v-if="state.loading" class="page-state">正在同步今日运营指标…</p>
    <p v-else-if="state.error" class="page-state">{{ state.error }}</p>

    <div class="split-grid">
      <section class="work-panel">
        <h3 class="section-title">今日调度表现</h3>
        <div class="overview-list">
          <p><span>订单确认</span><strong>{{ formatPercentage(summary.confirmationRate) }}</strong></p>
          <p><span>自动派发</span><strong>{{ formatPercentage(summary.autoDispatchRate) }}</strong></p>
          <p><span>人工复核</span><strong>{{ formatPercentage(summary.manualReviewRate) }}</strong></p>
        </div>
      </section>
      <section class="work-panel">
        <h3 class="section-title">服务闭环</h3>
        <div class="overview-list">
          <p><span>平均等待</span><strong>{{ formatMinutes(summary.averageWaitMinutes) }}</strong></p>
          <p><span>平均绕行</span><strong>{{ formatMinutes(summary.averageDetourMinutes) }}</strong></p>
          <p><span>任务完成 / 异常关闭</span><strong>{{ formatPercentage(summary.taskCompletionRate) }} / {{ formatPercentage(summary.exceptionCloseRate) }}</strong></p>
        </div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.overview-list { display: grid; gap: 10px; }
.overview-list p { align-items: center; border-bottom: 1px solid var(--line); display: flex; justify-content: space-between; margin: 0; padding-bottom: 10px; }
.overview-list p:last-child { border-bottom: 0; padding-bottom: 0; }
.overview-list span { color: var(--ink-muted); }
.overview-list strong { color: var(--ink); }
</style>
