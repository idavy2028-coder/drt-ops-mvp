<script setup lang="ts">
import { computed, ref } from "vue";
import type { VehicleAlarmAction, VehicleAlarmView } from "../api/vehicleAlarms";
import AlarmActionDialog from "./AlarmActionDialog.vue";

const props = withDefaults(defineProps<{ alarm?: VehicleAlarmView; canHandle?: boolean }>(), { alarm: undefined, canHandle: false });
const emit = defineEmits<{ action: [payload: { publicId: string; action: VehicleAlarmAction; expectedVersion: number; reason: string; confirmed: true }] }>();

const pendingAction = ref<VehicleAlarmAction>();
const actions = computed(() => availableActions(props.alarm?.status));

function availableActions(status?: string): VehicleAlarmAction[] {
  if (status === "NEW") return ["ACKNOWLEDGE", "MARK_FALSE_POSITIVE"];
  if (status === "ACKNOWLEDGED") return ["TAKE_OVER", "RESOLVE", "MARK_FALSE_POSITIVE"];
  if (status === "PROCESSING") return ["RESOLVE", "MARK_FALSE_POSITIVE"];
  if (status === "RESOLVED" || status === "FALSE_POSITIVE") return ["REOPEN"];
  return [];
}

function actionLabel(action: VehicleAlarmAction): string {
  return ({ ACKNOWLEDGE: "确认报警", TAKE_OVER: "接手处理", RESOLVE: "处理完成", MARK_FALSE_POSITIVE: "标记误报", REOPEN: "重新打开" } as Record<VehicleAlarmAction, string>)[action];
}

function formatTime(value?: string | null): string {
  if (!value) return "--";
  return new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(value));
}

function formatSpeed(value?: VehicleAlarmView["speedKph"]): string {
  const speed = Number(value);
  if (value === null || value === undefined || !Number.isFinite(speed)) return "尚无数据";
  return `${Number.isInteger(speed) ? speed : speed.toFixed(1)} km/h`;
}
</script>

<template>
  <section v-if="alarm" class="alarm-detail" aria-label="报警详情">
    <header><div><p>ALARM DETAIL</p><h3>{{ alarm.plateNumber ?? "车辆未关联" }} · {{ alarm.alarmType }}</h3></div><span class="alarm-level">L{{ alarm.level }}</span></header>
    <dl>
      <div><dt>模块</dt><dd>{{ alarm.module }}</dd></div><div><dt>状态</dt><dd>{{ alarm.status }}</dd></div>
      <div><dt>发生时间</dt><dd>{{ formatTime(alarm.occurredAt) }}</dd></div><div><dt>定位质量</dt><dd>{{ alarm.locationQualityStatus }}</dd></div>
      <div><dt>速度</dt><dd>{{ formatSpeed(alarm.speedKph) }}</dd></div>
    </dl>
    <p v-if="alarm.locationQualityStatus === 'QUARANTINED' || alarm.locationQualityStatus === 'REJECTED'" class="location-warning">位置可疑，已仅关联车辆，不触发地图聚焦。</p>
    <p class="attachment-note">{{ alarm.hasAttachment ? "附件暂不可用" : "无附件" }}</p>
    <div v-if="canHandle && actions.length" class="alarm-actions"><button v-for="action in actions" :key="action" type="button" @click="pendingAction = action">{{ actionLabel(action) }}</button></div>
    <AlarmActionDialog :visible="pendingAction !== undefined" :alarm="alarm" :action="pendingAction" @close="pendingAction = undefined" @confirm="(payload) => { pendingAction = undefined; emit('action', payload); }" />
  </section>
</template>

<style scoped>
.alarm-detail { background: #fbfcfb; border: 1px solid #d8e0dc; border-left: 3px solid #c84b35; margin-top: 8px; padding: 12px; }
.alarm-detail header { align-items: start; display: flex; gap: 12px; justify-content: space-between; }.alarm-detail header p { color: #a33b2a; font-size: 10px; font-weight: 900; letter-spacing: .11em; margin: 0 0 3px; }.alarm-detail h3 { color: #26352e; font-size: 14px; margin: 0; }.alarm-level { background: #4c1912; color: #fff; font-size: 12px; font-weight: 900; padding: 3px 6px; }
.alarm-detail dl { display: grid; gap: 7px; grid-template-columns: 1fr 1fr; margin: 12px 0; }.alarm-detail dl div { min-width: 0; }.alarm-detail dt { color: #718078; font-size: 11px; }.alarm-detail dd { color: #31443a; font-size: 12px; font-weight: 800; margin: 2px 0 0; overflow-wrap: anywhere; }
.location-warning, .attachment-note { font-size: 12px; font-weight: 800; margin: 8px 0; padding: 7px 8px; }.location-warning { background: #fff3dc; color: #8a5512; }.attachment-note { background: #edf1ef; color: #53645a; }
.alarm-actions { display: flex; flex-wrap: wrap; gap: 6px; }.alarm-actions button { background: #1e4b3b; border: 1px solid #16392d; color: #fff; cursor: pointer; font: inherit; font-size: 12px; font-weight: 900; padding: 7px 9px; }
</style>
