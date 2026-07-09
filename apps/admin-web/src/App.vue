<template>
  <AppLayout />
</template>

<script setup lang="ts">
import AppLayout from "./layouts/AppLayout.vue";
</script>

<style>
:root {
  color: #17201c;
  background: #eef2ef;
  font-family: "Aptos", "Microsoft YaHei UI", "Segoe UI", sans-serif;
  font-synthesis: none;
  text-rendering: geometricPrecision;
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  min-width: 320px;
  min-height: 100vh;
}

button,
input,
select,
textarea {
  font: inherit;
}

a {
  color: inherit;
  text-decoration: none;
}

.page {
  display: grid;
  gap: 18px;
}

.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  border-bottom: 1px solid #d9e1dc;
  padding-bottom: 18px;
}

.page-kicker {
  margin: 0 0 8px;
  color: #66736d;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0;
}

.page-title {
  margin: 0;
  font-size: 32px;
  line-height: 1.1;
}

.page-subtitle {
  max-width: 720px;
  margin: 10px 0 0;
  color: #5b6762;
  font-size: 15px;
  line-height: 1.7;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.metric-panel,
.work-panel,
.table-panel {
  border: 1px solid #d9e1dc;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 12px 28px rgba(23, 32, 28, 0.06);
}

.metric-panel {
  padding: 18px;
}

.metric-label {
  margin: 0;
  color: #66736d;
  font-size: 13px;
  font-weight: 700;
}

.metric-value {
  margin: 8px 0 0;
  color: #17201c;
  font-size: 28px;
  font-weight: 800;
}

.work-panel {
  padding: 22px;
}

.section-title {
  margin: 0 0 12px;
  font-size: 18px;
}

.section-copy {
  margin: 0;
  color: #5b6762;
  line-height: 1.7;
}

.table-panel {
  overflow: hidden;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
}

.data-table th,
.data-table td {
  border-bottom: 1px solid #e5ebe7;
  padding: 13px 16px;
  text-align: left;
  white-space: nowrap;
}

.data-table th {
  background: #f6f8f6;
  color: #53615a;
  font-size: 13px;
  font-weight: 800;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 4px 10px;
  background: #e6f3ee;
  color: #17634b;
  font-size: 12px;
  font-weight: 800;
}

@media (max-width: 820px) {
  .page-title {
    font-size: 26px;
  }

  .page-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .data-table {
    min-width: 640px;
  }
}
</style>
