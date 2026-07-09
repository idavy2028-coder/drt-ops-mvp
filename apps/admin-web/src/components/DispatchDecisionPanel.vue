<script setup lang="ts">
defineProps<{
  title?: string;
}>();
</script>

<template>
  <section class="work-panel">
    <h3 class="section-title">{{ title ?? "算法解释" }}</h3>
    <p class="section-copy">优先同向合乘，约束最大等待、最大绕行和车辆任务稳定性。</p>
    <ul class="timeline">
      <li class="timeline-item">
        <span class="timeline-index">1</span>
        <div class="timeline-body">
          <p class="timeline-title">候选车辆过滤</p>
          <p class="timeline-meta">班次可用、容量可用、服务区匹配</p>
        </div>
      </li>
      <li class="timeline-item">
        <span class="timeline-index">2</span>
        <div class="timeline-body">
          <p class="timeline-title">路径插入评分</p>
          <p class="timeline-meta">等待、绕行、稳定性、利用率加权</p>
        </div>
      </li>
      <li class="timeline-item">
        <span class="timeline-index">3</span>
        <div class="timeline-body">
          <p class="timeline-title">决策输出</p>
          <p class="timeline-meta">自动派发、人工确认或不可服务</p>
        </div>
      </li>
    </ul>
  </section>
</template>
