<script setup lang="ts">
import { reactive, watch } from "vue";
import type { DispatchRuleSet } from "../api/types";
import type { UpdateDispatchRuleSetInput } from "../api/rules";

const props = defineProps<{
  ruleSet: DispatchRuleSet | null;
}>();

const emit = defineEmits<{
  save: [value: UpdateDispatchRuleSetInput];
}>();

const form = reactive<UpdateDispatchRuleSetInput>({
  maxWaitMinutes: 0,
  maxDetourMinutes: 0,
  bookingWindowMinutes: 0,
  autoDispatchScoreThreshold: 0,
  manualReviewScoreThreshold: 0,
  waitWeight: 0,
  detourWeight: 0,
  stabilityWeight: 0,
  utilizationWeight: 0,
  insertionPolicy: "REALTIME_INSERTION"
});

watch(
  () => props.ruleSet,
  (ruleSet) => {
    if (!ruleSet) {
      return;
    }
    form.maxWaitMinutes = ruleSet.maxWaitMinutes;
    form.maxDetourMinutes = ruleSet.maxDetourMinutes;
    form.bookingWindowMinutes = ruleSet.bookingWindowMinutes;
    form.autoDispatchScoreThreshold = ruleSet.autoDispatchScoreThreshold;
    form.manualReviewScoreThreshold = ruleSet.manualReviewScoreThreshold;
    form.waitWeight = ruleSet.waitWeight;
    form.detourWeight = ruleSet.detourWeight;
    form.stabilityWeight = ruleSet.stabilityWeight;
    form.utilizationWeight = ruleSet.utilizationWeight;
    form.insertionPolicy = ruleSet.insertionPolicy;
  },
  { immediate: true }
);

function submit() {
  emit("save", { ...form });
}
</script>

<template>
  <form class="work-panel" @submit.prevent="submit">
    <h3 class="section-title">调度规则</h3>
    <div class="form-grid">
      <label class="field">
        <span>最大等待时间</span>
        <input v-model.number="form.maxWaitMinutes" type="number" min="1" />
      </label>
      <label class="field">
        <span>最大绕行时间</span>
        <input v-model.number="form.maxDetourMinutes" type="number" min="0" />
      </label>
      <label class="field">
        <span>预约窗口</span>
        <input v-model.number="form.bookingWindowMinutes" type="number" min="1" />
      </label>
      <label class="field">
        <span>执行中插单策略</span>
        <select v-model="form.insertionPolicy">
          <option value="REALTIME_INSERTION">实时插单</option>
          <option value="NEW_TASK_ONLY">仅新任务</option>
        </select>
      </label>
      <label class="field">
        <span>自动派发阈值</span>
        <input v-model.number="form.autoDispatchScoreThreshold" type="number" min="0" max="100" step="0.01" />
      </label>
      <label class="field">
        <span>人工确认阈值</span>
        <input v-model.number="form.manualReviewScoreThreshold" type="number" min="0" max="100" step="0.01" />
      </label>
      <label class="field">
        <span>等待权重</span>
        <input v-model.number="form.waitWeight" type="number" min="0" step="0.01" />
      </label>
      <label class="field">
        <span>绕行权重</span>
        <input v-model.number="form.detourWeight" type="number" min="0" step="0.01" />
      </label>
      <label class="field">
        <span>稳定性权重</span>
        <input v-model.number="form.stabilityWeight" type="number" min="0" step="0.01" />
      </label>
      <label class="field">
        <span>利用率权重</span>
        <input v-model.number="form.utilizationWeight" type="number" min="0" step="0.01" />
      </label>
    </div>
    <div class="toolbar">
      <button class="primary-button" type="submit" :disabled="!ruleSet">保存规则</button>
    </div>
  </form>
</template>
