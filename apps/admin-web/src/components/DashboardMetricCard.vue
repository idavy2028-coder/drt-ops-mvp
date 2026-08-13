<script setup lang="ts">
import type { DashboardMetricStatus } from "../api/types";
import { metricStatusLabel } from "../presentation/dashboardMetrics";

defineProps<{
  label: string;
  primary: string;
  secondary: string;
  status: DashboardMetricStatus;
  comparison: string;
  baseline: string;
  icon: string;
  accent: "teal" | "blue" | "amber" | "violet";
}>();
</script>

<template>
  <article class="dashboard-metric-card" :class="`metric-accent-${accent}`" :aria-label="label">
    <div class="metric-card-heading">
      <h3>{{ label }}</h3>
      <span class="metric-card-icon" aria-hidden="true">{{ icon }}</span>
    </div>
    <div class="metric-card-value">
      <strong>{{ primary }}</strong>
      <span>{{ secondary }}</span>
    </div>
    <div class="metric-card-detail">
      <span class="metric-state" :class="`metric-state-${status.toLowerCase()}`">
        {{ metricStatusLabel(status) }}
      </span>
      <span class="metric-comparison">{{ comparison }}</span>
    </div>
    <p class="metric-baseline">{{ baseline }}</p>
  </article>
</template>

<style scoped>
.dashboard-metric-card {
  --metric-accent: var(--ops-teal, #08a99d);
  position: relative;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--ops-line, #d8e3e9);
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 8px 22px rgba(26, 47, 61, 0.055);
  padding: 16px 16px 14px 19px;
}

.dashboard-metric-card::before {
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: var(--metric-accent);
  content: "";
}

.metric-accent-blue { --metric-accent: var(--ops-blue, #2e7be6); }
.metric-accent-amber { --metric-accent: var(--ops-warning, #d98a22); }
.metric-accent-violet { --metric-accent: #7762bd; }

.metric-card-heading,
.metric-card-detail {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.metric-card-heading h3 {
  margin: 0;
  color: #526572;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: 0.01em;
}

.metric-card-icon {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--metric-accent) 24%, white);
  border-radius: 7px;
  background: color-mix(in srgb, var(--metric-accent) 8%, white);
  color: var(--metric-accent);
  font-size: 12px;
  font-weight: 900;
}

.metric-card-value {
  display: flex;
  align-items: baseline;
  gap: 7px;
  min-height: 37px;
  margin: 8px 0 10px;
}

.metric-card-value strong {
  overflow: hidden;
  color: #172733;
  font-family: "Bahnschrift", "Microsoft YaHei UI", sans-serif;
  font-size: clamp(25px, 2vw, 31px);
  font-variant-numeric: tabular-nums;
  font-weight: 650;
  letter-spacing: -0.025em;
  line-height: 1;
  text-overflow: ellipsis;
}

.metric-card-value span {
  color: #71828d;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  font-weight: 800;
  white-space: nowrap;
}

.metric-card-detail {
  justify-content: flex-start;
}

.metric-state {
  flex: 0 0 auto;
  border-radius: 999px;
  background: #e8f7f3;
  color: #087c70;
  padding: 3px 7px;
  font-size: 11px;
  font-weight: 900;
}

.metric-state-high {
  background: #fff2df;
  color: #a9600f;
}

.metric-state-low {
  background: #eaf2ff;
  color: #2868bf;
}

.metric-state-no_baseline,
.metric-state-no_data {
  background: #edf1f3;
  color: #60717c;
}

.metric-comparison {
  overflow: hidden;
  color: #657781;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-baseline {
  overflow: hidden;
  margin: 8px 0 0;
  color: #83919a;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
