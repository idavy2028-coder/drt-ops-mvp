<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { listDispatchRuleSets, updateDispatchRuleSet, type UpdateDispatchRuleSetInput } from "../api/rules";
import type { DispatchRuleSet } from "../api/types";
import RuleSetForm from "../components/RuleSetForm.vue";
import { userMessage } from "../api/errors";
import { feedbackStore } from "../stores/feedbackStore";

const ruleSets = ref<DispatchRuleSet[]>([]);
const selectedRuleSetId = ref("");
const status = ref("");
const loading = ref(false);
const saving = ref(false);

const selectedRuleSet = computed(() => {
  return ruleSets.value.find((ruleSet) => ruleSet.id === selectedRuleSetId.value) ?? ruleSets.value[0] ?? null;
});

async function loadRules() {
  status.value = "";
  loading.value = true;
  try {
    ruleSets.value = await listDispatchRuleSets();
    selectedRuleSetId.value = selectedRuleSet.value?.id ?? "";
  } catch (error) {
    status.value = userMessage(error, "规则数据加载失败");
  } finally {
    loading.value = false;
  }
}

async function saveRuleSet(input: UpdateDispatchRuleSetInput) {
  if (!selectedRuleSet.value) {
    return;
  }
  saving.value = true;
  try {
    const saved = await updateDispatchRuleSet(selectedRuleSet.value.id, input);
    ruleSets.value = ruleSets.value.map((ruleSet) => (ruleSet.id === saved.id ? saved : ruleSet));
    status.value = "规则已保存";
    feedbackStore.success("调度规则已保存并生效");
  } catch (error) {
    status.value = userMessage(error, "规则保存失败");
    feedbackStore.error(status.value);
  } finally {
    saving.value = false;
  }
}

onMounted(() => {
  void loadRules();
});
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="page-kicker">RULES</p>
        <h2 class="page-title">规则配置</h2>
        <p class="page-subtitle">管理等待、绕行、预约窗口、插单策略、派发阈值和评分权重。</p>
      </div>
      <div class="toolbar">
        <select v-model="selectedRuleSetId" class="secondary-button" :disabled="loading">
        <option v-for="ruleSet in ruleSets" :key="ruleSet.id" :value="ruleSet.id">
          {{ ruleSet.name }}
        </option>
        </select>
        <button class="secondary-button" type="button" :disabled="loading" @click="loadRules">{{ loading ? "同步中" : "刷新" }}</button>
      </div>
    </header>

    <div class="split-grid">
      <section class="table-panel">
        <table class="data-table">
          <thead>
            <tr>
              <th>规则组</th>
              <th>最大等待</th>
              <th>最大绕行</th>
              <th>自动阈值</th>
              <th>人工阈值</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ruleSet in ruleSets" :key="ruleSet.id">
              <td>{{ ruleSet.name }}</td>
              <td>{{ ruleSet.maxWaitMinutes }} 分钟</td>
              <td>{{ ruleSet.maxDetourMinutes }} 分钟</td>
              <td>{{ ruleSet.autoDispatchScoreThreshold }}</td>
              <td>{{ ruleSet.manualReviewScoreThreshold }}</td>
            </tr>
            <tr v-if="ruleSets.length === 0">
              <td colspan="5">暂无规则组</td>
            </tr>
          </tbody>
        </table>
      </section>

      <RuleSetForm :rule-set="selectedRuleSet" :saving="saving" @save="saveRuleSet" />
    </div>

    <p v-if="loading" class="page-state">正在同步调度规则…</p>
    <p v-else-if="status" class="page-state">{{ status }}</p>
  </section>
</template>
