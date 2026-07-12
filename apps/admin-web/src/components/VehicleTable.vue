<script setup lang="ts">
import type { Vehicle } from "../api/types";

defineProps<{
  vehicles: Vehicle[];
}>();
</script>

<template>
  <section class="table-panel">
    <table class="data-table">
      <thead>
        <tr>
          <th>车牌</th>
          <th>车型</th>
          <th>容量</th>
          <th>状态</th>
          <th>车队</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="vehicle in vehicles" :key="vehicle.id">
          <td>{{ vehicle.plateNumber }}</td>
          <td>{{ vehicle.vehicleType }}</td>
          <td>{{ vehicle.capacity }}</td>
          <td><span class="status-pill">{{ vehicle.currentStatus }}</span></td>
          <td>{{ vehicle.fleetName }}</td>
        </tr>
        <tr v-if="vehicles.length === 0">
          <td colspan="5">暂无车辆</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
