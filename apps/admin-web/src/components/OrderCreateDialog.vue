<script setup lang="ts">
import { reactive } from "vue";
import type { CreateRideOrderInput } from "../api/orders";

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
  originLng: 116.312,
  originLat: 39.94,
  destinationLng: 116.325,
  destinationLat: 39.936,
  requestedDepartureAt
});

function submit() {
  emit("create", {
    ...form,
    requestedDepartureAt: new Date(`${form.requestedDepartureAt}:00`).toISOString()
  });
}
</script>

<template>
  <form class="work-panel" @submit.prevent="submit">
    <h3 class="section-title">录入需求</h3>
    <div class="form-grid">
      <label class="field">
        <span>乘客姓名</span>
        <input v-model="form.passengerName" required />
      </label>
      <label class="field">
        <span>乘客电话</span>
        <input v-model="form.passengerPhone" required />
      </label>
      <label class="field">
        <span>乘客人数</span>
        <input v-model.number="form.passengerCount" type="number" min="1" required />
      </label>
      <label class="field">
        <span>需求类型</span>
        <select v-model="form.requestType">
          <option value="IMMEDIATE">即时</option>
          <option value="RESERVATION">预约</option>
        </select>
      </label>
      <label class="field">
        <span>起点经度</span>
        <input v-model.number="form.originLng" type="number" step="0.0001" required />
      </label>
      <label class="field">
        <span>起点纬度</span>
        <input v-model.number="form.originLat" type="number" step="0.0001" required />
      </label>
      <label class="field">
        <span>终点经度</span>
        <input v-model.number="form.destinationLng" type="number" step="0.0001" required />
      </label>
      <label class="field">
        <span>终点纬度</span>
        <input v-model.number="form.destinationLat" type="number" step="0.0001" required />
      </label>
      <label class="field">
        <span>预计出发时间</span>
        <input v-model="form.requestedDepartureAt" type="datetime-local" required />
      </label>
    </div>
    <div class="toolbar">
      <button class="primary-button" type="submit" :disabled="submitting">{{ submitting ? "正在提交" : "提交需求" }}</button>
      <button class="secondary-button" type="button" :disabled="submitting" @click="emit('close')">取消</button>
    </div>
  </form>
</template>
