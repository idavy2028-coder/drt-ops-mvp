<script setup lang="ts">
import { computed } from "vue";
import type { DashboardDistributionItem } from "../api/types";
import { distributionPercentages } from "../presentation/dashboardMetrics";

const props = defineProps<{
  title: string;
  unit: string;
  items: DashboardDistributionItem[];
}>();

const total = computed(() => props.items.reduce((sum, item) => sum + Math.max(0, item.count), 0));
const percentages = computed(() => distributionPercentages(props.items));
const legendItems = computed(() => props.items.map((item, index) => ({
  ...item,
  percentage: percentages.value[index],
  color: colorFor(item.key, index)
})));
const donutBackground = computed(() => {
  let start = 0;
  const segments = legendItems.value
    .filter((item) => item.percentage > 0)
    .map((item) => {
      const end = start + item.percentage;
      const segment = `${item.color} ${start.toFixed(1)}% ${end.toFixed(1)}%`;
      start = end;
      return segment;
    });
  return `conic-gradient(${segments.join(", ")})`;
});

function colorFor(key: string, index: number): string {
  if (key === "COMPLETED" || key === "IDLE") {
    return "#08a99d";
  }
  if (key === "IN_PROGRESS" || key === "IN_SERVICE") {
    return "#2e7be6";
  }
  if (key === "PENDING" || key === "PENDING_DEPARTURE") {
    return "#d98a22";
  }
  if (key === "EXCEPTION_CANCELLED" || key === "UNAVAILABLE") {
    return "#e15b5b";
  }
  return ["#08a99d", "#2e7be6", "#d98a22", "#7762bd", "#e15b5b"][index % 5];
}
</script>

<template>
  <section class="distribution-panel">
    <header class="distribution-header">
      <h3>{{ title }}</h3>
      <span>共 {{ total }} {{ unit }}</span>
    </header>

    <div v-if="total > 0" class="distribution-content">
      <div
        class="distribution-donut"
        role="img"
        :aria-label="`${title}，共 ${total} ${unit}`"
        :style="{ background: donutBackground }"
      >
        <span><strong>{{ total }}</strong>{{ unit }}</span>
      </div>
      <ul class="distribution-legend">
        <li v-for="item in legendItems" :key="item.key">
          <i :style="{ background: item.color }" aria-hidden="true"></i>
          <span
            data-testid="distribution-percentage"
            :data-percentage="item.percentage"
          >{{ `${item.label} ${item.count} ${unit} · ${item.percentage.toFixed(1)}%` }}</span>
        </li>
      </ul>
    </div>
    <div v-else class="distribution-empty">
      <span aria-hidden="true">○</span>
      <p>当日暂无可统计数据</p>
    </div>
  </section>
</template>

<style scoped>
.distribution-panel {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--ops-line, #d8e3e9);
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 8px 20px rgba(26, 47, 61, 0.045);
}

.distribution-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 43px;
  border-bottom: 1px solid var(--ops-line, #d8e3e9);
  padding: 8px 13px;
}

.distribution-header h3 {
  position: relative;
  margin: 0;
  padding-left: 10px;
  color: #1e303b;
  font-size: 15px;
}

.distribution-header h3::before {
  position: absolute;
  top: 3px;
  bottom: 3px;
  left: 0;
  width: 3px;
  border-radius: 2px;
  background: var(--ops-teal, #08a99d);
  content: "";
}

.distribution-header span {
  color: #788993;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  font-weight: 700;
}

.distribution-content {
  display: grid;
  grid-template-columns: 116px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  min-height: 146px;
  padding: 12px 14px;
}

.distribution-donut {
  position: relative;
  display: grid;
  width: 98px;
  height: 98px;
  place-items: center;
  border-radius: 50%;
  box-shadow: inset 0 0 0 1px rgba(35, 59, 73, 0.05);
}

.distribution-donut::after {
  position: absolute;
  inset: 22px;
  border-radius: 50%;
  background: #ffffff;
  box-shadow: 0 0 0 1px rgba(35, 59, 73, 0.05);
  content: "";
}

.distribution-donut span {
  position: relative;
  z-index: 1;
  display: grid;
  color: #74848e;
  font-size: 11px;
  text-align: center;
}

.distribution-donut strong {
  color: #1a2b36;
  font-family: "Bahnschrift", "Microsoft YaHei UI", sans-serif;
  font-size: 21px;
  font-variant-numeric: tabular-nums;
  line-height: 1.05;
}

.distribution-legend {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.distribution-legend li {
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr);
  align-items: center;
  gap: 7px;
  color: #536873;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.distribution-legend i {
  width: 8px;
  height: 8px;
  border-radius: 2px;
}

.distribution-empty {
  display: grid;
  min-height: 146px;
  place-items: center;
  align-content: center;
  color: #84939c;
}

.distribution-empty span {
  font-size: 30px;
  line-height: 1;
}

.distribution-empty p {
  margin: 8px 0 0;
  font-size: 12px;
}

@media (max-width: 520px) {
  .distribution-content {
    grid-template-columns: 94px minmax(0, 1fr);
    padding-inline: 10px;
  }

  .distribution-donut {
    width: 82px;
    height: 82px;
  }

  .distribution-donut::after {
    inset: 19px;
  }
}
</style>
