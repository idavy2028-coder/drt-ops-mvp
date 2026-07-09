<script setup lang="ts">
import { onMounted, ref } from "vue";
import DriverTable from "../components/DriverTable.vue";
import VehicleTable from "../components/VehicleTable.vue";
import VirtualStopTable from "../components/VirtualStopTable.vue";
import { listDrivers, listServiceAreas, listVehicles, listVirtualStops } from "../api/resources";
import type { Driver, ServiceArea, Vehicle, VirtualStop } from "../api/types";

const serviceAreas = ref<ServiceArea[]>([]);
const virtualStops = ref<VirtualStop[]>([]);
const vehicles = ref<Vehicle[]>([]);
const drivers = ref<Driver[]>([]);
const error = ref("");

async function loadResources() {
  error.value = "";
  try {
    const [areas, stops, vehicleRows, driverRows] = await Promise.all([
      listServiceAreas(),
      listVirtualStops(),
      listVehicles(),
      listDrivers()
    ]);
    serviceAreas.value = areas;
    virtualStops.value = stops;
    vehicles.value = vehicleRows;
    drivers.value = driverRows;
  } catch (loadError) {
    error.value = loadError instanceof Error ? loadError.message : "资源数据加载失败";
  }
}

onMounted(() => {
  void loadResources();
});
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="page-kicker">RESOURCES</p>
        <h2 class="page-title">资源配置</h2>
        <p class="page-subtitle">维护服务区域、虚拟站点、车辆和驾驶员等基础运营资源。</p>
      </div>
      <button class="secondary-button" type="button" @click="loadResources">刷新</button>
    </header>

    <div class="summary-grid">
      <article class="metric-panel">
        <p class="metric-label">服务区域</p>
        <p class="metric-value">{{ serviceAreas.length }}</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">虚拟站点</p>
        <p class="metric-value">{{ virtualStops.length }}</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">可调车辆</p>
        <p class="metric-value">{{ vehicles.filter((vehicle) => vehicle.dispatchable).length }}</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">驾驶员</p>
        <p class="metric-value">{{ drivers.length }}</p>
      </article>
    </div>

    <p v-if="error" class="section-copy">后端连接状态：{{ error }}</p>

    <VirtualStopTable :stops="virtualStops" />
    <VehicleTable :vehicles="vehicles" />
    <DriverTable :drivers="drivers" />
  </section>
</template>
