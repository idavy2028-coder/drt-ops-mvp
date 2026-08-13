<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{
  code?: string;
}>();

const presentation = computed(() => {
  switch (props.code) {
    case "IDLE":
    case "AVAILABLE":
      return { label: "空闲", tone: "success" };
    case "DISPATCHED":
      return { label: "已派单", tone: "active" };
    case "IN_SERVICE":
      return { label: "执行中", tone: "active" };
    case "OFFLINE":
      return { label: "离线", tone: "danger" };
    case "UNAVAILABLE":
      return { label: "不可用", tone: "danger" };
    default:
      return { label: "状态未知", tone: "neutral" };
  }
});
</script>

<template>
  <span
    class="vehicle-status-badge"
    :class="`status-${presentation.tone}`"
    :title="code || '未提供车辆状态'"
  >
    {{ presentation.label }}
  </span>
</template>

<style scoped>
.vehicle-status-badge {
  border-radius: 999px;
  display: inline-flex;
  font-size: 12px;
  font-weight: 800;
  line-height: 1;
  padding: 5px 9px;
  white-space: nowrap;
}

.status-neutral { background: #eef1ef; color: #53615a; }
.status-active { background: #e7f0f7; color: #235f7d; }
.status-success { background: #e6f4eb; color: #17643f; }
.status-danger { background: #fff0ee; color: #a7352e; }
</style>
