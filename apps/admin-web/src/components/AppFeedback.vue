<script setup lang="ts">
import { feedbackStore } from "../stores/feedbackStore";
</script>

<template>
  <aside class="feedback-region" aria-live="polite" aria-label="系统提示">
    <article v-for="item in feedbackStore.items" :key="item.id" class="feedback-item" :class="`feedback-${item.tone}`">
      <span>{{ item.message }}</span>
      <button type="button" :aria-label="`关闭提示：${item.message}`" @click="feedbackStore.dismiss(item.id)">关闭</button>
    </article>
  </aside>
</template>

<style scoped>
.feedback-region { bottom: 24px; display: grid; gap: 10px; position: fixed; right: 24px; width: min(360px, calc(100vw - 32px)); z-index: 30; }
.feedback-item { align-items: center; border: 1px solid #d6ded9; border-radius: 8px; box-shadow: 0 14px 30px rgba(26, 42, 34, 0.16); display: flex; gap: 14px; justify-content: space-between; padding: 12px 14px; }
.feedback-item button { background: transparent; border: 0; color: inherit; cursor: pointer; font-weight: 800; padding: 0; }
.feedback-success { background: #e8f5ee; border-color: #9dceb2; color: #17643f; }
.feedback-error { background: #fff0ee; border-color: #efb8b2; color: #9f2e28; }
.feedback-info { background: #edf3f6; border-color: #bed0da; color: #275b72; }
</style>
