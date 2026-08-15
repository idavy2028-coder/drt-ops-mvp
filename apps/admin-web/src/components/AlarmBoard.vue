<script setup lang="ts">
import { computed, ref } from "vue";
import type { VehicleAlarmAction, VehicleAlarmView } from "../api/vehicleAlarms";
import AlarmDetailPanel from "./AlarmDetailPanel.vue";

const props = withDefaults(defineProps<{ alarms: VehicleAlarmView[]; canHandle?: boolean }>(), { canHandle: false });
const emit = defineEmits<{
  selectAlarm: [alarm: VehicleAlarmView];
  action: [payload: { publicId: string; action: VehicleAlarmAction; expectedVersion: number; reason: string; confirmed: true }];
}>();

const expanded = ref(true);
const selectedPublicId = ref<string>();
const level = ref("ALL");
const status = ref("ALL");
const vehicle = ref("");
const module = ref("ALL");
const attachment = ref("ALL");

const orderedAlarms = computed(() => props.alarms.filter(matchesFilters).sort((left, right) => {
  const newRank = Number(right.status === "NEW") - Number(left.status === "NEW");
  return newRank || new Date(right.occurredAt).getTime() - new Date(left.occurredAt).getTime() || left.publicId.localeCompare(right.publicId);
}));
const unacknowledgedCount = computed(() => props.alarms.filter((alarm) => alarm.status === "NEW").length);
const highestLevel = computed(() => Math.max(0, ...props.alarms.map((alarm) => alarm.level)));
const latestOccurredAt = computed(() => [...props.alarms].sort((left, right) => new Date(right.occurredAt).getTime() - new Date(left.occurredAt).getTime())[0]?.occurredAt);
const selectedAlarm = computed(() => selectedPublicId.value === undefined
  ? undefined
  : props.alarms.find((alarm) => alarm.publicId === selectedPublicId.value));

function matchesFilters(alarm: VehicleAlarmView): boolean {
  return (level.value === "ALL" || alarm.level === Number(level.value))
    && (status.value === "ALL" || alarm.status === status.value)
    && (module.value === "ALL" || alarm.module === module.value)
    && (attachment.value === "ALL" || alarm.hasAttachment === (attachment.value === "YES"))
    && (vehicle.value.trim() === "" || (alarm.plateNumber ?? "").includes(vehicle.value.trim()));
}

function select(alarm: VehicleAlarmView): void { selectedPublicId.value = alarm.publicId; emit("selectAlarm", alarm); }
function formatTime(value?: string): string { return value ? new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(value)) : "--"; }
function locationSuspicious(alarm: VehicleAlarmView): boolean { return alarm.locationQualityStatus === "QUARANTINED" || alarm.locationQualityStatus === "REJECTED"; }
</script>

<template>
  <section class="alarm-board" :class="{ collapsed: !expanded }" aria-label="主动安全报警看板">
    <header class="alarm-board-header"><div><p>ACTIVE SAFETY</p><h3>主动安全报警</h3></div><div class="board-header-actions"><span>{{ alarms.length }} 条</span><button type="button" :aria-label="expanded ? '收起报警看板' : '展开报警看板'" @click="expanded = !expanded">{{ expanded ? '收起' : '展开' }}</button></div></header>
    <template v-if="expanded">
      <div class="alarm-metrics" aria-label="报警概览"><span><small>未确认</small><strong>{{ unacknowledgedCount }}</strong></span><span><small>最高等级</small><strong>L{{ highestLevel }}</strong></span><span><small>最近发生</small><strong>{{ formatTime(latestOccurredAt) }}</strong></span></div>
      <div class="alarm-filters"><label>等级<select v-model="level" aria-label="报警等级"><option value="ALL">全部</option><option value="1">L1</option><option value="2">L2</option><option value="3">L3</option></select></label><label>状态<select v-model="status" aria-label="报警状态"><option value="ALL">全部</option><option value="NEW">未确认</option><option value="ACKNOWLEDGED">已确认</option><option value="PROCESSING">处理中</option><option value="RESOLVED">已完成</option><option value="FALSE_POSITIVE">误报</option></select></label><label>车辆<input v-model="vehicle" aria-label="车辆车牌" /></label><label>模块<select v-model="module" aria-label="报警模块"><option value="ALL">全部</option><option value="ADAS">ADAS</option><option value="DMS">DMS</option></select></label><label>附件<select v-model="attachment" aria-label="报警附件"><option value="ALL">全部</option><option value="YES">有附件</option><option value="NO">无附件</option></select></label></div>
      <div class="alarm-list"><button v-for="alarm in orderedAlarms" :key="alarm.publicId" type="button" class="alarm-row" :class="{ selected: selectedAlarm?.publicId === alarm.publicId }" :aria-label="`查看报警 ${alarm.plateNumber ?? '车辆未关联'} ${alarm.alarmType}`" @click="select(alarm)"><span class="alarm-level">L{{ alarm.level }}</span><span class="alarm-copy"><strong>{{ alarm.plateNumber ?? '车辆未关联' }} · {{ alarm.alarmType }}</strong><small>{{ alarm.module }} · {{ formatTime(alarm.occurredAt) }}</small></span><span v-if="locationSuspicious(alarm)" class="warning-tag">位置可疑</span><span v-if="alarm.hasAttachment" class="attachment-tag">附件暂不可用</span></button><p v-if="orderedAlarms.length === 0" class="alarm-empty">没有匹配的报警</p></div>
      <AlarmDetailPanel :alarm="selectedAlarm" :can-handle="canHandle" @action="(payload) => emit('action', payload)" />
    </template>
  </section>
</template>

<style scoped>
.alarm-board { background: #f6f8f7; border: 1px solid #d3ddd7; border-radius: 7px; color: #263a31; padding: 10px; }.alarm-board-header { align-items: center; display: flex; justify-content: space-between; }.alarm-board-header p { color: #b1422e; font-size: 10px; font-weight: 900; letter-spacing: .13em; margin: 0 0 2px; }.alarm-board-header h3 { font-size: 16px; margin: 0; }.board-header-actions { align-items: center; display: flex; gap: 8px; }.board-header-actions span { color: #5e7167; font-size: 12px; font-weight: 800; }.board-header-actions button { background: #fff; border: 1px solid #bdcbc3; color: #355346; cursor: pointer; font: inherit; font-size: 12px; font-weight: 900; padding: 5px 7px; }
.alarm-metrics { display: grid; gap: 6px; grid-template-columns: repeat(3, 1fr); margin-top: 10px; }.alarm-metrics span { background: #e8efeb; border-left: 2px solid #477565; display: grid; padding: 6px 8px; }.alarm-metrics small { color: #60746a; font-size: 10px; font-weight: 800; }.alarm-metrics strong { color: #1f392e; font-size: 14px; margin-top: 2px; }
.alarm-filters { display: grid; gap: 6px; grid-template-columns: repeat(5, minmax(0, 1fr)); margin-top: 9px; }.alarm-filters label { color: #5b6d64; display: grid; font-size: 10px; font-weight: 800; gap: 2px; }.alarm-filters input, .alarm-filters select { background: #fff; border: 1px solid #c7d2cc; color: #2c4338; font: inherit; font-size: 11px; max-width: 100%; min-width: 0; padding: 4px; }
.alarm-list { display: grid; gap: 5px; margin-top: 9px; max-height: 205px; overflow-y: auto; }.alarm-row { align-items: center; background: #fff; border: 1px solid #d8e0dc; color: #2d4137; cursor: pointer; display: grid; font: inherit; gap: 7px; grid-template-columns: auto minmax(0, 1fr) auto auto; padding: 7px; text-align: left; }.alarm-row:hover, .alarm-row.selected { border-color: #51856f; background: #eff7f2; }.alarm-row.selected { box-shadow: inset 3px 0 #197353; }.alarm-level { background: #551f16; color: #fff; font-size: 11px; font-weight: 900; padding: 3px 5px; }.alarm-copy { display: grid; min-width: 0; }.alarm-copy strong { font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.alarm-copy small { color: #6d7d75; font-size: 10px; margin-top: 2px; }.warning-tag, .attachment-tag { font-size: 10px; font-weight: 900; padding: 3px 5px; white-space: nowrap; }.warning-tag { background: #fff0d9; color: #8f5711; }.attachment-tag { background: #e9edeb; color: #5b6962; }.alarm-empty { color: #73837a; font-size: 12px; margin: 13px 0; text-align: center; }
@media (max-width: 900px) { .alarm-filters { grid-template-columns: repeat(3, minmax(0, 1fr)); }.alarm-row { grid-template-columns: auto minmax(0, 1fr); }.warning-tag, .attachment-tag { justify-self: start; } }
</style>
