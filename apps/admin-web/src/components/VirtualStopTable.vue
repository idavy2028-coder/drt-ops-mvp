<script setup lang="ts">
import type { VirtualStop } from "../api/types";

defineProps<{ stops: VirtualStop[]; canManage: boolean }>();
const emit = defineEmits<{ edit: [stop: VirtualStop] }>();
</script>

<template>
  <section class="table-panel" aria-labelledby="virtual-stop-table-title">
    <header class="table-header"><div><p class="section-kicker">STOPS</p><h3 id="virtual-stop-table-title">虚拟站点列表</h3></div><span>{{ stops.length }} 条</span></header>
    <div class="table-scroll">
      <table class="data-table">
        <thead>
          <tr><th>虚拟站点</th><th>地址 / 坐标</th><th>服务范围</th><th>上下车</th><th>状态</th><th>来源</th><th v-if="canManage">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="stop in stops" :key="stop.id">
            <td><strong>{{ stop.name }}</strong><small>{{ stop.safetyNote || "无安全备注" }}</small></td>
            <td>{{ stop.address || "未录入地址" }}<small>{{ stop.longitude?.toFixed(6) ?? "--" }}, {{ stop.latitude?.toFixed(6) ?? "--" }}</small></td>
            <td>{{ stop.serviceRadiusMeters }} 米</td>
            <td>{{ stop.boardingEnabled ? "可上车" : "不可上车" }} / {{ stop.alightingEnabled ? "可下车" : "不可下车" }}</td>
            <td><span class="status-pill" :class="stop.enabled ? 'enabled' : 'disabled'">{{ stop.enabled ? "已启用" : "未启用" }}</span></td>
            <td>{{ stop.source === "CSV_IMPORT" ? "批量导入" : stop.source === "MANUAL" ? "人工维护" : "历史数据" }}</td>
            <td v-if="canManage"><button type="button" class="secondary-button" @click="emit('edit', stop)">编辑</button></td>
          </tr>
          <tr v-if="stops.length === 0"><td :colspan="canManage ? 7 : 6">当前条件下没有虚拟站点</td></tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.table-header { align-items: center; display: flex; justify-content: space-between; margin-bottom: 12px; }
.section-kicker { color: var(--accent); font-size: 12px; font-weight: 800; margin: 0 0 4px; }
h3 { font-size: 20px; margin: 0; }
.table-header span { color: var(--ink-muted); font-weight: 700; }
.table-scroll { overflow-x: auto; }
small { color: var(--ink-muted); display: block; font-size: 12px; margin-top: 3px; }
.status-pill { border-radius: 999px; display: inline-block; font-size: 12px; font-weight: 800; padding: 4px 8px; white-space: nowrap; }
.enabled { background: #dff2e9; color: #006a4e; }.disabled { background: #fde3e3; color: #9a2424; }
</style>
