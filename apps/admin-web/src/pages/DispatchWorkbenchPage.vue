<script setup lang="ts">
import { onMounted, ref } from "vue";
import { approveManualReview, listManualReviews, rejectManualReview } from "../api/manualReviews";
import { listOrders } from "../api/orders";
import { listTasks } from "../api/tasks";
import type { ManualReviewQueueItem, RideOrder, UUID, VehicleTask } from "../api/types";
import DispatchMap from "../components/DispatchMap.vue";
import ManualReviewQueuePanel from "../components/ManualReviewQueuePanel.vue";
import RealtimeOrderList from "../components/RealtimeOrderList.vue";
import VehicleTaskList from "../components/VehicleTaskList.vue";
import { userMessage } from "../api/errors";
import { feedbackStore } from "../stores/feedbackStore";

const orders = ref<RideOrder[]>([]);
const tasks = ref<VehicleTask[]>([]);
const reviews = ref<ManualReviewQueueItem[]>([]);
const processingDecisionId = ref<UUID>();
const status = ref("");
const actionError = ref("");
const loading = ref(false);

async function loadWorkbench() {
  try {
    status.value = "";
    loading.value = true;
    const [loadedOrders, loadedTasks, loadedReviews] = await Promise.all([
      listOrders(),
      listTasks(),
      listManualReviews()
    ]);
    orders.value = loadedOrders;
    tasks.value = loadedTasks;
    reviews.value = loadedReviews;
  } catch (error) {
    status.value = userMessage(error, "工作台数据加载失败");
  } finally {
    loading.value = false;
  }
}

async function approve(decisionId: UUID) {
  processingDecisionId.value = decisionId;
  actionError.value = "";
  try {
    await approveManualReview(decisionId);
    await loadWorkbench();
  } catch (error) {
    actionError.value = userMessage(error, "人工确认失败");
    feedbackStore.error(actionError.value);
  } finally {
    processingDecisionId.value = undefined;
  }
  if (!actionError.value) {
    feedbackStore.success("人工复核已确认，车辆任务已生成");
  }
}

async function reject(payload: { decisionId: UUID; reason: string }) {
  processingDecisionId.value = payload.decisionId;
  actionError.value = "";
  try {
    await rejectManualReview(payload.decisionId, payload.reason);
    await loadWorkbench();
  } catch (error) {
    actionError.value = userMessage(error, "人工拒绝失败");
    feedbackStore.error(actionError.value);
  } finally {
    processingDecisionId.value = undefined;
  }
  if (!actionError.value) {
    feedbackStore.success("人工复核已拒绝，订单已关闭");
  }
}

onMounted(() => {
  void loadWorkbench();
});
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="page-kicker">DISPATCH</p>
        <h2 class="page-title">调度工作台</h2>
        <p class="page-subtitle">聚焦实时订单、车辆任务、服务区地图、算法解释和人工操作队列。</p>
      </div>
      <div class="toolbar">
        <button class="secondary-button" type="button" :disabled="loading" @click="loadWorkbench">{{ loading ? "同步中" : "刷新" }}</button>
        <span class="status-pill">{{ loading ? "同步中" : "实时" }}</span>
      </div>
    </header>

    <div class="summary-grid">
      <article class="metric-panel">
        <p class="metric-label">待调度</p>
        <p class="metric-value">{{ orders.filter((order) => order.status === "PENDING_DISPATCH").length }}</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">待复核</p>
        <p class="metric-value">{{ reviews.length }}</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">车辆任务</p>
        <p class="metric-value">{{ tasks.length }}</p>
      </article>
    </div>

    <p v-if="loading" class="page-state">正在汇总实时订单、车辆任务与人工复核队列…</p>
    <p v-else-if="status" class="page-state">{{ status }}</p>

    <div class="dispatch-grid">
      <RealtimeOrderList :orders="orders" />
      <DispatchMap />
      <ManualReviewQueuePanel
        :items="reviews"
        :processing-decision-id="processingDecisionId"
        :error="actionError"
        @approve="approve"
        @reject="reject"
      />
      <VehicleTaskList :tasks="tasks" />
    </div>
  </section>
</template>

<style scoped>
.dispatch-grid {
  display: grid;
  grid-template-columns: minmax(260px, 0.78fr) minmax(420px, 1.36fr) minmax(300px, 0.86fr);
  gap: 14px;
  align-items: stretch;
}

.dispatch-grid > :nth-child(4) {
  grid-column: 1 / -1;
}

@media (max-width: 1180px) {
  .dispatch-grid {
    grid-template-columns: 1fr;
  }
}
</style>
