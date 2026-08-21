<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
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
import RecordPagination from "../components/RecordPagination.vue";
import TaskStopTimeline from "../components/TaskStopTimeline.vue";
import StatusBadge from "../components/StatusBadge.vue";
import VehicleStatusBadge from "../components/VehicleStatusBadge.vue";
import LocationReportPanel from "../components/LocationReportPanel.vue";
import { authStore } from "../auth/authStore";
import { userMessage } from "../api/errors";
import { usePageScrollRetention } from "../composables/usePageScrollRetention";
import { formatShanghaiDateTime, shanghaiDateKey } from "../presentation/dateTime";
import { feedbackStore } from "../stores/feedbackStore";

type TaskGroupKey = "today" | "history";

const TASK_PAGE_SIZE = 6;
const tasks = ref<VehicleTask[]>([]);
const route = useRoute();
const activeTaskGroup = ref<TaskGroupKey>("today");
const taskPageByGroup = ref<Record<TaskGroupKey, number>>({ today: 1, history: 1 });
const selectedTaskIdByGroup = ref<Record<TaskGroupKey, string>>({ today: "", history: "" });
const status = ref("");
const taskError = ref("");
const stopReferenceWarning = ref("");
const locationReferenceWarning = ref("");
const lastAction = ref("等待操作");
const loading = ref(false);
const submittingLocation = ref(false);
const virtualStops = ref<VirtualStop[]>([]);
const latestLocationItems = ref<VehicleLocationSnapshotItem[]>([]);
const stopNameById = computed<Record<string, string>>(() =>
  Object.fromEntries(virtualStops.value.map((stop) => [stop.id, stop.name]))
);

usePageScrollRetention();

function taskCreatedAtValue(task: VehicleTask): number {
  const timestamp = task.createdAt ? Date.parse(task.createdAt) : Number.NEGATIVE_INFINITY;
  return Number.isNaN(timestamp) ? Number.NEGATIVE_INFINITY : timestamp;
}

const taskGroups = computed(() => {
  const todayKey = shanghaiDateKey(new Date().toISOString());
  const sorted = [...tasks.value].sort((left, right) => taskCreatedAtValue(right) - taskCreatedAtValue(left));
  return {
    today: {
      title: "今日新增",
      tableLabel: "今日新增任务列表",
      empty: "暂无今日新增任务",
      items: sorted.filter((task) => shanghaiDateKey(task.createdAt) === todayKey)
    },
    history: {
      title: "历史任务",
      tableLabel: "历史任务列表",
      empty: "暂无历史任务",
      items: sorted.filter((task) => shanghaiDateKey(task.createdAt) !== todayKey)
    }
  } satisfies Record<TaskGroupKey, { title: string; tableLabel: string; empty: string; items: VehicleTask[] }>;
});

const activeGroup = computed(() => taskGroups.value[activeTaskGroup.value]);
const selectedTaskId = computed({
  get: () => selectedTaskIdByGroup.value[activeTaskGroup.value],
  set: (taskId: string) => {
    selectedTaskIdByGroup.value[activeTaskGroup.value] = taskId;
  }
});
const activePage = computed({
  get: () => taskPageByGroup.value[activeTaskGroup.value],
  set: (page: number) => {
    taskPageByGroup.value[activeTaskGroup.value] = page;
    const start = (page - 1) * TASK_PAGE_SIZE;
    const visibleTasks = activeGroup.value.items.slice(start, start + TASK_PAGE_SIZE);
    if (!visibleTasks.some((task) => task.id === selectedTaskId.value)) {
      selectedTaskId.value = visibleTasks[0]?.id ?? "";
    }
  }
});
const pagedTasks = computed(() => {
  const start = (activePage.value - 1) * TASK_PAGE_SIZE;
  return activeGroup.value.items.slice(start, start + TASK_PAGE_SIZE);
});

type PendingTaskAction =
  | { type: "start"; label: "发车"; task: VehicleTask; initialLocation?: LocationCandidate }
  | { type: "arrive"; label: "到站"; task: VehicleTask; stop: TaskStop; initialLocation?: LocationCandidate }
  | { type: "board"; label: "上车"; task: VehicleTask; stop: TaskStop; initialLocation?: LocationCandidate }
  | { type: "alight"; label: "下车"; task: VehicleTask; stop: TaskStop; initialLocation?: LocationCandidate }
  | { type: "complete"; label: "完成"; task: VehicleTask; initialLocation?: LocationCandidate };

const pendingAction = ref<PendingTaskAction | null>(null);

const selectedTask = computed(() => {
  return activeGroup.value.items.find((task) => task.id === selectedTaskId.value) ?? activeGroup.value.items[0];
});
const runningTaskCount = computed(() => tasks.value.filter((task) => task.status === "IN_PROGRESS").length);
const pendingTaskCount = computed(() => tasks.value.filter((task) => ["PENDING_DEPARTURE", "DISPATCHED"].includes(task.status)).length);
const exceptionTaskCount = computed(() => tasks.value.filter((task) => task.status === "EXCEPTION").length);
const completedTaskCount = computed(() => tasks.value.filter((task) => task.status === "COMPLETED").length);
const taskSummary = computed(() => [
  { key: "running", label: "执行中", count: runningTaskCount.value },
  { key: "pending", label: "待发车", count: pendingTaskCount.value },
  { key: "exception", label: "异常", count: exceptionTaskCount.value },
  { key: "completed", label: "已完成", count: completedTaskCount.value }
]);
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

function taskVehicleLabel(task: VehicleTask) {
  return task.vehiclePlateNumber?.trim() || "未登记车牌";
}

function taskRate(count: number): string {
  return tasks.value.length === 0
    ? "0.0%"
    : `${((count / tasks.value.length) * 100).toFixed(1)}%`;
}

function groupForTask(task: VehicleTask): TaskGroupKey {
  const todayKey = shanghaiDateKey(new Date().toISOString());
  return shanghaiDateKey(task.createdAt) === todayKey ? "today" : "history";
}

function focusTask(task: VehicleTask): void {
  const group = groupForTask(task);
  const index = taskGroups.value[group].items.findIndex((candidate) => candidate.id === task.id);
  activeTaskGroup.value = group;
  selectedTaskIdByGroup.value[group] = task.id;
  taskPageByGroup.value[group] = Math.floor(Math.max(index, 0) / TASK_PAGE_SIZE) + 1;
}

function normalizeTaskViewState(preferredTaskId?: string): void {
  (["today", "history"] satisfies TaskGroupKey[]).forEach((group) => {
    const items = taskGroups.value[group].items;
    const pageCount = Math.max(1, Math.ceil(items.length / TASK_PAGE_SIZE));
    taskPageByGroup.value[group] = Math.min(Math.max(taskPageByGroup.value[group], 1), pageCount);
    if (!items.some((task) => task.id === selectedTaskIdByGroup.value[group])) {
      const start = (taskPageByGroup.value[group] - 1) * TASK_PAGE_SIZE;
      selectedTaskIdByGroup.value[group] = items[start]?.id ?? items[0]?.id ?? "";
    }
  });

  const preferredTask = preferredTaskId
    ? tasks.value.find((task) => task.id === preferredTaskId)
    : undefined;
  if (preferredTask) {
    focusTask(preferredTask);
    return;
  }
  if (taskGroups.value[activeTaskGroup.value].items.length === 0) {
    activeTaskGroup.value = taskGroups.value.today.items.length > 0 ? "today" : "history";
  }
}

function activateTaskGroup(group: TaskGroupKey): void {
  activeTaskGroup.value = group;
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
    const nextTasks = await listTasks();
    tasks.value = nextTasks;
    taskError.value = "";
    const requestedTaskId = typeof route.query.taskId === "string" ? route.query.taskId : undefined;
    normalizeTaskViewState(requestedTaskId);
  } catch (error) {
    const message = userMessage(error, "任务数据加载失败");
    taskError.value = message;
    if (tasks.value.length > 0) {
      feedbackStore.error(message);
    }
  } finally {
    loading.value = false;
  }
}

async function loadVirtualStopReference() {
  try {
    virtualStops.value = await listVirtualStops();
    stopReferenceWarning.value = "";
  } catch (error) {
    stopReferenceWarning.value = userMessage(error, "站点数据加载失败");
  }
}

async function loadLatestLocationReference() {
  try {
    latestLocationItems.value = await listLatestVehicleLocations();
    locationReferenceWarning.value = "";
  } catch (error) {
    locationReferenceWarning.value = userMessage(error, "最新位置加载失败");
  }
}

async function loadLocationReferenceData() {
  await Promise.all([loadVirtualStopReference(), loadLatestLocationReference()]);
}

function updateTask(task: VehicleTask, action: string) {
  const index = tasks.value.findIndex((candidate) => candidate.id === task.id);
  const existingTask = index >= 0 ? tasks.value[index] : undefined;
  const mergedTask = {
    ...existingTask,
    ...task,
    createdAt: task.createdAt ?? existingTask?.createdAt,
    vehiclePlateNumber: task.vehiclePlateNumber ?? existingTask?.vehiclePlateNumber,
    vehicleStatus: task.vehicleStatus ?? existingTask?.vehicleStatus
  } as VehicleTask;
  if (index >= 0) {
    tasks.value.splice(index, 1, mergedTask);
  } else {
    tasks.value.push(mergedTask);
  }
  focusTask(mergedTask);
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
    initialLocation: lastStop
      ? taskCompletionCandidate(lastStop)
      : snapshotCandidate(selectedTask.value.vehicleId)
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

function taskCompletionCandidate(stop: TaskStop): LocationCandidate | undefined {
  const candidate = stopCandidate(stop);
  if (!candidate) {
    return undefined;
  }
  const { virtualStopId: _virtualStopId, ...location } = candidate;
  return location;
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
  <section class="page tasks-page">
    <header class="page-header">
      <div>
        <p class="page-kicker">TASKS</p>
        <h2 class="page-title">车辆任务</h2>
        <p class="page-subtitle">跟踪车辆任务状态、站点到达、乘客上下车和异常处置。</p>
      </div>
      <span class="status-pill">执行</span>
    </header>

    <div v-if="!taskError || tasks.length > 0" class="summary-grid task-summary-grid">
      <article
        v-for="item in taskSummary"
        :key="item.key"
        class="metric-panel task-metric"
        :class="`task-metric-${item.key}`"
        :aria-label="`${item.label}任务`"
      >
        <p class="metric-label">{{ item.label }}任务</p>
        <p class="metric-value">{{ item.count }} 项 · {{ taskRate(item.count) }}</p>
      </article>
    </div>

    <p v-if="loading" class="page-state">正在同步车辆任务与站点执行状态…</p>
    <div v-else-if="taskError && tasks.length === 0" class="task-error-state" role="alert">
      <p>{{ taskError }}</p>
      <button type="button" class="secondary-button" @click="loadVehicleTasks">重试任务列表</button>
    </div>
    <p v-else-if="status" class="page-state">{{ status }}</p>

    <div
      v-if="stopReferenceWarning || locationReferenceWarning"
      class="reference-warnings"
      role="status"
    >
      <span v-if="stopReferenceWarning">站点名称暂不可用，将显示站点编号。</span>
      <span v-if="locationReferenceWarning">最新位置暂不可用，不影响任务执行操作。</span>
    </div>

    <div class="split-grid task-workspace">
      <section class="work-panel task-list-panel">
        <header class="panel-heading">
          <div>
            <h3 class="section-title">任务执行</h3>
            <p>按创建日期分区，每页显示 {{ TASK_PAGE_SIZE }} 条任务。</p>
          </div>
          <span>{{ activeGroup.items.length }} 条</span>
        </header>

        <div class="record-toolbar">
          <div class="segmented-control" aria-label="任务分区">
            <button
              type="button"
              :aria-pressed="activeTaskGroup === 'today'"
              @click="activateTaskGroup('today')"
            >
              今日新增 {{ taskGroups.today.items.length }}
            </button>
            <button
              type="button"
              :aria-pressed="activeTaskGroup === 'history'"
              @click="activateTaskGroup('history')"
            >
              历史任务 {{ taskGroups.history.items.length }}
            </button>
          </div>
        </div>

        <div class="task-table-scroll">
          <table class="data-table tasks-table" :aria-label="activeGroup.tableLabel">
            <thead>
              <tr>
                <th>任务</th>
                <th>车辆状态</th>
                <th>最新位置</th>
                <th>任务状态</th>
                <th>计划发车</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="task in pagedTasks" :key="task.id" :class="{ 'is-selected': task.id === selectedTaskId }">
                <td><strong>{{ taskLabel(task) }}</strong></td>
                <td>
                  <div class="vehicle-cell">
                    <strong>{{ taskVehicleLabel(task) }}</strong>
                    <VehicleStatusBadge :code="task.vehicleStatus" />
                  </div>
                </td>
                <td>
                  <div v-if="locationSnapshot(task.vehicleId)" class="location-cell">
                    <strong>{{ locationSourceLabel() }}</strong>
                    <span>{{ formatShanghaiDateTime(locationSnapshot(task.vehicleId)?.driverReportedAt) }}</span>
                    <small>{{ locationSnapshot(task.vehicleId)?.standardizedAddress }}</small>
                  </div>
                  <span v-else class="location-placeholder">暂无位置上报</span>
                </td>
                <td><StatusBadge :code="task.status" /></td>
                <td>{{ formatShanghaiDateTime(task.plannedStartAt) }}</td>
                <td>
                  <button class="secondary-button row-select-button" type="button" :aria-pressed="task.id === selectedTaskId" @click="selectedTaskId = task.id">选择</button>
                </td>
              </tr>
              <tr v-if="pagedTasks.length === 0">
                <td colspan="6" class="record-group-empty">{{ activeGroup.empty }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <RecordPagination
          v-model:current-page="activePage"
          class="task-pagination"
          :total-items="activeGroup.items.length"
          :page-size="TASK_PAGE_SIZE"
        />
      </section>

      <section class="work-panel task-detail-panel">
        <header class="panel-heading current-task-heading">
          <div>
            <h3 class="section-title">当前任务</h3>
            <p v-if="selectedTask">任务 {{ taskLabel(selectedTask) }} · 最近操作：{{ lastAction }}</p>
            <p v-else>请选择一条任务查看执行信息</p>
          </div>
          <StatusBadge v-if="selectedTask" :code="selectedTask.status" />
        </header>

        <section class="timeline-section" aria-labelledby="task-timeline-title">
          <h4 id="task-timeline-title">站点时间线</h4>
          <TaskStopTimeline :stops="selectedStops" :stop-name-by-id="stopNameById" />
        </section>

        <div v-if="authStore.has('TASK_EXECUTE')" class="execution-controls">
          <section class="action-section" aria-label="正常执行操作">
            <h4>正常执行</h4>
            <div class="toolbar task-actions">
              <button class="primary-button" type="button" :disabled="!canStartTask" @click="openStartTaskPanel">发车</button>
              <button class="secondary-button" type="button" :disabled="!canOperateStops || !nextPlannedStop" @click="openArriveStopPanel">到站</button>
              <button class="secondary-button" type="button" :disabled="!canOperateStops || !nextBoardingStop" @click="openBoardStopPanel">上车</button>
              <button class="secondary-button" type="button" :disabled="!canOperateStops || !nextAlightingStop" @click="openAlightStopPanel">下车</button>
              <button class="secondary-button" type="button" :disabled="!canOperateStops || !canComplete" @click="openCompleteTaskPanel">完成</button>
            </div>
          </section>
          <section class="action-section exception-actions" aria-label="异常处置操作">
            <h4>异常处置</h4>
            <div class="toolbar">
              <button class="danger-button" type="button" :disabled="!canHandleException" @click="failSelectedTask">车辆故障</button>
              <button class="danger-button" type="button" :disabled="!canHandleException" @click="delaySelectedTask">严重延误</button>
            </div>
          </section>
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
    </div>
  </section>
</template>

<style scoped>
.tasks-page { gap: 12px; }
.task-summary-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.task-metric { border-top: 3px solid #8aa29a; padding: 16px; }
.task-metric-running { border-top-color: #3984aa; }
.task-metric-pending { border-top-color: #d49732; }
.task-metric-exception { border-top-color: #c7584f; }
.task-metric-completed { border-top-color: #4d9a70; }
.task-metric .metric-value { font-size: 21px; margin-top: 6px; }
.task-error-state { align-items: center; background: #fff7f5; border: 1px solid #efcbc6; border-radius: 8px; color: #9b3d35; display: flex; gap: 12px; justify-content: space-between; padding: 10px 12px; }
.task-error-state p { margin: 0; }
.reference-warnings { background: #fff9eb; border: 1px solid #edd9a5; border-radius: 8px; color: #795a17; display: flex; flex-wrap: wrap; gap: 6px 16px; padding: 8px 12px; }
.task-workspace { align-items: start; gap: 12px; grid-template-columns: minmax(0, 1.55fr) minmax(300px, 0.75fr); }
.task-list-panel,
.task-detail-panel { min-width: 0; overflow: hidden; padding: 16px; }
.panel-heading { align-items: flex-start; display: flex; gap: 12px; justify-content: space-between; margin-bottom: 12px; }
.panel-heading .section-title { margin-bottom: 3px; }
.panel-heading p { color: var(--ink-muted); font-size: 12px; margin: 0; }
.panel-heading > span { color: var(--ink-muted); font-size: 12px; font-weight: 800; white-space: nowrap; }
.record-toolbar { align-items: center; background: #f7f9f7; border: 1px solid var(--line); border-radius: 8px 8px 0 0; display: flex; min-height: 48px; padding: 6px 8px; }
.segmented-control { background: #e9eeeb; border-radius: 8px; display: inline-flex; gap: 4px; padding: 3px; }
.segmented-control button { background: transparent; border: 0; border-radius: 6px; color: #52615a; cursor: pointer; font-size: 13px; font-weight: 900; min-height: 32px; padding: 6px 12px; }
.segmented-control button[aria-pressed="true"] { background: #ffffff; box-shadow: 0 1px 4px rgb(23 36 29 / 12%); color: var(--brand); }
.task-table-scroll { border-inline: 1px solid var(--line); max-width: 100%; overflow-x: auto; }
.tasks-table { min-width: 900px; }
.tasks-table th,
.tasks-table td { height: 44px; padding: 6px 12px; vertical-align: middle; }
.vehicle-cell,
.location-cell { display: grid; gap: 3px; }
.vehicle-cell { align-items: start; justify-items: start; }
.vehicle-cell strong,
.location-cell strong { font-size: 13px; }
.location-cell span,
.location-cell small,
.location-placeholder { color: var(--ink-muted); font-size: 12px; }
.row-select-button { min-height: 32px; padding: 5px 9px; }
.task-pagination { border: 1px solid var(--line); border-radius: 0 0 8px 8px; border-top: 0; padding: 0 10px; }
.record-group-empty { color: var(--ink-muted); padding: 20px 12px; text-align: center; }
.current-task-heading { border-bottom: 1px solid var(--line); padding-bottom: 12px; }
.timeline-section { border-bottom: 1px solid var(--line); padding: 4px 0 10px; }
.timeline-section h4,
.action-section h4 { color: var(--ink); font-size: 13px; margin: 0 0 10px; }
.execution-controls { display: grid; gap: 12px; padding-top: 12px; }
.action-section { background: #f7f9f7; border: 1px solid var(--line); border-radius: 8px; padding: 10px; }
.exception-actions { background: #fff9f8; border-color: #f0d4d0; }
.task-actions,
.exception-actions .toolbar { gap: 6px; }
.task-actions .primary-button,
.task-actions .secondary-button,
.exception-actions .danger-button { min-height: 32px; padding: 5px 9px; }

@media (max-width: 900px) {
  .task-summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .task-workspace { grid-template-columns: 1fr; }
}

@media (max-width: 560px) {
  .task-summary-grid { grid-template-columns: 1fr; }
  .segmented-control { width: 100%; }
  .segmented-control button { flex: 1; }
}
</style>
