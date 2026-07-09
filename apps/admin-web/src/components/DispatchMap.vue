<script setup lang="ts">
import "maplibre-gl/dist/maplibre-gl.css";
import { onBeforeUnmount, onMounted, ref } from "vue";

const mapContainer = ref<HTMLDivElement | null>(null);
let map: import("maplibre-gl").Map | null = null;

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
      center: [120.162, 30.277],
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
    <div class="map-marker vehicle-a">车</div>
    <div class="map-legend">
      <span>服务区</span>
      <span>虚拟站点</span>
      <span>任务路线</span>
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
.map-legend {
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

.vehicle-a {
  left: 48%;
  top: 43%;
  background: #17201c;
  color: #ffffff;
}

.map-legend {
  right: 14px;
  bottom: 14px;
  display: flex;
  gap: 8px;
  border: 1px solid #d9e1dc;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.92);
  padding: 8px 10px;
  color: #53615a;
  font-size: 12px;
  font-weight: 800;
}
</style>
