<script setup lang="ts">
import type { Driver } from "../api/types";
import StatusBadge from "./StatusBadge.vue";

defineProps<{
  drivers: Driver[];
}>();
</script>

<template>
  <section class="table-panel">
    <table class="data-table">
      <thead>
        <tr>
          <th>驾驶员</th>
          <th>资格</th>
          <th>状态</th>
          <th>车队</th>
          <th>电话</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="driver in drivers" :key="driver.id">
          <td>{{ driver.name }}</td>
          <td>{{ driver.qualificationStatus }}</td>
          <td><StatusBadge :code="driver.currentStatus" /></td>
          <td>{{ driver.fleetName }}</td>
          <td>{{ driver.phone }}</td>
        </tr>
        <tr v-if="drivers.length === 0">
          <td colspan="5">暂无驾驶员</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
