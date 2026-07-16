# 运营看板指标格式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将运营看板的比例和分钟指标转换为调度员可直接理解的业务格式。

**Architecture:** 在 `presentation/operations.ts` 增加纯展示层格式化函数，不改变 API 返回值。`DashboardPage.vue` 在构造指标卡与概览列表时调用这些函数，确保同一指标在同一页面采用同一口径。

**Tech Stack:** Vue 3、TypeScript、Vitest、Vue Testing Library。

## Global Constraints

- 比例指标显示为四舍五入后的整数百分比，例如 `0.875` 为 `88%`。
- 等待和绕行指标显示为一位小数的分钟值，例如 `3` 为 `3.0 分钟`。
- 不修改后端指标算法、接口、数据库或其他页面展示。
- 无法转换的值保留原始文本。

---

### Task 1: 添加运营指标格式化函数

**Files:**
- Modify: `apps/admin-web/src/presentation/operations.ts`
- Test: `apps/admin-web/src/presentation/operations.test.ts`

**Interfaces:**
- Consumes: `DecimalValue`，即 `number | string`。
- Produces: `formatPercentage(value: DecimalValue): string` 与 `formatMinutes(value: DecimalValue): string`，供运营看板调用。

- [ ] **Step 1: 写入失败测试**

```ts
import { formatMinutes, formatPercentage } from "./operations";

it("formats a decimal ratio as a percentage", () => {
  expect(formatPercentage("0.875")).toBe("88%");
  expect(formatPercentage(0)).toBe("0%");
});

it("formats minutes with one decimal place", () => {
  expect(formatMinutes("3")).toBe("3.0 分钟");
});

it("preserves an invalid metric value", () => {
  expect(formatPercentage("unknown")).toBe("unknown");
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm.cmd run test -- operations.test.ts`

Expected: FAIL，提示 `formatPercentage` 和 `formatMinutes` 尚未导出。

- [ ] **Step 3: 实现最小格式化函数**

```ts
import type { DecimalValue } from "../api/types";

function metricNumber(value: DecimalValue): number | null {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function formatPercentage(value: DecimalValue): string {
  const parsed = metricNumber(value);
  return parsed === null ? String(value) : `${Math.round(parsed * 100)}%`;
}

export function formatMinutes(value: DecimalValue): string {
  const parsed = metricNumber(value);
  return parsed === null ? String(value) : `${parsed.toFixed(1)} 分钟`;
}
```

- [ ] **Step 4: 运行格式化单元测试**

Run: `npm.cmd run test -- operations.test.ts`

Expected: PASS，现有审计原因测试与新增格式化测试全部通过。

- [ ] **Step 5: 提交任务**

```powershell
git add apps/admin-web/src/presentation/operations.ts apps/admin-web/src/presentation/operations.test.ts
git commit -m "feat: format dashboard metrics"
```

### Task 2: 在运营看板统一使用业务格式

**Files:**
- Modify: `apps/admin-web/src/pages/DashboardPage.vue`
- Test: `apps/admin-web/src/pages/dashboard-page.test.ts`

**Interfaces:**
- Consumes: `formatPercentage(value: DecimalValue): string` 与 `formatMinutes(value: DecimalValue): string`。
- Produces: 指标卡及“今日调度表现”“服务闭环”区块中的统一展示文本。

- [ ] **Step 1: 写入失败页面测试**

```ts
it("renders dashboard rates and durations in operational formats", async () => {
  render(DashboardPage);

  expect((await screen.findAllByText("100%")).length).toBeGreaterThan(0);
  expect(screen.getAllByText("3.0 分钟").length).toBeGreaterThan(0);
});
```

- [ ] **Step 2: 运行页面测试确认失败**

Run: `npm.cmd run test -- dashboard-page.test.ts`

Expected: FAIL，页面仍显示 `1.0000` 和 `3.00`。

- [ ] **Step 3: 接入格式化函数**

```ts
import { formatMinutes, formatPercentage } from "../presentation/operations";

const metrics = computed(() => [
  { label: "订单量", value: summary.value.orderCount, tone: "当日需求总量" },
  { label: "订单确认率", value: formatPercentage(summary.value.confirmationRate), tone: "已确认与可执行需求" },
  { label: "自动派发率", value: formatPercentage(summary.value.autoDispatchRate), tone: "算法直接落单" },
  { label: "人工复核率", value: formatPercentage(summary.value.manualReviewRate), tone: "需要调度员介入" },
  { label: "平均等待时间", value: formatMinutes(summary.value.averageWaitMinutes), tone: "服务等待时长" },
  { label: "平均绕行时间", value: formatMinutes(summary.value.averageDetourMinutes), tone: "服务绕行时长" },
  { label: "任务完成率", value: formatPercentage(summary.value.taskCompletionRate), tone: "执行闭环" },
  { label: "车辆利用率", value: formatPercentage(summary.value.vehicleUtilizationRate), tone: "有任务车辆占比" }
]);
```

概览列表也使用同一函数：订单确认、自动派发、人工复核、任务完成和异常关闭调用 `formatPercentage`；平均等待和平均绕行调用 `formatMinutes`。

- [ ] **Step 4: 运行页面测试、类型检查与生产构建**

Run: `npm.cmd run test -- dashboard-page.test.ts; npm.cmd run typecheck; npm.cmd run build`

Expected: PASS，页面测试通过，类型检查无错误，生产构建完成。

- [ ] **Step 5: 运行完整前端测试并提交任务**

Run: `npm.cmd run test`

Expected: PASS，所有前端测试通过。

```powershell
git add apps/admin-web/src/pages/DashboardPage.vue apps/admin-web/src/pages/dashboard-page.test.ts
git commit -m "feat: present dashboard metrics clearly"
```
