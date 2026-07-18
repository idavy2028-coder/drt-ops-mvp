<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { checkServiceAreaContainment } from "../api/map";
import { createTileMap } from "../maps/tileMapRuntime";
import type { TileMapHandle } from "../maps/tileMapTypes";
import type { ServiceAreaContainment, VirtualStop } from "../api/types";

export interface AddressCoordinateValue {
  address: string;
  longitude?: number;
  latitude?: number;
  virtualStopId?: string;
}

const props = withDefaults(defineProps<{
  label: string;
  purpose: "BOARDING" | "ALIGHTING";
  modelValue: AddressCoordinateValue;
  serviceAreaId?: string;
  virtualStops?: VirtualStop[];
}>(), {
  serviceAreaId: undefined,
  virtualStops: () => []
});

const emit = defineEmits<{ "update:modelValue": [value: AddressCoordinateValue] }>();
const keyword = ref(props.modelValue.address);
const containment = ref<ServiceAreaContainment>();
const containmentError = ref("");
const mapOpen = ref(false);
const mapError = ref("");
const mapContainer = ref<HTMLElement>();
let tileMap: TileMapHandle | undefined;
let unsubscribeClick: (() => void) | undefined;
let unsubscribeBaseLayerError: (() => void) | undefined;

const matchingStops = computed(() => props.virtualStops
  .filter((stop) => stop.enabled && (props.purpose === "BOARDING" ? stop.boardingEnabled : stop.alightingEnabled))
  .map((stop) => ({ stop, distanceMeters: distanceMeters(stop.location, props.modelValue.longitude, props.modelValue.latitude) }))
  .filter((candidate) => candidate.distanceMeters !== undefined)
  .sort((left, right) => (left.distanceMeters ?? Number.MAX_VALUE) - (right.distanceMeters ?? Number.MAX_VALUE)));
const recommendedStop = computed(() => matchingStops.value.find((candidate) => candidate.distanceMeters! <= candidate.stop.serviceRadiusMeters)?.stop);
const displayedStopId = computed(() => props.modelValue.virtualStopId ?? recommendedStop.value?.id ?? "");
const hasCoordinates = computed(() => Number.isFinite(props.modelValue.longitude) && Number.isFinite(props.modelValue.latitude));

watch(() => props.modelValue.address, (value) => {
  if (value !== keyword.value) keyword.value = value;
});
watch(() => [props.modelValue.longitude, props.modelValue.latitude, props.serviceAreaId], () => { void refreshContainment(); }, { immediate: true });
onBeforeUnmount(closeMap);

function onAddressInput(): void {
  update({ address: keyword.value, virtualStopId: undefined });
}
function updateManualCoordinate(field: "longitude" | "latitude", value: string): void {
  const number = Number(value);
  update({ [field]: Number.isFinite(number) ? number : undefined, virtualStopId: undefined });
}
function selectStop(value: string): void { update({ virtualStopId: value || undefined }); }
function update(patch: Partial<AddressCoordinateValue>): void { emit("update:modelValue", { ...props.modelValue, ...patch }); }

async function refreshContainment(): Promise<void> {
  containment.value = undefined;
  containmentError.value = "";
  if (!props.serviceAreaId || !hasCoordinates.value) return;
  try {
    containment.value = await checkServiceAreaContainment(props.serviceAreaId, props.modelValue.longitude!, props.modelValue.latitude!);
  } catch {
    containmentError.value = "暂时无法校验服务区，提交时仍会由系统最终校验。";
  }
}

async function openMap(): Promise<void> {
  closeMap();
  mapOpen.value = true;
  mapError.value = "";
  await nextTick();
  if (!mapContainer.value) {
    mapError.value = "开放底图暂不可用";
    return;
  }
  try {
    tileMap = createTileMap(
      mapContainer.value,
      hasCoordinates.value ? { longitude: props.modelValue.longitude!, latitude: props.modelValue.latitude! } : { longitude: 105.2421, latitude: 35.2103 },
      14
    );
    unsubscribeClick = tileMap.onClick((point) => {
      update({ address: keyword.value.trim() || "地图点选位置", longitude: point.longitude, latitude: point.latitude, virtualStopId: undefined });
      closeMap();
    });
    unsubscribeBaseLayerError = tileMap.onBaseLayerError(() => { mapError.value = "开放底图暂不可用"; });
  } catch {
    mapError.value = "开放底图暂不可用";
  }
}
function closeMap(): void {
  unsubscribeClick?.();
  unsubscribeBaseLayerError?.();
  unsubscribeClick = undefined;
  unsubscribeBaseLayerError = undefined;
  tileMap?.destroy();
  tileMap = undefined;
  mapOpen.value = false;
}
function distanceMeters(location: string, longitude?: number, latitude?: number): number | undefined {
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) return undefined;
  const match = location.match(/POINT\(([-\d.]+)\s+([-\d.]+)\)/i);
  if (!match) return undefined;
  const stopLongitude = Number(match[1]);
  const stopLatitude = Number(match[2]);
  const deltaLatitude = toRadians(stopLatitude - latitude!);
  const deltaLongitude = toRadians(stopLongitude - longitude!);
  const a = Math.sin(deltaLatitude / 2) ** 2 + Math.cos(toRadians(latitude!)) * Math.cos(toRadians(stopLatitude)) * Math.sin(deltaLongitude / 2) ** 2;
  return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}
function toRadians(value: number): number { return value * Math.PI / 180; }
</script>

<template>
  <fieldset class="location-field">
    <legend>{{ label }}</legend>
    <label class="field"><span>{{ label }}地址</span><input v-model="keyword" :aria-label="`${label}地址`" required @input="onAddressInput" /></label>
    <p v-if="containment" class="containment" :class="containment.inside ? 'inside' : 'outside'">{{ containment.inside ? "服务区内，可继续录入" : "服务区外，提交会被系统拦截" }}</p>
    <p v-else-if="containmentError" class="field-warning">{{ containmentError }}</p>
    <label class="field">
      <span>{{ purpose === "BOARDING" ? "推荐上车点" : "推荐下车点" }}</span>
      <select :value="displayedStopId" :aria-label="purpose === 'BOARDING' ? '推荐上车点' : '推荐下车点'" @change="selectStop(($event.target as HTMLSelectElement).value)">
        <option value="">不指定，由系统匹配</option>
        <option v-for="candidate in matchingStops" :key="candidate.stop.id" :value="candidate.stop.id">{{ candidate.stop.name }}{{ candidate.distanceMeters === undefined ? "" : `（约 ${Math.round(candidate.distanceMeters)} 米）` }}</option>
      </select>
    </label>
    <div class="location-actions"><button type="button" class="secondary-button" @click="openMap">地图点选</button><span v-if="recommendedStop" class="recommendation">已推荐：{{ recommendedStop.name }}</span></div>
    <div v-if="mapOpen" class="map-picker-panel"><div ref="mapContainer" class="point-picker-map" :aria-label="`${label}地图点选`"></div><button type="button" class="secondary-button compact-button" @click="closeMap">关闭地图</button></div>
    <p v-if="mapError" class="field-warning">{{ mapError }}</p>
    <details class="manual-coordinates">
      <summary>手工输入经纬度</summary>
      <div class="coordinate-grid">
        <label class="field"><span>{{ label }}经度</span><input :value="modelValue.longitude" :aria-label="`${label}经度`" type="number" min="-180" max="180" step="0.000001" required @input="updateManualCoordinate('longitude', ($event.target as HTMLInputElement).value)" /></label>
        <label class="field"><span>{{ label }}纬度</span><input :value="modelValue.latitude" :aria-label="`${label}纬度`" type="number" min="-90" max="90" step="0.000001" required @input="updateManualCoordinate('latitude', ($event.target as HTMLInputElement).value)" /></label>
      </div>
    </details>
  </fieldset>
</template>

<style scoped>
.location-field { border: 1px solid var(--line); margin: 0; min-width: 0; padding: 14px; }
.location-field legend { color: var(--ink); font-size: 14px; font-weight: 800; padding: 0 4px; }
.location-actions { align-items: center; display: flex; gap: 8px; margin-top: 12px; }
.compact-button { padding: 8px 10px; }
.recommendation { color: var(--ink-muted); font-size: 13px; }
.containment, .field-warning { font-size: 13px; font-weight: 700; margin: 8px 0; }
.inside { color: var(--success); }.outside, .field-warning { color: var(--danger); }
.map-picker-panel { display: grid; gap: 8px; margin-top: 10px; }
.point-picker-map { border: 1px solid var(--line); height: 220px; width: 100%; }
.manual-coordinates { margin-top: 12px; }.manual-coordinates summary { color: var(--ink-muted); cursor: pointer; font-size: 13px; font-weight: 700; }
.coordinate-grid { display: grid; gap: 10px; grid-template-columns: repeat(2, minmax(0, 1fr)); margin-top: 10px; }
@media (max-width: 640px) { .coordinate-grid { grid-template-columns: 1fr; } }
</style>
