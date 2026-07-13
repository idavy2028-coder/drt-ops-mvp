<script setup lang="ts">
import type { RideOrder } from "../api/types";
import StatusBadge from "./StatusBadge.vue";

defineProps<{
  orders: RideOrder[];
}>();
</script>

<template>
  <section class="work-panel">
    <h3 class="section-title">实时订单</h3>
    <table class="data-table">
      <thead>
        <tr>
          <th>订单</th>
          <th>状态</th>
          <th>人数</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="order in orders" :key="order.id">
          <td>{{ order.id.slice(0, 8) }}</td>
          <td><StatusBadge :code="order.status" /></td>
          <td>{{ order.passengerCount }}</td>
        </tr>
        <tr v-if="orders.length === 0">
          <td colspan="3">暂无实时订单</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
