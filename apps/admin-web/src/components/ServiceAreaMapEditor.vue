<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { loadAmap } from "../maps/amapLoader";
import type { AmapRuntime } from "../maps/amapTypes";
import type { ServiceAreaBoundaryDraft, ServiceAreaBoundaryView } from "../api/types";

const props = withDefaults(defineProps<{
  serviceArea?: ServiceAreaBoundaryView;
  readonly: boolean;
  amapEnabled: boolean;
  feedback?: string;
}>(), {
  serviceArea: undefined,
  feedback: ""
});

const emit = defineEmits<{
  "import-district": [keyword: string];
  "save-boundary": [draft: ServiceAreaBoundaryDraft];
  publish: [];
}>();

const mapContainer = ref<HTMLElement>();
const runtime = ref<AmapRuntime>();
const mapError = ref("");
const importKeyword = ref("甘肃省定西市通渭县");
const inputFormat = ref<"wkt" | "geoJson">("wkt");
const boundaryText = ref("");
let mapInstance: AmapMap | undefined;
let polygonOverlay: AmapPolygon | undefined;
let polygonEditor: AmapPolygonEditor | undefined;

const activeBoundary = computed(() => props.serviceArea?.draftBoundaryWkt || props.serviceArea?.boundaryWkt || "");
const boundaryVersion = computed(() => {
  if (props.serviceArea?.draftBoundaryWkt) {
    return `草稿 v${props.serviceArea.draftBoundaryVersion}`;
  }
  if (props.serviceArea?.publishedAt) {
    return `已发布 v${props.serviceArea.boundaryVersion}`;
  }
  return "未发布";
});
const coordinateSystem = computed(() => props.serviceArea?.coordinateSystem === "GCJ02" ? "GCJ-02" : props.serviceArea?.coordinateSystem ?? "GCJ-02");
const mapAvailable = computed(() => props.amapEnabled && runtime.value?.enabled === true && runtime.value.AMap !== undefined);

watch(activeBoundary, (value) => {
  if (!boundaryText.value || value) {
    boundaryText.value = value;
  }
}, { immediate: true });

onMounted(async () => {
  if (!props.amapEnabled) {
    return;
  }

  runtime.value = await loadAmap();
  await nextTick();
  if (!runtime.value.enabled || !runtime.value.AMap || !mapContainer.value) {
    return;
  }

  try {
    const AMap = runtime.value.AMap as AmapApi;
    mapInstance = new AMap.Map(mapContainer.value, {
      zoom: 11,
      center: [105.24, 35.21],
      viewMode: "2D"
    });
    renderWktBoundary();
  } catch {
    mapError.value = "高德地图初始化失败，可继续使用边界文本录入。";
  }
});

onBeforeUnmount(() => {
  polygonEditor?.close?.();
  mapInstance?.destroy?.();
});

watch(boundaryText, () => {
  if (inputFormat.value === "wkt") {
    renderWktBoundary();
  }
});

function startDrawing(): void {
  const AMap = runtime.value?.AMap as AmapApi | undefined;
  if (!AMap || !mapInstance) {
    mapError.value = "地图绘制工具不可用，可继续粘贴 WKT 或 GeoJSON 草稿。";
    return;
  }

  polygonEditor?.close?.();
  const mouseTool = new AMap.MouseTool(mapInstance);
  mouseTool.on("draw", (event: { obj: AmapPolygon }) => {
    polygonOverlay?.setMap?.(null);
    polygonOverlay = event.obj;
    boundaryText.value = toWkt(event.obj.getPath());
    inputFormat.value = "wkt";
    mouseTool.close?.();
    mapError.value = "";
  });
  mouseTool.polygon({
    strokeColor: "#007a5e",
    strokeWeight: 3,
    fillColor: "#8bd6bc",
    fillOpacity: 0.26
  });
}

function startEditing(): void {
  const AMap = runtime.value?.AMap as AmapApi | undefined;
  if (!AMap || !mapInstance || !polygonOverlay) {
    mapError.value = "请先绘制或录入一个 WKT 边界后再编辑。";
    return;
  }

  polygonEditor?.close?.();
  polygonEditor = new AMap.PolygonEditor(mapInstance, polygonOverlay);
  polygonEditor.on("adjust", () => {
    if (polygonOverlay) {
      boundaryText.value = toWkt(polygonOverlay.getPath());
      inputFormat.value = "wkt";
    }
  });
  polygonEditor.open();
  mapError.value = "";
}

function renderWktBoundary(): void {
  const AMap = runtime.value?.AMap as AmapApi | undefined;
  const coordinates = parseWkt(boundaryText.value);
  if (!AMap || !mapInstance || coordinates.length < 3) {
    return;
  }

  polygonEditor?.close?.();
  polygonOverlay?.setMap?.(null);
  polygonOverlay = new AMap.Polygon({
    map: mapInstance,
    path: coordinates,
    strokeColor: "#007a5e",
    strokeWeight: 3,
    fillColor: "#8bd6bc",
    fillOpacity: 0.26
  });
}

function parseWkt(value: string): AmapCoordinate[] {
  const match = value.trim().match(/^POLYGON\s*\(\((.+)\)\)$/i);
  if (!match) {
    return [];
  }
  return match[1]
    .split(",")
    .map((pair) => pair.trim().split(/\s+/).map(Number))
    .filter(([longitude, latitude]) => Number.isFinite(longitude) && Number.isFinite(latitude))
    .map(([longitude, latitude]) => [longitude, latitude] as AmapCoordinate);
}

function toWkt(path: AmapPoint[]): string {
  const coordinates = path.map((point) => [point.lng, point.lat] as AmapCoordinate);
  const first = coordinates[0];
  const last = coordinates[coordinates.length - 1];
  if (first && last && (first[0] !== last[0] || first[1] !== last[1])) {
    coordinates.push(first);
  }
  return `POLYGON((${coordinates.map(([longitude, latitude]) => `${longitude} ${latitude}`).join(", ")}))`;
}

function importDistrict(): void {
  if (importKeyword.value.trim()) {
    emit("import-district", importKeyword.value.trim());
  }
}

function saveBoundary(): void {
  const value = boundaryText.value.trim();
  if (!value) {
    mapError.value = "请先录入服务区边界。";
    return;
  }
  mapError.value = "";
  emit("save-boundary", inputFormat.value === "wkt" ? { boundaryWkt: value } : { geoJson: value });
}

function requestPublish(): void {
  if (window.confirm("发布后，订单录入将按该服务区边界校验起终点。确认发布并启用吗？")) {
    emit("publish");
  }
}
</script>

<template>
  <section class="service-area-editor" aria-labelledby="service-area-editor-title">
    <header class="editor-header">
      <div>
        <p class="section-kicker">GEOFENCE</p>
        <h3 id="service-area-editor-title">服务区电子围栏</h3>
        <p>{{ serviceArea?.name ?? "尚未选择服务区" }} · {{ coordinateSystem }} · {{ boundaryVersion }}</p>
      </div>
      <span class="area-status" :class="serviceArea?.publishedAt ? 'enabled' : 'draft'">{{ serviceArea?.publishedAt ? "已发布" : "草稿待发布" }}</span>
    </header>

    <div class="editor-grid">
      <div class="map-shell">
        <div v-if="mapAvailable" ref="mapContainer" class="amap-canvas" aria-label="服务区电子围栏地图"></div>
        <div v-else class="map-fallback">
          <strong>地图不可用，可粘贴边界数据或稍后配置高德 Key。</strong>
          <span>当前可继续导入行政区、保存草稿和发布围栏。</span>
        </div>
        <div class="map-meta">
          <span>坐标系：{{ coordinateSystem }}</span>
          <div v-if="mapAvailable" class="map-actions">
            <button type="button" class="secondary-button" :disabled="readonly" @click="startDrawing">开始绘制</button>
            <button type="button" class="secondary-button" :disabled="readonly" @click="startEditing">编辑边界</button>
          </div>
        </div>
      </div>

      <div class="editor-controls">
        <label>
          行政区检索
          <div class="inline-field">
            <input v-model="importKeyword" :disabled="readonly" aria-label="行政区检索" />
            <button type="button" class="secondary-button" :disabled="readonly" @click="importDistrict">导入通渭县边界</button>
          </div>
        </label>

        <label>
          边界格式
          <select v-model="inputFormat" :disabled="readonly" aria-label="边界格式">
            <option value="wkt">WKT</option>
            <option value="geoJson">GeoJSON</option>
          </select>
        </label>

        <label>
          服务区边界草稿
          <textarea v-model="boundaryText" :disabled="readonly" rows="7" aria-label="服务区边界草稿" placeholder="POLYGON((lng lat, lng lat, ...))"></textarea>
        </label>

        <p v-if="mapError" class="editor-message error">{{ mapError }}</p>
        <p v-else-if="feedback" class="editor-message success">{{ feedback }}</p>

        <div class="editor-actions">
          <button type="button" class="secondary-button" :disabled="readonly" @click="saveBoundary">保存草稿</button>
          <button type="button" class="primary-button" :disabled="readonly || !serviceArea?.draftBoundaryWkt" @click="requestPublish">发布并启用</button>
        </div>
        <p class="publish-note">发布后，订单录入将按该边界校验起终点；围栏外位置仍会保留告警记录。</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.service-area-editor { border: 1px solid #cbd8d2; background: #f8faf8; padding: 20px; }
.editor-header { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; margin-bottom: 16px; }
.section-kicker { margin: 0 0 4px; color: #477065; font-size: 12px; font-weight: 700; }
h3 { margin: 0; font-size: 22px; }
.editor-header p:not(.section-kicker) { margin: 6px 0 0; color: #54665f; }
.area-status { border-radius: 999px; padding: 4px 9px; font-size: 13px; font-weight: 700; white-space: nowrap; }
.area-status.enabled { background: #dff2e9; color: #006a4e; }
.area-status.draft { background: #fff0c7; color: #805b00; }
.editor-grid { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(300px, .9fr); gap: 16px; }
.map-shell { min-height: 360px; border: 1px solid #cbd8d2; background: #edf4f0; display: grid; grid-template-rows: 1fr auto; }
.amap-canvas { min-height: 310px; }
.map-fallback { display: grid; place-content: center; gap: 8px; min-height: 310px; padding: 28px; color: #40574e; text-align: center; }
.map-fallback span { font-size: 14px; }
.map-meta { display: flex; align-items: center; justify-content: space-between; gap: 12px; border-top: 1px solid #cbd8d2; background: #f8faf8; padding: 10px 12px; color: #40574e; font-size: 13px; }
.map-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.editor-controls { display: grid; align-content: start; gap: 14px; }
label { display: grid; gap: 6px; color: #263a32; font-size: 14px; font-weight: 700; }
input, select, textarea { box-sizing: border-box; width: 100%; border: 1px solid #b9cac2; background: #fff; color: #14231d; font: inherit; padding: 9px 10px; }
textarea { resize: vertical; line-height: 1.45; }
.inline-field { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
.editor-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.editor-message { margin: 0; font-size: 14px; font-weight: 700; }
.editor-message.success { color: #00745a; }
.editor-message.error { color: #ad2a2a; }
.publish-note { margin: 0; border-left: 3px solid #d59a00; padding-left: 10px; color: #65521f; font-size: 13px; line-height: 1.5; }
@media (max-width: 900px) { .editor-grid { grid-template-columns: 1fr; } .map-shell { min-height: 280px; } .map-fallback, .amap-canvas { min-height: 230px; } }
@media (max-width: 560px) { .editor-header, .map-meta { align-items: stretch; flex-direction: column; } .inline-field { grid-template-columns: 1fr; } }
</style>

<script lang="ts">
type AmapCoordinate = [number, number];

interface AmapPoint {
  lng: number;
  lat: number;
}

interface AmapMap {
  destroy?: () => void;
}

interface AmapPolygon {
  getPath: () => AmapPoint[];
  setMap?: (map: AmapMap | null) => void;
}

interface AmapMouseTool {
  on: (event: "draw", listener: (event: { obj: AmapPolygon }) => void) => void;
  polygon: (options: Record<string, unknown>) => void;
  close?: () => void;
}

interface AmapPolygonEditor {
  on: (event: "adjust", listener: () => void) => void;
  open: () => void;
  close?: () => void;
}

interface AmapApi {
  Map: new (container: HTMLElement, options: Record<string, unknown>) => AmapMap;
  Polygon: new (options: Record<string, unknown>) => AmapPolygon;
  MouseTool: new (map: AmapMap) => AmapMouseTool;
  PolygonEditor: new (map: AmapMap, polygon: AmapPolygon) => AmapPolygonEditor;
}
</script>
