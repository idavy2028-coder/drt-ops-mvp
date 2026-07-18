<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import DriverTable from "../components/DriverTable.vue";
import ServiceAreaMapEditor from "../components/ServiceAreaMapEditor.vue";
import VehicleTable from "../components/VehicleTable.vue";
import VirtualStopImportPanel from "../components/VirtualStopImportPanel.vue";
import VirtualStopMap from "../components/VirtualStopMap.vue";
import VirtualStopTable from "../components/VirtualStopTable.vue";
import { importDistrictBoundary, publishServiceAreaBoundary, saveServiceAreaBoundary } from "../api/map";
import { createVirtualStop, importVirtualStops, listDrivers, listServiceAreas, listVehicles, listVirtualStops, updateVirtualStop } from "../api/resources";
import type { Driver, ServiceArea, ServiceAreaBoundaryDraft, ServiceAreaBoundaryView, Vehicle, VirtualStop, VirtualStopDraft, VirtualStopImportResult } from "../api/types";
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
const stopActionLoading = ref(false);
const stopFeedback = ref("");
const importResult = ref<VirtualStopImportResult>();
const stopKeyword = ref("");
const stopEnabled = ref<"ALL" | "true" | "false">("ALL");
const editingStopId = ref<string>();
const stopDraft = ref<VirtualStopDraft>(emptyStopDraft());

const canManageServiceArea = computed(() => authStore.user?.roles.includes("SYSTEM_ADMIN") ?? false);
const canManageStops = computed(() => authStore.has("RESOURCE_MANAGE"));
const filteredStops = computed(() => virtualStops.value.filter((stop) => {
  const keyword = stopKeyword.value.trim();
  const matchKeyword = !keyword || stop.name.includes(keyword) || (stop.address ?? "").includes(keyword);
  const matchEnabled = stopEnabled.value === "ALL" || String(stop.enabled) === stopEnabled.value;
  return matchKeyword && matchEnabled;
}));

function emptyStopDraft(): VirtualStopDraft {
  return { serviceAreaId: "", name: "", address: "", longitude: 105.2421, latitude: 35.2103, serviceRadiusMeters: 500, boardingEnabled: true, alightingEnabled: true, safetyNote: "", enabled: true };
}

async function loadResources() {
  error.value = "";
  loading.value = true;
  try {
    const [areas, stops, vehicleRows, driverRows] = await Promise.all([listServiceAreas(), listVirtualStops(), listVehicles(), listDrivers()]);
    serviceAreas.value = areas;
    virtualStops.value = stops;
    vehicles.value = vehicleRows;
    drivers.value = driverRows;
    const selectedExists = selectedServiceArea.value && areas.some((area) => area.id === selectedServiceArea.value?.id);
    if (!selectedExists && areas[0]) selectedServiceArea.value = asServiceAreaBoundary(areas[0]);
    if (!stopDraft.value.serviceAreaId && areas[0]) stopDraft.value.serviceAreaId = areas[0].id;
  } catch (loadError) {
    error.value = userMessage(loadError, "资源数据加载失败");
  } finally { loading.value = false; }
}

function asServiceAreaBoundary(area: ServiceArea): ServiceAreaBoundaryView {
  return { id: area.id, name: area.name, boundaryWkt: area.boundary, boundarySource: area.boundarySource, boundaryVersion: area.boundaryVersion, draftBoundaryWkt: area.draftBoundary, draftBoundarySource: area.draftBoundarySource, draftBoundaryVersion: area.draftBoundaryVersion, publishedAt: area.publishedAt, updatedAt: area.updatedAt, coordinateSystem: area.coordinateSystem };
}

function applyServiceAreaUpdate(updated: ServiceAreaBoundaryView): void {
  selectedServiceArea.value = updated;
  const index = serviceAreas.value.findIndex((area) => area.id === updated.id);
  if (index >= 0) serviceAreas.value[index] = { ...serviceAreas.value[index], boundary: updated.boundaryWkt, enabled: updated.publishedAt !== null };
}

async function importDistrict(keyword: string): Promise<void> {
  serviceAreaFeedback.value = ""; serviceAreaActionLoading.value = true;
  try { applyServiceAreaUpdate(await importDistrictBoundary(keyword)); serviceAreaFeedback.value = "通渭县边界已导入为服务区草稿。"; }
  catch (actionError) { error.value = userMessage(actionError, "行政区边界导入失败"); }
  finally { serviceAreaActionLoading.value = false; }
}

async function saveBoundary(draft: ServiceAreaBoundaryDraft): Promise<void> {
  if (!selectedServiceArea.value) return;
  serviceAreaFeedback.value = ""; serviceAreaActionLoading.value = true;
  try { applyServiceAreaUpdate(await saveServiceAreaBoundary(selectedServiceArea.value.id, draft)); serviceAreaFeedback.value = "服务区草稿已保存。"; }
  catch (actionError) { error.value = userMessage(actionError, "服务区草稿保存失败"); }
  finally { serviceAreaActionLoading.value = false; }
}

async function publishBoundary(): Promise<void> {
  if (!selectedServiceArea.value) return;
  serviceAreaFeedback.value = ""; serviceAreaActionLoading.value = true;
  try { applyServiceAreaUpdate(await publishServiceAreaBoundary(selectedServiceArea.value.id)); serviceAreaFeedback.value = "服务区已发布并启用。"; }
  catch (actionError) { error.value = userMessage(actionError, "服务区发布失败"); }
  finally { serviceAreaActionLoading.value = false; }
}

function useMapPoint(longitude: number, latitude: number): void {
  stopDraft.value.longitude = longitude; stopDraft.value.latitude = latitude;
  stopFeedback.value = "已取用地图坐标，请补全站点名称后保存。";
}

function editStop(stop: VirtualStop): void {
  editingStopId.value = stop.id;
  stopDraft.value = { serviceAreaId: stop.serviceAreaId, name: stop.name, address: stop.address ?? "", longitude: stop.longitude ?? 105.2421, latitude: stop.latitude ?? 35.2103, serviceRadiusMeters: stop.serviceRadiusMeters, boardingEnabled: stop.boardingEnabled, alightingEnabled: stop.alightingEnabled, safetyNote: stop.safetyNote, enabled: stop.enabled };
  stopFeedback.value = `正在编辑：${stop.name}`;
}

function resetStopForm(clearFeedback = true): void {
  editingStopId.value = undefined; stopDraft.value = emptyStopDraft();
  if (serviceAreas.value[0]) stopDraft.value.serviceAreaId = serviceAreas.value[0].id;
  if (clearFeedback) stopFeedback.value = "";
}

async function saveStop(): Promise<void> {
  stopActionLoading.value = true; stopFeedback.value = "";
  try {
    const saved = editingStopId.value ? await updateVirtualStop(editingStopId.value, stopDraft.value) : await createVirtualStop(stopDraft.value);
    const index = virtualStops.value.findIndex((stop) => stop.id === saved.id);
    if (index >= 0) virtualStops.value[index] = saved; else virtualStops.value.unshift(saved);
    stopFeedback.value = saved.enabled ? "虚拟站点已保存并启用。" : "虚拟站点已保存，但坐标在服务区外，当前保持未启用。";
    resetStopForm(false);
  } catch (actionError) { error.value = userMessage(actionError, "虚拟站点保存失败"); }
  finally { stopActionLoading.value = false; }
}

async function importStops(file: File): Promise<void> {
  stopActionLoading.value = true; importResult.value = undefined; stopFeedback.value = "";
  try {
    importResult.value = await importVirtualStops(file);
    await loadResources();
    stopFeedback.value = "导入结果已刷新到虚拟站点列表。";
  } catch (actionError) { error.value = userMessage(actionError, "虚拟站点导入失败"); }
  finally { stopActionLoading.value = false; }
}

onMounted(() => { void loadResources(); });
</script>

<template>
  <section class="page">
    <header class="page-header"><div><p class="page-kicker">RESOURCES</p><h2 class="page-title">资源配置</h2><p class="page-subtitle">维护服务区域、虚拟站点、车辆和驾驶员等基础运营资源。</p></div><button class="secondary-button" type="button" :disabled="loading" @click="loadResources">{{ loading ? "同步中" : "刷新" }}</button></header>
    <div class="summary-grid"><article class="metric-panel"><p class="metric-label">服务区域</p><p class="metric-value">{{ serviceAreas.length }}</p></article><article class="metric-panel"><p class="metric-label">虚拟站点</p><p class="metric-value">{{ virtualStops.length }}</p></article><article class="metric-panel"><p class="metric-label">可调车辆</p><p class="metric-value">{{ vehicles.filter((vehicle) => vehicle.dispatchable).length }}</p></article><article class="metric-panel"><p class="metric-label">驾驶员</p><p class="metric-value">{{ drivers.length }}</p></article></div>
    <p v-if="loading" class="page-state">正在同步服务区、站点、车辆与驾驶员资源。</p><p v-else-if="error" class="page-state error-state">{{ error }}</p>
    <ServiceAreaMapEditor :service-area="selectedServiceArea" :readonly="!canManageServiceArea || serviceAreaActionLoading" :feedback="serviceAreaFeedback" @import-district="importDistrict" @save-boundary="saveBoundary" @publish="publishBoundary" />
    <VirtualStopImportPanel :disabled="!canManageStops" :loading="stopActionLoading" :result="importResult" @import="importStops" />
    <section class="stop-editor" aria-labelledby="virtual-stop-editor-title">
      <header><div><p class="section-kicker">STOP EDITOR</p><h3 id="virtual-stop-editor-title">{{ editingStopId ? "编辑虚拟站点" : "新增虚拟站点" }}</h3></div><button type="button" class="secondary-button" :disabled="!canManageStops || stopActionLoading" @click="() => resetStopForm()">清空</button></header>
      <div class="stop-editor-grid"><label>所属服务区<select v-model="stopDraft.serviceAreaId" :disabled="!canManageStops || stopActionLoading"><option v-for="area in serviceAreas" :key="area.id" :value="area.id">{{ area.name }}</option></select></label><label>站点名称<input v-model="stopDraft.name" :disabled="!canManageStops || stopActionLoading" required /></label><label class="address-field">地址<input v-model="stopDraft.address" :disabled="!canManageStops || stopActionLoading" /></label><label>经度<input v-model.number="stopDraft.longitude" type="number" step="0.000001" :disabled="!canManageStops || stopActionLoading" /></label><label>纬度<input v-model.number="stopDraft.latitude" type="number" step="0.000001" :disabled="!canManageStops || stopActionLoading" /></label><label>服务半径（米）<input v-model.number="stopDraft.serviceRadiusMeters" type="number" min="1" :disabled="!canManageStops || stopActionLoading" /></label><label>安全说明<input v-model="stopDraft.safetyNote" :disabled="!canManageStops || stopActionLoading" /></label><label><span>上下车能力</span><span class="checkbox-row"><input v-model="stopDraft.boardingEnabled" type="checkbox" :disabled="!canManageStops || stopActionLoading" />允许上车</span><span class="checkbox-row"><input v-model="stopDraft.alightingEnabled" type="checkbox" :disabled="!canManageStops || stopActionLoading" />允许下车</span></label><label><span>启用状态</span><span class="checkbox-row"><input v-model="stopDraft.enabled" type="checkbox" :disabled="!canManageStops || stopActionLoading" />保存后启用</span></label></div>
      <div class="stop-editor-actions"><button type="button" class="primary-button" :disabled="!canManageStops || stopActionLoading || !stopDraft.serviceAreaId || !stopDraft.name.trim()" @click="saveStop">{{ stopActionLoading ? "正在保存" : editingStopId ? "保存修改" : "新增站点" }}</button><p v-if="stopFeedback" class="stop-feedback">{{ stopFeedback }}</p></div>
    </section>
    <VirtualStopMap :stops="filteredStops" :readonly="!canManageStops || stopActionLoading" @pick="useMapPoint" />
    <section class="stop-filters"><label>关键词<input v-model="stopKeyword" placeholder="站点名称或地址" /></label><label>状态<select v-model="stopEnabled"><option value="ALL">全部</option><option value="true">已启用</option><option value="false">未启用</option></select></label></section>
    <VirtualStopTable :stops="filteredStops" :can-manage="canManageStops" @edit="editStop" />
    <VehicleTable :vehicles="vehicles" /><DriverTable :drivers="drivers" />
  </section>
</template>

<style scoped>
.error-state { color: var(--danger); }.stop-editor, .stop-filters { border: 1px solid var(--line); background: var(--surface); padding: 18px; }.stop-editor header { align-items: center; display: flex; justify-content: space-between; margin-bottom: 14px; }.section-kicker { color: var(--accent); font-size: 12px; font-weight: 800; margin: 0 0 4px; }h3 { font-size: 20px; margin: 0; }.stop-editor-grid { display: grid; gap: 12px; grid-template-columns: repeat(3, minmax(0, 1fr)); }.stop-editor label, .stop-filters label { color: var(--ink); display: grid; font-size: 13px; font-weight: 700; gap: 6px; }.address-field { grid-column: span 2; }.checkbox-row { align-items: center; display: flex; gap: 6px; font-weight: 500; }.stop-editor-actions { align-items: center; display: flex; flex-wrap: wrap; gap: 12px; margin-top: 16px; }.stop-feedback { color: var(--success); font-weight: 700; margin: 0; }.stop-filters { display: flex; gap: 12px; }.stop-filters label { min-width: 220px; }input, select { background: var(--surface); border: 1px solid var(--line); box-sizing: border-box; color: var(--ink); font: inherit; padding: 9px 10px; width: 100%; }@media (max-width: 900px) { .stop-editor-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }.address-field { grid-column: span 2; }@media (max-width: 640px) { .stop-editor-grid { grid-template-columns: 1fr; }.address-field { grid-column: auto; }.stop-filters { flex-direction: column; }.stop-filters label { min-width: 0; } }
</style>
