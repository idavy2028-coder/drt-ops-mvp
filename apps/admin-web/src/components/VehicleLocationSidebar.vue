<script setup lang="ts">
import { computed } from "vue";
import type { VehicleLocationSnapshotItem } from "../api/types";

const props = withDefaults(defineProps<{
  locations: VehicleLocationSnapshotItem[];
  selectedVehicleId?: string;
}>(), {
  selectedVehicleId: undefined
});

const emit = defineEmits<{
  select: [vehicleId: string];
}>();

const sortedLocations = computed(() => [...props.locations].sort((left, right) => {
  const rankDifference = statusRank(left.currentStatus) - statusRank(right.currentStatus);
  return rankDifference || left.plateNumber.localeCompare(right.plateNumber, "zh-CN");
}));

function statusRank(status: string): number {
  if (status === "IN_SERVICE" || status === "DISPATCHED") return 0;
  if (status === "IDLE") return 1;
  return 2;
}

function statusLabel(status: string): string {
  return ({ IN_SERVICE: "执行中", DISPATCHED: "已派单", IDLE: "空闲", OFFLINE: "离线", COMPLETED: "已完成" } as Record<string, string>)[status] ?? status;
}

function statusClass(item: VehicleLocationSnapshotItem): string {
  if (item.latestLocation.outsideServiceArea || item.currentStatus === "OFFLINE") return "is-alert";
  if (item.currentStatus === "IDLE") return "is-idle";
  if (item.currentStatus === "IN_SERVICE" || item.currentStatus === "DISPATCHED") return "is-active";
  return "is-unknown";
}

function hasValidLocation(item: VehicleLocationSnapshotItem): boolean {
  return Number.isFinite(Number(item.latestLocation.longitude)) && Number.isFinite(Number(item.latestLocation.latitude));
}

function shortTaskId(item: VehicleLocationSnapshotItem): string {
  return item.latestLocation.vehicleTaskId?.slice(0, 8) ?? "--";
}

function formatDateTime(value?: string): string {
  if (!value) return "--";
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  }).format(new Date(value));
}
</script>

<template>
  <aside class="vehicle-sidebar" aria-label="车辆位置侧栏">
    <header class="vehicle-sidebar-header">
      <div>
        <p>LIVE FLEET</p>
        <h3>车辆位置</h3>
      </div>
      <span>{{ locations.length }} 辆</span>
    </header>

    <div class="vehicle-list">
      <button
        v-for="item in sortedLocations"
        :key="item.vehicleId"
        type="button"
        class="vehicle-row"
        :class="[{ selected: item.vehicleId === selectedVehicleId }, statusClass(item)]"
        :disabled="!hasValidLocation(item)"
        :aria-label="`定位车辆 ${item.plateNumber}`"
        :aria-pressed="item.vehicleId === selectedVehicleId"
        @click="emit('select', item.vehicleId)"
      >
        <span class="vehicle-status-dot" aria-hidden="true"></span>
        <span class="vehicle-copy">
          <span class="vehicle-primary"><strong>{{ item.plateNumber }}</strong><em>{{ statusLabel(item.currentStatus) }}</em></span>
          <span v-if="hasValidLocation(item)" class="vehicle-meta">任务 {{ shortTaskId(item) }}</span>
          <span v-else class="vehicle-meta is-unavailable">位置不可用</span>
          <span class="vehicle-time">最后位置 {{ formatDateTime(item.latestLocation.driverReportedAt) }}</span>
        </span>
        <span class="locate-glyph" aria-hidden="true">⌖</span>
      </button>

      <p v-if="locations.length === 0" class="vehicle-empty">暂无车辆位置</p>
    </div>
  </aside>
</template>

<style scoped>
.vehicle-sidebar { background: #fff; border: 1px solid #d9e1dc; border-radius: 8px; display: flex; flex-direction: column; min-height: 0; overflow: hidden; }
.vehicle-sidebar-header { align-items: center; border-bottom: 1px solid #e4e9e6; display: flex; justify-content: space-between; padding: 15px 16px 13px; }
.vehicle-sidebar-header p { color: #0b8064; font-size: 10px; font-weight: 900; letter-spacing: .12em; margin: 0 0 3px; }
.vehicle-sidebar-header h3 { color: #13251e; font-size: 17px; margin: 0; }
.vehicle-sidebar-header > span { background: #edf5f1; border-radius: 999px; color: #315d4d; font-size: 12px; font-weight: 900; padding: 5px 8px; }
.vehicle-list { display: grid; gap: 7px; min-height: 0; overflow-y: auto; padding: 10px; }
.vehicle-row { align-items: center; background: #f8faf9; border: 1px solid transparent; border-radius: 6px; color: #263b33; cursor: pointer; display: grid; font: inherit; gap: 10px; grid-template-columns: 10px minmax(0, 1fr) 22px; padding: 11px 10px; text-align: left; transition: background .16s ease, border-color .16s ease, transform .16s ease; width: 100%; }
.vehicle-row:hover:not(:disabled) { background: #f0f7f4; border-color: #b9d5ca; transform: translateY(-1px); }
.vehicle-row.selected { background: #e5f3ed; border-color: #36866a; box-shadow: inset 3px 0 0 #0b8064; }
.vehicle-row:disabled { cursor: not-allowed; opacity: .68; }
.vehicle-status-dot { background: #7c8983; border: 2px solid #fff; border-radius: 50%; box-shadow: 0 0 0 1px #283c3430; height: 9px; width: 9px; }
.vehicle-row.is-idle .vehicle-status-dot { background: #16885d; }
.vehicle-row.is-active .vehicle-status-dot { background: #1774c9; }
.vehicle-row.is-alert .vehicle-status-dot { background: #d4473f; }
.vehicle-copy, .vehicle-primary, .vehicle-meta, .vehicle-time { display: block; min-width: 0; }
.vehicle-primary { align-items: center; display: flex; gap: 8px; justify-content: space-between; }
.vehicle-primary strong { color: #15281f; font-size: 14px; }
.vehicle-primary em { color: #60736a; font-size: 11px; font-style: normal; font-weight: 800; }
.vehicle-meta { color: #345448; font-size: 12px; font-weight: 800; margin-top: 4px; }
.vehicle-meta.is-unavailable { color: #a03c36; }
.vehicle-time { color: #7a8982; font-size: 11px; margin-top: 3px; }
.locate-glyph { color: #0b8064; font-size: 20px; line-height: 1; text-align: center; }
.vehicle-row:disabled .locate-glyph { color: #98a39e; }
.vehicle-empty { color: #77857e; font-size: 13px; margin: 22px 8px; text-align: center; }
</style>
