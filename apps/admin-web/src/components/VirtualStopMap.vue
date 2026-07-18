<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as L from "leaflet";
import { toLeafletLatLng } from "../maps/coordinateTransform";
import { createTileMap } from "../maps/tileMapRuntime";
import type { TileMapHandle } from "../maps/tileMapTypes";
import type { VirtualStop } from "../api/types";

const props = defineProps<{ stops: VirtualStop[]; readonly: boolean }>();
const emit = defineEmits<{ pick: [longitude: number, latitude: number] }>();
const mapContainer = ref<HTMLElement>();
const mapReady = ref(false);
const mapWarning = ref("");
const tileMap = ref<TileMapHandle>();
let markers: L.Marker[] = [];
let unsubscribeClick: (() => void) | undefined;
let unsubscribeBaseLayerError: (() => void) | undefined;

onMounted(() => { void initialize(); });
onBeforeUnmount(() => {
  unsubscribeClick?.();
  unsubscribeBaseLayerError?.();
  clearMarkers();
  tileMap.value?.destroy();
});

watch(() => props.stops, () => renderStops(), { deep: true });

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
    renderStops();
  } catch {
    mapWarning.value = "开放底图暂不可用";
  }
}

function renderStops(): void {
  if (!tileMap.value) {
    return;
  }

  clearMarkers();
  markers = props.stops
    .filter((stop) => Number.isFinite(stop.longitude) && Number.isFinite(stop.latitude))
    .map((stop) => createStopMarker(stop));
  tileMap.value.fitLayers(markers);
}

function createStopMarker(stop: VirtualStop): L.Marker {
  const label = `${stop.name} · ${stop.enabled ? "已启用" : "未启用"}`;
  return L.marker(toLeafletLatLng({ longitude: stop.longitude!, latitude: stop.latitude! }), { title: label })
    .bindTooltip(label, { direction: "top" })
    .addTo(tileMap.value!.map);
}

function clearMarkers(): void {
  markers.forEach((marker) => marker.remove());
  markers = [];
}
</script>

<template>
  <section class="stop-map" aria-labelledby="virtual-stop-map-title">
    <header>
      <div><p class="section-kicker">MAP</p><h3 id="virtual-stop-map-title">虚拟站点地图</h3></div>
      <span>{{ stops.length }} 个站点</span>
    </header>
    <div class="map-stage">
      <div ref="mapContainer" class="tile-map-canvas" aria-label="虚拟站点地图，点击可选取站点坐标"></div>
      <div v-if="!mapReady" class="map-fallback"><strong>开放底图暂不可用</strong><span>仍可通过经纬度录入和筛选虚拟站点。</span></div>
    </div>
    <p v-if="mapWarning" class="map-warning">{{ mapWarning }}，仍可通过经纬度录入和筛选虚拟站点。</p>
  </section>
</template>

<style scoped>
.stop-map { border: 1px solid var(--line); background: var(--surface); padding: 18px; }
header { align-items: center; display: flex; justify-content: space-between; margin-bottom: 12px; }
.section-kicker { color: var(--accent); font-size: 12px; font-weight: 800; margin: 0 0 4px; }
h3 { font-size: 20px; margin: 0; }
header span { color: var(--ink-muted); font-weight: 700; }
.map-stage { border: 1px solid var(--line); height: 300px; position: relative; width: 100%; }
.tile-map-canvas { height: 100%; width: 100%; }
.map-fallback { background: var(--surface); color: var(--ink-muted); display: grid; gap: 6px; inset: 0; place-content: center; position: absolute; text-align: center; }
.map-warning { color: var(--warning, #805b00); font-size: 13px; font-weight: 700; margin: 10px 0 0; }
</style>
