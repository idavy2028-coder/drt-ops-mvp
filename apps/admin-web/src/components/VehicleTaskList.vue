<script setup lang="ts">
import type { VehicleTask } from "../api/types";
import StatusBadge from "./StatusBadge.vue";

const props = defineProps<{
  tasks: VehicleTask[];
  selectedTaskId?: string;
  compact?: boolean;
}>();
const emit = defineEmits<{ select: [taskId: string] }>();
</script>

<template>
  <section class="work-panel" :class="{ compact: props.compact }">
    <h3 class="section-title">车辆任务</h3>
    <div v-if="props.compact" class="compact-list">
      <article v-for="task in tasks" :key="task.id" class="compact-item" :class="{ selected: task.id === props.selectedTaskId }">
        <div><strong>{{ task.id.slice(0, 8) }}</strong><small>车辆 {{ task.vehicleId.slice(0, 8) }}</small></div>
        <StatusBadge :code="task.status" />
        <button type="button" class="compact-action" :aria-pressed="task.id === props.selectedTaskId" @click="emit('select', task.id)">查看地图</button>
      </article>
      <p v-if="tasks.length === 0" class="compact-empty">暂无车辆任务</p>
    </div>
    <table v-else class="data-table">
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
.compact { min-width: 0; }
.compact-list { display: grid; gap: 6px; }
.compact-item { align-items: center; border: 1px solid #e2e9e5; border-radius: 5px; display: grid; gap: 7px; grid-template-columns: minmax(64px, 1fr) auto; padding: 8px; }
.compact-item.selected { border-color: #4c9278; box-shadow: inset 3px 0 0 #177052; }
.compact-item strong, .compact-item small { display: block; }
.compact-item strong { color: #243a31; font-size: 12px; }
.compact-item small { color: #718078; font-size: 10px; margin-top: 2px; }
.compact-action { background: transparent; border: 0; color: #0b765a; cursor: pointer; font-size: 11px; font-weight: 900; grid-column: 1 / -1; justify-self: start; padding: 0; }
.compact-empty { color: #73817a; font-size: 12px; margin: 10px 0; }
</style>
