<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { checkServiceAreaContainment, searchAddressSuggestions } from "../api/map";
import { loadAmap } from "../maps/amapLoader";
import type { AddressSuggestion, ServiceAreaContainment, VirtualStop } from "../api/types";

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
  city?: string;
}>(), {
  serviceAreaId: undefined,
  virtualStops: () => [],
  city: "甘肃省定西市通渭县"
});

const emit = defineEmits<{
  "update:modelValue": [value: AddressCoordinateValue];
}>();

const keyword = ref(props.modelValue.address);
const suggestions = ref<AddressSuggestion[]>([]);
const searching = ref(false);
const lookupError = ref("");
const containment = ref<ServiceAreaContainment>();
const containmentError = ref("");
const mapOpen = ref(false);
const mapError = ref("");
const mapContainer = ref<HTMLElement>();
let mapInstance: AmapMap | undefined;

const matchingStops = computed(() => props.virtualStops
  .filter((stop) => stop.enabled && (props.purpose === "BOARDING" ? stop.boardingEnabled : stop.alightingEnabled))
  .map((stop) => ({ stop, distanceMeters: distanceMeters(stop.location, props.modelValue.longitude, props.modelValue.latitude) }))
  .filter((candidate) => candidate.distanceMeters !== undefined)
  .sort((left, right) => (left.distanceMeters ?? Number.MAX_VALUE) - (right.distanceMeters ?? Number.MAX_VALUE)));

const recommendedStop = computed(() => matchingStops.value.find((candidate) => candidate.distanceMeters! <= candidate.stop.serviceRadiusMeters)?.stop);
const displayedStopId = computed(() => props.modelValue.virtualStopId ?? recommendedStop.value?.id ?? "");
const hasCoordinates = computed(() => Number.isFinite(props.modelValue.longitude) && Number.isFinite(props.modelValue.latitude));

watch(() => props.modelValue.address, (value) => {
  if (value !== keyword.value) {
    keyword.value = value;
  }
});

watch(
  () => [props.modelValue.longitude, props.modelValue.latitude, props.serviceAreaId],
  () => { void refreshContainment(); },
  { immediate: true }
);

onBeforeUnmount(() => mapInstance?.destroy?.());

async function search(): Promise<void> {
  const value = keyword.value.trim();
  suggestions.value = [];
  lookupError.value = "";
  if (value.length < 2) {
    return;
  }
  searching.value = true;
  try {
    suggestions.value = await searchAddressSuggestions(value, props.city) ?? [];
  } catch {
    lookupError.value = "地图服务暂不可用，可手工输入经纬度或选择虚拟站点。";
  } finally {
    searching.value = false;
  }
}

function selectSuggestion(suggestion: AddressSuggestion): void {
  keyword.value = [suggestion.name, suggestion.address].filter(Boolean).join(" ");
  suggestions.value = [];
  update({
    address: keyword.value,
    longitude: suggestion.location.longitude,
    latitude: suggestion.location.latitude,
    virtualStopId: undefined
  });
}

function onAddressInput(): void {
  update({ address: keyword.value, virtualStopId: undefined });
  void search();
}

function updateManualCoordinate(field: "longitude" | "latitude", value: string): void {
  const number = Number(value);
  update({ [field]: Number.isFinite(number) ? number : undefined, virtualStopId: undefined });
}

function selectStop(value: string): void {
  update({ virtualStopId: value || undefined });
}

function update(patch: Partial<AddressCoordinateValue>): void {
  emit("update:modelValue", { ...props.modelValue, ...patch });
}

async function refreshContainment(): Promise<void> {
  containment.value = undefined;
  containmentError.value = "";
  if (!props.serviceAreaId || !hasCoordinates.value) {
    return;
  }
  try {
    containment.value = await checkServiceAreaContainment(
      props.serviceAreaId,
      props.modelValue.longitude!,
      props.modelValue.latitude!
    );
  } catch {
    containmentError.value = "暂时无法校验服务区，提交时仍会由系统最终校验。";
  }
}

async function openMap(): Promise<void> {
  mapOpen.value = true;
  mapError.value = "";
  await nextTick();
  try {
    const runtime = await loadAmap();
    if (!runtime.enabled || !runtime.AMap || !mapContainer.value) {
      throw new Error("地图不可用");
    }
    const AMap = runtime.AMap as AmapApi;
    mapInstance?.destroy?.();
    mapInstance = new AMap.Map(mapContainer.value, {
      zoom: 14,
      center: hasCoordinates.value
        ? [props.modelValue.longitude!, props.modelValue.latitude!]
        : [105.2421, 35.2103]
    });
    mapInstance.on("click", (event) => {
      update({
        address: keyword.value.trim() || "地图点选位置",
        longitude: event.lnglat.lng,
        latitude: event.lnglat.lat,
        virtualStopId: undefined
      });
    });
  } catch {
    mapError.value = "地图服务暂不可用，可手工输入经纬度或选择虚拟站点。";
  }
}

function distanceMeters(location: string, longitude?: number, latitude?: number): number | undefined {
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) {
    return undefined;
  }
  const match = location.match(/POINT\(([-\d.]+)\s+([-\d.]+)\)/i);
  if (!match) {
    return undefined;
  }
  const stopLongitude = Number(match[1]);
  const stopLatitude = Number(match[2]);
  const deltaLatitude = toRadians(stopLatitude - latitude!);
  const deltaLongitude = toRadians(stopLongitude - longitude!);
  const a = Math.sin(deltaLatitude / 2) ** 2
    + Math.cos(toRadians(latitude!)) * Math.cos(toRadians(stopLatitude)) * Math.sin(deltaLongitude / 2) ** 2;
  return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function toRadians(value: number): number {
  return value * Math.PI / 180;
}
</script>

<template>
  <fieldset class="location-field">
    <legend>{{ label }}</legend>
    <label class="field">
      <span>{{ label }}地址</span>
      <div class="input-with-action">
        <input v-model="keyword" :aria-label="`${label}地址`" required @input="onAddressInput" />
        <button class="secondary-button compact-button" type="button" :disabled="searching" @click="search">
          {{ searching ? "搜索中" : "搜索" }}
        </button>
      </div>
    </label>

    <div v-if="suggestions.length" class="suggestion-list" :aria-label="`${label}地址建议`">
      <button v-for="suggestion in suggestions" :key="suggestion.id" type="button" class="suggestion-item" @click="selectSuggestion(suggestion)">
        <strong>{{ suggestion.name }}</strong>
        <span>{{ suggestion.address || suggestion.district }}</span>
      </button>
    </div>

    <p v-if="lookupError" class="field-warning">{{ lookupError }}</p>
    <p v-if="containment" class="containment" :class="containment.inside ? 'inside' : 'outside'">
      {{ containment.inside ? "服务区内，可继续录入" : "服务区外，提交会被系统拒绝" }}
    </p>
    <p v-else-if="containmentError" class="field-warning">{{ containmentError }}</p>

    <label class="field">
      <span>{{ purpose === "BOARDING" ? "推荐上车点" : "推荐下车点" }}</span>
      <select :value="displayedStopId" @change="selectStop(($event.target as HTMLSelectElement).value)">
        <option value="">不指定，由系统匹配</option>
        <option v-for="candidate in matchingStops" :key="candidate.stop.id" :value="candidate.stop.id">
          {{ candidate.stop.name }}{{ candidate.distanceMeters === undefined ? "" : `（约 ${Math.round(candidate.distanceMeters)} 米）` }}
        </option>
      </select>
    </label>

    <div class="location-actions">
      <button type="button" class="secondary-button" @click="openMap">地图点选</button>
      <span v-if="recommendedStop" class="recommendation">已推荐：{{ recommendedStop.name }}</span>
    </div>
    <div v-if="mapOpen" ref="mapContainer" class="point-picker-map" :aria-label="`${label}地图点选`"></div>
    <p v-if="mapError" class="field-warning">{{ mapError }}</p>

    <details class="manual-coordinates">
      <summary>手工输入经纬度</summary>
      <div class="coordinate-grid">
        <label class="field">
          <span>{{ label }}经度</span>
          <input :value="modelValue.longitude" :aria-label="`${label}经度`" type="number" min="-180" max="180" step="0.000001" required @input="updateManualCoordinate('longitude', ($event.target as HTMLInputElement).value)" />
        </label>
        <label class="field">
          <span>{{ label }}纬度</span>
          <input :value="modelValue.latitude" :aria-label="`${label}纬度`" type="number" min="-90" max="90" step="0.000001" required @input="updateManualCoordinate('latitude', ($event.target as HTMLInputElement).value)" />
        </label>
      </div>
    </details>
  </fieldset>
</template>

<style scoped>
.location-field { border: 1px solid var(--line); margin: 0; min-width: 0; padding: 14px; }
.location-field legend { color: var(--ink); font-size: 14px; font-weight: 800; padding: 0 4px; }
.input-with-action, .location-actions { align-items: center; display: flex; gap: 8px; }
.input-with-action input { min-width: 0; }
.compact-button { flex: 0 0 auto; padding: 9px 12px; }
.suggestion-list { border: 1px solid var(--line); display: grid; margin: 8px 0; max-height: 180px; overflow: auto; }
.suggestion-item { background: var(--surface); border: 0; border-bottom: 1px solid var(--line); cursor: pointer; display: grid; gap: 3px; padding: 9px 10px; text-align: left; }
.suggestion-item:last-child { border-bottom: 0; }
.suggestion-item:hover { background: var(--surface-muted); }
.suggestion-item span, .recommendation { color: var(--ink-muted); font-size: 13px; }
.containment, .field-warning { font-size: 13px; font-weight: 700; margin: 8px 0; }
.inside { color: var(--success); }
.outside, .field-warning { color: var(--danger); }
.point-picker-map { border: 1px solid var(--line); height: 220px; margin-top: 10px; width: 100%; }
.manual-coordinates { margin-top: 12px; }
.manual-coordinates summary { color: var(--ink-muted); cursor: pointer; font-size: 13px; font-weight: 700; }
.coordinate-grid { display: grid; gap: 10px; grid-template-columns: repeat(2, minmax(0, 1fr)); margin-top: 10px; }
@media (max-width: 640px) { .coordinate-grid { grid-template-columns: 1fr; } }
</style>

<script lang="ts">
type AmapCoordinate = [number, number];
interface AmapMap {
  on(eventName: "click", handler: (event: { lnglat: { lng: number; lat: number } }) => void): void;
  destroy?(): void;
}
interface AmapApi {
  Map: new (container: HTMLElement, options: { zoom: number; center: AmapCoordinate }) => AmapMap;
}
</script>
