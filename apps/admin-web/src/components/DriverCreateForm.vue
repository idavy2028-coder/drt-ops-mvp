<script setup lang="ts">
import { reactive } from "vue";
import type { CreateDriverInput } from "../api/types";

const props = defineProps<{ disabled: boolean; loading: boolean }>();
const emit = defineEmits<{ create: [input: CreateDriverInput] }>();

const form = reactive({
  name: "",
  phone: "",
  qualificationStatus: "QUALIFIED",
  shiftStart: "",
  shiftEnd: "",
  currentStatus: "AVAILABLE",
  fleetName: "通渭县试点车队"
});

const canSubmit = () => Boolean(form.name.trim() && form.phone.trim() && form.fleetName.trim());

function toOffsetDateTime(value: string): string | undefined {
  if (!value) {
    return undefined;
  }
  const [date, time] = value.split("T");
  return `${date}T${time}:00+08:00`;
}

function submit(): void {
  if (!canSubmit() || props.disabled || props.loading) {
    return;
  }
  emit("create", {
    name: form.name.trim(),
    phone: form.phone.trim(),
    qualificationStatus: form.qualificationStatus,
    shiftStart: toOffsetDateTime(form.shiftStart),
    shiftEnd: toOffsetDateTime(form.shiftEnd),
    currentStatus: form.currentStatus,
    fleetName: form.fleetName.trim()
  });
}

function reset(): void {
  form.name = "";
  form.phone = "";
  form.qualificationStatus = "QUALIFIED";
  form.shiftStart = "";
  form.shiftEnd = "";
  form.currentStatus = "AVAILABLE";
  form.fleetName = "通渭县试点车队";
}

defineExpose({ reset });
</script>

<template>
  <section class="fleet-form" aria-labelledby="driver-create-title">
    <header><div><p class="section-kicker">DRIVERS</p><h3 id="driver-create-title">新增驾驶员</h3></div></header>
    <div class="fleet-form-grid">
      <label>姓名<input v-model="form.name" required :disabled="props.disabled || props.loading" /></label>
      <label>手机号<input v-model="form.phone" type="tel" required :disabled="props.disabled || props.loading" /></label>
      <label>资质状态<select v-model="form.qualificationStatus" :disabled="props.disabled || props.loading"><option value="QUALIFIED">资质有效</option><option value="EXPIRED">资质过期</option></select></label>
      <label>初始状态<select v-model="form.currentStatus" :disabled="props.disabled || props.loading"><option value="AVAILABLE">可用</option><option value="OFFLINE">离线</option></select></label>
      <label>班次开始<input v-model="form.shiftStart" type="datetime-local" :disabled="props.disabled || props.loading" /></label>
      <label>班次结束<input v-model="form.shiftEnd" type="datetime-local" :disabled="props.disabled || props.loading" /></label>
      <label>车队名称<input v-model="form.fleetName" required :disabled="props.disabled || props.loading" /></label>
    </div>
    <button class="primary-button" type="button" :disabled="props.disabled || props.loading || !canSubmit()" @click="submit">{{ props.loading ? "正在保存" : "新增驾驶员" }}</button>
  </section>
</template>

<style scoped>
.fleet-form { border: 1px solid var(--line); background: var(--surface); padding: 18px; }
header { margin-bottom: 14px; }
.section-kicker { color: var(--accent); font-size: 12px; font-weight: 800; margin: 0 0 4px; }
h3 { font-size: 20px; margin: 0; }
.fleet-form-grid { display: grid; gap: 12px; grid-template-columns: repeat(4, minmax(0, 1fr)); }
.fleet-form label { color: var(--ink); display: grid; font-size: 13px; font-weight: 700; gap: 6px; }
input, select { background: var(--surface); border: 1px solid var(--line); box-sizing: border-box; color: var(--ink); font: inherit; padding: 9px 10px; width: 100%; }
button { margin-top: 16px; }
@media (max-width: 900px) { .fleet-form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .fleet-form-grid { grid-template-columns: 1fr; } }
</style>
