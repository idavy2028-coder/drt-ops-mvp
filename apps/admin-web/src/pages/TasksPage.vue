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
import type { TaskStop, VehicleTask } from "../api/types";
import TaskStopTimeline from "../components/TaskStopTimeline.vue";

const tasks = ref<VehicleTask[]>([]);
const selectedTaskId = ref("");
const status = ref("");
const lastAction = ref("等待操作");

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

async function loadVehicleTasks() {
  status.value = "";
  try {
    tasks.value = await listTasks();
    if (!selectedTaskId.value && tasks.value.length > 0) {
      selectedTaskId.value = tasks.value[0].id;
    }
  } catch (error) {
    status.value = error instanceof Error ? error.message : "任务数据加载失败";
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
  } catch (error) {
    status.value = error instanceof Error ? error.message : `${action}失败`;
  }
}

async function startSelectedTask() {
  if (!selectedTask.value) {
    return;
  }
  await runTaskAction("发车", () => startTask(selectedTask.value!.id));
}

async function arriveNextStop() {
  if (!selectedTask.value || !nextPlannedStop.value) {
    return;
  }
  await runTaskAction("到站", () => arriveStop(selectedTask.value!.id, nextPlannedStop.value!.id));
}

async function boardNextPassenger() {
  if (!selectedTask.value || !nextBoardingStop.value) {
    return;
  }
  await runTaskAction("上车", () => boardStop(selectedTask.value!.id, nextBoardingStop.value!.id));
}

async function alightNextPassenger() {
  if (!selectedTask.value || !nextAlightingStop.value) {
    return;
  }
  await runTaskAction("下车", () => alightStop(selectedTask.value!.id, nextAlightingStop.value!.id));
}

async function completeSelectedTask() {
  if (!selectedTask.value) {
    return;
  }
  await runTaskAction("完成", () => completeTask(selectedTask.value!.id));
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

onMounted(() => {
  void loadVehicleTasks();
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

    <p v-if="status" class="section-copy">状态：{{ status }}</p>

    <div class="split-grid">
      <section class="work-panel">
        <h3 class="section-title">任务执行</h3>
        <table class="data-table">
          <thead>
            <tr>
              <th>任务</th>
              <th>车辆</th>
              <th>状态</th>
              <th>计划发车</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="task in tasks" :key="task.id">
              <td>{{ taskLabel(task) }}</td>
              <td>{{ task.vehicleId }}</td>
              <td><span class="status-pill">{{ task.status }}</span></td>
              <td>{{ formatDateTime(task.plannedStartAt) }}</td>
              <td>
                <button class="secondary-button" type="button" @click="selectedTaskId = task.id">选择</button>
              </td>
            </tr>
            <tr v-if="tasks.length === 0">
              <td colspan="5">暂无车辆任务</td>
            </tr>
          </tbody>
        </table>
        <div class="toolbar">
          <button class="primary-button" type="button" :disabled="!selectedTask" @click="startSelectedTask">发车</button>
          <button class="secondary-button" type="button" :disabled="!nextPlannedStop" @click="arriveNextStop">到站</button>
          <button class="secondary-button" type="button" :disabled="!nextBoardingStop" @click="boardNextPassenger">上车</button>
          <button class="secondary-button" type="button" :disabled="!nextAlightingStop" @click="alightNextPassenger">下车</button>
          <button class="secondary-button" type="button" :disabled="!canComplete" @click="completeSelectedTask">完成</button>
          <button class="danger-button" type="button" :disabled="!selectedTask" @click="failSelectedTask">车辆故障</button>
          <button class="danger-button" type="button" :disabled="!selectedTask" @click="delaySelectedTask">严重延误</button>
        </div>
      </section>

      <section class="work-panel">
        <h3 class="section-title">站点时间线</h3>
        <TaskStopTimeline :stops="selectedStops" />
      </section>
    </div>
  </section>
</template>
