<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { listAuditLogs } from "../api/metrics";
import type { AuditLog } from "../api/types";
import { displayDateTime, labelFor, shortId } from "../presentation/operations";
import { userMessage } from "../api/errors";

const logs = ref<AuditLog[]>([]);
const entityTypeFilter = ref("");
const actionFilter = ref("");
const dateFilter = ref("");
const status = ref("");
const loading = ref(false);

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
  loading.value = true;
  try {
    logs.value = await listAuditLogs();
  } catch (error) {
    status.value = userMessage(error, "审计日志加载失败");
  } finally {
    loading.value = false;
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
      <button class="secondary-button" type="button" :disabled="loading" @click="loadLogs">{{ loading ? "同步中" : "刷新" }}</button>
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

    <p v-if="loading" class="page-state">正在读取审计记录…</p>
    <p v-else-if="status" class="page-state">{{ status }}</p>

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
            <td>{{ displayDateTime(log.createdAt) }}</td>
            <td :title="log.entityId">{{ labelFor(log.entityType) }} · {{ shortId(log.entityId) }}</td>
            <td :title="log.action">{{ labelFor(log.action) }}</td>
            <td :title="log.actorId">{{ labelFor(log.actorType) }} · {{ shortId(log.actorId) }}</td>
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
