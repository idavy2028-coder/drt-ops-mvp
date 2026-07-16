<script setup lang="ts">
import "maplibre-gl/dist/maplibre-gl.css";
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import type { VehicleLocationEventView, VehicleLocationSnapshotItem } from "../api/types";

const props = withDefaults(defineProps<{
  locations?: VehicleLocationSnapshotItem[];
  eventChain?: VehicleLocationEventView[];
}>(), {
  locations: () => [],
  eventChain: () => []
});

const mapContainer = ref<HTMLDivElement | null>(null);
let map: import("maplibre-gl").Map | null = null;

const orderedChain = computed(() => [...props.eventChain].sort((left, right) => new Date(left.driverReportedAt).getTime() - new Date(right.driverReportedAt).getTime()));

async function initializeMap() {
  if (
    !mapContainer.value ||
    typeof window.URL.createObjectURL !== "function" ||
    typeof window.WebGLRenderingContext !== "function"
  ) {
    return;
  }
  try {
    const { default: maplibregl } = await import("maplibre-gl");
    map = new maplibregl.Map({
      container: mapContainer.value,
      center: [104.6378, 35.2109],
      zoom: 12,
      interactive: false,
      attributionControl: false,
      style: {
        version: 8,
        sources: {},
        layers: [
          {
            id: "background",
            type: "background",
            paint: { "background-color": "#eef2ef" }
          }
        ]
      }
    });
  } catch {
    map = null;
  }
}

function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    IN_SERVICE: "执行中",
    DISPATCHED: "已派单",
    IDLE: "空闲",
    OFFLINE: "离线"
  };
  return labels[status] ?? status;
}

function eventLabel(eventType: string): string {
  const labels: Record<string, string> = {
    TASK_STARTED: "发车",
    TASK_STOP_ARRIVED: "到站",
    PASSENGER_BOARDED: "上车",
    PASSENGER_ALIGHTED: "下车",
    TASK_COMPLETED: "完成",
    MANUAL_CORRECTION: "人工补报"
  };
  return labels[eventType] ?? eventType;
}

function formatDateTime(value?: string): string {
  if (!value) {
    return "--";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  }).format(new Date(value));
}

function markerStyle(index: number) {
  const positions = [
    { left: "48%", top: "43%" },
    { left: "58%", top: "36%" },
    { left: "38%", top: "54%" },
    { left: "66%", top: "52%" }
  ];
  return positions[index % positions.length];
}

onMounted(() => {
  void initializeMap();
});

onBeforeUnmount(() => {
  map?.remove();
});
</script>

<template>
  <section class="dispatch-map" aria-label="调度地图">
    <div ref="mapContainer" class="map-canvas"></div>
    <div class="map-overlay service-area"></div>
    <div class="map-route route-main"></div>
    <div class="map-marker stop-a">站</div>
    <div class="map-marker stop-b">站</div>

    <article
      v-for="(item, index) in locations"
      :key="item.vehicleId"
      class="vehicle-location-card"
      :style="markerStyle(index)"
      :aria-label="`车辆位置 ${item.plateNumber}`"
    >
      <div class="vehicle-dot">车</div>
      <div>
        <strong>{{ item.plateNumber }}</strong>
        <span>{{ statusLabel(item.currentStatus) }}</span>
        <small>任务 {{ item.latestLocation.vehicleTaskId ?? "--" }}</small>
        <small>最后反馈 {{ formatDateTime(item.latestLocation.driverReportedAt) }}</small>
        <small>{{ item.latestLocation.standardizedAddress }}</small>
      </div>
      <em>人工上报</em>
    </article>

    <div class="map-chain" aria-label="人工节点链">
      <p>离散节点链，仅表示人工上报节点，不是实际行驶轨迹</p>
      <ol>
        <li v-for="event in orderedChain" :key="event.id">
          <span>{{ eventLabel(event.eventType) }}</span>
          <small>{{ formatDateTime(event.driverReportedAt) }}</small>
        </li>
      </ol>
    </div>

    <div class="map-legend">
      <span>服务区</span>
      <span>虚拟站点</span>
      <span>任务路线</span>
      <span>人工上报</span>
    </div>
  </section>
</template>

<style scoped>
.dispatch-map {
  position: relative;
  min-height: 430px;
  overflow: hidden;
  border: 1px solid #d9e1dc;
  border-radius: 8px;
  background: #eef2ef;
}

.map-canvas {
  position: absolute;
  inset: 0;
}

.map-overlay,
.map-route,
.map-marker,
.map-legend,
.vehicle-location-card,
.map-chain {
  position: absolute;
  z-index: 2;
}

.service-area {
  inset: 42px 54px 58px 62px;
  border: 2px solid rgba(23, 99, 75, 0.42);
  border-radius: 22px 46px 30px 54px;
  background: rgba(139, 214, 188, 0.13);
}

.map-route {
  left: 22%;
  top: 44%;
  width: 54%;
  height: 4px;
  border-radius: 999px;
  background: #17634b;
  transform: rotate(-13deg);
  box-shadow: 120px -46px 0 -1px #17634b;
}

.map-marker {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #ffffff;
  color: #17634b;
  font-size: 12px;
  font-weight: 900;
  box-shadow: 0 10px 24px rgba(23, 32, 28, 0.18);
}

.stop-a {
  left: 28%;
  top: 55%;
}

.stop-b {
  right: 24%;
  top: 35%;
}

.vehicle-location-card {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 10px;
  min-width: 210px;
  max-width: 260px;
  border: 1px solid rgba(23, 99, 75, 0.2);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.94);
  padding: 10px;
  box-shadow: 0 16px 30px rgba(23, 32, 28, 0.14);
}

.vehicle-dot {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #17201c;
  color: #ffffff;
  font-size: 12px;
  font-weight: 900;
}

.vehicle-location-card strong,
.vehicle-location-card span,
.vehicle-location-card small {
  display: block;
  overflow-wrap: anywhere;
}

.vehicle-location-card strong {
  color: #17201c;
  font-size: 14px;
}

.vehicle-location-card span,
.vehicle-location-card small {
  color: #53615a;
  font-size: 12px;
  font-weight: 800;
}

.vehicle-location-card em {
  grid-column: 1 / -1;
  justify-self: start;
  border-radius: 999px;
  background: #dff4ed;
  color: #007a5d;
  padding: 3px 8px;
  font-size: 12px;
  font-style: normal;
  font-weight: 900;
}

.map-chain {
  left: 18px;
  bottom: 14px;
  max-width: 360px;
  border: 1px dashed rgba(23, 99, 75, 0.46);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.94);
  padding: 9px 10px;
}

.map-chain p {
  margin: 0 0 8px;
  color: #3f4c46;
  font-size: 12px;
  font-weight: 900;
}

.map-chain ol {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.map-chain li {
  border-left: 2px dashed #17634b;
  padding-left: 8px;
  color: #53615a;
  font-size: 12px;
  font-weight: 800;
}

.map-chain span,
.map-chain small {
  display: block;
}

.map-legend {
  right: 14px;
  bottom: 14px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  max-width: 320px;
  border: 1px solid #d9e1dc;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.92);
  padding: 8px 10px;
  color: #53615a;
  font-size: 12px;
  font-weight: 800;
}
</style>
