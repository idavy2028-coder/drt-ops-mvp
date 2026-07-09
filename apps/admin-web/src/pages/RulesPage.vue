<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="page-kicker">RULES</p>
        <h2 class="page-title">规则配置</h2>
        <p class="page-subtitle">管理等待、绕行、自动派发阈值、人工复核阈值和插单策略。</p>
      </div>
      <span class="status-pill">策略</span>
    </header>

    <section class="table-panel">
      <table class="data-table">
        <thead>
          <tr>
            <th>规则组</th>
            <th>最大等待</th>
            <th>最大绕行</th>
            <th>派发策略</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>默认动态响应</td>
            <td>按后端配置</td>
            <td>按后端配置</td>
            <td>实时插单</td>
          </tr>
        </tbody>
      </table>
    </section>
  </section>
</template>
