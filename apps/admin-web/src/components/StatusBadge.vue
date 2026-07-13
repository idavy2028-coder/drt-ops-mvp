<script setup lang="ts">
import { computed } from "vue";
import { labelFor, statusTone } from "../presentation/operations";

const props = defineProps<{
  code: string;
}>();

const label = computed(() => labelFor(props.code));
const tone = computed(() => statusTone(props.code));
</script>

<template>
  <span class="status-badge" :class="`status-${tone}`" :title="code">{{ label }}</span>
</template>

<style scoped>
.status-badge { border-radius: 999px; display: inline-flex; font-size: 12px; font-weight: 800; line-height: 1; padding: 5px 9px; white-space: nowrap; }
.status-neutral { background: #eef1ef; color: #53615a; }
.status-active { background: #e7f0f7; color: #235f7d; }
.status-success { background: #e6f4eb; color: #17643f; }
.status-warning { background: #fff4dd; color: #8a5b11; }
.status-danger { background: #fff0ee; color: #a7352e; }
</style>
