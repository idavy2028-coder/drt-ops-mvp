<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { approveManualReview, listManualReviews, rejectManualReview } from "../api/manualReviews";
import { listOrders } from "../api/orders";
import { listTasks } from "../api/tasks";
import { listLatestVehicleLocations, listVehicleLocationEvents } from "../api/vehicleLocations";
import { listServiceAreas, listVirtualStops } from "../api/resources";
import type { ManualReviewQueueItem, RideOrder, ServiceArea, UUID, VehicleLocationEventView, VehicleLocationSnapshotItem, VehicleTask, VirtualStop } from "../api/types";
import DispatchMap from "../components/DispatchMap.vue";
import ManualReviewQueuePanel from "../components/ManualReviewQueuePanel.vue";
import RealtimeOrderList from "../components/RealtimeOrderList.vue";
import VehicleLocationSidebar from "../components/VehicleLocationSidebar.vue";
import VehicleTaskList from "../components/VehicleTaskList.vue";
import { userMessage } from "../api/errors";
import { feedbackStore } from "../stores/feedbackStore";

const LOCATION_POLL_INTERVAL_MS = 15_000;
const orders = ref<RideOrder[]>([]);
const tasks = ref<VehicleTask[]>([]);
const reviews = ref<ManualReviewQueueItem[]>([]);
const latestLocations = ref<VehicleLocationSnapshotItem[]>([]);
const serviceAreas = ref<ServiceArea[]>([]);
const virtualStops = ref<VirtualStop[]>([]);
const eventChain = ref<VehicleLocationEventView[]>([]);
const selectedTaskId = ref<UUID>();
const selectedVehicleId = ref<UUID>();
const vehicleFocusRequest = ref(0);
const processingDecisionId = ref<UUID>();
const status = ref("");
const locationStatus = ref("");
const actionError = ref("");
const loading = ref(false);
let locationPollTimer: number | undefined;

const selectedTask = computed(() => tasks.value.find((task) => task.id === selectedTaskId.value));
const activeServiceArea = computed(() => serviceAreas.value.find((area) => area.enabled) ?? serviceAreas.value[0]);
const staleThresholdMinutes = computed(() => { const configured = Number(import.meta.env.VITE_MANUAL_LOCATION_STALE_MINUTES); return Number.isFinite(configured) && configured > 0 ? configured : 30; });
const staleLocations = computed(() => latestLocations.value.filter((item) => isActiveVehicle(item) && Date.now() - new Date(item.latestLocation.driverReportedAt).getTime() > staleThresholdMinutes.value * 60_000));
const activeVehicleCount = computed(() => latestLocations.value.filter(isActiveVehicle).length);

async function loadWorkbench() {
  try {
    status.value = ""; loading.value = true;
    const [loadedOrders, loadedTasks, loadedReviews, loadedLocations, loadedAreas, loadedStops] = await Promise.all([
      listOrders(), listTasks(), listManualReviews(), listLatestVehicleLocations(), listServiceAreas(), listVirtualStops()
    ]);
    orders.value = loadedOrders; tasks.value = loadedTasks; reviews.value = loadedReviews; latestLocations.value = loadedLocations;
    serviceAreas.value = loadedAreas; virtualStops.value = loadedStops; locationStatus.value = "";
    if (!selectedTaskId.value || !loadedTasks.some((task) => task.id === selectedTaskId.value)) selectedTaskId.value = loadedTasks[0]?.id;
    if (!selectedVehicleId.value || !loadedLocations.some((item) => item.vehicleId === selectedVehicleId.value && hasValidLocation(item))) {
      selectedVehicleId.value = (loadedLocations.find((item) => isActiveVehicle(item) && hasValidLocation(item))
        ?? loadedLocations.find(hasValidLocation))?.vehicleId;
    }
    await loadTaskChain(selectedTaskId.value);
  } catch (error) {
    status.value = userMessage(error, "工作台数据加载失败");
  } finally { loading.value = false; }
}

async function loadLatestLocations() {
  try { latestLocations.value = await listLatestVehicleLocations(); locationStatus.value = ""; }
  catch (error) { locationStatus.value = userMessage(error, "车辆位置加载失败，已保留上次快照"); }
}

async function selectTask(taskId: UUID): Promise<void> {
  selectedTaskId.value = taskId;
  const taskVehicleId = tasks.value.find((task) => task.id === taskId)?.vehicleId;
  if (taskVehicleId && latestLocations.value.some((item) => item.vehicleId === taskVehicleId && hasValidLocation(item))) selectedVehicleId.value = taskVehicleId;
  await loadTaskChain(taskId);
}

function selectVehicle(vehicleId: UUID): void {
  selectedVehicleId.value = vehicleId;
  vehicleFocusRequest.value += 1;
}

async function loadTaskChain(taskId?: UUID): Promise<void> {
  if (!taskId) { eventChain.value = []; return; }
  try { eventChain.value = await listVehicleLocationEvents({ taskId }); }
  catch (error) { eventChain.value = []; locationStatus.value = userMessage(error, "任务位置链加载失败，地图保留现有任务和车辆数据"); }
}

async function approve(decisionId: UUID) {
  processingDecisionId.value = decisionId; actionError.value = "";
  try { await approveManualReview(decisionId); await loadWorkbench(); }
  catch (error) { actionError.value = userMessage(error, "人工确认失败"); feedbackStore.error(actionError.value); }
  finally { processingDecisionId.value = undefined; }
  if (!actionError.value) feedbackStore.success("人工复核已确认，车辆任务已生成。");
}

async function reject(payload: { decisionId: UUID; reason: string }) {
  processingDecisionId.value = payload.decisionId; actionError.value = "";
  try { await rejectManualReview(payload.decisionId, payload.reason); await loadWorkbench(); }
  catch (error) { actionError.value = userMessage(error, "人工拒绝失败"); feedbackStore.error(actionError.value); }
  finally { processingDecisionId.value = undefined; }
  if (!actionError.value) feedbackStore.success("人工复核已拒绝，订单已关闭。");
}

function isActiveVehicle(item: VehicleLocationSnapshotItem): boolean { return item.latestLocation.vehicleTaskId !== undefined && !["IDLE", "OFFLINE", "COMPLETED"].includes(item.currentStatus); }
function hasValidLocation(item: VehicleLocationSnapshotItem): boolean { return Number.isFinite(Number(item.latestLocation.longitude)) && Number.isFinite(Number(item.latestLocation.latitude)); }

onMounted(() => { void loadWorkbench(); locationPollTimer = window.setInterval(() => { void loadLatestLocations(); }, LOCATION_POLL_INTERVAL_MS); });
onBeforeUnmount(() => { if (locationPollTimer !== undefined) window.clearInterval(locationPollTimer); });
</script>

<template>
  <section class="page">
    <header class="page-header"><div><p class="page-kicker">DISPATCH</p><h2 class="page-title">调度工作台</h2><p class="page-subtitle">聚焦实时订单、车辆任务、服务区地图、算法解释和人工操作队列。</p></div><div class="toolbar"><button class="secondary-button" type="button" :disabled="loading" @click="loadWorkbench">{{ loading ? "同步中" : "刷新" }}</button><span class="status-pill">{{ loading ? "同步中" : "实时" }}</span></div></header>
    <p v-if="loading" class="page-state">正在汇总实时订单、车辆任务、人工复核和地图资源。</p><p v-else-if="status" class="page-state">{{ status }}</p><p v-if="locationStatus" class="page-state">{{ locationStatus }}</p>
    <section v-if="staleLocations.length" class="stale-panel" aria-label="位置较久未更新"><strong>位置较久未更新</strong><p v-for="item in staleLocations" :key="item.vehicleId">{{ item.plateNumber }} 超过 {{ staleThresholdMinutes }} 分钟未更新位置</p></section>
    <div class="dispatch-console">
      <aside class="operations-rail" aria-label="待处理订单与任务">
        <div class="rail-section"><RealtimeOrderList :orders="orders" compact /></div>
        <div class="rail-section review-section"><ManualReviewQueuePanel :items="reviews" :processing-decision-id="processingDecisionId" :error="actionError" @approve="approve" @reject="reject" /></div>
        <div class="rail-section"><VehicleTaskList :tasks="tasks" :selected-task-id="selectedTaskId" compact @select="selectTask" /></div>
      </aside>
      <main class="map-stage">
        <DispatchMap
          :service-area="activeServiceArea"
          :stops="virtualStops"
          :locations="latestLocations"
          :event-chain="eventChain"
          :selected-task="selectedTask"
          :selected-vehicle-id="selectedVehicleId"
          :vehicle-focus-request="vehicleFocusRequest"
          @select-vehicle="selectVehicle"
        />
        <div class="map-metrics" aria-label="调度关键指标">
          <span><small>待调度</small><strong>{{ orders.filter((order) => order.status === "PENDING_DISPATCH").length }}</strong></span>
          <span><small>待复核</small><strong>{{ reviews.length }}</strong></span>
          <span><small>执行中</small><strong>{{ activeVehicleCount }}</strong></span>
        </div>
      </main>
      <VehicleLocationSidebar :locations="latestLocations" :selected-vehicle-id="selectedVehicleId" @select="selectVehicle" />
    </div>
  </section>
</template>

<style scoped>
.dispatch-console { display: grid; gap: 12px; grid-template-columns: minmax(230px, 270px) minmax(0, 1fr) minmax(260px, 310px); height: max(560px, calc(100dvh - 220px)); min-height: 0; }
.operations-rail { background: #f4f7f5; border: 1px solid #d9e1dc; border-radius: 8px; display: grid; gap: 8px; grid-template-rows: minmax(120px, .8fr) minmax(160px, 1.2fr) minmax(130px, 1fr); min-height: 0; overflow: hidden; padding: 8px; }
.rail-section { min-height: 0; overflow: auto; }
.rail-section :deep(.work-panel) { border: 0; border-radius: 5px; box-shadow: none; min-height: 100%; padding: 11px; }
.rail-section :deep(.section-title) { font-size: 14px; margin-bottom: 9px; }
.review-section :deep(.review-main), .review-section :deep(.review-metrics) { align-items: flex-start; display: grid; gap: 6px; grid-template-columns: 1fr; }
.review-section :deep(.review-item) { padding: 10px; }
.review-section :deep(.decision-id), .review-section :deep(.review-metrics span) { overflow-wrap: anywhere; }
.map-stage { min-height: 0; position: relative; }
.map-stage > :deep(.dispatch-map) { height: 100%; min-height: 0; }
.map-metrics { display: flex; gap: 6px; pointer-events: none; position: absolute; right: 14px; top: 14px; z-index: 600; }
.map-metrics span { align-items: center; backdrop-filter: blur(8px); background: #10251ee8; border: 1px solid #ffffff24; border-radius: 5px; box-shadow: 0 8px 20px #10251e26; color: #fff; display: flex; gap: 9px; min-width: 74px; padding: 7px 9px; }
.map-metrics small { color: #b9cbc3; font-size: 10px; font-weight: 800; }
.map-metrics strong { font-size: 17px; }
.stale-panel { background: #fff7f7; border: 1px solid #edc8c8; color: #9f2424; display: grid; font-size: 13px; font-weight: 800; gap: 4px; margin-bottom: 10px; padding: 9px 12px; }
.stale-panel strong, .stale-panel p { margin: 0; }
@media (max-width: 1500px) {
  .map-metrics { top: 58px; }
}
@media (max-width: 1180px) {
  .dispatch-console { grid-template-columns: minmax(220px, 260px) minmax(0, 1fr); }
  .vehicle-sidebar { grid-column: 1 / -1; max-height: 330px; }
}
@media (max-width: 820px) {
  .dispatch-console { display: flex; flex-direction: column; height: auto; }
  .operations-rail { grid-template-rows: repeat(3, minmax(160px, auto)); max-height: 680px; }
  .map-stage { height: 620px; }
  .map-metrics { left: 14px; right: auto; top: 58px; }
}
</style>
