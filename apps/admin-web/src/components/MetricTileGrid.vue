<script setup lang="ts">
import type { DecimalValue } from "../api/types";

defineProps<{
  metrics: Array<{
    label: string;
    value: DecimalValue;
    tone?: string;
  }>;
}>();
</script>

<template>
  <div class="summary-grid">
    <article v-for="metric in metrics" :key="metric.label" class="metric-panel">
      <p class="metric-label">{{ metric.label }}</p>
      <p class="metric-value">{{ metric.value }}</p>
      <p v-if="metric.tone" class="section-copy">{{ metric.tone }}</p>
    </article>
  </div>
</template>
