<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { loadAmap } from "../maps/amapLoader";
import type { ServiceArea, VehicleLocationEventView, VehicleLocationSnapshotItem, VehicleTask, VirtualStop } from "../api/types";

const props = withDefaults(defineProps<{
  serviceArea?: ServiceArea;
  stops?: VirtualStop[];
  locations?: VehicleLocationSnapshotItem[];
  eventChain?: VehicleLocationEventView[];
  selectedTask?: VehicleTask;
  amapEnabled?: boolean;
}>(), {
  serviceArea: undefined,
  stops: () => [],
  locations: () => [],
  eventChain: () => [],
  selectedTask: undefined,
  amapEnabled: false
});

const mapContainer = ref<HTMLDivElement>();
const mapAvailable = ref(false);
const mapFeedback = ref("");
const layers = ref({ serviceArea: true, stops: true, route: true, locations: true });
let map: AmapMap | undefined;
let AMap: AmapApi | undefined;
let overlays: AmapOverlay[] = [];

const orderedChain = computed(() => [...props.eventChain].sort((left, right) => new Date(left.driverReportedAt).getTime() - new Date(right.driverReportedAt).getTime()));
const visibleStops = computed(() => props.stops.filter((stop) => Number.isFinite(stop.longitude) && Number.isFinite(stop.latitude)));
const selectedTaskStops = computed(() => {
  if (!props.selectedTask) return [];
  const stopsById = new Map(props.stops.map((stop) => [stop.id, stop]));
  return [...props.selectedTask.stops]
    .sort((left, right) => left.sequenceNumber - right.sequenceNumber)
    .map((taskStop) => stopsById.get(taskStop.virtualStopId))
    .filter((stop): stop is VirtualStop => stop !== undefined && Number.isFinite(stop.longitude) && Number.isFinite(stop.latitude));
});
const chainPoints = computed(() => orderedChain.value
  .map((event) => [Number(event.longitude), Number(event.latitude)] as AmapCoordinate)
  .filter(([longitude, latitude]) => Number.isFinite(longitude) && Number.isFinite(latitude)));

onMounted(() => { void initializeMap(); });
onBeforeUnmount(() => {
  clearOverlays();
  map?.destroy?.();
});
watch([() => props.serviceArea, () => props.stops, () => props.locations, () => props.eventChain, () => props.selectedTask, layers], () => renderAmap(), { deep: true });

async function initializeMap(): Promise<void> {
  if (!props.amapEnabled) {
    mapFeedback.value = "高德地图未启用，当前显示静态运营摘要。";
    return;
  }
  await nextTick();
  try {
    const runtime = await loadAmap();
    if (!runtime.enabled || !runtime.AMap || !mapContainer.value) throw new Error("地图不可用");
    AMap = runtime.AMap as AmapApi;
    map = new AMap.Map(mapContainer.value, { zoom: 12, center: [105.2421, 35.2103], viewMode: "2D" });
    mapAvailable.value = true;
    renderAmap();
  } catch {
    mapFeedback.value = "高德地图暂不可用，当前显示静态运营摘要。";
  }
}

function renderAmap(): void {
  if (!map || !AMap) return;
  const amap = AMap;
  const activeMap = map;
  clearOverlays();
  const fitTargets: AmapOverlay[] = [];
  if (layers.value.serviceArea && props.serviceArea?.boundary) {
    const path = parsePolygon(props.serviceArea.boundary);
    if (path.length >= 3) {
      const polygon = new amap.Polygon({ map: activeMap, path, strokeColor: "#007a5e", strokeWeight: 3, fillColor: "#8bd6bc", fillOpacity: 0.22 });
      overlays.push(polygon); fitTargets.push(polygon);
    }
  }
  if (layers.value.stops) {
    visibleStops.value.forEach((stop) => {
      const marker = new amap.Marker({ map: activeMap, position: [stop.longitude!, stop.latitude!], title: `${stop.name} · ${stop.enabled ? "已启用" : "未启用"}`, content: `<span class="drt-amap-stop ${stop.enabled ? "enabled" : "disabled"}">站</span>` });
      overlays.push(marker); fitTargets.push(marker);
    });
  }
  if (layers.value.locations) {
    props.locations.forEach((item) => {
      const marker = new amap.Marker({ map: activeMap, position: [Number(item.latestLocation.longitude), Number(item.latestLocation.latitude)], title: `${item.plateNumber} · 人工上报`, content: "<span class=\"drt-amap-vehicle\">车</span>" });
      overlays.push(marker); fitTargets.push(marker);
    });
  }
  if (layers.value.route) {
    const route = selectedTaskStops.value.map((stop) => [stop.longitude!, stop.latitude!] as AmapCoordinate);
    if (route.length > 1) {
      const line = new amap.Polyline({ map: activeMap, path: route, strokeColor: "#17634b", strokeWeight: 4, strokeStyle: "dashed", strokeOpacity: 0.86 });
      overlays.push(line); fitTargets.push(line);
    }
    if (chainPoints.value.length > 1) {
      const chain = new amap.Polyline({ map: activeMap, path: chainPoints.value, strokeColor: "#496b5e", strokeWeight: 3, strokeStyle: "dashed", strokeOpacity: 0.8 });
      overlays.push(chain); fitTargets.push(chain);
    }
  }
  if (fitTargets.length) activeMap.setFitView?.(fitTargets, false, [50, 50, 50, 50]);
}

function clearOverlays(): void {
  overlays.forEach((overlay) => overlay.setMap?.(null));
  overlays = [];
}

function parsePolygon(value: string): AmapCoordinate[] {
  const match = value.trim().match(/^POLYGON\s*\(\((.+)\)\)$/i);
  if (!match) return [];
  return match[1].split(",").map((pair) => pair.trim().split(/\s+/).map(Number))
    .filter(([longitude, latitude]) => Number.isFinite(longitude) && Number.isFinite(latitude))
    .map(([longitude, latitude]) => [longitude, latitude] as AmapCoordinate);
}

function statusLabel(status: string): string {
  return ({ IN_SERVICE: "执行中", DISPATCHED: "已派单", IDLE: "空闲", OFFLINE: "离线" } as Record<string, string>)[status] ?? status;
}

function eventLabel(eventType: string): string {
  return ({ TASK_STARTED: "发车", TASK_STOP_ARRIVED: "到站", PASSENGER_BOARDED: "上车", PASSENGER_ALIGHTED: "下车", TASK_COMPLETED: "完成", MANUAL_CORRECTION: "人工补报" } as Record<string, string>)[eventType] ?? eventType;
}

function formatDateTime(value?: string): string {
  if (!value) return "--";
  return new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(value));
}

function markerStyle(index: number): Record<string, string> {
  const positions = [{ left: "48%", top: "43%" }, { left: "58%", top: "36%" }, { left: "38%", top: "54%" }, { left: "66%", top: "52%" }];
  return positions[index % positions.length];
}
</script>

<template>
  <section class="dispatch-map" :class="{ 'map-degraded': !mapAvailable }" aria-label="调度地图">
    <div ref="mapContainer" class="map-canvas"></div>
    <div v-if="!mapAvailable" class="fallback-surface"><strong>{{ mapFeedback || "地图加载中" }}</strong><span>服务区、虚拟站点、任务与人工位置仍可在下方图层摘要中核对。</span></div>
    <div class="map-controls" aria-label="地图图层控制">
      <label><input v-model="layers.serviceArea" type="checkbox" aria-label="服务区图层" />服务区</label>
      <label><input v-model="layers.stops" type="checkbox" aria-label="虚拟站点图层" />虚拟站点</label>
      <label><input v-model="layers.route" type="checkbox" aria-label="任务路线图层" />任务路线</label>
      <label><input v-model="layers.locations" type="checkbox" aria-label="车辆位置图层" />车辆位置</label>
    </div>
    <div v-if="!mapAvailable && layers.serviceArea && serviceArea" class="map-overlay service-area"><strong>{{ serviceArea.name }}</strong><span>已发布服务区</span></div>
    <div v-if="layers.route && selectedTaskStops.length > 1" class="map-route"><strong>任务 {{ selectedTask?.id.slice(0, 8) }}</strong><span>{{ selectedTaskStops.length }} 个站点连接</span></div>
    <div v-if="!mapAvailable && layers.stops" class="fallback-stops" aria-label="虚拟站点图层内容"><span v-for="stop in visibleStops" :key="stop.id" class="stop-chip" :class="{ disabled: !stop.enabled }">{{ stop.name }}</span></div>
    <article v-for="(item, index) in locations" v-show="layers.locations" :key="item.vehicleId" class="vehicle-location-card" :style="markerStyle(index)" :aria-label="`车辆位置 ${item.plateNumber}`"><div class="vehicle-dot">车</div><div><strong>{{ item.plateNumber }}</strong><span>{{ statusLabel(item.currentStatus) }}</span><small>任务 {{ item.latestLocation.vehicleTaskId ?? "--" }}</small><small>最后反馈 {{ formatDateTime(item.latestLocation.driverReportedAt) }}</small><small>{{ item.latestLocation.standardizedAddress }}</small></div><em>人工上报</em></article>
    <div v-if="layers.route" class="map-chain" aria-label="人工节点链"><p>离散节点链，仅表示人工上报节点，不是实际行驶轨迹</p><ol><li v-for="event in orderedChain" :key="event.id"><span>{{ eventLabel(event.eventType) }}</span><small>{{ formatDateTime(event.driverReportedAt) }}</small></li></ol></div>
    <div class="map-legend"><span>服务区</span><span>虚拟站点</span><span>任务路线</span><span>人工上报</span></div>
  </section>
</template>

<style scoped>
.dispatch-map { background: #eef2ef; border: 1px solid #d9e1dc; border-radius: 8px; min-height: 430px; overflow: hidden; position: relative; }.map-canvas, .fallback-surface { inset: 0; position: absolute; }.fallback-surface { background: #e5eee9; color: #365348; display: grid; gap: 7px; place-content: center; text-align: center; }.fallback-surface span { font-size: 13px; }.map-controls, .map-overlay, .map-route, .fallback-stops, .vehicle-location-card, .map-chain, .map-legend { position: absolute; z-index: 2; }.map-controls { display: flex; flex-wrap: wrap; gap: 8px; left: 14px; top: 14px; }.map-controls label { align-items: center; background: #fffc; border: 1px solid #d9e1dc; color: #40574e; display: flex; font-size: 12px; font-weight: 800; gap: 4px; padding: 5px 7px; }.map-overlay { background: #e1f2eaeb; border: 2px solid #43846d; bottom: 58px; color: #1b6049; display: grid; gap: 2px; left: 62px; padding: 10px; right: 54px; top: 62px; }.map-overlay span, .map-route span { font-size: 12px; }.map-route { background: #17634beb; bottom: 90px; color: #fff; display: grid; gap: 2px; left: 32%; padding: 8px 10px; top: 46%; }.fallback-stops { display: flex; flex-wrap: wrap; gap: 6px; left: 18px; max-width: 52%; top: 110px; }.stop-chip { background: #fff; border: 1px solid #17634b; color: #17634b; font-size: 12px; font-weight: 800; padding: 4px 7px; }.stop-chip.disabled { border-color: #9f2424; color: #9f2424; }.vehicle-location-card { background: #fffffff0; border: 1px solid #17634b33; box-shadow: 0 12px 24px #17201c24; display: grid; gap: 10px; grid-template-columns: 34px minmax(0, 1fr); max-width: 260px; min-width: 210px; padding: 10px; }.vehicle-dot { background: #17201c; color: #fff; display: grid; font-size: 12px; font-weight: 900; height: 32px; place-items: center; width: 32px; }.vehicle-location-card strong, .vehicle-location-card span, .vehicle-location-card small { display: block; overflow-wrap: anywhere; }.vehicle-location-card strong { color: #17201c; font-size: 14px; }.vehicle-location-card span, .vehicle-location-card small { color: #53615a; font-size: 12px; font-weight: 800; }.vehicle-location-card em { background: #dff4ed; color: #007a5d; font-size: 12px; font-style: normal; font-weight: 900; grid-column: 1 / -1; justify-self: start; padding: 3px 8px; }.map-chain { background: #fffffff0; border: 1px dashed #17634b76; bottom: 14px; left: 18px; max-width: 360px; padding: 9px 10px; }.map-chain p { color: #3f4c46; font-size: 12px; font-weight: 900; margin: 0 0 8px; }.map-chain ol { display: flex; flex-wrap: wrap; gap: 8px; list-style: none; margin: 0; padding: 0; }.map-chain li { border-left: 2px dashed #17634b; color: #53615a; font-size: 12px; font-weight: 800; padding-left: 8px; }.map-chain span, .map-chain small { display: block; }.map-legend { background: #fffffff0; bottom: 14px; color: #53615a; display: flex; flex-wrap: wrap; font-size: 12px; font-weight: 800; gap: 8px; max-width: 320px; padding: 8px 10px; right: 14px; }@media (max-width: 700px) { .vehicle-location-card { left: 18px !important; max-width: calc(100% - 36px); right: auto; top: 180px !important; }.fallback-stops { max-width: calc(100% - 36px); }.map-chain { display: none; } }
</style>

<script lang="ts">
type AmapCoordinate = [number, number];
interface AmapMap { destroy?(): void; setFitView?(overlays: AmapOverlay[], immediately?: boolean, padding?: [number, number, number, number]): void; }
interface AmapOverlay { setMap?(map: AmapMap | null): void; }
interface AmapApi { Map: new (container: HTMLElement, options: Record<string, unknown>) => AmapMap; Polygon: new (options: Record<string, unknown>) => AmapOverlay; Marker: new (options: Record<string, unknown>) => AmapOverlay; Polyline: new (options: Record<string, unknown>) => AmapOverlay; }
</script>
