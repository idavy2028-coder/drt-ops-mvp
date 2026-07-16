<script setup lang="ts">
import { computed, ref } from "vue";
import { exportVehicleLocationEvents, listVehicleLocationEvents } from "../api/vehicleLocations";
import type { VehicleLocationEventFilters, VehicleLocationEventView } from "../api/types";
import { authStore } from "../auth/authStore";
import { userMessage } from "../api/errors";
import { feedbackStore } from "../stores/feedbackStore";

const vehicleId = ref("");
const taskId = ref("");
const date = ref("");
const eventType = ref("");
const events = ref<VehicleLocationEventView[]>([]);
const status = ref("请输入车辆编号或任务编号后查询位置历史。");
const loading = ref(false);
const exporting = ref(false);

const canExport = computed(() => authStore.has("LOCATION_EXPORT"));
const canCorrect = computed(() => authStore.has("LOCATION_CORRECT"));

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
        <button v-if="canExport" class="secondary-button" type="button" :disabled="exporting" @click="exportCsv">{{ exporting ? "导出中" : "导出 CSV" }}</button>
      </div>
    </header>

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

  .location-timeline li {
    grid-template-columns: 1fr;
  }
}
</style>
