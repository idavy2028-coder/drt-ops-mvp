<script setup lang="ts">
import { ref } from "vue";
import type { RideOrder, UUID } from "../api/types";

const props = defineProps<{
  order: RideOrder;
  submitting?: boolean;
}>();

const emit = defineEmits<{
  close: [];
  confirm: [value: { reason: string; idempotencyKey: UUID }];
}>();

const reasonCode = ref("");

function confirm(): void {
  if (reasonCode.value !== "WAITING_PERIOD_EXPIRED" || props.submitting) {
    return;
  }
  emit("confirm", {
    reason: "乘客在等待期内未出现",
    idempotencyKey: crypto.randomUUID()
  });
}

function formatDateTime(value?: string): string {
  if (!value) {
    return "--";
  }
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  });
}
</script>

<template>
  <div class="dialog-backdrop">
    <section class="work-panel no-show-dialog" role="dialog" aria-modal="true" aria-labelledby="no-show-title">
      <header>
        <p class="section-kicker">HIGH-RISK ACTION</p>
        <h3 id="no-show-title" class="section-title">确认乘客未到</h3>
      </header>
      <p>订单 {{ order.id.slice(0, 8) }}</p>
      <dl class="detail-grid">
        <div><dt>计划上车</dt><dd>{{ formatDateTime(order.estimatedBoardingAt) }}</dd></div>
        <div><dt>最早处理</dt><dd>{{ formatDateTime(order.noShowEligibleAt) }}</dd></div>
        <div><dt>已有效等待</dt><dd>{{ Math.floor(order.noShowWaitedSeconds / 60) }} 分 {{ order.noShowWaitedSeconds % 60 }} 秒</dd></div>
      </dl>
      <p class="danger-copy">该操作将异常关闭订单，并可能取消车辆任务、释放车辆和驾驶员。</p>
      <label class="field">
        <span>爽约原因</span>
        <select v-model="reasonCode">
          <option value="">请选择</option>
          <option value="WAITING_PERIOD_EXPIRED">乘客在等待期内未出现</option>
        </select>
      </label>
      <div class="toolbar">
        <button
          class="danger-button"
          type="button"
          :disabled="submitting || !reasonCode"
          @click="confirm"
        >
          {{ submitting ? "正在关闭" : "确认乘客未到并关闭订单" }}
        </button>
        <button class="secondary-button" type="button" :disabled="submitting" @click="emit('close')">返回</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgb(6 24 20 / 58%);
}
.no-show-dialog { width: min(620px, 100%); display: grid; gap: 16px; }
.no-show-dialog p, .no-show-dialog dd { margin: 0; }
.detail-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin: 0; }
.detail-grid div { padding: 12px; border: 1px solid var(--line); border-radius: 10px; }
.detail-grid dt { color: var(--ink-muted); font-size: 12px; font-weight: 700; }
.detail-grid dd { margin-top: 6px; font-weight: 700; }
.danger-copy { color: #8b1f16; font-weight: 700; }
@media (max-width: 700px) { .detail-grid { grid-template-columns: 1fr; } }
</style>
