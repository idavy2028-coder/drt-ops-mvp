<script setup lang="ts">
import { onMounted, ref } from "vue";
import { listOrders } from "../api/orders";
import type { RideOrder, VehicleTask } from "../api/types";
import DispatchDecisionPanel from "../components/DispatchDecisionPanel.vue";
import DispatchMap from "../components/DispatchMap.vue";
import RealtimeOrderList from "../components/RealtimeOrderList.vue";
import VehicleTaskList from "../components/VehicleTaskList.vue";

const orders = ref<RideOrder[]>([]);
const tasks = ref<VehicleTask[]>([]);
const status = ref("");

async function loadWorkbench() {
  try {
    orders.value = await listOrders();
  } catch (error) {
    status.value = error instanceof Error ? error.message : "工作台数据加载失败";
  }
  tasks.value = [];
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
        <button class="secondary-button" type="button" @click="loadWorkbench">刷新</button>
        <span class="status-pill">实时</span>
      </div>
    </header>

    <div class="summary-grid">
      <article class="metric-panel">
        <p class="metric-label">待调度</p>
        <p class="metric-value">{{ orders.filter((order) => order.status === "PENDING_DISPATCH").length }}</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">待复核</p>
        <p class="metric-value">{{ orders.filter((order) => order.status === "PENDING_MANUAL_REVIEW").length }}</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">车辆任务</p>
        <p class="metric-value">{{ tasks.length }}</p>
      </article>
    </div>

    <p v-if="status" class="section-copy">后端连接状态：{{ status }}</p>

    <div class="dispatch-grid">
      <RealtimeOrderList :orders="orders" />
      <DispatchMap />
      <DispatchDecisionPanel />
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
