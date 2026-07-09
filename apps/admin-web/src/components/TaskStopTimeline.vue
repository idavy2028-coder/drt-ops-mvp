<script setup lang="ts">
import type { TaskStop } from "../api/types";

defineProps<{
  stops: TaskStop[];
}>();
</script>

<template>
  <ul class="timeline" aria-label="站点时间线">
    <li v-for="stop in stops" :key="stop.id" class="timeline-item">
      <span class="timeline-index">{{ stop.sequenceNumber }}</span>
      <div class="timeline-body">
        <p class="timeline-title">{{ stop.stopType }} · {{ stop.status }}</p>
        <p class="timeline-meta">计划到站 {{ stop.plannedArrivalAt }}</p>
      </div>
    </li>
    <li v-if="stops.length === 0" class="timeline-item">
      <span class="timeline-index">0</span>
      <div class="timeline-body">
        <p class="timeline-title">暂无站点</p>
        <p class="timeline-meta">等待任务同步</p>
      </div>
    </li>
  </ul>
</template>
