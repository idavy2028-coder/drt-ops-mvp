<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { listAuditLogs } from "../api/metrics";
import type { AuditLog } from "../api/types";

const logs = ref<AuditLog[]>([]);
const entityTypeFilter = ref("");
const actionFilter = ref("");
const dateFilter = ref("");
const status = ref("");

const filteredLogs = computed(() => {
  return logs.value.filter((log) => {
    const entityMatches = entityTypeFilter.value === "" || log.entityType === entityTypeFilter.value;
    const actionMatches = actionFilter.value === "" || log.action.includes(actionFilter.value);
    const dateMatches = dateFilter.value === "" || log.createdAt.startsWith(dateFilter.value);
    return entityMatches && actionMatches && dateMatches;
  });
});

async function loadLogs() {
  status.value = "";
  try {
    logs.value = await listAuditLogs();
  } catch (error) {
    status.value = error instanceof Error ? error.message : "审计日志加载失败";
  }
}

onMounted(() => {
  void loadLogs();
});
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="page-kicker">AUDIT</p>
        <h2 class="page-title">审计日志</h2>
        <p class="page-subtitle">保留调度决策、人工确认、任务执行和异常处理的关键操作记录。</p>
      </div>
      <button class="secondary-button" type="button" @click="loadLogs">刷新</button>
    </header>

    <section class="work-panel">
      <div class="form-grid">
        <label class="field">
          <span>实体类型</span>
          <input v-model="entityTypeFilter" placeholder="RIDE_ORDER" />
        </label>
        <label class="field">
          <span>操作</span>
          <input v-model="actionFilter" placeholder="DISPATCH" />
        </label>
        <label class="field">
          <span>日期</span>
          <input v-model="dateFilter" type="date" />
        </label>
      </div>
    </section>

    <p v-if="status" class="section-copy">状态：{{ status }}</p>

    <section class="table-panel">
      <table class="data-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>对象</th>
            <th>动作</th>
            <th>操作者</th>
            <th>原因</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="log in filteredLogs" :key="log.id">
            <td>{{ log.createdAt }}</td>
            <td>{{ log.entityType }} · {{ log.entityId.slice(0, 8) }}</td>
            <td>{{ log.action }}</td>
            <td>{{ log.actorType }} / {{ log.actorId }}</td>
            <td>{{ log.reason ?? "--" }}</td>
          </tr>
          <tr v-if="filteredLogs.length === 0">
            <td colspan="5">暂无审计日志</td>
          </tr>
        </tbody>
      </table>
    </section>
  </section>
</template>
