<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  alightStop,
  arriveStop,
  boardStop,
  completeTask,
  listTasks,
  markTaskException,
  markTaskSevereDelay,
  startTask
} from "../api/tasks";
import { listVirtualStops } from "../api/resources";
import { listLatestVehicleLocations } from "../api/vehicleLocations";
import type { LocationCandidate, LocationReportInput, TaskActionResponse, TaskStop, UUID, VehicleLocationSnapshotItem, VehicleTask, VirtualStop } from "../api/types";
import TaskStopTimeline from "../components/TaskStopTimeline.vue";
import StatusBadge from "../components/StatusBadge.vue";
import LocationReportPanel from "../components/LocationReportPanel.vue";
import { authStore } from "../auth/authStore";
import { userMessage } from "../api/errors";
import { feedbackStore } from "../stores/feedbackStore";

const tasks = ref<VehicleTask[]>([]);
const selectedTaskId = ref("");
const status = ref("");
const lastAction = ref("等待操作");
const loading = ref(false);
const submittingLocation = ref(false);
const virtualStops = ref<VirtualStop[]>([]);
const latestLocationItems = ref<VehicleLocationSnapshotItem[]>([]);

type PendingTaskAction =
  | { type: "start"; label: "发车"; task: VehicleTask; initialLocation?: LocationCandidate }
  | { type: "arrive"; label: "到站"; task: VehicleTask; stop: TaskStop; initialLocation?: LocationCandidate }
  | { type: "board"; label: "上车"; task: VehicleTask; stop: TaskStop; initialLocation?: LocationCandidate }
  | { type: "alight"; label: "下车"; task: VehicleTask; stop: TaskStop; initialLocation?: LocationCandidate }
  | { type: "complete"; label: "完成"; task: VehicleTask; initialLocation?: LocationCandidate };

const pendingAction = ref<PendingTaskAction | null>(null);

const selectedTask = computed(() => {
  return tasks.value.find((task) => task.id === selectedTaskId.value) ?? tasks.value[0];
});
const runningTaskCount = computed(() => tasks.value.filter((task) => task.status === "IN_PROGRESS").length);
const exceptionTaskCount = computed(() => tasks.value.filter((task) => task.status === "EXCEPTION").length);
const selectedStops = computed<TaskStop[]>(() => selectedTask.value?.stops ?? []);
const nextPlannedStop = computed(() => selectedStops.value.find((stop) => stop.status === "PLANNED"));
const nextBoardingStop = computed(() => selectedStops.value.find((stop) => stop.stopType === "BOARDING" && stop.status === "ARRIVED"));
const nextAlightingStop = computed(() => selectedStops.value.find((stop) => stop.stopType === "ALIGHTING" && stop.status === "ARRIVED"));
const canComplete = computed(() => {
  return selectedStops.value.length > 0 && selectedStops.value.every((stop) => {
    if (stop.stopType === "BOARDING") {
      return stop.status === "BOARDED";
    }
    if (stop.stopType === "ALIGHTING") {
      return stop.status === "ALIGHTED";
    }
    return stop.status !== "PLANNED";
  });
});
const canStartTask = computed(() => selectedTask.value?.status === "DISPATCHED");
const canOperateStops = computed(() => selectedTask.value?.status === "IN_PROGRESS");
const canHandleException = computed(() => {
  return selectedTask.value?.status === "DISPATCHED" || selectedTask.value?.status === "IN_PROGRESS";
});

function taskLabel(task: VehicleTask) {
  return task.id.length > 8 ? task.id.slice(0, 8) : task.id;
}

function formatDateTime(value?: string) {
  if (!value) {
    return "--";
  }
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
}

function locationSnapshot(vehicleId: UUID) {
  return latestLocationItems.value.find((location) => location.vehicleId === vehicleId)?.latestLocation;
}

function locationSourceLabel() {
  return "人工上报";
}

async function loadVehicleTasks() {
  status.value = "";
  loading.value = true;
  try {
    tasks.value = await listTasks();
    if (!selectedTaskId.value && tasks.value.length > 0) {
      selectedTaskId.value = tasks.value[0].id;
    }
  } catch (error) {
    status.value = userMessage(error, "任务数据加载失败");
  } finally {
    loading.value = false;
  }
}

async function loadLocationReferenceData() {
  try {
    const [stops, locations] = await Promise.all([
      listVirtualStops(),
      listLatestVehicleLocations()
    ]);
    virtualStops.value = stops;
    latestLocationItems.value = locations;
  } catch {
    virtualStops.value = [];
    latestLocationItems.value = [];
  }
}

function updateTask(task: VehicleTask, action: string) {
  const index = tasks.value.findIndex((candidate) => candidate.id === task.id);
  if (index >= 0) {
    tasks.value.splice(index, 1, task);
  } else {
    tasks.value.push(task);
  }
  selectedTaskId.value = task.id;
  lastAction.value = action;
}

async function runTaskAction(action: string, operation: () => Promise<VehicleTask>) {
  status.value = "";
  try {
    updateTask(await operation(), action);
    feedbackStore.success(`任务已${action}`);
  } catch (error) {
    status.value = userMessage(error, `${action}失败`);
    feedbackStore.error(status.value);
  }
}

function openStartTaskPanel() {
  if (!selectedTask.value) {
    return;
  }
  pendingAction.value = {
    type: "start",
    label: "发车",
    task: selectedTask.value,
    initialLocation: snapshotCandidate(selectedTask.value.vehicleId)
  };
}

function openArriveStopPanel() {
  if (!selectedTask.value || !nextPlannedStop.value) {
    return;
  }
  pendingAction.value = {
    type: "arrive",
    label: "到站",
    task: selectedTask.value,
    stop: nextPlannedStop.value,
    initialLocation: stopCandidate(nextPlannedStop.value)
  };
}

function openBoardStopPanel() {
  if (!selectedTask.value || !nextBoardingStop.value) {
    return;
  }
  pendingAction.value = {
    type: "board",
    label: "上车",
    task: selectedTask.value,
    stop: nextBoardingStop.value,
    initialLocation: stopCandidate(nextBoardingStop.value)
  };
}

function openAlightStopPanel() {
  if (!selectedTask.value || !nextAlightingStop.value) {
    return;
  }
  pendingAction.value = {
    type: "alight",
    label: "下车",
    task: selectedTask.value,
    stop: nextAlightingStop.value,
    initialLocation: stopCandidate(nextAlightingStop.value)
  };
}

function openCompleteTaskPanel() {
  if (!selectedTask.value) {
    return;
  }
  const lastStop = selectedStops.value[selectedStops.value.length - 1];
  pendingAction.value = {
    type: "complete",
    label: "完成",
    task: selectedTask.value,
    initialLocation: lastStop ? stopCandidate(lastStop) : snapshotCandidate(selectedTask.value.vehicleId)
  };
}

async function failSelectedTask() {
  if (!selectedTask.value) {
    return;
  }
  await runTaskAction("车辆故障", () => markTaskException(selectedTask.value!.id, "车辆故障"));
}

async function delaySelectedTask() {
  if (!selectedTask.value) {
    return;
  }
  await runTaskAction("严重延误", () => markTaskSevereDelay(selectedTask.value!.id, "预计到达严重延误"));
}

async function submitPendingLocation(locationReport: LocationReportInput) {
  const action = pendingAction.value;
  if (!action) {
    return;
  }
  status.value = "";
  submittingLocation.value = true;
  try {
    const response = await runPendingOperation(action, locationReport);
    updateTask(response.task, action.label);
    applyLocationEvent(response);
    pendingAction.value = null;
    if (response.warnings.includes("OUTSIDE_SERVICE_AREA")) {
      feedbackStore.success(`任务已${lastAction.value}，位置在服务区外`);
    } else {
      feedbackStore.success(`任务已${lastAction.value}`);
    }
  } catch (error) {
    status.value = userMessage(error, `${action.label}失败`);
    feedbackStore.error(status.value);
  } finally {
    submittingLocation.value = false;
  }
}

function runPendingOperation(action: PendingTaskAction, locationReport: LocationReportInput): Promise<TaskActionResponse> {
  switch (action.type) {
    case "start":
      return startTask(action.task.id, locationReport);
    case "arrive":
      return arriveStop(action.task.id, action.stop.id, locationReport);
    case "board":
      return boardStop(action.task.id, action.stop.id, locationReport);
    case "alight":
      return alightStop(action.task.id, action.stop.id, locationReport);
    case "complete":
      return completeTask(action.task.id, locationReport);
  }
}

function snapshotCandidate(vehicleId: UUID): LocationCandidate | undefined {
  const item = latestLocationItems.value.find((location) => location.vehicleId === vehicleId);
  if (!item) {
    return undefined;
  }
  const coordinates = parseCoordinatePair(item.latestLocation.longitude, item.latestLocation.latitude);
  if (!coordinates) {
    return {
      standardizedAddress: item.latestLocation.standardizedAddress,
      outsideServiceArea: item.latestLocation.outsideServiceArea === true
    };
  }
  return {
    ...coordinates,
    standardizedAddress: item.latestLocation.standardizedAddress,
    outsideServiceArea: item.latestLocation.outsideServiceArea === true
  };
}

function stopCandidate(stop: TaskStop): LocationCandidate | undefined {
  const virtualStop = virtualStops.value.find((candidate) => candidate.id === stop.virtualStopId);
  if (!virtualStop) {
    return undefined;
  }
  const coordinates = parsePoint(virtualStop.location);
  if (!coordinates) {
    return {
      standardizedAddress: virtualStop.name,
      virtualStopId: virtualStop.id,
      providerDegraded: true
    };
  }
  return {
    ...coordinates,
    standardizedAddress: virtualStop.name,
    virtualStopId: virtualStop.id
  };
}

function applyLocationEvent(response: TaskActionResponse) {
  if (!response.snapshotApplied || !response.locationEvent) {
    return;
  }
  const item: VehicleLocationSnapshotItem = {
    vehicleId: response.locationEvent.vehicleId,
    plateNumber: "",
    currentStatus: "",
    latestLocation: {
      longitude: response.locationEvent.longitude,
      latitude: response.locationEvent.latitude,
      standardizedAddress: response.locationEvent.standardizedAddress,
      source: response.locationEvent.source,
      coordinateSystem: response.locationEvent.coordinateSystem,
      driverReportedAt: response.locationEvent.driverReportedAt,
      recordedAt: response.locationEvent.recordedAt,
      eventId: response.locationEvent.id,
      vehicleTaskId: response.locationEvent.vehicleTaskId,
      outsideServiceArea: response.locationEvent.outsideServiceArea === true
    }
  };
  latestLocationItems.value = [
    item,
    ...latestLocationItems.value.filter((location) => location.vehicleId !== item.vehicleId)
  ];
}

function parsePoint(value: string): { longitude: number; latitude: number } | null {
  const matched = value.match(/POINT\s*\(\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*\)/i);
  if (!matched) {
    return null;
  }
  return { longitude: Number(matched[1]), latitude: Number(matched[2]) };
}

function parseCoordinatePair(longitudeValue: number | string, latitudeValue: number | string): { longitude: number; latitude: number } | null {
  const longitude = parseCoordinateValue(longitudeValue);
  const latitude = parseCoordinateValue(latitudeValue);
  if (longitude === null || latitude === null) {
    return null;
  }
  return { longitude, latitude };
}

function parseCoordinateValue(value: number | string): number | null {
  if (typeof value === "string" && value.trim() === "") {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function isOutsideServiceArea(location: LocationCandidate) {
  return location.outsideServiceArea === true;
}

onMounted(() => {
  void loadVehicleTasks();
  void loadLocationReferenceData();
});
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="page-kicker">TASKS</p>
        <h2 class="page-title">车辆任务</h2>
        <p class="page-subtitle">跟踪车辆任务状态、站点到达、乘客上下车和异常处置。</p>
      </div>
      <span class="status-pill">执行</span>
    </header>

    <div class="summary-grid">
      <article class="metric-panel">
        <p class="metric-label">运行任务</p>
        <p class="metric-value">{{ runningTaskCount }}</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">异常任务</p>
        <p class="metric-value">{{ exceptionTaskCount }}</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">最近操作</p>
        <p class="metric-value">{{ lastAction }}</p>
      </article>
    </div>

    <p v-if="loading" class="page-state">正在同步车辆任务与站点执行状态…</p>
    <p v-else-if="status" class="page-state">{{ status }}</p>

    <div class="split-grid">
      <section class="work-panel">
        <h3 class="section-title">任务执行</h3>
        <table class="data-table">
          <thead>
            <tr>
              <th>任务</th>
              <th>车辆</th>
              <th>位置</th>
              <th>状态</th>
              <th>计划发车</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="task in tasks" :key="task.id" :class="{ 'is-selected': task.id === selectedTaskId }">
              <td>{{ taskLabel(task) }}</td>
              <td>{{ task.vehicleId }}</td>
              <td>
                <div v-if="locationSnapshot(task.vehicleId)" class="location-cell">
                  <strong>{{ locationSourceLabel() }}</strong>
                  <span>{{ formatDateTime(locationSnapshot(task.vehicleId)?.driverReportedAt) }}</span>
                  <small>{{ locationSnapshot(task.vehicleId)?.standardizedAddress }}</small>
                </div>
                <span v-else class="location-placeholder">无位置上报</span>
              </td>
              <td><StatusBadge :code="task.status" /></td>
              <td>{{ formatDateTime(task.plannedStartAt) }}</td>
              <td>
                <button class="secondary-button" type="button" :aria-pressed="task.id === selectedTaskId" @click="selectedTaskId = task.id">选择</button>
              </td>
            </tr>
            <tr v-if="tasks.length === 0">
              <td colspan="6">暂无车辆任务</td>
            </tr>
          </tbody>
        </table>
        <div v-if="authStore.has('TASK_EXECUTE')" class="toolbar">
          <button class="primary-button" type="button" :disabled="!canStartTask" @click="openStartTaskPanel">发车</button>
          <button class="secondary-button" type="button" :disabled="!canOperateStops || !nextPlannedStop" @click="openArriveStopPanel">到站</button>
          <button class="secondary-button" type="button" :disabled="!canOperateStops || !nextBoardingStop" @click="openBoardStopPanel">上车</button>
          <button class="secondary-button" type="button" :disabled="!canOperateStops || !nextAlightingStop" @click="openAlightStopPanel">下车</button>
          <button class="secondary-button" type="button" :disabled="!canOperateStops || !canComplete" @click="openCompleteTaskPanel">完成</button>
          <button class="danger-button" type="button" :disabled="!canHandleException" @click="failSelectedTask">车辆故障</button>
          <button class="danger-button" type="button" :disabled="!canHandleException" @click="delaySelectedTask">严重延误</button>
        </div>
        <LocationReportPanel
          v-if="pendingAction"
          :action-label="pendingAction.label"
          :initial-location="pendingAction.initialLocation"
          :virtual-stops="virtualStops"
          :submitting="submittingLocation"
          :is-outside-service-area="isOutsideServiceArea"
          @close="pendingAction = null"
          @submit="submitPendingLocation"
        />
      </section>

      <section class="work-panel">
        <h3 class="section-title">站点时间线</h3>
        <TaskStopTimeline :stops="selectedStops" />
      </section>
    </div>
  </section>
</template>
