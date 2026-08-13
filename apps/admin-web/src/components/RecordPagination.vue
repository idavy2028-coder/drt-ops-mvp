<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{
  currentPage: number;
  totalItems: number;
  pageSize: number;
}>();

const emit = defineEmits<{
  "update:currentPage": [page: number];
}>();

const pageCount = computed(() => Math.max(1, Math.ceil(props.totalItems / props.pageSize)));

function move(page: number): void {
  if (page >= 1 && page <= pageCount.value && page !== props.currentPage) {
    emit("update:currentPage", page);
  }
}
</script>

<template>
  <nav class="record-pagination" aria-label="记录分页">
    <span>第 {{ currentPage }} / {{ pageCount }} 页 · 共 {{ totalItems }} 条</span>
    <div class="record-pagination-actions">
      <button
        type="button"
        class="secondary-button"
        :disabled="currentPage <= 1"
        @click="move(currentPage - 1)"
      >
        上一页
      </button>
      <button
        type="button"
        class="secondary-button"
        :disabled="currentPage >= pageCount"
        @click="move(currentPage + 1)"
      >
        下一页
      </button>
    </div>
  </nav>
</template>

<style scoped>
.record-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 44px;
  color: #52606d;
  font-size: 13px;
}

.record-pagination-actions {
  display: flex;
  gap: 8px;
}
</style>
