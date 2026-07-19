<script setup lang="ts">
import { reactive } from "vue";
import type { CreateDispatchRuleSetInput } from "../api/rules";

const props = defineProps<{
  saving: boolean;
  showCancel?: boolean;
}>();

const emit = defineEmits<{
  create: [value: CreateDispatchRuleSetInput];
  cancel: [];
}>();

const form = reactive<CreateDispatchRuleSetInput>({
  name: "通渭县试点动态调度规则",
  maxWaitMinutes: 5,
  maxDetourMinutes: 8,
  bookingWindowMinutes: 60,
  autoDispatchScoreThreshold: 82,
  manualReviewScoreThreshold: 62,
  waitWeight: 0.35,
  detourWeight: 0.20,
  stabilityWeight: 0.30,
  utilizationWeight: 0.15,
  insertionPolicy: "REALTIME_INSERTION"
});

function submit(): void {
  emit("create", { ...form, name: form.name.trim() });
}
</script>

<template>
  <form class="work-panel" @submit.prevent="submit">
    <h3 class="section-title">新建调度规则组</h3>
    <p class="form-hint">已带入通渭县试点初始参数，创建后仍可继续调整。</p>
    <div class="form-grid">
      <label class="field name-field">
        <span>规则组名称</span>
        <input v-model="form.name" :disabled="saving" required />
      </label>
      <label class="field">
        <span>最大等待时间</span>
        <input v-model.number="form.maxWaitMinutes" :disabled="saving" type="number" min="1" required />
      </label>
      <label class="field">
        <span>最大绕行时间</span>
        <input v-model.number="form.maxDetourMinutes" :disabled="saving" type="number" min="0" required />
      </label>
      <label class="field">
        <span>预约窗口</span>
        <input v-model.number="form.bookingWindowMinutes" :disabled="saving" type="number" min="1" required />
      </label>
      <label class="field">
        <span>执行中插单策略</span>
        <select v-model="form.insertionPolicy" :disabled="saving">
          <option value="REALTIME_INSERTION">实时插单</option>
          <option value="NEW_TASK_ONLY">仅新任务</option>
        </select>
      </label>
      <label class="field">
        <span>自动派发阈值</span>
        <input v-model.number="form.autoDispatchScoreThreshold" :disabled="saving" type="number" min="0" max="100" step="0.01" required />
      </label>
      <label class="field">
        <span>人工确认阈值</span>
        <input v-model.number="form.manualReviewScoreThreshold" :disabled="saving" type="number" min="0" max="100" step="0.01" required />
      </label>
      <label class="field">
        <span>等待权重</span>
        <input v-model.number="form.waitWeight" :disabled="saving" type="number" min="0" step="0.01" required />
      </label>
      <label class="field">
        <span>绕行权重</span>
        <input v-model.number="form.detourWeight" :disabled="saving" type="number" min="0" step="0.01" required />
      </label>
      <label class="field">
        <span>稳定性权重</span>
        <input v-model.number="form.stabilityWeight" :disabled="saving" type="number" min="0" step="0.01" required />
      </label>
      <label class="field">
        <span>利用率权重</span>
        <input v-model.number="form.utilizationWeight" :disabled="saving" type="number" min="0" step="0.01" required />
      </label>
    </div>
    <div class="toolbar">
      <button class="primary-button" type="submit" :disabled="saving || !form.name.trim()">
        {{ saving ? "正在创建" : "创建规则组" }}
      </button>
      <button v-if="props.showCancel" class="secondary-button" type="button" :disabled="saving" @click="emit('cancel')">取消</button>
    </div>
  </form>
</template>

<style scoped>
.form-hint { color: var(--ink-muted); margin: 0 0 16px; }.name-field { grid-column: span 2; }
@media (max-width: 640px) { .name-field { grid-column: auto; } }
</style>
