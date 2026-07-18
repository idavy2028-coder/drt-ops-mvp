<script setup lang="ts">
import { ref } from "vue";
import type { VirtualStopImportResult } from "../api/types";

const props = defineProps<{ disabled: boolean; loading: boolean; result?: VirtualStopImportResult }>();
const emit = defineEmits<{ import: [file: File] }>();
const selectedFile = ref<File>();
const localError = ref("");

function selectFile(event: Event): void {
  const file = (event.target as HTMLInputElement).files?.[0];
  selectedFile.value = file;
  localError.value = file && !file.name.toLowerCase().endsWith(".csv") ? "仅支持 UTF-8 CSV 文件" : "";
}

function submit(): void {
  if (!selectedFile.value) {
    localError.value = "请先选择虚拟站点 CSV 文件";
    return;
  }
  if (!selectedFile.value.name.toLowerCase().endsWith(".csv")) {
    localError.value = "仅支持 UTF-8 CSV 文件";
    return;
  }
  localError.value = "";
  emit("import", selectedFile.value);
}
</script>

<template>
  <section class="import-panel" aria-labelledby="virtual-stop-import-title">
    <header>
      <div>
        <p class="section-kicker">PILOT DATA</p>
        <h3 id="virtual-stop-import-title">虚拟站点批量导入</h3>
        <p>使用 UTF-8 CSV 模板导入通渭县高频上下车点。服务区外站点会保留为未启用状态。</p>
      </div>
      <a class="template-link" href="/pilot/tongwei-virtual-stops-template.csv" download>下载模板</a>
    </header>
    <div class="import-controls">
      <input aria-label="虚拟站点 CSV 文件" type="file" accept=".csv,text/csv" :disabled="disabled || loading" @change="selectFile" />
      <button type="button" class="primary-button" :disabled="disabled || loading" @click="submit">{{ loading ? "正在导入" : "导入站点" }}</button>
    </div>
    <p v-if="localError" class="import-error">{{ localError }}</p>
    <div v-if="result" class="import-result" aria-live="polite">
      <strong>已创建 {{ result.createdCount }} 个站点，跳过 {{ result.skippedCount }} 行。</strong>
      <ul v-if="result.issues.length">
        <li v-for="issue in result.issues" :key="`${issue.rowNumber}-${issue.message}`">第 {{ issue.rowNumber }} 行：{{ issue.message }}</li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.import-panel { border: 1px solid var(--line); background: var(--surface); padding: 18px; }
header { display: flex; justify-content: space-between; gap: 16px; }
.section-kicker { margin: 0 0 4px; color: var(--accent); font-size: 12px; font-weight: 800; }
h3 { margin: 0; font-size: 20px; }
header p:not(.section-kicker) { color: var(--ink-muted); margin: 6px 0 0; }
.template-link { align-self: start; color: var(--accent); font-weight: 700; white-space: nowrap; }
.import-controls { align-items: center; display: flex; flex-wrap: wrap; gap: 10px; margin-top: 16px; }
.import-error { color: var(--danger); font-weight: 700; margin: 10px 0 0; }
.import-result { border-left: 3px solid var(--accent); color: var(--ink); margin-top: 14px; padding-left: 10px; }
.import-result ul { color: var(--ink-muted); margin: 8px 0 0; padding-left: 18px; }
@media (max-width: 640px) { header { flex-direction: column; } }
</style>
