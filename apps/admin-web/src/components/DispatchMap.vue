<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as L from "leaflet";
import { toLeafletLatLng } from "../maps/coordinateTransform";
import { createTileMap } from "../maps/tileMapRuntime";
import type { GeoPoint, TileMapHandle } from "../maps/tileMapTypes";
import type { ServiceArea, VehicleLocationEventView, VehicleLocationSnapshotItem, VehicleTask, VirtualStop } from "../api/types";

const props = withDefaults(defineProps<{
  serviceArea?: ServiceArea;
  stops?: VirtualStop[];
  locations?: VehicleLocationSnapshotItem[];
  eventChain?: VehicleLocationEventView[];
  selectedTask?: VehicleTask;
  selectedVehicleId?: string;
  vehicleFocusRequest?: number;
}>(), {
  serviceArea: undefined,
  stops: () => [],
  locations: () => [],
  eventChain: () => [],
  selectedTask: undefined,
  selectedVehicleId: undefined,
  vehicleFocusRequest: 0
});

const emit = defineEmits<{
  selectVehicle: [vehicleId: string];
}>();

const mapRoot = ref<HTMLElement>();
const mapContainer = ref<HTMLDivElement>();
const mapReady = ref(false);
const mapFeedback = ref("");
const mapWarning = ref("");
const layers = ref({ serviceArea: true, stops: true, locations: true });
const tileMap = ref<TileMapHandle>();
let renderedLayers: L.Layer[] = [];
let vehicleMarkers = new Map<string, L.Marker>();
let hasFittedInitialLayers = false;
let unsubscribeBaseLayerError: (() => void) | undefined;
let resizeObserver: ResizeObserver | undefined;

const orderedChain = computed(() => [...props.eventChain].sort((left, right) => new Date(left.driverReportedAt).getTime() - new Date(right.driverReportedAt).getTime()));
const visibleStops = computed(() => props.stops.filter(hasCoordinates));
const selectedTaskStops = computed(() => {
  if (!props.selectedTask) return [];
  const stopsById = new Map(props.stops.map((stop) => [stop.id, stop]));
  return [...props.selectedTask.stops]
    .sort((left, right) => left.sequenceNumber - right.sequenceNumber)
    .map((taskStop) => stopsById.get(taskStop.virtualStopId))
    .filter((stop): stop is VirtualStop => stop !== undefined && hasCoordinates(stop));
});
const chainPoints = computed(() => orderedChain.value
  .map((event) => ({ longitude: Number(event.longitude), latitude: Number(event.latitude) }))
  .filter(hasFiniteCoordinates));

onMounted(() => { void initializeMap(); });
onBeforeUnmount(() => {
  unsubscribeBaseLayerError?.();
  resizeObserver?.disconnect();
  clearRenderedLayers();
  tileMap.value?.destroy();
});
watch([() => props.serviceArea, () => props.stops, () => props.locations, () => props.eventChain, () => props.selectedTask, layers], () => renderMapLayers(), { deep: true });
watch(() => props.vehicleFocusRequest, () => focusSelectedVehicle(), { flush: "post" });

async function initializeMap(): Promise<void> {
  await nextTick();
  if (!mapContainer.value) return;

  try {
    tileMap.value = createTileMap(mapContainer.value, { longitude: 105.2421, latitude: 35.2103 }, 12);
    unsubscribeBaseLayerError = tileMap.value.onBaseLayerError(() => {
      mapWarning.value = "开放底图暂不可用";
    });
    mapReady.value = true;
    observeMapSize();
    renderMapLayers();
  } catch {
    mapFeedback.value = "开放底图暂不可用";
  }
}

function renderMapLayers(): void {
  if (!tileMap.value) return;

  clearRenderedLayers();
  const map = tileMap.value.map;
  if (layers.value.serviceArea && props.serviceArea?.boundary) {
    const boundary = parsePolygon(props.serviceArea.boundary);
    if (boundary.length >= 3) {
      renderedLayers.push(L.polygon(boundary.map(toLeafletLatLng), {
        color: "#007a5e", weight: 3, fillColor: "#8bd6bc", fillOpacity: 0.22
      }).addTo(map));
    }
  }
  if (layers.value.stops) {
    visibleStops.value.forEach((stop) => {
      const label = `${stop.name} · ${stop.enabled ? "已启用" : "未启用"}`;
      renderedLayers.push(L.marker(toLeafletLatLng({ longitude: stop.longitude!, latitude: stop.latitude! }), { title: label })
        .bindTooltip(label, { direction: "top" }).addTo(map));
    });
  }
  addDashedLine(selectedTaskStops.value.map((stop) => ({ longitude: stop.longitude!, latitude: stop.latitude! })), "#17634b", "8 6");
  addDashedLine(chainPoints.value, "#496b5e", "4 8");
  if (layers.value.locations) {
    props.locations.filter((item) => hasFiniteCoordinates({ longitude: Number(item.latestLocation.longitude), latitude: Number(item.latestLocation.latitude) })).forEach((item) => {
      const location = item.latestLocation;
      const marker = L.marker(toLeafletLatLng({ longitude: Number(location.longitude), latitude: Number(location.latitude) }), {
        title: item.plateNumber,
        icon: L.divIcon({
          className: `dispatch-vehicle-marker ${vehicleStatusClass(item)}`,
          html: '<span class="vehicle-marker-pin"><span aria-hidden="true">车</span></span>',
          iconSize: [36, 36],
          iconAnchor: [18, 18],
          popupAnchor: [0, -18]
        })
      });
      marker.bindPopup(vehiclePopup(item));
      marker.on("click", () => emit("selectVehicle", item.vehicleId));
      marker.addTo(map);
      vehicleMarkers.set(item.vehicleId, marker);
      renderedLayers.push(marker);
    });
  }
  if (!hasFittedInitialLayers && renderedLayers.length > 0) {
    tileMap.value.fitLayers(renderedLayers);
    hasFittedInitialLayers = true;
  }
}

function addDashedLine(points: GeoPoint[], color: string, dashArray: string): void {
  if (!tileMap.value || points.length < 2) return;
  renderedLayers.push(L.polyline(points.map(toLeafletLatLng), { color, weight: 3, dashArray, opacity: 0.86 }).addTo(tileMap.value.map));
}

function clearRenderedLayers(): void {
  renderedLayers.forEach((layer) => layer.remove());
  renderedLayers = [];
  vehicleMarkers = new Map<string, L.Marker>();
}

function observeMapSize(): void {
  if (!mapRoot.value || typeof ResizeObserver === "undefined") return;
  resizeObserver = new ResizeObserver(() => {
    requestAnimationFrame(() => tileMap.value?.invalidateSize());
  });
  resizeObserver.observe(mapRoot.value);
  requestAnimationFrame(() => tileMap.value?.invalidateSize());
}

function focusSelectedVehicle(): void {
  if (!tileMap.value || !props.selectedVehicleId) return;
  const item = props.locations.find((location) => location.vehicleId === props.selectedVehicleId);
  if (!item) return;
  const point = { longitude: Number(item.latestLocation.longitude), latitude: Number(item.latestLocation.latitude) };
  if (!hasFiniteCoordinates(point)) return;
  tileMap.value.focusPoint(point);
  vehicleMarkers.get(item.vehicleId)?.openPopup();
}

function fitAllLayers(): void {
  tileMap.value?.fitLayers(renderedLayers);
}

function parsePolygon(value: string): GeoPoint[] {
  const match = value.trim().match(/^POLYGON\s*\(\((.+)\)\)$/i);
  if (!match) return [];
  return match[1].split(",").map((pair) => {
    const [longitude, latitude] = pair.trim().split(/\s+/).map(Number);
    return { longitude, latitude };
  }).filter(hasFiniteCoordinates);
}

function hasCoordinates(stop: VirtualStop): stop is VirtualStop & Required<Pick<VirtualStop, "longitude" | "latitude">> {
  return hasFiniteCoordinates({ longitude: Number(stop.longitude), latitude: Number(stop.latitude) });
}

function hasFiniteCoordinates(point: { longitude: number; latitude: number }): boolean {
  return Number.isFinite(point.longitude) && Number.isFinite(point.latitude);
}

function statusLabel(status: string): string {
  return ({ IN_SERVICE: "执行中", DISPATCHED: "已派单", IDLE: "空闲", OFFLINE: "离线", COMPLETED: "已完成" } as Record<string, string>)[status] ?? status;
}

function vehicleStatusClass(item: VehicleLocationSnapshotItem): string {
  if (item.latestLocation.outsideServiceArea || item.currentStatus === "OFFLINE") return "is-alert";
  if (item.currentStatus === "IDLE") return "is-idle";
  if (item.currentStatus === "IN_SERVICE" || item.currentStatus === "DISPATCHED") return "is-active";
  return "is-unknown";
}

function vehiclePopup(item: VehicleLocationSnapshotItem): string {
  const taskId = item.latestLocation.vehicleTaskId;
  return `<div class="dispatch-vehicle-popup"><strong>${escapeHtml(item.plateNumber)}</strong><dl><div><dt>状态</dt><dd>${escapeHtml(statusLabel(item.currentStatus))}</dd></div><div><dt>当前任务</dt><dd>${taskId ? `任务 ${escapeHtml(taskId.slice(0, 8))}` : "--"}</dd></div><div><dt>最后位置</dt><dd>${escapeHtml(formatDateTime(item.latestLocation.driverReportedAt))}</dd></div></dl></div>`;
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;" })[character]!);
}

function eventLabel(eventType: string): string {
  return ({ TASK_STARTED: "发车", TASK_STOP_ARRIVED: "到站", PASSENGER_BOARDED: "上车", PASSENGER_ALIGHTED: "下车", TASK_COMPLETED: "完成", MANUAL_CORRECTION: "人工补报" } as Record<string, string>)[eventType] ?? eventType;
}

function formatDateTime(value?: string): string {
  if (!value) return "--";
  return new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(value));
}

</script>

<template>
  <section ref="mapRoot" class="dispatch-map" :class="{ 'map-degraded': !mapReady }" aria-label="调度地图">
    <div ref="mapContainer" class="map-canvas"></div>
    <div v-if="!mapReady" class="fallback-surface"><strong>{{ mapFeedback || "开放底图暂不可用" }}</strong><span>服务区、虚拟站点、任务与人工位置仍可在下方图层摘要中核对。</span></div>
    <p v-if="mapReady && mapWarning" class="map-warning" role="status">{{ mapWarning }}</p>
    <div class="map-controls" aria-label="地图图层控制">
      <label><input v-model="layers.serviceArea" type="checkbox" aria-label="服务区图层" />服务区</label>
      <label><input v-model="layers.stops" type="checkbox" aria-label="虚拟站点图层" />虚拟站点</label>
      <label><input v-model="layers.locations" type="checkbox" aria-label="车辆位置图层" />车辆位置</label>
      <button type="button" class="map-reset-button" @click="fitAllLayers">回到全局</button>
    </div>
    <div v-if="!mapReady && layers.serviceArea && serviceArea" class="map-overlay service-area"><strong>{{ serviceArea.name }}</strong><span>已发布服务区</span></div>
    <div v-if="!mapReady && selectedTaskStops.length > 1" class="map-route"><strong>任务 {{ selectedTask?.id.slice(0, 8) }}</strong><span>{{ selectedTaskStops.length }} 个站点连接</span></div>
    <div v-if="!mapReady && layers.stops" class="fallback-stops" aria-label="虚拟站点图层内容"><span v-for="stop in visibleStops" :key="stop.id" class="stop-chip" :class="{ disabled: !stop.enabled }">{{ stop.name }}</span></div>
    <div v-if="orderedChain.length" class="map-chain" aria-label="人工节点链"><p>离散节点链，仅表示人工上报节点，不是实际行驶轨迹</p><ol><li v-for="event in orderedChain" :key="event.id"><span>{{ eventLabel(event.eventType) }}</span><small>{{ formatDateTime(event.driverReportedAt) }}</small></li></ol></div>
    <div class="map-legend"><span><i class="legend-dot is-idle"></i>空闲</span><span><i class="legend-dot is-active"></i>执行中</span><span><i class="legend-dot is-alert"></i>异常/越界</span></div>
  </section>
</template>

<style scoped>
.dispatch-map { background: #eef2ef; border: 0; border-radius: 8px; box-shadow: inset 0 0 0 1px #d9e1dc; height: 100%; min-height: 560px; overflow: hidden; position: relative; }
.map-canvas, .fallback-surface { height: 100%; inset: 0; position: absolute; width: 100%; }
.fallback-surface { background: #e5eee9; color: #365348; display: grid; gap: 7px; place-content: center; text-align: center; }
.fallback-surface span { font-size: 13px; }
.map-controls, .map-warning, .map-overlay, .map-route, .fallback-stops, .map-chain, .map-legend { position: absolute; z-index: 500; }
.map-controls { display: flex; flex-wrap: wrap; gap: 8px; left: 58px; top: 14px; z-index: 800; }
.map-controls label, .map-reset-button { align-items: center; background: #fffffff2; border: 1px solid #cbd8d1; color: #40574e; display: flex; font-size: 12px; font-weight: 800; gap: 4px; padding: 6px 8px; }
.map-reset-button { cursor: pointer; font-family: inherit; }
.map-reset-button:hover { border-color: #17634b; color: #17634b; }
.map-warning { background: #fff7d8; border: 1px solid #d1a749; color: #6a4a00; font-size: 12px; font-weight: 800; left: 14px; margin: 0; padding: 6px 9px; top: 100px; }
.map-overlay { background: #e1f2eaeb; border: 2px solid #43846d; bottom: 58px; color: #1b6049; display: grid; gap: 2px; left: 62px; padding: 10px; right: 54px; top: 62px; }
.map-overlay span, .map-route span { font-size: 12px; }
.map-route { background: #17634beb; bottom: 90px; color: #fff; display: grid; gap: 2px; left: 32%; padding: 8px 10px; top: 46%; }
.fallback-stops { display: flex; flex-wrap: wrap; gap: 6px; left: 18px; max-width: 52%; top: 110px; }
.stop-chip { background: #fff; border: 1px solid #17634b; color: #17634b; font-size: 12px; font-weight: 800; padding: 4px 7px; }
.stop-chip.disabled { border-color: #9f2424; color: #9f2424; }
.map-chain { background: #fffffff0; border: 1px dashed #17634b76; bottom: 14px; left: 18px; max-width: 360px; padding: 9px 10px; }
.map-chain p { color: #3f4c46; font-size: 12px; font-weight: 900; margin: 0 0 8px; }
.map-chain ol { display: flex; flex-wrap: wrap; gap: 8px; list-style: none; margin: 0; padding: 0; }
.map-chain li { border-left: 2px dashed #17634b; color: #53615a; font-size: 12px; font-weight: 800; padding-left: 8px; }
.map-chain span, .map-chain small { display: block; }
.map-legend { background: #fffffff0; bottom: 14px; color: #53615a; display: flex; flex-wrap: wrap; font-size: 12px; font-weight: 800; gap: 10px; max-width: 320px; padding: 8px 10px; right: 14px; }
.map-legend span { align-items: center; display: inline-flex; gap: 5px; }
.legend-dot { background: #74827b; border: 2px solid #fff; border-radius: 50%; box-shadow: 0 0 0 1px #17201c25; display: inline-block; height: 10px; width: 10px; }
.legend-dot.is-idle { background: #16885d; }
.legend-dot.is-active { background: #1774c9; }
.legend-dot.is-alert { background: #d4473f; }
:deep(.dispatch-vehicle-marker) { background: transparent; border: 0; }
:deep(.vehicle-marker-pin) { align-items: center; background: #74827b; border: 3px solid #fff; border-radius: 50% 50% 50% 8px; box-shadow: 0 6px 14px #17201c40; color: #fff; display: flex; font-size: 12px; font-weight: 900; height: 30px; justify-content: center; transform: rotate(-45deg); width: 30px; }
:deep(.vehicle-marker-pin > span) { transform: rotate(45deg); }
:deep(.dispatch-vehicle-marker.is-idle .vehicle-marker-pin) { background: #16885d; }
:deep(.dispatch-vehicle-marker.is-active .vehicle-marker-pin) { background: #1774c9; }
:deep(.dispatch-vehicle-marker.is-alert .vehicle-marker-pin) { background: #d4473f; }
:deep(.dispatch-vehicle-popup) { min-width: 180px; }
:deep(.dispatch-vehicle-popup strong) { color: #17201c; display: block; font-size: 15px; margin-bottom: 8px; }
:deep(.dispatch-vehicle-popup dl) { display: grid; gap: 5px; margin: 0; }
:deep(.dispatch-vehicle-popup dl div) { display: grid; gap: 8px; grid-template-columns: 56px 1fr; }
:deep(.dispatch-vehicle-popup dt) { color: #718078; }
:deep(.dispatch-vehicle-popup dd) { color: #263d33; font-weight: 800; margin: 0; }
@media (max-width: 700px) { .dispatch-map { min-height: 520px; }.fallback-stops { max-width: calc(100% - 36px); }.map-chain { display: none; } }
</style>
