<script setup lang="ts">
import { computed, onMounted } from "vue";
import { useOperationsStore } from "../stores/operationsStore";
import type { OperationsSummary } from "../api/types";

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

    <div class="summary-grid">
      <article class="metric-panel">
        <p class="metric-label">订单量</p>
        <p class="metric-value">{{ summary.orderCount }}</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">确认率</p>
        <p class="metric-value">{{ summary.confirmationRate }}</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">自动派发率</p>
        <p class="metric-value">{{ summary.autoDispatchRate }}</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">人工复核率</p>
        <p class="metric-value">{{ summary.manualReviewRate }}</p>
      </article>
    </div>

    <section class="work-panel">
      <h3 class="section-title">运行质量</h3>
      <p class="section-copy">
        平均等待 {{ summary.averageWaitMinutes }} 分钟，平均绕行 {{ summary.averageDetourMinutes }} 分钟，任务完成率
        {{ summary.taskCompletionRate }}，异常关闭率 {{ summary.exceptionCloseRate }}。
      </p>
      <p v-if="state.error" class="section-copy">后端连接状态：{{ state.error }}</p>
    </section>
  </section>
</template>
