<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { loadAmap } from "../maps/amapLoader";
import type { VirtualStop } from "../api/types";

const props = defineProps<{ stops: VirtualStop[]; amapEnabled: boolean; readonly: boolean }>();
const emit = defineEmits<{ pick: [longitude: number, latitude: number] }>();
const container = ref<HTMLElement>();
const available = ref(false);
const feedback = ref("");
let map: AmapMap | undefined;
let markers: AmapMarker[] = [];

onMounted(() => { void initialize(); });
onBeforeUnmount(() => map?.destroy?.());
watch(() => props.stops, () => renderStops(), { deep: true });

async function initialize(): Promise<void> {
  if (!props.amapEnabled) return;
  await nextTick();
  try {
    const runtime = await loadAmap();
    if (!runtime.enabled || !runtime.AMap || !container.value) throw new Error("地图不可用");
    const AMap = runtime.AMap as AmapApi;
    map = new AMap.Map(container.value, { zoom: 13, center: [105.2421, 35.2103] });
    map.on("click", (event) => {
      if (!props.readonly) emit("pick", event.lnglat.lng, event.lnglat.lat);
    });
    available.value = true;
    renderStops();
  } catch {
    feedback.value = "高德地图暂不可用，仍可通过经纬度录入和筛选虚拟站点。";
  }
}

function renderStops(): void {
  const AMap = (window.AMap as AmapApi | undefined);
  if (!AMap || !map) return;
  const activeMap = map;
  markers.forEach((marker) => marker.setMap?.(null));
  markers = props.stops.filter((stop) => Number.isFinite(stop.longitude) && Number.isFinite(stop.latitude)).map((stop) => new AMap.Marker({
    map: activeMap,
    position: [stop.longitude!, stop.latitude!],
    title: `${stop.name} ${stop.enabled ? "已启用" : "未启用"}`,
    offset: [0, -10]
  }));
}
</script>

<template>
  <section class="stop-map" aria-labelledby="virtual-stop-map-title">
    <header><div><p class="section-kicker">MAP</p><h3 id="virtual-stop-map-title">虚拟站点地图</h3></div><span>{{ stops.length }} 个站点</span></header>
    <div v-show="available" ref="container" class="amap-canvas" aria-label="虚拟站点地图，点击可选取站点坐标"></div>
    <div v-if="!available" class="map-fallback"><strong>地图不可用</strong><span>{{ feedback || "配置高德地图 Key 后可在此显示站点并点击取点。" }}</span></div>
  </section>
</template>

<style scoped>
.stop-map { border: 1px solid var(--line); background: var(--surface); padding: 18px; }
header { align-items: center; display: flex; justify-content: space-between; margin-bottom: 12px; }
.section-kicker { color: var(--accent); font-size: 12px; font-weight: 800; margin: 0 0 4px; }
h3 { font-size: 20px; margin: 0; }
header span { color: var(--ink-muted); font-weight: 700; }
.amap-canvas, .map-fallback { border: 1px solid var(--line); height: 300px; width: 100%; }
.map-fallback { color: var(--ink-muted); display: grid; gap: 6px; place-content: center; text-align: center; }
</style>

<script lang="ts">
type AmapCoordinate = [number, number];
interface AmapMap { on(event: "click", handler: (event: { lnglat: { lng: number; lat: number } }) => void): void; destroy?(): void; }
interface AmapMarker { setMap?(map: AmapMap | null): void; }
interface AmapApi {
  Map: new (container: HTMLElement, options: { zoom: number; center: AmapCoordinate }) => AmapMap;
  Marker: new (options: { map: AmapMap; position: AmapCoordinate; title: string; offset: AmapCoordinate }) => AmapMarker;
}
</script>
