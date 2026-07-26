<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as L from "leaflet";
import { toLeafletLatLng } from "../maps/coordinateTransform";
import { createTileMap } from "../maps/tileMapRuntime";
import type { TileMapHandle } from "../maps/tileMapTypes";
import type { ServiceAreaBoundaryView, VirtualStop } from "../api/types";

const props = defineProps<{ stops: VirtualStop[]; readonly: boolean; serviceArea?: ServiceAreaBoundaryView }>();
const emit = defineEmits<{ pick: [longitude: number, latitude: number] }>();
const mapContainer = ref<HTMLElement>();
const mapReady = ref(false);
const mapWarning = ref("");
const hasBoundary = ref(false);
const tileMap = ref<TileMapHandle>();
let markers: L.Marker[] = [];
let boundaryLayer: L.Polygon | undefined;
let unsubscribeClick: (() => void) | undefined;
let unsubscribeBaseLayerError: (() => void) | undefined;

const enabledStopCount = computed(() => props.stops.filter((stop) => stop.enabled).length);
const disabledStopCount = computed(() => props.stops.length - enabledStopCount.value);
const activeBoundaryWkt = computed(() => {
  const area = props.serviceArea;
  if (!area) {
    return undefined;
  }

  if (area.draftBoundaryWkt && area.draftBoundaryVersion > area.boundaryVersion) {
    return area.draftBoundaryWkt;
  }

  return area.boundaryWkt ?? area.draftBoundaryWkt;
});
const serviceAreaName = computed(() => props.serviceArea?.name ?? "未选择服务区");

onMounted(() => { void initialize(); });
onBeforeUnmount(() => {
  unsubscribeClick?.();
  unsubscribeBaseLayerError?.();
  clearMapLayers();
  tileMap.value?.destroy();
});

watch([() => props.stops, () => props.serviceArea], () => renderMapLayers(), { deep: true });

async function initialize(): Promise<void> {
  await nextTick();
  if (!mapContainer.value) {
    return;
  }

  try {
    tileMap.value = createTileMap(mapContainer.value, { longitude: 105.2421, latitude: 35.2103 }, 13);
    unsubscribeClick = tileMap.value.onClick((point) => {
      if (!props.readonly) {
        emit("pick", point.longitude, point.latitude);
      }
    });
    unsubscribeBaseLayerError = tileMap.value.onBaseLayerError(() => {
      mapWarning.value = "开放底图暂不可用";
    });
    mapReady.value = true;
    renderMapLayers();
  } catch {
    mapWarning.value = "开放底图暂不可用";
  }
}

function renderMapLayers(): void {
  if (!tileMap.value) {
    return;
  }

  clearMapLayers();
  const layers: L.Layer[] = [];
  boundaryLayer = createServiceAreaBoundary();
  if (boundaryLayer) {
    layers.push(boundaryLayer);
  }
  markers = props.stops
    .filter((stop) => Number.isFinite(stop.longitude) && Number.isFinite(stop.latitude))
    .map((stop) => createStopMarker(stop));
  layers.push(...markers);
  tileMap.value.fitLayers(layers);
}

function createServiceAreaBoundary(): L.Polygon | undefined {
  hasBoundary.value = false;
  const boundaryWkt = activeBoundaryWkt.value;
  if (!boundaryWkt) {
    return undefined;
  }

  const coordinates = parsePolygonWkt(boundaryWkt);
  if (coordinates.length < 3) {
    mapWarning.value = "服务区边界格式无法在地图上展示";
    return undefined;
  }

  hasBoundary.value = true;
  return L.polygon(coordinates.map(([longitude, latitude]) => toLeafletLatLng({ longitude, latitude })), {
    color: "#007f67",
    fillColor: "#007f67",
    fillOpacity: 0.12,
    weight: 3
  }).bindTooltip(`${serviceAreaName.value} · 服务区边界`, { sticky: true }).addTo(tileMap.value!.map);
}

function parsePolygonWkt(wkt: string): Array<[number, number]> {
  const match = wkt.trim().match(/^POLYGON\s*\(\(\s*(.*?)\s*\)\)$/i);
  if (!match?.[1]) {
    return [];
  }

  return match[1].split(",").flatMap((pair): Array<[number, number]> => {
    const [longitude, latitude] = pair.trim().split(/\s+/).map(Number);
    return Number.isFinite(longitude) && Number.isFinite(latitude) ? [[longitude, latitude]] : [];
  });
}

function focusServiceArea(): void {
  if (boundaryLayer && tileMap.value) {
    tileMap.value.fitLayers([boundaryLayer]);
  }
}

function createStopMarker(stop: VirtualStop): L.Marker {
  const label = `${stop.name} · ${stop.enabled ? "已启用" : "未启用"}`;
  return L.marker(toLeafletLatLng({ longitude: stop.longitude!, latitude: stop.latitude! }), { title: label })
    .bindTooltip(label, { direction: "top" })
    .addTo(tileMap.value!.map);
}

function clearMapLayers(): void {
  markers.forEach((marker) => marker.remove());
  markers = [];
  boundaryLayer?.remove();
  boundaryLayer = undefined;
  hasBoundary.value = false;
}
</script>

<template>
  <section class="stop-map" aria-labelledby="virtual-stop-map-title">
    <header>
      <div><p class="section-kicker">MAP</p><h3 id="virtual-stop-map-title">虚拟站点地图</h3></div>
      <div class="map-header-actions">
        <span>{{ stops.length }} 个站点</span>
        <button class="secondary-button" type="button" :disabled="!mapReady || !hasBoundary" @click="focusServiceArea">聚焦服务区</button>
      </div>
    </header>
    <div class="map-stage">
      <div ref="mapContainer" class="tile-map-canvas" aria-label="虚拟站点地图，点击可选取站点坐标"></div>
      <div v-if="!mapReady" class="map-fallback"><strong>开放底图暂不可用</strong><span>仍可通过经纬度录入和筛选虚拟站点。</span></div>
    </div>
    <div class="map-summary" aria-label="地图图例与摘要">
      <span><i class="legend-swatch boundary-swatch"></i>服务区边界：{{ serviceAreaName }}</span>
      <span><i class="legend-swatch stop-swatch"></i>站点：{{ stops.length }} 个（已启用 {{ enabledStopCount }}，未启用 {{ disabledStopCount }}）</span>
    </div>
    <p v-if="mapWarning" class="map-warning">{{ mapWarning }}，仍可通过经纬度录入和筛选虚拟站点。</p>
  </section>
</template>

<style scoped>
.stop-map { border: 1px solid var(--line); background: var(--surface); padding: 18px; }
header { align-items: center; display: flex; justify-content: space-between; margin-bottom: 12px; }
.map-header-actions { align-items: center; display: flex; gap: 12px; }
.section-kicker { color: var(--accent); font-size: 12px; font-weight: 800; margin: 0 0 4px; }
h3 { font-size: 20px; margin: 0; }
header span { color: var(--ink-muted); font-weight: 700; }
.map-stage { border: 1px solid var(--line); height: 300px; position: relative; width: 100%; }
.tile-map-canvas { height: 100%; width: 100%; }
.map-summary { align-items: center; color: var(--ink-muted); display: flex; flex-wrap: wrap; font-size: 13px; gap: 18px; margin-top: 10px; }
.map-summary span { align-items: center; display: inline-flex; gap: 6px; }
.legend-swatch { border: 2px solid; display: inline-block; height: 10px; width: 18px; }
.boundary-swatch { background: rgba(0, 127, 103, 0.12); border-color: #007f67; }
.stop-swatch { background: #1687c8; border-color: #1687c8; border-radius: 50%; height: 10px; width: 10px; }
.map-fallback { background: var(--surface); color: var(--ink-muted); display: grid; gap: 6px; inset: 0; place-content: center; position: absolute; text-align: center; }
.map-warning { color: var(--warning, #805b00); font-size: 13px; font-weight: 700; margin: 10px 0 0; }
@media (max-width: 640px) { header { align-items: flex-start; gap: 10px; }.map-header-actions { align-items: flex-end; flex-direction: column; gap: 6px; }.map-summary { align-items: flex-start; flex-direction: column; gap: 8px; } }
</style>
