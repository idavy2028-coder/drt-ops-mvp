<script setup lang="ts">
import { ref } from "vue";
import type { TaskStop } from "../api/types";
import TaskStopTimeline from "../components/TaskStopTimeline.vue";

const lastAction = ref("等待操作");

const sampleStops: TaskStop[] = [
  {
    id: "stop-1",
    virtualStopId: "virtual-stop-1",
    sequenceNumber: 1,
    stopType: "BOARDING",
    plannedArrivalAt: "2026-07-08T10:30:00+08:00",
    status: "PLANNED"
  },
  {
    id: "stop-2",
    virtualStopId: "virtual-stop-2",
    sequenceNumber: 2,
    stopType: "ALIGHTING",
    plannedArrivalAt: "2026-07-08T10:46:00+08:00",
    status: "PLANNED"
  }
];

function markAction(action: string) {
  lastAction.value = action;
}
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="page-kicker">TASKS</p>
        <h2 class="page-title">车辆任务</h2>
        <p class="page-subtitle">跟踪车辆任务状态、站点到达、乘客上下车和异常处置。</p>
      </div>
      <span class="status-pill">执行</span>
    </header>

    <div class="summary-grid">
      <article class="metric-panel">
        <p class="metric-label">运行任务</p>
        <p class="metric-value">0</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">异常任务</p>
        <p class="metric-value">0</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">最近操作</p>
        <p class="metric-value">{{ lastAction }}</p>
      </article>
    </div>

    <div class="split-grid">
      <section class="work-panel">
        <h3 class="section-title">任务执行</h3>
        <div class="toolbar">
          <button class="primary-button" type="button" @click="markAction('发车')">发车</button>
          <button class="secondary-button" type="button" @click="markAction('到站')">到站</button>
          <button class="secondary-button" type="button" @click="markAction('上车')">上车</button>
          <button class="secondary-button" type="button" @click="markAction('下车')">下车</button>
          <button class="secondary-button" type="button" @click="markAction('完成')">完成</button>
          <button class="danger-button" type="button" @click="markAction('车辆故障')">车辆故障</button>
        </div>
      </section>

      <section class="work-panel">
        <h3 class="section-title">站点时间线</h3>
        <TaskStopTimeline :stops="sampleStops" />
      </section>
    </div>
  </section>
</template>
