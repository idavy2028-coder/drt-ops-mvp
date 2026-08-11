<script setup lang="ts">
import type { RideOrder } from "../api/types";
import StatusBadge from "./StatusBadge.vue";

withDefaults(defineProps<{
  orders: RideOrder[];
  compact?: boolean;
}>(), { compact: false });
</script>

<template>
  <section class="work-panel" :class="{ compact }">
    <h3 class="section-title">实时订单</h3>
    <div v-if="compact" class="compact-list">
      <article v-for="order in orders" :key="order.id" class="compact-item">
        <strong>{{ order.id.slice(0, 8) }}</strong>
        <StatusBadge :code="order.status" />
        <span>{{ order.passengerCount }} 人</span>
      </article>
      <p v-if="orders.length === 0" class="compact-empty">暂无实时订单</p>
    </div>
    <table v-else class="data-table">
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

<style scoped>
.compact { min-width: 0; }
.compact-list { display: grid; gap: 6px; }
.compact-item { align-items: center; border-bottom: 1px solid #e5ebe7; display: grid; gap: 6px; grid-template-columns: minmax(58px, .8fr) minmax(76px, 1.15fr) auto; padding: 7px 0; }
.compact-item strong { color: #22382f; font-size: 12px; }
.compact-item span:last-child { color: #65766e; font-size: 11px; font-weight: 800; white-space: nowrap; }
.compact-empty { color: #73817a; font-size: 12px; margin: 10px 0; }
</style>
