<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { listDispatchRuleSets, updateDispatchRuleSet, type UpdateDispatchRuleSetInput } from "../api/rules";
import type { DispatchRuleSet } from "../api/types";
import RuleSetForm from "../components/RuleSetForm.vue";

const ruleSets = ref<DispatchRuleSet[]>([]);
const selectedRuleSetId = ref("");
const status = ref("");

const selectedRuleSet = computed(() => {
  return ruleSets.value.find((ruleSet) => ruleSet.id === selectedRuleSetId.value) ?? ruleSets.value[0] ?? null;
});

async function loadRules() {
  status.value = "";
  try {
    ruleSets.value = await listDispatchRuleSets();
    selectedRuleSetId.value = selectedRuleSet.value?.id ?? "";
  } catch (error) {
    status.value = error instanceof Error ? error.message : "规则数据加载失败";
  }
}

async function saveRuleSet(input: UpdateDispatchRuleSetInput) {
  if (!selectedRuleSet.value) {
    return;
  }
  const saved = await updateDispatchRuleSet(selectedRuleSet.value.id, input);
  ruleSets.value = ruleSets.value.map((ruleSet) => (ruleSet.id === saved.id ? saved : ruleSet));
  status.value = "规则已保存";
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
      <select v-model="selectedRuleSetId" class="secondary-button">
        <option v-for="ruleSet in ruleSets" :key="ruleSet.id" :value="ruleSet.id">
          {{ ruleSet.name }}
        </option>
      </select>
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

      <RuleSetForm :rule-set="selectedRuleSet" @save="saveRuleSet" />
    </div>

    <p v-if="status" class="section-copy">状态：{{ status }}</p>
  </section>
</template>
