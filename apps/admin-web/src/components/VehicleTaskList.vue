<script setup lang="ts">
import type { VehicleTask } from "../api/types";
import StatusBadge from "./StatusBadge.vue";

defineProps<{
  tasks: VehicleTask[];
}>();
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
        </tr>
      </thead>
      <tbody>
        <tr v-for="task in tasks" :key="task.id">
          <td>{{ task.id.slice(0, 8) }}</td>
          <td>{{ task.vehicleId.slice(0, 8) }}</td>
          <td><StatusBadge :code="task.status" /></td>
        </tr>
        <tr v-if="tasks.length === 0">
          <td colspan="3">暂无车辆任务</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
