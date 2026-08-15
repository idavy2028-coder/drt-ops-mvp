<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { VehicleAlarmAction, VehicleAlarmView } from "../api/vehicleAlarms";

const props = withDefaults(defineProps<{
  visible?: boolean;
  alarm?: VehicleAlarmView;
  action?: VehicleAlarmAction;
}>(), { visible: false, alarm: undefined, action: undefined });

const emit = defineEmits<{
  close: [];
  confirm: [payload: { publicId: string; action: VehicleAlarmAction; expectedVersion: number; reason: string; confirmed: true }];
}>();

const reason = ref("");
const confirmed = ref(false);
const canConfirm = computed(() => reason.value.trim().length > 0 && confirmed.value && props.alarm !== undefined && props.action !== undefined);

watch(() => props.visible, (visible) => {
  if (visible) { reason.value = ""; confirmed.value = false; }
});

function submit(): void {
  if (!canConfirm.value || !props.alarm || !props.action) return;
  emit("confirm", {
    publicId: props.alarm.publicId,
    action: props.action,
    expectedVersion: props.alarm.version,
    reason: reason.value.trim(),
    confirmed: true
  });
}

function actionLabel(action?: VehicleAlarmAction): string {
  return ({ ACKNOWLEDGE: "确认报警", TAKE_OVER: "接手处理", RESOLVE: "处理完成", MARK_FALSE_POSITIVE: "标记误报", REOPEN: "重新打开" } as Record<string, string>)[action ?? ""] ?? "处理报警";
}
</script>

<template>
  <div v-if="visible" class="alarm-action-backdrop" role="presentation">
    <section class="alarm-action-dialog" role="dialog" aria-modal="true" aria-label="确认报警处理">
      <header><p>SAFETY ACTION</p><h3>{{ actionLabel(action) }}</h3></header>
      <p>此操作会更新报警状态。请记录核实依据，原因同时作为处理备注保留。</p>
      <label>处理原因（同时作为备注）<textarea v-model="reason" rows="3" maxlength="300" /></label>
      <label class="confirmation"><input v-model="confirmed" type="checkbox" />我已核实并确认执行该处理</label>
      <footer><button type="button" class="secondary-button" @click="emit('close')">取消</button><button type="button" class="danger-button" :disabled="!canConfirm" @click="submit">确认执行</button></footer>
    </section>
  </div>
</template>

<style scoped>
.alarm-action-backdrop { align-items: center; background: #0c1813a8; display: grid; inset: 0; justify-items: center; padding: 18px; position: fixed; z-index: 1500; }
.alarm-action-dialog { background: #fff; border-top: 4px solid #c84b35; box-shadow: 0 22px 65px #07100b75; max-width: 460px; padding: 20px; width: min(100%, 460px); }
.alarm-action-dialog header p { color: #af3f2d; font-size: 10px; font-weight: 900; letter-spacing: .13em; margin: 0 0 4px; }
.alarm-action-dialog h3 { color: #1f2925; font-size: 20px; margin: 0; }
.alarm-action-dialog > p { color: #59665f; font-size: 13px; line-height: 1.55; }
.alarm-action-dialog label { color: #35443d; display: grid; font-size: 13px; font-weight: 800; gap: 6px; }
.alarm-action-dialog textarea { border: 1px solid #bfcac4; font: inherit; padding: 8px; resize: vertical; }
.alarm-action-dialog .confirmation { align-items: center; display: flex; font-size: 12px; margin-top: 13px; }
.alarm-action-dialog footer { display: flex; gap: 8px; justify-content: flex-end; margin-top: 18px; }
.danger-button { background: #a93c2c; border: 1px solid #873021; color: #fff; cursor: pointer; font: inherit; font-weight: 900; padding: 8px 12px; }
.danger-button:disabled { cursor: not-allowed; opacity: .5; }
</style>
