# 任务选择与运营日统计修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 可靠切换车辆任务页面的操作上下文，并让运营看板按上海运营日统计订单和任务。

**Architecture:** 前端以页面级回归测试锁定任务选择行为，只有复现失败时才采用最小状态管理修复。后端把运营日计算集中在 `OperationsMetricsService`，以 `Asia/Shanghai` 将 `OffsetDateTime` 映射为 `LocalDate`；前端现有请求日期逻辑不重复实现。

**Tech Stack:** Vue 3、TypeScript、Vitest、Spring Boot、Java 21、JUnit 5、AssertJ。

## Global Constraints

- 运营日时区固定为 `Asia/Shanghai`。
- 不更改指标接口路径、指标名称、调度算法或权限模型。
- 所有业务代码改动必须先有失败测试，再写最小实现。
- 每个任务完成后执行独立验证和代码审阅。
- 实现完成后必须重新执行本地真实业务验收，并更新验收记录。

---

### Task 1: 锁定车辆任务选择行为

**Files:**
- Modify: `apps/admin-web/src/pages/tasks-page.test.ts`
- Modify only if test fails: `apps/admin-web/src/pages/TasksPage.vue`

**Interfaces:**
- Consumes: `VehicleTask.id`、`VehicleTask.status`、`VehicleTask.stops`。
- Produces: 覆盖非首条 `DISPATCHED` 任务选择、时间线和按钮状态的页面回归测试。

- [ ] **Step 1: 写出失败的页面测试**

在任务 mock 中保留一条 `COMPLETED` 历史任务，并新增 `task-dispatched`：

```ts
{
  id: "task-dispatched",
  status: "DISPATCHED",
  stops: [
    { id: "boarding-dispatched", stopType: "BOARDING", status: "PLANNED" },
    { id: "alighting-dispatched", stopType: "ALIGHTING", status: "PLANNED" }
  ]
}
```

点击 `task-dispatched` 所在行的“选择”按钮，断言该按钮 `aria-pressed="true"`、历史任务按钮为 `false`、新任务站点文本可见，且“发车”按钮启用。

- [ ] **Step 2: 运行聚焦测试并确认结果**

运行：

```powershell
Set-Location apps/admin-web
npm run test -- tasks-page.test.ts
```

预期：若无法切换任务，测试失败并显示选中状态或“发车”按钮断言失败；若通过，记录为自动化验收环境未复现。

- [ ] **Step 3: 仅在失败时实施最小选择状态修复**

在 `TasksPage.vue` 新增：

```ts
function selectTask(taskId: string) {
  selectedTaskId.value = taskId;
}
```

把模板中的内联赋值替换为 `@click="selectTask(task.id)"`。将 `selectedTask` 改为只返回匹配任务或 `null`；在 `loadVehicleTasks()` 中仅当当前选择为空或已不存在时回退到首条任务。

- [ ] **Step 4: 运行前端验证**

```powershell
Set-Location apps/admin-web
npm run test -- tasks-page.test.ts
npm run typecheck
npm run test
```

预期：聚焦测试、类型检查和全量前端测试均通过。

- [ ] **Step 5: 提交任务 1**

```powershell
git add apps/admin-web/src/pages/TasksPage.vue apps/admin-web/src/pages/tasks-page.test.ts
git commit -m "fix: stabilize vehicle task selection"
```

若聚焦测试无需代码修复即通过，只提交测试文件，并用提交说明标明增加任务选择回归覆盖。

### Task 2: 统一上海运营日统计

**Files:**
- Modify: `apps/api/src/main/java/com/idavy/drtops/metrics/OperationsMetricsService.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/metrics/OperationsMetricsController.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/metrics/OperationsMetricsServiceTest.java`

**Interfaces:**
- Consumes: `RideOrder.requestedDepartureAt`、`VehicleTask.plannedStartAt`（`OffsetDateTime`）。
- Produces: 对上海运营日一致的 `OperationsSummary`。

- [ ] **Step 1: 写出跨 UTC 日界失败测试**

新增订单和任务，时间都设置为：

```java
OffsetDateTime.parse("2026-07-12T23:17:00Z")
```

调用 `calculateSummary(LocalDate.parse("2026-07-13"))`，断言订单数和任务完成率包含该数据；调用 `calculateSummary(LocalDate.parse("2026-07-12"))`，断言不包含该数据。

- [ ] **Step 2: 运行聚焦后端测试并确认失败**

```powershell
$env:Path = "$PWD\.tools\apache-maven-3.9.11\bin;$env:Path"
mvn -q -pl apps/api -Dtest=OperationsMetricsServiceTest test
```

预期：当前实现将 UTC `23:17` 归到 2026-07-12，针对 2026-07-13 的断言失败。

- [ ] **Step 3: 实施运营日转换**

在 `OperationsMetricsService` 中新增：

```java
private static final ZoneId OPERATING_ZONE = ZoneId.of("Asia/Shanghai");

private LocalDate operatingDateOf(OffsetDateTime timestamp) {
    return timestamp.atZoneSameInstant(OPERATING_ZONE).toLocalDate();
}
```

把订单与任务筛选中的 `toLocalDate()` 改为 `operatingDateOf(...)`；默认日期改为 `LocalDate.now(OPERATING_ZONE)`。控制器默认日期分支也使用 `LocalDate.now(OPERATING_ZONE)`。

- [ ] **Step 4: 运行后端验证**

```powershell
$env:Path = "$PWD\.tools\apache-maven-3.9.11\bin;$env:Path"
mvn -q -pl apps/api -Dtest=OperationsMetricsServiceTest test
mvn -q -pl apps/api test
```

预期：跨 UTC 日界测试和 API 全量测试通过。

- [ ] **Step 5: 提交任务 2**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/metrics/OperationsMetricsService.java apps/api/src/main/java/com/idavy/drtops/metrics/OperationsMetricsController.java apps/api/src/test/java/com/idavy/drtops/metrics/OperationsMetricsServiceTest.java
git commit -m "fix: align metrics with Shanghai operating day"
```

### Task 3: 全链路复验和验收结论更新

**Files:**
- Modify: `docs/release/internal-acceptance-2026-07-13.md`
- Modify: `.superpowers/sdd/progress.md`

**Interfaces:**
- Consumes: Task 1 与 Task 2 的验证结果和本地演示环境。
- Produces: 更新后的内部验收结论与可追溯验证记录。

- [ ] **Step 1: 运行构建与接口验证**

```powershell
Set-Location apps/admin-web
npm run typecheck
npm run test
npm run build
Set-Location ..\..
$env:Path = "$PWD\.tools\apache-maven-3.9.11\bin;$env:Path"
mvn -q -pl apps/api test
```

预期：所有命令退出码为 0；仅保留已知的 MapLibre 构建包体积警告。

- [ ] **Step 2: 重走本地真实验收流程**

运营员录入带“复验”标识的即时需求，调度员执行调度与人工复核，在车辆任务页选择新建任务并通过页面完成发车、到站、上车、下车、完成。管理员刷新看板并核对订单量、确认率、人工复核率和任务完成率，再检查审计日志的完整动作序列。

- [ ] **Step 3: 更新验收记录**

将问题 1 和问题 2 改为“已修复并复验通过”，并记录复验订单/任务短号、页面验证结果、看板指标值、审计动作序列和剩余生产试点风险。

- [ ] **Step 4: 检查改动并提交任务 3**

```powershell
git diff --check
git status --short
git add docs/release/internal-acceptance-2026-07-13.md .superpowers/sdd/progress.md
git commit -m "docs: record acceptance revalidation"
```

预期：`git diff --check` 无格式错误；仅预期文件进入提交。
