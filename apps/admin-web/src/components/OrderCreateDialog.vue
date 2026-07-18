<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { listServiceAreas, listVirtualStops } from "../api/resources";
import type { CreateRideOrderInput } from "../api/orders";
import type { ServiceArea, VirtualStop } from "../api/types";
import { userMessage } from "../api/errors";
import AddressCoordinateField, { type AddressCoordinateValue } from "./AddressCoordinateField.vue";

const emit = defineEmits<{
  close: [];
  create: [value: CreateRideOrderInput];
}>();

defineProps<{
  submitting?: boolean;
}>();

const requestedDepartureAt = new Date(Date.now() + 15 * 60 * 1000).toISOString().slice(0, 16);
const form = reactive({
  passengerName: "",
  passengerPhone: "",
  passengerCount: 1,
  requestType: "IMMEDIATE",
  requestedDepartureAt
});
const origin = ref<AddressCoordinateValue>({ address: "" });
const destination = ref<AddressCoordinateValue>({ address: "" });
const serviceAreas = ref<ServiceArea[]>([]);
const virtualStops = ref<VirtualStop[]>([]);
const setupError = ref("");
const formError = ref("");

onMounted(() => { void loadReferenceData(); });

async function loadReferenceData(): Promise<void> {
  try {
    serviceAreas.value = await listServiceAreas();
    virtualStops.value = await listVirtualStops();
  } catch (error) {
    setupError.value = userMessage(error, "地址与站点参考数据暂不可用，可手工录入坐标后重试。");
  }
}

function submit(): void {
  formError.value = "";
  if (!hasCoordinate(origin.value) || !hasCoordinate(destination.value)) {
    formError.value = "请通过地图点选、虚拟站点或手工经纬度补全起点和终点。";
    return;
  }
  emit("create", {
    ...form,
    originAddress: origin.value.address.trim() || undefined,
    originLng: origin.value.longitude!,
    originLat: origin.value.latitude!,
    originVirtualStopId: origin.value.virtualStopId,
    destinationAddress: destination.value.address.trim() || undefined,
    destinationLng: destination.value.longitude!,
    destinationLat: destination.value.latitude!,
    destinationVirtualStopId: destination.value.virtualStopId,
    coordinateSystem: "GCJ02",
    requestedDepartureAt: new Date(`${form.requestedDepartureAt}:00`).toISOString()
  });
}

function hasCoordinate(value: AddressCoordinateValue): boolean {
  return Number.isFinite(value.longitude) && Number.isFinite(value.latitude);
}
</script>

<template>
  <form class="work-panel order-create-dialog" @submit.prevent="submit">
    <header class="dialog-header">
      <div>
        <p class="section-kicker">IMMEDIATE DEMAND</p>
        <h3 class="section-title">录入需求</h3>
        <p>录入地址文本和坐标，系统会提示服务区范围并推荐可用虚拟站点。</p>
      </div>
    </header>
    <p v-if="setupError" class="page-state">{{ setupError }}</p>
    <div class="form-grid">
      <label class="field"><span>乘客姓名</span><input v-model="form.passengerName" required /></label>
      <label class="field"><span>乘客电话</span><input v-model="form.passengerPhone" required /></label>
      <label class="field"><span>乘客人数</span><input v-model.number="form.passengerCount" type="number" min="1" required /></label>
      <label class="field"><span>需求类型</span><select v-model="form.requestType"><option value="IMMEDIATE">即时</option><option value="RESERVATION">预约</option></select></label>
      <label class="field"><span>预计出发时间</span><input v-model="form.requestedDepartureAt" type="datetime-local" required /></label>
    </div>
    <div class="location-grid">
      <AddressCoordinateField v-model="origin" label="起点" purpose="BOARDING" :service-area-id="serviceAreas[0]?.id" :virtual-stops="virtualStops" />
      <AddressCoordinateField v-model="destination" label="终点" purpose="ALIGHTING" :service-area-id="serviceAreas[0]?.id" :virtual-stops="virtualStops" />
    </div>
    <p v-if="formError" class="page-state">{{ formError }}</p>
    <div class="toolbar">
      <button class="primary-button" type="submit" :disabled="submitting">{{ submitting ? "正在提交" : "提交需求" }}</button>
      <button class="secondary-button" type="button" :disabled="submitting" @click="emit('close')">取消</button>
    </div>
  </form>
</template>

<style scoped>
.order-create-dialog { display: grid; gap: 16px; }
.dialog-header p { margin: 0; }
.location-grid { display: grid; gap: 14px; grid-template-columns: repeat(2, minmax(0, 1fr)); }
@media (max-width: 900px) { .location-grid { grid-template-columns: 1fr; } }
</style>
