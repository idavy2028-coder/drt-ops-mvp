<script setup lang="ts">
import { computed, onMounted } from "vue";
import type { OperationsSummary } from "../api/types";
import MetricTileGrid from "../components/MetricTileGrid.vue";
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
  { label: "订单确认率", value: summary.value.confirmationRate, tone: "已确认与可执行需求" },
  { label: "自动派发率", value: summary.value.autoDispatchRate, tone: "算法直接落单" },
  { label: "人工复核率", value: summary.value.manualReviewRate, tone: "需要调度员介入" },
  { label: "平均等待时间", value: summary.value.averageWaitMinutes, tone: "分钟" },
  { label: "平均绕行时间", value: summary.value.averageDetourMinutes, tone: "分钟" },
  { label: "任务完成率", value: summary.value.taskCompletionRate, tone: "执行闭环" },
  { label: "车辆利用率", value: summary.value.vehicleUtilizationRate, tone: "有任务车辆占比" }
]);

onMounted(() => {
  void loadSummary(today);
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
      <span class="status-pill">{{ state.loading ? "同步中" : "今日" }}</span>
    </header>

    <MetricTileGrid :metrics="metrics" />

    <div class="split-grid">
      <section class="work-panel">
        <h3 class="section-title">基础趋势</h3>
        <p class="section-copy">订单确认、等待时间、绕行时间和车辆利用率会在后续接入时间序列数据。</p>
      </section>
      <section class="work-panel">
        <h3 class="section-title">异常闭环</h3>
        <p class="section-copy">异常关闭率 {{ summary.exceptionCloseRate }}，任务完成率 {{ summary.taskCompletionRate }}。</p>
        <p v-if="state.error" class="section-copy">后端连接状态：{{ state.error }}</p>
      </section>
    </div>
  </section>
</template>
