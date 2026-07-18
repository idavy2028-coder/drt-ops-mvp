<script setup lang="ts">
import type { VehicleTask } from "../api/types";
import StatusBadge from "./StatusBadge.vue";

const props = defineProps<{
  tasks: VehicleTask[];
  selectedTaskId?: string;
}>();
const emit = defineEmits<{ select: [taskId: string] }>();
</script>

<template>
  <section class="work-panel">
    <h3 class="section-title">车辆任务</h3>
    <table class="data-table">
      <thead>
        <tr>
          <th>任务</th>
          <th>车辆</th>
          <th>状态</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="task in tasks" :key="task.id" :class="{ selected: task.id === props.selectedTaskId }">
          <td>{{ task.id.slice(0, 8) }}</td>
          <td>{{ task.vehicleId.slice(0, 8) }}</td>
          <td><StatusBadge :code="task.status" /></td>
          <td><button type="button" class="secondary-button" :aria-pressed="task.id === props.selectedTaskId" @click="emit('select', task.id)">查看地图</button></td>
        </tr>
        <tr v-if="tasks.length === 0">
          <td colspan="4">暂无车辆任务</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.selected { background: #e2f3ec; }
</style>
