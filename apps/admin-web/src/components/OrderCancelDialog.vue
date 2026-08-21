<script setup lang="ts">
import { ref } from "vue";
import type { RideOrder } from "../api/types";
import StatusBadge from "./StatusBadge.vue";

const props = defineProps<{
  order: RideOrder;
  submitting: boolean;
  submitError: string;
}>();

const emit = defineEmits<{
  close: [];
  confirm: [value: { reason: string }];
}>();

const reason = ref("");
const validationError = ref("");

function close(): void {
  if (!props.submitting) {
    emit("close");
  }
}

function confirm(): void {
  if (props.submitting) {
    return;
  }

  const normalizedReason = reason.value.trim();
  if (normalizedReason.length < 2 || normalizedReason.length > 100) {
    validationError.value = "请输入 2–100 字取消原因";
    return;
  }

  validationError.value = "";
  emit("confirm", { reason: normalizedReason });
}
</script>

<template>
  <div
    class="dialog-backdrop"
    @click.self="close"
    @keydown.esc="close"
  >
    <section
      class="work-panel cancel-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="cancel-order-title"
    >
      <header class="cancel-header">
        <div>
          <p class="section-kicker">CONFIRM ACTION</p>
          <h3 id="cancel-order-title" class="section-title">取消订单</h3>
        </div>
        <StatusBadge :code="order.status" />
      </header>

      <div class="order-summary">
        <strong>{{ order.passengerName }} · {{ order.passengerCount }} 人</strong>
        <span>{{ order.originAddress || "上车点未填写" }} → {{ order.destinationAddress || "下车点未填写" }}</span>
      </div>

      <p class="warning-copy">确认后订单将进入已取消状态，请填写可审计的业务原因。</p>

      <label class="field">
        <span>取消原因</span>
        <textarea
          v-model="reason"
          rows="4"
          maxlength="100"
          :disabled="submitting"
          :aria-invalid="validationError ? 'true' : undefined"
          aria-describedby="cancel-reason-help"
        />
      </label>
      <p id="cancel-reason-help" class="field-help">2–100 字，将随本次操作记录。</p>
      <p v-if="validationError" class="dialog-error" role="alert">{{ validationError }}</p>
      <p v-if="submitError" class="dialog-error" role="alert">{{ submitError }}</p>

      <div class="toolbar dialog-actions">
        <button class="danger-button" type="button" :disabled="submitting" @click="confirm">
          {{ submitting ? "正在取消" : "确认取消" }}
        </button>
        <button class="secondary-button" type="button" :disabled="submitting" @click="close">返回</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.dialog-backdrop { background: rgb(6 24 20 / 58%); display: grid; inset: 0; padding: 24px; place-items: center; position: fixed; z-index: 1000; }
.cancel-dialog { display: grid; gap: 14px; padding: 16px; width: min(560px, 100%); }
.cancel-header { align-items: center; display: flex; justify-content: space-between; }
.cancel-header .section-title { margin: 0; }
.section-kicker { color: var(--ink-muted); font-size: 11px; font-weight: 900; margin: 0 0 4px; }
.order-summary { background: var(--surface-muted); border: 1px solid var(--line); border-radius: 8px; display: grid; gap: 5px; padding: 12px; }
.order-summary strong { font-size: 14px; }
.order-summary span { color: var(--ink-muted); font-size: 13px; }
.warning-copy { color: #8b3b22; font-size: 13px; font-weight: 800; margin: 0; }
.field textarea { background: #ffffff; border: 1px solid #cfd8d3; border-radius: 8px; color: var(--ink); padding: 9px 10px; resize: vertical; }
.field-help { color: var(--ink-muted); font-size: 12px; margin: -8px 0 0; }
.dialog-error { background: #fff0ee; border-left: 3px solid var(--danger); color: #9f2e28; font-size: 13px; font-weight: 800; margin: 0; padding: 8px 10px; }
.dialog-actions { justify-content: flex-end; }
</style>
