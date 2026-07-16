<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { approveManualReview, listManualReviews, rejectManualReview } from "../api/manualReviews";
import { listOrders } from "../api/orders";
import { listTasks } from "../api/tasks";
import { listLatestVehicleLocations } from "../api/vehicleLocations";
import type { ManualReviewQueueItem, RideOrder, UUID, VehicleLocationSnapshotItem, VehicleTask } from "../api/types";
import DispatchMap from "../components/DispatchMap.vue";
import ManualReviewQueuePanel from "../components/ManualReviewQueuePanel.vue";
import RealtimeOrderList from "../components/RealtimeOrderList.vue";
import VehicleTaskList from "../components/VehicleTaskList.vue";
import { userMessage } from "../api/errors";
import { feedbackStore } from "../stores/feedbackStore";

const LOCATION_POLL_INTERVAL_MS = 15_000;

const orders = ref<RideOrder[]>([]);
const tasks = ref<VehicleTask[]>([]);
const reviews = ref<ManualReviewQueueItem[]>([]);
const latestLocations = ref<VehicleLocationSnapshotItem[]>([]);
const processingDecisionId = ref<UUID>();
const status = ref("");
const locationStatus = ref("");
const actionError = ref("");
const loading = ref(false);
let locationPollTimer: number | undefined;

const staleThresholdMinutes = computed(() => {
  const configured = Number(import.meta.env.VITE_MANUAL_LOCATION_STALE_MINUTES);
  return Number.isFinite(configured) && configured > 0 ? configured : 30;
});

const staleLocations = computed(() => {
  const now = Date.now();
  const thresholdMs = staleThresholdMinutes.value * 60_000;
  return latestLocations.value.filter((item) => {
    if (!isActiveVehicle(item)) {
      return false;
    }
    return now - new Date(item.latestLocation.driverReportedAt).getTime() > thresholdMs;
  });
});

async function loadWorkbench() {
  try {
    status.value = "";
    loading.value = true;
    const [loadedOrders, loadedTasks, loadedReviews, loadedLocations] = await Promise.all([
      listOrders(),
      listTasks(),
      listManualReviews(),
      listLatestVehicleLocations()
    ]);
    orders.value = loadedOrders;
    tasks.value = loadedTasks;
    reviews.value = loadedReviews;
    latestLocations.value = loadedLocations;
    locationStatus.value = "";
  } catch (error) {
    status.value = userMessage(error, "工作台数据加载失败");
  } finally {
    loading.value = false;
  }
}

async function loadLatestLocations() {
  try {
    latestLocations.value = await listLatestVehicleLocations();
    locationStatus.value = "";
  } catch (error) {
    locationStatus.value = userMessage(error, "车辆位置加载失败，已保留上次快照");
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

function isActiveVehicle(item: VehicleLocationSnapshotItem): boolean {
  return item.latestLocation.vehicleTaskId !== undefined && !["IDLE", "OFFLINE", "COMPLETED"].includes(item.currentStatus);
}

onMounted(() => {
  void loadWorkbench();
  locationPollTimer = window.setInterval(() => {
    void loadLatestLocations();
  }, LOCATION_POLL_INTERVAL_MS);
});

onBeforeUnmount(() => {
  if (locationPollTimer !== undefined) {
    window.clearInterval(locationPollTimer);
  }
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
    <p v-if="locationStatus" class="page-state">{{ locationStatus }}</p>

    <section v-if="staleLocations.length > 0" class="stale-panel" aria-label="位置较久未更新">
      <strong>位置较久未更新</strong>
      <p v-for="item in staleLocations" :key="item.vehicleId">{{ item.plateNumber }} 超过 {{ staleThresholdMinutes }} 分钟未更新位置</p>
    </section>

    <div class="dispatch-grid">
      <RealtimeOrderList :orders="orders" />
      <DispatchMap :locations="latestLocations" />
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

.stale-panel {
  display: grid;
  gap: 6px;
  margin-bottom: 14px;
  border: 1px solid #edc8c8;
  border-radius: 8px;
  background: #fff7f7;
  padding: 12px 14px;
  color: #9f2424;
  font-size: 14px;
  font-weight: 800;
}

.stale-panel strong,
.stale-panel p {
  margin: 0;
}

@media (max-width: 1180px) {
  .dispatch-grid {
    grid-template-columns: 1fr;
  }
}
</style>
