<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="page-kicker">DISPATCH</p>
        <h2 class="page-title">调度工作台</h2>
        <p class="page-subtitle">聚焦待调度订单、候选车辆、人工复核和派发结果。</p>
      </div>
      <span class="status-pill">实时</span>
    </header>

    <div class="summary-grid">
      <article class="metric-panel">
        <p class="metric-label">待调度</p>
        <p class="metric-value">0</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">待复核</p>
        <p class="metric-value">0</p>
      </article>
      <article class="metric-panel">
        <p class="metric-label">候选车辆</p>
        <p class="metric-value">0</p>
      </article>
    </div>

    <section class="work-panel">
      <h3 class="section-title">调度队列</h3>
      <p class="section-copy">待接入订单列表、算法推荐和人工确认操作。</p>
    </section>
  </section>
</template>
