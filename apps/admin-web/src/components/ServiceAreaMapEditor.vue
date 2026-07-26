<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import * as L from "leaflet";
import "@geoman-io/leaflet-geoman-free";
import { gcj02ToWgs84, wgs84ToGcj02 } from "../maps/coordinateTransform";
import { createTileMap } from "../maps/tileMapRuntime";
import type { TileMapHandle } from "../maps/tileMapTypes";
import type {
  CreateServiceAreaInput,
  DispatchRuleSet,
  ServiceAreaBoundaryDraft,
  ServiceAreaBoundaryView,
  VirtualStop
} from "../api/types";

const props = withDefaults(defineProps<{
  serviceArea?: ServiceAreaBoundaryView;
  stops?: VirtualStop[];
  ruleSets?: DispatchRuleSet[];
  readonly: boolean;
  busy?: boolean;
  feedback?: string;
}>(), {
  serviceArea: undefined,
  stops: () => [],
  ruleSets: () => [],
  busy: false,
  feedback: ""
});

const emit = defineEmits<{
  create: [input: CreateServiceAreaInput];
  "import-district": [keyword: string];
  "save-boundary": [draft: ServiceAreaBoundaryDraft];
  publish: [];
}>();

const mapContainer = ref<HTMLElement>();
const tileMap = ref<TileMapHandle>();
const mapReady = ref(false);
const mapError = ref("");
const mapWarning = ref("");
const importKeyword = ref("甘肃省定西市通渭县");
const inputFormat = ref<"wkt" | "geoJson">("wkt");
const boundaryText = ref("");
const isEditingBoundary = ref(false);
const referenceBufferMeters = ref(500);
const createForm = reactive({
  name: "通渭县试点服务区",
  serviceStart: "06:30",
  serviceEnd: "19:00",
  ruleSetId: ""
});
let polygonLayer: L.Polygon | undefined;
let stopLayers: L.CircleMarker[] = [];
let unsubscribeBaseLayerError: (() => void) | undefined;
let boundaryUpdatedByMap = false;

const hasPendingDraft = computed(() => {
  const serviceArea = props.serviceArea;
  return Boolean(serviceArea?.draftBoundaryWkt) && serviceArea!.draftBoundaryVersion > serviceArea!.boundaryVersion;
});
const activeBoundary = computed(() => hasPendingDraft.value
  ? props.serviceArea?.draftBoundaryWkt || ""
  : props.serviceArea?.boundaryWkt || props.serviceArea?.draftBoundaryWkt || "");
const hasUnsavedBoundaryChanges = computed(() =>
  Boolean(props.serviceArea) && isEditingBoundary.value && boundaryText.value.trim() !== activeBoundary.value.trim()
);
const boundaryVersion = computed(() => {
  const serviceArea = props.serviceArea;
  if (hasPendingDraft.value && serviceArea) {
    return `草稿 v${serviceArea.draftBoundaryVersion}`;
  }
  if (serviceArea?.publishedAt) {
    return `已发布 v${serviceArea.boundaryVersion}`;
  }
  return "未发布";
});
const coordinateSystem = computed(() => props.serviceArea?.coordinateSystem === "GCJ02" ? "GCJ-02" : props.serviceArea?.coordinateSystem ?? "GCJ-02");
const calibrationStops = computed(() => props.stops.filter((stop) => !props.serviceArea || stop.serviceAreaId === props.serviceArea.id));
const parsedActiveBoundary = computed(() => parseWkt(boundaryText.value));
const stopCoverage = computed(() => {
  const boundary = parsedActiveBoundary.value;
  const insideCount = boundary.length >= 3
    ? calibrationStops.value.filter((stop) => isPointInsidePolygon(stop.longitude, stop.latitude, boundary)).length
    : 0;
  return {
    total: calibrationStops.value.length,
    inside: insideCount,
    outside: calibrationStops.value.length - insideCount
  };
});
const canGenerateReferenceBoundary = computed(() => !props.readonly && calibrationStops.value.length >= 3);
const createDisabled = computed(() =>
  props.readonly ||
  !createForm.name.trim() ||
  !createForm.serviceStart ||
  !createForm.serviceEnd ||
  !createForm.ruleSetId ||
  !boundaryText.value.trim()
);

watch(
  () => props.ruleSets,
  (rules) => {
    if (!rules.some((rule) => rule.id === createForm.ruleSetId)) {
      createForm.ruleSetId = rules[0]?.id ?? "";
    }
  },
  { immediate: true }
);

watch(activeBoundary, (value) => {
  if (!boundaryText.value || value) {
    boundaryText.value = value;
  }
  finishBoundaryEdit();
}, { immediate: true });

watch(
  () => props.feedback,
  (feedback) => {
    if (feedback === "服务区草稿已保存。" || feedback === "服务区已发布并启用。") {
      finishBoundaryEdit();
    }
  }
);

watch(
  () => props.stops,
  () => {
    renderStopLayers();
    fitCalibrationLayers();
  },
  { deep: true }
);

watch(boundaryText, () => {
  if (inputFormat.value === "wkt" && !boundaryUpdatedByMap) {
    renderWktBoundary();
  }
  boundaryUpdatedByMap = false;
});

onMounted(async () => {
  await nextTick();
  if (!mapContainer.value) {
    return;
  }

  try {
    tileMap.value = createTileMap(mapContainer.value, { longitude: 105.24, latitude: 35.21 }, 11);
    tileMap.value.map.on("pm:create", updateBoundaryFromGeomanEvent);
    unsubscribeBaseLayerError = tileMap.value.onBaseLayerError(() => {
      mapWarning.value = "开放底图暂不可用";
    });
    mapReady.value = true;
    renderWktBoundary();
  } catch {
    mapWarning.value = "开放底图暂不可用";
  }
});

onBeforeUnmount(() => {
  if (tileMap.value) {
    tileMap.value.map.off("pm:create", updateBoundaryFromGeomanEvent);
  }
  clearStopLayers();
  unsubscribeBaseLayerError?.();
  tileMap.value?.destroy();
});

function startDrawing(): void {
  const mapPm = tileMap.value?.map.pm;
  if (!mapPm) {
    mapError.value = "地图绘制工具不可用，可继续粘贴 WKT 或 GeoJSON 草稿。";
    return;
  }
  if (!confirmDraftEdit()) {
    return;
  }

  polygonLayer?.pm?.disable();
  mapPm.enableDraw("Polygon", {
    snappable: true,
    allowSelfIntersection: false,
    templineStyle: { color: "#007a5e" },
    hintlineStyle: { color: "#007a5e" },
    pathOptions: polygonStyle()
  });
  isEditingBoundary.value = true;
  renderStopLayers();
  mapError.value = "";
}

function startEditing(): void {
  if (!polygonLayer?.pm) {
    mapError.value = "请先绘制或录入一个 WKT 边界后再编辑。";
    return;
  }
  if (!confirmDraftEdit()) {
    return;
  }

  polygonLayer.pm.enable({ allowSelfIntersection: false });
  isEditingBoundary.value = true;
  renderStopLayers();
  mapError.value = "";
}

function confirmDraftEdit(): boolean {
  if (!props.serviceArea?.publishedAt || hasPendingDraft.value) {
    return true;
  }
  return window.confirm("将基于当前已发布围栏创建下一版草稿。保存草稿和再次发布前，当前服务区边界不会变化。是否继续编辑？");
}

function cancelBoundaryEdit(): void {
  if (!props.serviceArea) {
    return;
  }

  finishBoundaryEdit();
  boundaryUpdatedByMap = true;
  inputFormat.value = "wkt";
  boundaryText.value = activeBoundary.value;
  renderWktBoundary();
  isEditingBoundary.value = false;
  mapError.value = "";
}

function finishBoundaryEdit(): void {
  const mapPm = tileMap.value?.map.pm;
  if (mapPm?.globalDrawModeEnabled?.()) {
    mapPm.disableDraw("Polygon");
  }
  polygonLayer?.pm?.disable();
  isEditingBoundary.value = false;
  renderStopLayers();
}

function updateBoundaryFromGeomanEvent(event: unknown): void {
  const layer = (event as { layer?: L.Polygon }).layer;
  if (!layer) {
    return;
  }

  if (layer !== polygonLayer) {
    clearPolygonLayer();
    polygonLayer = layer;
    bindPolygonChangeEvents(layer);
  }
  boundaryUpdatedByMap = true;
  boundaryText.value = toGcj02Wkt(layer);
  inputFormat.value = "wkt";
  mapError.value = "";
}

function renderWktBoundary(): void {
  const coordinates = parseWkt(boundaryText.value);
  if (!tileMap.value) {
    return;
  }

  clearPolygonLayer();
  if (coordinates.length < 3) {
    renderStopLayers();
    fitCalibrationLayers();
    return;
  }

  const leafletCoordinates = coordinates.map((coordinate) => {
    const wgs84 = gcj02ToWgs84(coordinate);
    return [wgs84.latitude, wgs84.longitude] as L.LatLngTuple;
  });
  polygonLayer = L.polygon(leafletCoordinates, polygonStyle()).addTo(tileMap.value.map);
  bindPolygonChangeEvents(polygonLayer);
  renderStopLayers();
  fitCalibrationLayers();
}

function clearPolygonLayer(): void {
  unbindPolygonChangeEvents(polygonLayer);
  polygonLayer?.pm?.disable();
  polygonLayer?.remove();
  polygonLayer = undefined;
}

function renderStopLayers(): void {
  if (!tileMap.value) {
    return;
  }

  clearStopLayers();
  const boundary = parsedActiveBoundary.value;
  stopLayers = calibrationStops.value
    .filter((stop) => Number.isFinite(stop.longitude) && Number.isFinite(stop.latitude))
    .map((stop) => {
      const inside = boundary.length >= 3 && isPointInsidePolygon(stop.longitude!, stop.latitude!, boundary);
      const wgs84 = gcj02ToWgs84({ longitude: stop.longitude!, latitude: stop.latitude! });
      const label = `${stop.name} · ${inside ? "服务区内" : "服务区外"}`;
      return L.circleMarker([wgs84.latitude, wgs84.longitude], {
        color: inside ? "#007f67" : "#c47a00",
        fillColor: inside ? "#007f67" : "#f1b95c",
        fillOpacity: 0.9,
        interactive: !isEditingBoundary.value,
        radius: inside ? 7 : 8,
        weight: 2
      }).bindTooltip(label, { direction: "top" }).addTo(tileMap.value!.map);
    });
}

function clearStopLayers(): void {
  stopLayers.forEach((layer) => layer.remove());
  stopLayers = [];
}

function fitCalibrationLayers(): void {
  if (!tileMap.value) {
    return;
  }

  const layers: L.Layer[] = [];
  if (polygonLayer) {
    layers.push(polygonLayer);
  }
  layers.push(...stopLayers);
  tileMap.value.fitLayers(layers);
}

function bindPolygonChangeEvents(layer: L.Polygon): void {
  layer.on("pm:change", updateBoundaryFromGeomanEvent);
  layer.on("pm:update", updateBoundaryFromGeomanEvent);
}

function unbindPolygonChangeEvents(layer: L.Polygon | undefined): void {
  layer?.off("pm:change", updateBoundaryFromGeomanEvent);
  layer?.off("pm:update", updateBoundaryFromGeomanEvent);
}

function parseWkt(value: string): Array<{ longitude: number; latitude: number }> {
  const match = value.trim().match(/^POLYGON\s*\(\((.+)\)\)$/i);
  if (!match) {
    return [];
  }
  return match[1]
    .split(",")
    .map((pair) => pair.trim().split(/\s+/).map(Number))
    .filter(([longitude, latitude]) => Number.isFinite(longitude) && Number.isFinite(latitude))
    .map(([longitude, latitude]) => ({ longitude, latitude }));
}

function isPointInsidePolygon(longitude: number | undefined, latitude: number | undefined, polygon: Array<{ longitude: number; latitude: number }>): boolean {
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) {
    return false;
  }

  const pointLongitude = longitude as number;
  const pointLatitude = latitude as number;
  let inside = false;
  for (let index = 0, previous = polygon.length - 1; index < polygon.length; previous = index++) {
    const currentPoint = polygon[index]!;
    const previousPoint = polygon[previous]!;
    const intersects = (currentPoint.latitude > pointLatitude) !== (previousPoint.latitude > pointLatitude)
      && pointLongitude < (previousPoint.longitude - currentPoint.longitude)
        * (pointLatitude - currentPoint.latitude)
        / (previousPoint.latitude - currentPoint.latitude)
        + currentPoint.longitude;
    if (intersects) {
      inside = !inside;
    }
  }
  return inside;
}

function generateReferenceBoundary(): void {
  const points = calibrationStops.value
    .filter((stop) => Number.isFinite(stop.longitude) && Number.isFinite(stop.latitude))
    .map((stop) => ({ longitude: stop.longitude!, latitude: stop.latitude! }));
  if (points.length < 3) {
    return;
  }

  const minLongitude = Math.min(...points.map((point) => point.longitude));
  const maxLongitude = Math.max(...points.map((point) => point.longitude));
  const minLatitude = Math.min(...points.map((point) => point.latitude));
  const maxLatitude = Math.max(...points.map((point) => point.latitude));
  const averageLatitude = (minLatitude + maxLatitude) / 2;
  const bufferMeters = Number.isFinite(referenceBufferMeters.value) && referenceBufferMeters.value >= 0 ? referenceBufferMeters.value : 0;
  const latitudeBuffer = bufferMeters / 111_320;
  const longitudeBuffer = bufferMeters / (111_320 * Math.max(Math.cos(averageLatitude * Math.PI / 180), 0.01));
  const west = minLongitude - longitudeBuffer;
  const east = maxLongitude + longitudeBuffer;
  const south = minLatitude - latitudeBuffer;
  const north = maxLatitude + latitudeBuffer;

  boundaryUpdatedByMap = true;
  inputFormat.value = "wkt";
  boundaryText.value = `POLYGON((${west} ${south}, ${east} ${south}, ${east} ${north}, ${west} ${north}, ${west} ${south}))`;
  isEditingBoundary.value = true;
  mapError.value = "";
  renderWktBoundary();
}

function toGcj02Wkt(layer: L.Polygon): string {
  const rings = layer.getLatLngs() as unknown as L.LatLng[][];
  const path = Array.isArray(rings[0]) ? rings[0] : [];
  const coordinates = path.map((point) => wgs84ToGcj02({ longitude: point.lng, latitude: point.lat }));
  const first = coordinates[0];
  const last = coordinates[coordinates.length - 1];
  if (first && last && (first.longitude !== last.longitude || first.latitude !== last.latitude)) {
    coordinates.push({ ...first });
  }
  return `POLYGON((${coordinates.map((point) => `${point.longitude} ${point.latitude}`).join(", ")}))`;
}

function polygonStyle(): L.PathOptions {
  return {
    color: "#007a5e",
    weight: 3,
    fillColor: "#8bd6bc",
    fillOpacity: 0.26
  };
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

function createServiceAreaDraft(): void {
  const boundaryWkt = inputFormat.value === "wkt" ? boundaryText.value.trim() : geoJsonToWkt(boundaryText.value);
  if (!boundaryWkt) {
    return;
  }

  mapError.value = "";
  emit("create", {
    name: createForm.name.trim(),
    boundaryWkt,
    serviceStart: withSeconds(createForm.serviceStart),
    serviceEnd: withSeconds(createForm.serviceEnd),
    ruleSetId: createForm.ruleSetId
  });
}

function geoJsonToWkt(value: string): string | undefined {
  try {
    const geoJson = JSON.parse(value) as { type?: unknown; coordinates?: unknown };
    const ring = Array.isArray(geoJson.coordinates) ? geoJson.coordinates[0] : undefined;
    if (geoJson.type !== "Polygon" || !Array.isArray(ring) || ring.length < 3) {
      throw new Error("invalid GeoJSON");
    }

    const coordinates = ring.map((point) => {
      if (!Array.isArray(point) || point.length !== 2 || !Number.isFinite(point[0]) || !Number.isFinite(point[1])) {
        throw new Error("invalid GeoJSON");
      }
      return [point[0], point[1]] as [number, number];
    });
    const first = coordinates[0];
    const last = coordinates[coordinates.length - 1];
    if (first[0] !== last[0] || first[1] !== last[1]) {
      coordinates.push([...first]);
    }
    return `POLYGON((${coordinates.map(([longitude, latitude]) => `${longitude} ${latitude}`).join(", ")}))`;
  } catch {
    mapError.value = "GeoJSON 必须是包含至少三个坐标点的 Polygon。";
    return undefined;
  }
}

function withSeconds(value: string): string {
  return value.length === 5 ? `${value}:00` : value;
}

function requestPublish(): void {
  if (window.confirm("发布后，订单录入将按该服务区边界校验起终点。确认发布并启用吗？")) {
    emit("publish");
  }
}
</script>

<template>
  <section class="service-area-editor" :class="{ 'service-area-editing': isEditingBoundary }" :aria-busy="busy" aria-labelledby="service-area-editor-title">
    <header class="editor-header">
      <div>
        <p class="section-kicker">GEOFENCE</p>
        <h3 id="service-area-editor-title">服务区电子围栏</h3>
        <p>{{ serviceArea?.name ?? "尚未选择服务区" }} · {{ coordinateSystem }} · {{ boundaryVersion }}</p>
      </div>
      <span class="area-status" :class="hasPendingDraft ? 'draft' : serviceArea?.publishedAt ? 'enabled' : 'draft'">{{ hasPendingDraft ? "草稿待发布" : serviceArea?.publishedAt ? "已发布" : "草稿待发布" }}</span>
    </header>

    <div class="editor-layout">
      <div class="editor-map-panel">
        <div class="map-shell">
          <div class="map-stage">
            <div ref="mapContainer" class="tile-map-canvas" aria-label="服务区电子围栏地图"></div>
            <div v-if="!mapReady" class="map-fallback">
              <strong>开放底图暂不可用</strong>
              <span>当前可继续导入行政区、录入边界文本、保存草稿和发布围栏。</span>
            </div>
          </div>
          <div class="map-meta">
            <span>坐标系：{{ coordinateSystem }}</span>
            <div v-if="mapReady" class="map-actions">
              <span v-if="isEditingBoundary" class="edit-mode-badge" role="status">正在编辑电子围栏：蓝色节点可拖动</span>
              <button type="button" class="secondary-button" :disabled="readonly" @click="startDrawing">开始绘制</button>
              <button type="button" class="secondary-button" :disabled="readonly" @click="startEditing">编辑边界</button>
              <button v-if="serviceArea && isEditingBoundary" type="button" class="secondary-button" :disabled="readonly" @click="cancelBoundaryEdit">取消编辑</button>
            </div>
          </div>
          <div v-if="calibrationStops.length" class="calibration-panel" aria-label="站点覆盖校准">
            <div class="calibration-summary">
              <strong>站点覆盖：{{ stopCoverage.total }} 个，服务区内 {{ stopCoverage.inside }} 个，服务区外 {{ stopCoverage.outside }} 个</strong>
              <span><i class="calibration-swatch inside-swatch"></i>服务区内</span>
              <span><i class="calibration-swatch outside-swatch"></i>服务区外</span>
              <span><i class="calibration-swatch control-node-swatch"></i>蓝色节点：围栏控制点</span>
            </div>
            <div v-if="calibrationStops.length >= 3" class="calibration-actions">
              <label>参考范围缓冲（米）<input v-model.number="referenceBufferMeters" type="number" min="0" step="50" :disabled="readonly" /></label>
              <button type="button" class="secondary-button" :disabled="!canGenerateReferenceBoundary" @click="generateReferenceBoundary">生成参考范围</button>
              <span class="calibration-note">仅写入当前编辑草稿，核对后请点击“保存草稿”。</span>
            </div>
          </div>
        </div>
      </div>

      <div class="editor-controls">
        <label class="district-field">
          行政区检索
          <div class="inline-field">
            <input v-model="importKeyword" :disabled="readonly" aria-label="行政区检索" />
            <button type="button" class="secondary-button" :disabled="readonly" @click="importDistrict">导入通渭县边界</button>
          </div>
        </label>

        <label class="boundary-format-field">
          边界格式
          <select v-model="inputFormat" :disabled="readonly" aria-label="边界格式">
            <option value="wkt">WKT</option>
            <option value="geoJson">GeoJSON</option>
          </select>
        </label>

        <label class="boundary-draft-field">
          服务区边界草稿
          <textarea v-model="boundaryText" :disabled="readonly" rows="7" aria-label="服务区边界草稿" placeholder="POLYGON((lng lat, lng lat, ...))"></textarea>
        </label>

        <p v-if="mapWarning" class="editor-message warning">{{ mapWarning }}</p>
        <p v-if="mapError" class="editor-message error">{{ mapError }}</p>
        <p v-else-if="hasUnsavedBoundaryChanges" class="editor-message warning">边界修改尚未保存为草稿，当前已发布服务区仍按已发布边界运行。</p>
        <p v-else-if="feedback" class="editor-message success">{{ feedback }}</p>

        <div v-if="!serviceArea" class="create-fields">
          <label>服务区名称<input v-model="createForm.name" :disabled="readonly" aria-label="服务区名称" /></label>
          <label>运营开始时间<input v-model="createForm.serviceStart" :disabled="readonly" type="time" aria-label="运营开始时间" /></label>
          <label>运营结束时间<input v-model="createForm.serviceEnd" :disabled="readonly" type="time" aria-label="运营结束时间" /></label>
          <label>调度规则组
            <select v-model="createForm.ruleSetId" :disabled="readonly" aria-label="调度规则组">
              <option v-for="rule in ruleSets" :key="rule.id" :value="rule.id">{{ rule.name }}</option>
            </select>
          </label>
          <p v-if="ruleSets.length === 0" class="editor-message warning">请先创建调度规则组，再创建服务区。</p>
        </div>

        <div class="editor-actions">
          <button v-if="!serviceArea" type="button" class="primary-button" :disabled="createDisabled" @click="createServiceAreaDraft">创建服务区草稿</button>
          <template v-else>
            <button type="button" class="secondary-button" :disabled="readonly || busy" @click="saveBoundary">{{ busy ? "正在保存" : "保存草稿" }}</button>
            <button type="button" class="primary-button" :disabled="readonly || busy || !hasPendingDraft" @click="requestPublish">{{ busy ? "保存中，请稍候" : hasPendingDraft ? "发布并启用" : "已发布并启用" }}</button>
          </template>
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
.editor-layout { display: grid; gap: 16px; }
.editor-map-panel { min-width: 0; }
.map-shell { border: 1px solid #cbd8d2; background: #edf4f0; display: grid; grid-template-rows: minmax(360px, 1fr) auto; }
.map-stage { min-height: 360px; position: relative; }
.tile-map-canvas { height: 100%; min-height: 360px; width: 100%; }
.map-fallback { position: absolute; inset: 0; display: grid; place-content: center; gap: 8px; padding: 28px; color: #40574e; text-align: center; background: #edf4f0; }
.map-fallback span { font-size: 14px; }
.map-meta { display: flex; align-items: center; justify-content: space-between; gap: 12px; border-top: 1px solid #cbd8d2; background: #f8faf8; padding: 10px 12px; color: #40574e; font-size: 13px; }
.map-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.edit-mode-badge { background: #eaf3ff; border: 1px solid #3388ff; color: #1555a4; font-weight: 700; padding: 6px 8px; }
.calibration-panel { display: grid; gap: 10px; border-top: 1px solid #cbd8d2; background: #f8faf8; padding: 10px 12px; color: #40574e; font-size: 13px; }
.calibration-summary, .calibration-actions { align-items: center; display: flex; flex-wrap: wrap; gap: 12px; }
.calibration-summary strong { color: #263a32; }
.calibration-swatch { border: 2px solid; border-radius: 50%; display: inline-block; height: 10px; width: 10px; }
.inside-swatch { background: #007f67; border-color: #007f67; }
.outside-swatch { background: #f1b95c; border-color: #c47a00; }
.control-node-swatch { background: #fff; border-color: #3388ff; }
.calibration-actions label { align-items: center; display: inline-flex; flex-direction: row; gap: 6px; }
.calibration-actions input { max-width: 100px; padding: 6px 8px; }
.calibration-note { color: #805b00; }
.editor-controls { display: grid; align-content: start; gap: 14px 16px; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.boundary-draft-field, .editor-message, .create-fields, .editor-actions, .publish-note { grid-column: 1 / -1; }
.create-fields { display: grid; gap: 14px 16px; grid-template-columns: repeat(2, minmax(0, 1fr)); }
label { display: grid; gap: 6px; color: #263a32; font-size: 14px; font-weight: 700; }
input, select, textarea { box-sizing: border-box; width: 100%; border: 1px solid #b9cac2; background: #fff; color: #14231d; font: inherit; padding: 9px 10px; }
textarea { resize: vertical; line-height: 1.45; }
.inline-field { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
.editor-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.editor-message { margin: 0; font-size: 14px; font-weight: 700; }
.editor-message.success { color: #00745a; }
.editor-message.warning { color: #805b00; }
.editor-message.error { color: #ad2a2a; }
.publish-note { margin: 0; border-left: 3px solid #d59a00; padding-left: 10px; color: #65521f; font-size: 13px; line-height: 1.5; }
:deep(.service-area-editing .marker-icon) { background-color: #fff !important; border: 2px solid #3388ff !important; box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.8); }
:deep(.service-area-editing .marker-icon-middle) { background-color: #eaf3ff !important; border: 2px solid #7db3ff !important; }
@media (max-width: 900px) { .map-shell { grid-template-rows: minmax(280px, 1fr) auto; } .map-stage, .tile-map-canvas { min-height: 280px; } }
@media (max-width: 560px) { .editor-header, .map-meta { align-items: stretch; flex-direction: column; } .editor-controls, .create-fields, .inline-field { grid-template-columns: 1fr; } }
</style>
