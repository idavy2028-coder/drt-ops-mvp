<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import DriverTable from "../components/DriverTable.vue";
import ServiceAreaMapEditor from "../components/ServiceAreaMapEditor.vue";
import VehicleTable from "../components/VehicleTable.vue";
import VirtualStopTable from "../components/VirtualStopTable.vue";
import { importDistrictBoundary, publishServiceAreaBoundary, saveServiceAreaBoundary } from "../api/map";
import { listDrivers, listServiceAreas, listVehicles, listVirtualStops } from "../api/resources";
import type { Driver, ServiceArea, ServiceAreaBoundaryDraft, ServiceAreaBoundaryView, Vehicle, VirtualStop } from "../api/types";
import { authStore } from "../auth/authStore";
import { userMessage } from "../api/errors";

const serviceAreas = ref<ServiceArea[]>([]);
const virtualStops = ref<VirtualStop[]>([]);
const vehicles = ref<Vehicle[]>([]);
const drivers = ref<Driver[]>([]);
const error = ref("");
const loading = ref(false);
const serviceAreaActionLoading = ref(false);
const serviceAreaFeedback = ref("");
const selectedServiceArea = ref<ServiceAreaBoundaryView>();

const canManageServiceArea = computed(() => authStore.user?.roles.includes("SYSTEM_ADMIN") ?? false);
const amapEnabled = computed(() => import.meta.env.VITE_AMAP_ENABLED === "true" || import.meta.env.VITE_AMAP_ENABLED === "1");

async function loadResources() {
  error.value = "";
  loading.value = true;
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
    const selectedExists = selectedServiceArea.value && areas.some((area) => area.id === selectedServiceArea.value?.id);
    if (!selectedExists && areas[0]) {
      selectedServiceArea.value = asServiceAreaBoundary(areas[0]);
    }
  } catch (loadError) {
    error.value = userMessage(loadError, "资源数据加载失败");
  } finally {
    loading.value = false;
  }
}

function asServiceAreaBoundary(area: ServiceArea): ServiceAreaBoundaryView {
  return {
    id: area.id,
    name: area.name,
    boundaryWkt: area.boundary,
    boundarySource: area.boundarySource,
    boundaryVersion: area.boundaryVersion,
    draftBoundaryWkt: area.draftBoundary,
    draftBoundarySource: area.draftBoundarySource,
    draftBoundaryVersion: area.draftBoundaryVersion,
    publishedAt: area.publishedAt,
    updatedAt: area.updatedAt,
    coordinateSystem: area.coordinateSystem
  };
}

function applyServiceAreaUpdate(updated: ServiceAreaBoundaryView): void {
  selectedServiceArea.value = updated;
  const index = serviceAreas.value.findIndex((area) => area.id === updated.id);
  if (index >= 0) {
    serviceAreas.value[index] = {
      ...serviceAreas.value[index],
      boundary: updated.boundaryWkt,
      enabled: updated.publishedAt !== null
    };
  }
}

async function importDistrict(keyword: string): Promise<void> {
  serviceAreaFeedback.value = "";
  serviceAreaActionLoading.value = true;
  try {
    applyServiceAreaUpdate(await importDistrictBoundary(keyword));
    serviceAreaFeedback.value = "通渭县边界已导入为服务区草稿。";
  } catch (actionError) {
    error.value = userMessage(actionError, "行政区边界导入失败");
  } finally {
    serviceAreaActionLoading.value = false;
  }
}

async function saveBoundary(draft: ServiceAreaBoundaryDraft): Promise<void> {
  if (!selectedServiceArea.value) {
    return;
  }
  serviceAreaFeedback.value = "";
  serviceAreaActionLoading.value = true;
  try {
    applyServiceAreaUpdate(await saveServiceAreaBoundary(selectedServiceArea.value.id, draft));
    serviceAreaFeedback.value = "服务区草稿已保存。";
  } catch (actionError) {
    error.value = userMessage(actionError, "服务区草稿保存失败");
  } finally {
    serviceAreaActionLoading.value = false;
  }
}

async function publishBoundary(): Promise<void> {
  if (!selectedServiceArea.value) {
    return;
  }
  serviceAreaFeedback.value = "";
  serviceAreaActionLoading.value = true;
  try {
    applyServiceAreaUpdate(await publishServiceAreaBoundary(selectedServiceArea.value.id));
    serviceAreaFeedback.value = "服务区已发布并启用。";
  } catch (actionError) {
    error.value = userMessage(actionError, "服务区发布失败");
  } finally {
    serviceAreaActionLoading.value = false;
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
      <button class="secondary-button" type="button" :disabled="loading" @click="loadResources">{{ loading ? "同步中" : "刷新" }}</button>
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

    <p v-if="loading" class="page-state">正在同步服务区、站点、车辆与驾驶员资源…</p>
    <p v-else-if="error" class="page-state">{{ error }}</p>

    <ServiceAreaMapEditor
      :service-area="selectedServiceArea"
      :readonly="!canManageServiceArea || serviceAreaActionLoading"
      :amap-enabled="amapEnabled"
      :feedback="serviceAreaFeedback"
      @import-district="importDistrict"
      @save-boundary="saveBoundary"
      @publish="publishBoundary"
    />

    <VirtualStopTable :stops="virtualStops" />
    <VehicleTable :vehicles="vehicles" />
    <DriverTable :drivers="drivers" />
  </section>
</template>
