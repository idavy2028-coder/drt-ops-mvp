<script setup lang="ts">
import { reactive } from "vue";
import type { CreateVehicleInput } from "../api/types";

const props = defineProps<{ disabled: boolean; loading: boolean }>();
const emit = defineEmits<{ create: [input: CreateVehicleInput] }>();

const form = reactive<CreateVehicleInput>({
  plateNumber: "",
  vehicleType: "MINIBUS",
  capacity: 8,
  currentStatus: "IDLE",
  lng: 105.2421,
  lat: 35.2103,
  fleetName: "通渭县试点车队",
  dispatchable: true
});

const canSubmit = () => Boolean(form.plateNumber.trim() && form.vehicleType.trim() && form.capacity > 0 && form.fleetName.trim());

function submit(): void {
  if (!canSubmit() || props.disabled || props.loading) {
    return;
  }
  emit("create", { ...form, plateNumber: form.plateNumber.trim(), vehicleType: form.vehicleType.trim(), fleetName: form.fleetName.trim() });
}

function reset(): void {
  form.plateNumber = "";
  form.vehicleType = "MINIBUS";
  form.capacity = 8;
  form.currentStatus = "IDLE";
  form.lng = 105.2421;
  form.lat = 35.2103;
  form.fleetName = "通渭县试点车队";
  form.dispatchable = true;
}

defineExpose({ reset });
</script>

<template>
  <section class="fleet-form" aria-labelledby="vehicle-create-title">
    <header><div><p class="section-kicker">VEHICLES</p><h3 id="vehicle-create-title">新增车辆</h3></div></header>
    <div class="fleet-form-grid">
      <label>车牌号<input v-model="form.plateNumber" required :disabled="props.disabled || props.loading" /></label>
      <label>车型<input v-model="form.vehicleType" required :disabled="props.disabled || props.loading" /></label>
      <label>核载人数<input v-model.number="form.capacity" type="number" min="1" required :disabled="props.disabled || props.loading" /></label>
      <label>车队名称<input v-model="form.fleetName" required :disabled="props.disabled || props.loading" /></label>
      <label>初始位置经度<input v-model.number="form.lng" type="number" step="0.000001" required :disabled="props.disabled || props.loading" /></label>
      <label>初始位置纬度<input v-model.number="form.lat" type="number" step="0.000001" required :disabled="props.disabled || props.loading" /></label>
      <label>初始状态<select v-model="form.currentStatus" :disabled="props.disabled || props.loading"><option value="IDLE">空闲</option><option value="OFFLINE">离线</option></select></label>
      <label class="checkbox-field"><span>调度能力</span><span class="checkbox-row"><input v-model="form.dispatchable" type="checkbox" :disabled="props.disabled || props.loading" />可调度</span></label>
    </div>
    <button class="primary-button" type="button" :disabled="props.disabled || props.loading || !canSubmit()" @click="submit">{{ props.loading ? "正在保存" : "新增车辆" }}</button>
  </section>
</template>

<style scoped>
.fleet-form { border: 1px solid var(--line); background: var(--surface); padding: 18px; }
header { margin-bottom: 14px; }
.section-kicker { color: var(--accent); font-size: 12px; font-weight: 800; margin: 0 0 4px; }
h3 { font-size: 20px; margin: 0; }
.fleet-form-grid { display: grid; gap: 12px; grid-template-columns: repeat(4, minmax(0, 1fr)); }
.fleet-form label { color: var(--ink); display: grid; font-size: 13px; font-weight: 700; gap: 6px; }
.checkbox-field { align-content: start; }
.checkbox-row { align-items: center; display: flex; gap: 6px; font-weight: 500; }
input, select { background: var(--surface); border: 1px solid var(--line); box-sizing: border-box; color: var(--ink); font: inherit; padding: 9px 10px; width: 100%; }
button { margin-top: 16px; }
@media (max-width: 900px) { .fleet-form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .fleet-form-grid { grid-template-columns: 1fr; } }
</style>
