# 车辆任务车牌展示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 车辆任务接口提供车牌号，任务页只显示车牌或“未登记车牌”。

**Architecture:** 在 API 层将领域任务实体映射为 `VehicleTaskView`，由 `VehicleRepository` 提供车牌。控制器对列表和动作结果统一输出展示 DTO；前端保持使用 `vehicleId` 执行任务操作，只将 `vehiclePlateNumber` 用于表格显示。

**Tech Stack:** Spring Boot、JPA、MockMvc、Vue 3、TypeScript、Vitest、Vue Testing Library。

## Global Constraints

- 不修改数据库结构、任务状态机、调度规则、车辆 ID 或任务动作请求。
- 保留响应中的 `vehicleId`，仅新增只读 `vehiclePlateNumber`。
- 缺失车牌时 UI 显示“未登记车牌”，不得回退展示 UUID。

---

### Task 1: 任务 API 车牌展示字段

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskView.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskController.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/task/TaskExecutionApiTest.java`

**Interfaces:**
- Consumes: `VehicleTask` 的 `id`、`vehicleId`、`driverId`、`status`、`plannedStartAt`、`stops`，以及 `VehicleRepository.findById(UUID)`。
- Produces: `VehicleTaskView`，新增 `String vehiclePlateNumber`；车牌缺失时为 `null`。

- [ ] **Step 1: 写入失败 API 断言**

为车辆任务列表和一次任务动作响应加入：

```java
.andExpect(jsonPath("$.data[0].vehiclePlateNumber").value("甘J18817D"));
// 动作响应：
.andExpect(jsonPath("$.data.task.vehiclePlateNumber").value("甘J18817D"));
```

- [ ] **Step 2: 运行 API 测试确认失败**

运行：

```powershell
.\\.tools\\apache-maven-3.9.11\\bin\\mvn.cmd -q -pl apps/api -Dtest=TaskExecutionApiTest test
```

预期：JSON 路径 `vehiclePlateNumber` 不存在。

- [ ] **Step 3: 实施 DTO 与控制器映射**

创建 `VehicleTaskView`，从任务和车辆资料生成展示数据；控制器把列表、开始、到站、上下车、完成、故障和延误响应映射到该视图。领域 `VehicleTask` 和请求体保持不变。

- [ ] **Step 4: 运行 API 测试确认通过**

运行：

```powershell
.\\.tools\\apache-maven-3.9.11\\bin\\mvn.cmd -q -pl apps/api -Dtest=TaskExecutionApiTest test
```

预期：测试通过，响应同时保留 `vehicleId` 和新增的 `vehiclePlateNumber`。

### Task 2: 任务页展示与回归保护

**Files:**
- Modify: `apps/admin-web/src/api/types.ts:323-330`
- Modify: `apps/admin-web/src/pages/TasksPage.vue:95-105,420-438`
- Modify: `apps/admin-web/src/pages/tasks-page.test.ts`

**Interfaces:**
- Consumes: API 的 `VehicleTask.vehiclePlateNumber?: string`。
- Produces: “车辆”列表单元格的车牌或“未登记车牌”。

- [ ] **Step 1: 写入失败 UI 测试**

在任务 fixture 为 `vehicle-2` 加入 `vehiclePlateNumber: "甘J18817D"`，并断言：

```ts
expect(await screen.findByText("甘J18817D")).toBeInTheDocument();
expect(screen.queryByText("vehicle-2")).not.toBeInTheDocument();
```

另加缺失车牌 fixture，断言 `未登记车牌` 可见。

- [ ] **Step 2: 运行前端测试确认失败**

运行：

```powershell
npm.cmd test -- --run src/pages/tasks-page.test.ts
```

预期：车牌不可见，UUID 仍在表格中。

- [ ] **Step 3: 实施最小渲染逻辑**

在 `VehicleTask` 加入可选车牌字段；增加 `taskVehicleLabel(task)`，返回非空车牌或“未登记车牌”；表格使用该函数，所有动作仍引用 `task.vehicleId`。

- [ ] **Step 4: 运行前端测试与类型检查**

运行：

```powershell
npm.cmd test -- --run src/pages/tasks-page.test.ts
npm.cmd run typecheck
```

预期：测试与类型检查通过。

### Task 3: 本地试点浏览器验收

**Files:**
- Modify: `docs/pilot/evidence/`（仅在验收记录需要新增时）

- [ ] **Step 1: 部署本地 API 与前端**

重启受影响的本地容器，使 API DTO 与前端页面同时加载新版本；先告知用户页面刷新会清空未提交表单。

- [ ] **Step 2: 浏览器验收**

打开“车辆任务”，确认已完成任务和待发车任务的车辆列均显示车牌；确认页面中不再出现对应 UUID；不执行任务动作。

- [ ] **Step 3: 提交**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskView.java apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskController.java apps/api/src/test/java/com/idavy/drtops/domain/task/TaskExecutionApiTest.java apps/admin-web/src/api/types.ts apps/admin-web/src/pages/TasksPage.vue apps/admin-web/src/pages/tasks-page.test.ts docs/superpowers/specs/2026-08-03-task-vehicle-plate-display-design.md docs/superpowers/plans/2026-08-03-task-vehicle-plate-display.md
git commit -m "feat: display license plates in vehicle tasks"
```
