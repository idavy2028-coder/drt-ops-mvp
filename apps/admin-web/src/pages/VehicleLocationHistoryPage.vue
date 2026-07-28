<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { exportVehicleLocationEvents, listLocationReportVehicles, listVehicleLocationEvents, reportVehicleStandbyLocation } from "../api/vehicleLocations";
import { listServiceAreas, listVirtualStops } from "../api/resources";
import type { DecimalValue, LocationCandidate, LocationReportInput, ServiceArea, ServiceAreaBoundaryView, VehicleLocationEventFilters, VehicleLocationEventView, VehicleLocationReportCandidate, VirtualStop } from "../api/types";
import { authStore } from "../auth/authStore";
import { userMessage } from "../api/errors";
import { feedbackStore } from "../stores/feedbackStore";
import LocationReportPanel from "../components/LocationReportPanel.vue";

const vehicleId = ref("");
const taskId = ref("");
const date = ref("");
const eventType = ref("");
const events = ref<VehicleLocationEventView[]>([]);
const status = ref("请输入车辆编号或任务编号后查询位置历史。");
const loading = ref(false);
const exporting = ref(false);
const reportingResourcesLoading = ref(false);
const reporting = ref(false);
const reportingPanelVisible = ref(false);
const reportingSetupMessage = ref("");
const reportingEntryMessage = ref("");
const reportVehicles = ref<VehicleLocationReportCandidate[]>([]);
const enabledVirtualStops = ref<VirtualStop[]>([]);
const enabledServiceArea = ref<ServiceAreaBoundaryView>();
const selectedReportVehicleId = ref("");
const vehicleExportUnsupportedMessage = "车辆维度导出需后端支持，请改用任务编号或清空车辆筛选";

const canExport = computed(() => authStore.has("LOCATION_EXPORT"));
const canCorrect = computed(() => authStore.has("LOCATION_CORRECT"));
const canReport = computed(() => authStore.has("LOCATION_REPORT"));
const vehicleOnlyExportUnsupported = computed(() => vehicleId.value.trim() !== "" && taskId.value.trim() === "");
const exportDisabled = computed(() => exporting.value || vehicleOnlyExportUnsupported.value);
const selectedReportVehicle = computed(() => reportVehicles.value.find((vehicle) => vehicle.vehicleId === selectedReportVehicleId.value));
const selectedReportInitialLocation = computed<LocationCandidate | undefined>(() => {
  const latestLocation = selectedReportVehicle.value?.latestLocation;
  if (latestLocation === null || latestLocation === undefined) {
    return undefined;
  }
  const longitude = parseFiniteCoordinate(latestLocation.longitude);
  const latitude = parseFiniteCoordinate(latestLocation.latitude);
  if (longitude === undefined || latitude === undefined) {
    return undefined;
  }
  return {
    longitude,
    latitude,
    standardizedAddress: usableSnapshotAddress(latestLocation.standardizedAddress),
    outsideServiceArea: latestLocation.outsideServiceArea
  };
});
const reportEntryDisabled = computed(() => reportingResourcesLoading.value || enabledServiceArea.value === undefined);

onMounted(() => {
  if (canReport.value) {
    void loadReportingResources();
  }
});

async function search() {
  const filters = buildFilters();
  if (!filters.vehicleId && !filters.taskId) {
    status.value = "请至少输入车辆编号或任务编号。";
    events.value = [];
    return;
  }
  loading.value = true;
  status.value = "";
  try {
    events.value = await listVehicleLocationEvents(filters);
    if (events.value.length === 0) {
      status.value = "暂无符合条件的位置事件。";
    }
  } catch (error) {
    status.value = userMessage(error, "位置历史查询失败");
  } finally {
    loading.value = false;
  }
}

async function exportCsv() {
  if (vehicleOnlyExportUnsupported.value) {
    feedbackStore.info(vehicleExportUnsupportedMessage);
    return;
  }
  exporting.value = true;
  try {
    await exportVehicleLocationEvents(buildFilters());
    feedbackStore.success("位置事件导出已提交");
  } catch (error) {
    feedbackStore.error(userMessage(error, "位置事件导出失败"));
  } finally {
    exporting.value = false;
  }
}

async function loadReportingResources() {
  reportingResourcesLoading.value = true;
  reportingSetupMessage.value = "";
  try {
    const [vehicles, serviceAreas, stops] = await Promise.all([
      listLocationReportVehicles(),
      listServiceAreas(),
      listVirtualStops({ enabled: true })
    ]);
    reportVehicles.value = vehicles;
    enabledVirtualStops.value = stops;
    enabledServiceArea.value = toBoundaryView(serviceAreas.find((serviceArea) => serviceArea.enabled));
    if (enabledServiceArea.value === undefined) {
      reportingSetupMessage.value = "未找到已启用服务区，无法校验待命位置";
    }
  } catch (error) {
    reportingSetupMessage.value = userMessage(error, "待命位置上报资源加载失败");
  } finally {
    reportingResourcesLoading.value = false;
  }
}

function openStandbyLocationReport() {
  reportingEntryMessage.value = "";
  if (enabledServiceArea.value === undefined) {
    reportingEntryMessage.value = "未找到已启用服务区，无法校验待命位置";
    return;
  }
  if (selectedReportVehicle.value === undefined) {
    reportingEntryMessage.value = "请先选择车辆";
    return;
  }
  reportingPanelVisible.value = true;
}

async function submitStandbyLocation(input: LocationReportInput) {
  const vehicle = selectedReportVehicle.value;
  if (vehicle === undefined || reporting.value) {
    return;
  }
  reporting.value = true;
  try {
    const response = await reportVehicleStandbyLocation(vehicle.vehicleId, input);
    reportingPanelVisible.value = false;
    await loadReportingResources();
    vehicleId.value = vehicle.vehicleId;
    await search();
    if (response.warnings.includes("OUTSIDE_SERVICE_AREA")) {
      feedbackStore.success("待命位置已上报，已保存服务区外位置；车辆快照是否推进以接口返回为准");
    } else if (response.replayed) {
      feedbackStore.info("待命位置已上报（重复提交已复用）");
    } else {
      feedbackStore.success("待命位置已上报");
    }
  } catch (error) {
    feedbackStore.error(userMessage(error, "待命位置上报失败"));
  } finally {
    reporting.value = false;
  }
}

function toBoundaryView(serviceArea: ServiceArea | undefined): ServiceAreaBoundaryView | undefined {
  if (serviceArea === undefined) {
    return undefined;
  }
  return {
    id: serviceArea.id,
    name: serviceArea.name,
    boundaryWkt: serviceArea.boundary,
    boundarySource: serviceArea.boundarySource,
    boundaryVersion: serviceArea.boundaryVersion,
    draftBoundaryWkt: serviceArea.draftBoundary,
    draftBoundarySource: serviceArea.draftBoundarySource,
    draftBoundaryVersion: serviceArea.draftBoundaryVersion,
    publishedAt: serviceArea.publishedAt,
    updatedAt: serviceArea.updatedAt,
    coordinateSystem: serviceArea.coordinateSystem
  };
}

function usableSnapshotAddress(value: string | null | undefined): string {
  const address = value?.trim() ?? "";
  return address.includes("?") ? "" : address;
}

function parseFiniteCoordinate(value: DecimalValue): number | undefined {
  if (typeof value === "string" && value.trim() === "") {
    return undefined;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function buildFilters(): VehicleLocationEventFilters {
  const filters: VehicleLocationEventFilters = {};
  if (vehicleId.value.trim()) {
    filters.vehicleId = vehicleId.value.trim();
  }
  if (taskId.value.trim()) {
    filters.taskId = taskId.value.trim();
  }
  if (eventType.value) {
    filters.eventType = eventType.value;
  }
  if (date.value) {
    const range = shanghaiDateRange(date.value);
    filters.from = range.from;
    filters.to = range.to;
  }
  return filters;
}

function shanghaiDateRange(value: string): { from: string; to: string } {
  const from = new Date(`${value}T00:00:00+08:00`);
  const to = new Date(from.getTime() + 24 * 60 * 60 * 1000);
  return { from: from.toISOString(), to: to.toISOString() };
}

function eventLabel(value: string): string {
  const labels: Record<string, string> = {
    TASK_STARTED: "发车",
    TASK_STOP_ARRIVED: "到站",
    PASSENGER_BOARDED: "乘客上车",
    PASSENGER_ALIGHTED: "乘客下车",
    TASK_COMPLETED: "任务完成",
    MANUAL_CORRECTION: "人工补报"
  };
  return labels[value] ?? value;
}

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  }).format(new Date(value));
}

function delayMinutes(event: VehicleLocationEventView): number {
  const delayMs = new Date(event.recordedAt).getTime() - new Date(event.driverReportedAt).getTime();
  return Math.max(0, Math.round(delayMs / 60_000));
}
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="page-kicker">LOCATION</p>
        <h2 class="page-title">位置历史</h2>
        <p class="page-subtitle">按车辆、任务和节点追溯人工上报位置，区分驾驶员反馈时间与系统录入时间。</p>
      </div>
      <div class="toolbar">
        <button v-if="canCorrect" class="secondary-button" type="button">修正位置</button>
        <button v-if="canExport" class="secondary-button" type="button" :disabled="exportDisabled" :title="vehicleOnlyExportUnsupported ? vehicleExportUnsupportedMessage : undefined" @click="exportCsv">{{ exporting ? "导出中" : "导出 CSV" }}</button>
      </div>
    </header>
    <p v-if="canExport && vehicleOnlyExportUnsupported" class="page-state">{{ vehicleExportUnsupportedMessage }}</p>

    <section v-if="canReport" class="work-panel standby-report-panel" aria-label="待命位置上报">
      <label>
        <span>待命车辆</span>
        <select v-model="selectedReportVehicleId" :disabled="reportingResourcesLoading || reporting || reportingPanelVisible" @change="reportingEntryMessage = ''">
          <option value="">请选择车辆</option>
          <option v-for="vehicle in reportVehicles" :key="vehicle.vehicleId" :value="vehicle.vehicleId">
            {{ vehicle.plateNumber }} · {{ vehicle.currentStatus }} · {{ vehicle.dispatchable ? "可调度" : "不可调度" }}
          </option>
        </select>
      </label>
      <div class="standby-report-actions">
        <button class="primary-button" type="button" :disabled="reportEntryDisabled" @click="openStandbyLocationReport">上报待命位置</button>
        <button class="secondary-button" type="button" :disabled="reportingResourcesLoading || reporting" @click="loadReportingResources">刷新车辆</button>
      </div>
      <p v-if="reportingSetupMessage" class="page-state">{{ reportingSetupMessage }}</p>
      <p v-if="reportingEntryMessage" class="page-state">{{ reportingEntryMessage }}</p>
    </section>

    <LocationReportPanel
      v-if="reportingPanelVisible && selectedReportVehicle && enabledServiceArea"
      action-label="待命"
      :initial-location="selectedReportInitialLocation"
      :virtual-stops="enabledVirtualStops"
      :service-area="enabledServiceArea"
      :submitting="reporting"
      @close="reportingPanelVisible = false"
      @submit="submitStandbyLocation"
    />

    <section class="work-panel filter-panel" aria-label="位置历史筛选">
      <label>
        <span>车辆编号</span>
        <input v-model="vehicleId" type="text" autocomplete="off" placeholder="vehicle-1" />
      </label>
      <label>
        <span>任务编号</span>
        <input v-model="taskId" type="text" autocomplete="off" placeholder="task-1" />
      </label>
      <label>
        <span>日期</span>
        <input v-model="date" type="date" />
      </label>
      <label>
        <span>事件类型</span>
        <select v-model="eventType">
          <option value="">全部</option>
          <option value="TASK_STARTED">发车</option>
          <option value="TASK_STOP_ARRIVED">到站</option>
          <option value="PASSENGER_BOARDED">乘客上车</option>
          <option value="PASSENGER_ALIGHTED">乘客下车</option>
          <option value="TASK_COMPLETED">任务完成</option>
          <option value="MANUAL_CORRECTION">人工补报</option>
        </select>
      </label>
      <button class="primary-button" type="button" :disabled="loading" @click="search">{{ loading ? "查询中" : "查询" }}</button>
    </section>

    <p v-if="status" class="page-state">{{ status }}</p>

    <section class="work-panel timeline-panel" aria-label="位置事件时间线">
      <ol class="location-timeline">
        <li v-for="event in events" :key="event.id">
          <div class="timeline-index">{{ eventLabel(event.eventType) }}</div>
          <div class="timeline-body">
            <div class="timeline-title">
              <strong>{{ event.standardizedAddress }}</strong>
              <span>人工上报</span>
            </div>
            <p>驾驶员反馈 {{ formatDateTime(event.driverReportedAt) }}</p>
            <p>系统录入 {{ formatDateTime(event.recordedAt) }}</p>
            <p>录入延迟 {{ delayMinutes(event) }} 分钟</p>
            <p>操作人 {{ event.recordedBy }}</p>
            <p v-if="event.correctsEventId">修正原事件 {{ event.correctsEventId }}</p>
            <button v-if="canCorrect" class="secondary-button" type="button">修正位置</button>
          </div>
        </li>
      </ol>
    </section>
  </section>
</template>

<style scoped>
.filter-panel {
  display: grid;
  grid-template-columns: repeat(4, minmax(160px, 1fr)) auto;
  gap: 12px;
  align-items: end;
  margin-bottom: 14px;
}

.standby-report-panel {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) auto;
  gap: 12px;
  align-items: end;
  margin-bottom: 14px;
}

.standby-report-panel label {
  display: grid;
  gap: 6px;
  color: #3f4c46;
  font-size: 13px;
  font-weight: 900;
}

.standby-report-panel select {
  min-height: 38px;
  border: 1px solid #cfd9d4;
  border-radius: 6px;
  background: #ffffff;
  color: #17201c;
  padding: 8px 10px;
  font: inherit;
}

.standby-report-actions {
  display: flex;
  gap: 10px;
}

.standby-report-panel .page-state {
  grid-column: 1 / -1;
  margin: 0;
}

.filter-panel label {
  display: grid;
  gap: 6px;
  color: #3f4c46;
  font-size: 13px;
  font-weight: 900;
}

.filter-panel input,
.filter-panel select {
  min-height: 38px;
  border: 1px solid #cfd9d4;
  border-radius: 6px;
  background: #ffffff;
  color: #17201c;
  padding: 8px 10px;
  font: inherit;
}

.timeline-panel {
  min-height: 220px;
}

.location-timeline {
  display: grid;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.location-timeline li {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  gap: 14px;
  border-bottom: 1px solid #e4ebe7;
  padding: 12px 0;
}

.location-timeline li:last-child {
  border-bottom: 0;
}

.timeline-index {
  color: #007a5d;
  font-size: 14px;
  font-weight: 900;
}

.timeline-body {
  display: grid;
  gap: 6px;
}

.timeline-body p {
  margin: 0;
  color: #53615a;
  font-size: 13px;
  font-weight: 800;
}

.timeline-title {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.timeline-title strong {
  color: #17201c;
  font-size: 15px;
}

.timeline-title span {
  border-radius: 999px;
  background: #dff4ed;
  color: #007a5d;
  padding: 3px 8px;
  font-size: 12px;
  font-weight: 900;
}

@media (max-width: 980px) {
  .filter-panel {
    grid-template-columns: 1fr;
  }

  .standby-report-panel {
    grid-template-columns: 1fr;
  }

  .location-timeline li {
    grid-template-columns: 1fr;
  }
}
</style>
