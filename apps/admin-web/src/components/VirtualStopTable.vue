<script setup lang="ts">
import type { VirtualStop } from "../api/types";

defineProps<{
  stops: VirtualStop[];
}>();
</script>

<template>
  <section class="table-panel">
    <table class="data-table">
      <thead>
        <tr>
          <th>虚拟站点</th>
          <th>服务半径</th>
          <th>上车</th>
          <th>下车</th>
          <th>安全备注</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="stop in stops" :key="stop.id">
          <td>{{ stop.name }}</td>
          <td>{{ stop.serviceRadiusMeters }} 米</td>
          <td>{{ stop.boardingEnabled ? "启用" : "关闭" }}</td>
          <td>{{ stop.alightingEnabled ? "启用" : "关闭" }}</td>
          <td>{{ stop.safetyNote }}</td>
        </tr>
        <tr v-if="stops.length === 0">
          <td colspan="5">暂无虚拟站点</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
