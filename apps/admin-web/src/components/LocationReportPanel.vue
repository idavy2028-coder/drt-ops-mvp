<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import type { LocationCandidate, LocationPickerProvider, LocationReportInput, VirtualStop } from "../api/types";

type ConfirmedLocationCandidate = LocationCandidate & {
  longitude: number;
  latitude: number;
};

const props = withDefaults(defineProps<{
  actionLabel: string;
  initialLocation?: LocationCandidate;
  virtualStops?: VirtualStop[];
  provider?: LocationPickerProvider | null;
  submitting?: boolean;
  isOutsideServiceArea?: (location: LocationCandidate) => boolean;
}>(), {
  virtualStops: () => [],
  provider: null,
  submitting: false,
  isOutsideServiceArea: () => false
});

const emit = defineEmits<{
  close: [];
  submit: [value: LocationReportInput];
}>();

const idempotencyKey = crypto.randomUUID();
const searchKeyword = ref("");
const searchResults = ref<LocationCandidate[]>([]);
const errorMessage = ref("");
const outsideWarningVisible = ref(false);
const outsideConfirmed = ref(false);
const candidateOutsideServiceArea = ref(props.initialLocation?.outsideServiceArea === true);
const mapContainer = ref<HTMLElement | null>(null);

const form = reactive({
  longitude: props.initialLocation?.longitude?.toString() ?? "",
  latitude: props.initialLocation?.latitude?.toString() ?? "",
  standardizedAddress: props.initialLocation?.standardizedAddress ?? "",
  virtualStopId: props.initialLocation?.virtualStopId ?? "",
  driverReportedAt: toLocalInputValue(new Date()),
  note: ""
});

const degraded = computed(() => props.provider === null || props.initialLocation?.providerDegraded === true);

watch(() => props.initialLocation, (location) => {
  if (!location) {
    return;
  }
  applyCandidate(location);
});

function submit() {
  errorMessage.value = "";
  const candidate = currentCandidate();
  if (candidate === null) {
    return;
  }
  if (!form.driverReportedAt) {
    errorMessage.value = "请填写驾驶员反馈时间";
    return;
  }
  if (props.isOutsideServiceArea(candidate) && !outsideConfirmed.value) {
    outsideWarningVisible.value = true;
    return;
  }
  const { providerDegraded: _providerDegraded, outsideServiceArea: _outsideServiceArea, ...reportCandidate } = candidate;
  emit("submit", {
    ...reportCandidate,
    driverReportedAt: new Date(`${form.driverReportedAt}:00`).toISOString(),
    note: form.note.trim() || undefined,
    idempotencyKey
  });
}

function currentCandidate(): ConfirmedLocationCandidate | null {
  const longitude = parseCoordinateInput(form.longitude, "longitude");
  if (longitude === null) {
    return null;
  }
  const latitude = parseCoordinateInput(form.latitude, "latitude");
  if (latitude === null) {
    return null;
  }
  if (!form.standardizedAddress.trim()) {
    errorMessage.value = "请填写标准化地址";
    return null;
  }
  return {
    longitude,
    latitude,
    standardizedAddress: form.standardizedAddress.trim(),
    virtualStopId: form.virtualStopId || undefined,
    providerDegraded: degraded.value,
    outsideServiceArea: candidateOutsideServiceArea.value
  };
}

async function searchAddress() {
  if (props.provider === null || searchKeyword.value.trim() === "") {
    return;
  }
  errorMessage.value = "";
  try {
    searchResults.value = await props.provider.search(searchKeyword.value.trim());
  } catch {
    errorMessage.value = "地图交互失败，请稍后重试或手工录入。";
  }
}

async function pickOnMap() {
  if (props.provider === null || mapContainer.value === null) {
    return;
  }
  errorMessage.value = "";
  try {
    const selected = await props.provider.pickOnMap(mapContainer.value, currentCandidate() ?? undefined);
    applyCandidate(selected);
  } catch {
    errorMessage.value = "地图交互失败，请稍后重试或手工录入。";
  }
}

function selectVirtualStop() {
  const stop = props.virtualStops.find((candidate) => candidate.id === form.virtualStopId);
  if (!stop) {
    return;
  }
  const coordinates = parsePoint(stop.location);
  form.standardizedAddress = stop.name;
  if (coordinates !== null) {
    form.longitude = coordinates.longitude.toString();
    form.latitude = coordinates.latitude.toString();
  } else {
    form.longitude = "";
    form.latitude = "";
  }
  candidateOutsideServiceArea.value = false;
  outsideWarningVisible.value = false;
  outsideConfirmed.value = false;
}

function applyCandidate(candidate: LocationCandidate) {
  form.longitude = candidate.longitude === undefined ? "" : candidate.longitude.toString();
  form.latitude = candidate.latitude === undefined ? "" : candidate.latitude.toString();
  form.standardizedAddress = candidate.standardizedAddress;
  form.virtualStopId = candidate.virtualStopId ?? "";
  candidateOutsideServiceArea.value = candidate.outsideServiceArea === true;
  outsideWarningVisible.value = false;
  outsideConfirmed.value = false;
}

function parseCoordinateInput(value: number | string | null | undefined, coordinate: "longitude" | "latitude"): number | null {
  const trimmed = String(value ?? "").trim();
  if (trimmed === "") {
    errorMessage.value = "请填写有效经纬度";
    return null;
  }
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed)) {
    errorMessage.value = "请填写有效经纬度";
    return null;
  }
  if (coordinate === "longitude" && (parsed < -180 || parsed > 180)) {
    errorMessage.value = "经度必须在 -180 到 180 之间";
    return null;
  }
  if (coordinate === "latitude" && (parsed < -90 || parsed > 90)) {
    errorMessage.value = "纬度必须在 -90 到 90 之间";
    return null;
  }
  return parsed;
}

function parsePoint(value: string): { longitude: number; latitude: number } | null {
  const matched = value.match(/POINT\s*\(\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*\)/i);
  if (!matched) {
    return null;
  }
  return { longitude: Number(matched[1]), latitude: Number(matched[2]) };
}

function toLocalInputValue(value: Date): string {
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, "0");
  const day = String(value.getDate()).padStart(2, "0");
  const hours = String(value.getHours()).padStart(2, "0");
  const minutes = String(value.getMinutes()).padStart(2, "0");
  return `${year}-${month}-${day}T${hours}:${minutes}`;
}
</script>

<template>
  <form class="location-panel work-panel" @submit.prevent="submit">
    <div class="panel-heading">
      <div>
        <p class="panel-kicker">LOCATION REPORT</p>
        <h3 class="section-title">确认{{ actionLabel }}位置</h3>
      </div>
      <button class="secondary-button" type="button" :disabled="submitting" @click="emit('close')">关闭</button>
    </div>

    <p v-if="degraded" class="inline-warning">地图服务不可用，已切换为经纬度录入。</p>
    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>
    <div v-if="outsideWarningVisible" class="inline-warning">
      <p>当前位置可能在服务区外，请确认后再保存。</p>
      <label class="check-field">
        <input v-model="outsideConfirmed" type="checkbox" />
        <span>确认服务区外位置仍需保存</span>
      </label>
    </div>

    <div class="provider-row">
      <label class="field">
        <span>地址搜索</span>
        <input v-model="searchKeyword" :disabled="provider === null" />
      </label>
      <button class="secondary-button" type="button" :disabled="provider === null || !searchKeyword.trim()" @click="searchAddress">搜索</button>
      <button class="secondary-button" type="button" :disabled="provider === null" @click="pickOnMap">地图选点</button>
    </div>
    <div ref="mapContainer" class="map-slot" aria-hidden="true"></div>
    <div v-if="searchResults.length > 0" class="candidate-list">
      <button v-for="candidate in searchResults" :key="`${candidate.longitude}-${candidate.latitude}-${candidate.standardizedAddress}`" class="candidate-button" type="button" @click="applyCandidate(candidate)">
        {{ candidate.standardizedAddress }}
      </button>
    </div>

    <div class="form-grid">
      <label class="field">
        <span>虚拟站点</span>
        <select v-model="form.virtualStopId" @change="selectVirtualStop">
          <option value="">手工录入</option>
          <option v-for="stop in virtualStops" :key="stop.id" :value="stop.id">{{ stop.name }}</option>
        </select>
      </label>
      <label class="field">
        <span>驾驶员反馈时间</span>
        <input v-model="form.driverReportedAt" type="datetime-local" />
      </label>
      <label class="field">
        <span>经度</span>
        <input v-model="form.longitude" type="number" step="0.000001" />
      </label>
      <label class="field">
        <span>纬度</span>
        <input v-model="form.latitude" type="number" step="0.000001" />
      </label>
      <label class="field form-grid-wide">
        <span>标准化地址</span>
        <input v-model="form.standardizedAddress" />
      </label>
      <label class="field form-grid-wide">
        <span>备注</span>
        <input v-model="form.note" />
      </label>
    </div>

    <div class="toolbar">
      <button class="primary-button" type="submit" :disabled="submitting">{{ submitting ? "正在提交" : `确认${actionLabel}` }}</button>
      <button class="secondary-button" type="button" :disabled="submitting" @click="emit('close')">取消</button>
    </div>
  </form>
</template>

<style scoped>
.location-panel {
  display: grid;
  gap: 16px;
  border-left: 4px solid var(--brand);
}

.panel-heading {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 14px;
}

.panel-kicker {
  margin: 0 0 6px;
  color: var(--ink-muted);
  font-size: 12px;
  font-weight: 800;
}

.inline-warning,
.inline-error {
  border-radius: 8px;
  margin: 0;
  padding: 12px 14px;
  line-height: 1.6;
}

.inline-warning {
  border: 1px solid #f0d493;
  background: #fff8e8;
  color: #76520f;
}

.inline-error {
  border: 1px solid #e4aaa5;
  background: #fff0ef;
  color: var(--danger);
}

.provider-row {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) auto auto;
  gap: 10px;
  align-items: end;
}

.map-slot {
  display: none;
}

.candidate-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.candidate-button {
  border: 1px solid #b8d8c9;
  border-radius: 8px;
  background: #f3fbf7;
  color: var(--brand);
  min-height: 34px;
  padding: 6px 10px;
  font-weight: 800;
}

.check-field {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 800;
}

.form-grid-wide {
  grid-column: 1 / -1;
}

@media (max-width: 820px) {
  .provider-row {
    grid-template-columns: 1fr;
  }
}
</style>
