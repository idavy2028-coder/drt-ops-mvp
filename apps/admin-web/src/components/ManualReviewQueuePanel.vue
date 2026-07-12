<script setup lang="ts">
import { reactive, ref } from "vue";
import type { ManualReviewQueueItem, UUID } from "../api/types";

const props = defineProps<{
  items: ManualReviewQueueItem[];
  processingDecisionId?: UUID;
  error?: string;
}>();

const emit = defineEmits<{
  approve: [decisionId: UUID];
  reject: [payload: { decisionId: UUID; reason: string }];
}>();

const activeRejectDecisionId = ref<UUID>();
const rejectReasons = reactive<Record<string, string>>({});
const validationErrors = reactive<Record<string, string>>({});

function showReject(decisionId: UUID) {
  activeRejectDecisionId.value = decisionId;
  validationErrors[decisionId] = "";
}

function submitReject(decisionId: UUID) {
  const reason = rejectReasons[decisionId]?.trim() ?? "";
  if (!reason) {
    validationErrors[decisionId] = "请填写拒绝原因";
    return;
  }
  validationErrors[decisionId] = "";
  emit("reject", { decisionId, reason });
}

function isProcessing(decisionId: UUID) {
  return props.processingDecisionId === decisionId;
}
</script>

<template>
  <section class="work-panel manual-review-panel">
    <h3 class="section-title">人工复核队列</h3>
    <p v-if="error" class="panel-error">{{ error }}</p>
    <p v-if="items.length === 0" class="empty-state">暂无待复核订单</p>

    <ul v-else class="review-list" aria-label="人工复核队列">
      <li v-for="item in items" :key="item.decisionId" class="review-item">
        <div class="review-main">
          <div>
            <p class="review-title">{{ item.passengerName }}</p>
            <p class="review-meta">{{ item.passengerCount }} 人 · {{ item.requestedDepartureAt }}</p>
          </div>
          <span class="decision-id">决策 {{ item.decisionId }}</span>
        </div>

        <div class="review-metrics">
          <span>{{ item.bestVehicleId ? `候选车辆 ${item.bestVehicleId}` : "暂无候选车辆" }}</span>
          <span>候选方案 {{ item.candidateCount }} 个</span>
        </div>

        <div class="review-actions">
          <button
            class="primary-button"
            type="button"
            :disabled="isProcessing(item.decisionId)"
            @click="emit('approve', item.decisionId)"
          >
            确认派单
          </button>
          <button
            class="secondary-button"
            type="button"
            :disabled="isProcessing(item.decisionId)"
            @click="showReject(item.decisionId)"
          >
            拒绝
          </button>
        </div>

        <div v-if="activeRejectDecisionId === item.decisionId" class="reject-box">
          <label>
            拒绝原因
            <textarea v-model="rejectReasons[item.decisionId]" rows="3" />
          </label>
          <p v-if="validationErrors[item.decisionId]" class="validation-error">
            {{ validationErrors[item.decisionId] }}
          </p>
          <button
            class="danger-button"
            type="button"
            :disabled="isProcessing(item.decisionId)"
            @click="submitReject(item.decisionId)"
          >
            确认拒绝
          </button>
        </div>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.manual-review-panel {
  min-width: 0;
}

.panel-error,
.validation-error {
  color: #b42318;
  font-size: 0.9rem;
}

.empty-state {
  color: #64748b;
  margin: 0;
}

.review-list {
  display: grid;
  gap: 0.9rem;
  list-style: none;
  margin: 0;
  padding: 0;
}

.review-item {
  border: 1px solid #d8dee8;
  border-radius: 8px;
  display: grid;
  gap: 0.75rem;
  padding: 0.9rem;
}

.review-main,
.review-actions,
.review-metrics {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 0.65rem;
  justify-content: space-between;
}

.review-title {
  font-weight: 700;
  margin: 0 0 0.2rem;
}

.review-meta,
.decision-id,
.review-metrics {
  color: #64748b;
  font-size: 0.88rem;
}

.decision-id {
  overflow-wrap: anywhere;
}

.reject-box {
  display: grid;
  gap: 0.55rem;
}

.reject-box label {
  color: #334155;
  display: grid;
  font-size: 0.9rem;
  gap: 0.35rem;
}

.reject-box textarea {
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  font: inherit;
  padding: 0.6rem;
  resize: vertical;
}
</style>
