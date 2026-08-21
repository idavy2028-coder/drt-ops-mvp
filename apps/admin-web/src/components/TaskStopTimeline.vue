<script setup lang="ts">
import { computed } from "vue";
import type { TaskStop } from "../api/types";
import { formatShanghaiDateTime } from "../presentation/dateTime";
import { labelFor } from "../presentation/operations";

const props = defineProps<{
  stops: TaskStop[];
  stopNameById: Readonly<Record<string, string>>;
}>();

const sortedStops = computed(() => [...props.stops].sort((left, right) => left.sequenceNumber - right.sequenceNumber));

function stopName(stop: TaskStop): string {
  return props.stopNameById[stop.virtualStopId] ?? `未知站点 · ${stop.virtualStopId.slice(0, 8)}`;
}

function stateClass(stop: TaskStop): string {
  if (["BOARDED", "ALIGHTED", "CANCELLED"].includes(stop.status)) {
    return "is-complete";
  }
  if (stop.status === "ARRIVED") {
    return "is-current";
  }
  return "is-upcoming";
}

function timeDescription(stop: TaskStop): string {
  if (["ARRIVED", "BOARDED", "ALIGHTED"].includes(stop.status)) {
    const actualTime = formatShanghaiDateTime(stop.actualArrivalAt, "time");
    if (stop.status === "BOARDED") {
      return `已到站 ${actualTime} · 已上车`;
    }
    if (stop.status === "ALIGHTED") {
      return `已到站 ${actualTime} · 已下车`;
    }
    return `已到站 ${actualTime}`;
  }

  const plannedTime = formatShanghaiDateTime(stop.plannedArrivalAt, "time");
  return stop.status === "PLANNED"
    ? `计划到站 ${plannedTime}`
    : `计划到站 ${plannedTime} · ${labelFor(stop.status)}`;
}
</script>

<template>
  <ul class="timeline" aria-label="站点步骤">
    <li
      v-for="stop in sortedStops"
      :key="stop.id"
      class="timeline-item"
      :class="stateClass(stop)"
    >
      <span class="timeline-index">{{ stop.sequenceNumber }}</span>
      <div class="timeline-body">
        <p class="timeline-title">
          <strong>{{ stopName(stop) }}</strong>
          <span>{{ stop.stopType === "BOARDING" ? "上车站" : "下车站" }}</span>
        </p>
        <p class="timeline-meta">{{ timeDescription(stop) }}</p>
      </div>
    </li>
    <li v-if="sortedStops.length === 0" class="timeline-item is-empty">
      <span class="timeline-index">0</span>
      <div class="timeline-body">
        <p class="timeline-title">暂无站点</p>
        <p class="timeline-meta">等待任务同步</p>
      </div>
    </li>
  </ul>
</template>

<style scoped>
.timeline {
  display: grid;
  gap: 0;
  list-style: none;
  margin: 0;
  padding: 0;
}

.timeline-item {
  display: grid;
  gap: 10px;
  grid-template-columns: 28px minmax(0, 1fr);
  min-height: 56px;
  padding: 0 0 10px;
  position: relative;
}

.timeline-item:not(:last-child)::before {
  background: #dce4e0;
  content: "";
  height: calc(100% - 24px);
  left: 13px;
  position: absolute;
  top: 26px;
  width: 2px;
}

.timeline-index {
  align-items: center;
  background: #f3f6f4;
  border: 2px solid #cbd7d1;
  border-radius: 50%;
  color: #53615a;
  display: inline-flex;
  font-size: 11px;
  font-weight: 800;
  height: 28px;
  justify-content: center;
  position: relative;
  width: 28px;
  z-index: 1;
}

.timeline-body {
  min-width: 0;
  padding-top: 2px;
}

.timeline-title,
.timeline-meta {
  margin: 0;
}

.timeline-title {
  align-items: baseline;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.timeline-title strong {
  color: #1f342b;
  font-size: 13px;
}

.timeline-title span,
.timeline-meta {
  color: #6a7972;
  font-size: 12px;
}

.timeline-meta {
  margin-top: 3px;
}

.is-complete .timeline-index {
  background: #e6f4eb;
  border-color: #4d9a70;
  color: #17643f;
}

.is-current .timeline-index {
  background: #e7f0f7;
  border-color: #3984aa;
  color: #235f7d;
  box-shadow: 0 0 0 4px rgb(57 132 170 / 12%);
}

.is-upcoming .timeline-index,
.is-empty .timeline-index {
  background: #f3f6f4;
}
</style>
