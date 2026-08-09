# 订单与车辆任务按创建日期分组实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with review checkpoints.

**Goal:** 为订单中心和车辆任务页补充真实创建时间，并按浏览器本地当天把记录分成“今日新增”和“历史”两个可见区域。

**Architecture:** 后端读取视图直接暴露实体已有的 `createdAt`，不新增数据库字段。前端页面用共享的本地日期判断和创建时间倒序分组逻辑渲染两段表格，保留现有行级操作与详情组件。

**Tech Stack:** Spring Boot/JUnit，Vue 3/TypeScript，Vitest、Testing Library、vue-tsc、Vite。

## Global Constraints

- 分组依据必须是 `createdAt`，禁止用预计出发或计划发车时间替代。
- 当天判断使用浏览器本地日期；历史记录必须仍然显示。
- 不修改数据库 schema、订单状态、任务状态、车辆资源或 API 写入行为。
- 生产代码变更前必须先看到对应测试失败；完成后运行页面测试、类型检查和生产构建。

---

### Task 1: 后端视图暴露创建时间

**Files:**
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderView.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskView.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/order/RideOrderApiTest.java`（使用现有订单视图测试文件）
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/task/TaskExecutionApiTest.java`（使用现有任务 API 测试文件）

- [ ] **Step 1: 写视图序列化失败测试**
  - 在现有订单和任务视图响应断言中要求 JSON 包含实体创建时间 `createdAt`。
  - 使用既有测试实体创建时间，断言响应字段值与实体一致。
- [ ] **Step 2: 运行 API 定向测试确认失败**
  - Run: `.\\tools\\apache-maven-3.9.11\\bin\\mvn.cmd -q -pl apps/api -Dtest=RideOrderApiTest,VehicleTaskApiTest test`
  - Expected: FAIL，响应视图缺少 `createdAt`。
- [ ] **Step 3: 最小实现**
  - 在两个 record 增加 `OffsetDateTime createdAt`，并从 `RideOrder.getCreatedAt()`、`VehicleTask.getCreatedAt()` 映射。
- [ ] **Step 4: 运行定向测试确认通过**
  - 重跑同一 Maven 命令，Expected: PASS。

### Task 2: 前端类型与订单分组

**Files:**
- Modify: `apps/admin-web/src/api/types.ts`
- Modify: `apps/admin-web/src/pages/OrdersPage.vue`
- Test: `apps/admin-web/src/pages/orders-page.test.ts`

- [ ] **Step 1: 写订单分组失败测试**
  - 构造同一浏览器日期和历史日期的两条订单，断言出现“今日新增订单”“历史订单”标题、数量及各自乘客只在对应区域。
  - 让预计出发时间与创建日期相反，证明分组使用 `createdAt`。
- [ ] **Step 2: 运行订单页面定向测试确认失败**
  - Run: `npm.cmd --prefix apps/admin-web test -- src/pages/orders-page.test.ts`
  - Expected: FAIL，页面当前没有分组标题。
- [ ] **Step 3: 最小实现**
  - `RideOrder` 增加 `createdAt`。
  - 在 `OrdersPage.vue` 增加本地日期键、分组 computed 和创建时间倒序排序。
  - 将现有表头与行渲染提取为两个相同分组区域，空分组显示明确空状态。
- [ ] **Step 4: 运行订单测试确认通过**
  - 重跑定向 Vitest，Expected: PASS。

### Task 3: 车辆任务类型与分组

**Files:**
- Modify: `apps/admin-web/src/api/types.ts`
- Modify: `apps/admin-web/src/pages/TasksPage.vue`
- Test: `apps/admin-web/src/pages/tasks-page.test.ts`

- [ ] **Step 1: 写任务分组失败测试**
  - 构造当天和历史任务，断言“今日新增任务”“历史任务”标题、数量、车牌和选择按钮均位于正确区域。
  - 让计划发车日期与创建日期不一致，证明分组依据 `createdAt`。
- [ ] **Step 2: 运行任务页面定向测试确认失败**
  - Run: `npm.cmd --prefix apps/admin-web test -- src/pages/tasks-page.test.ts`
  - Expected: FAIL，页面当前没有分组标题。
- [ ] **Step 3: 最小实现**
  - `VehicleTask` 增加 `createdAt`。
  - 在 `TasksPage.vue` 增加分组 computed，并让首个可选任务仍按原逻辑选中；两个区域复用现有任务表头、操作和选择行为。
- [ ] **Step 4: 运行任务测试确认通过**
  - 重跑定向 Vitest，Expected: PASS。

### Task 4: 回归验证与本地部署

**Files:**
- Modify: 页面测试夹具中所有 `RideOrder`/`VehicleTask` 数据，补齐 `createdAt`。

- [ ] **Step 1: 运行前端全量测试**
  - Run: `npm.cmd --prefix apps/admin-web test`
  - Expected: 所有测试通过。
- [ ] **Step 2: 运行类型检查和构建**
  - Run: `npm.cmd --prefix apps/admin-web typecheck`
  - Run: `npm.cmd --prefix apps/admin-web build`
  - Expected: 均退出码 0。
- [ ] **Step 3: 运行 API 全量测试**
  - Run: `.\\tools\\apache-maven-3.9.11\\bin\\mvn.cmd -q -pl apps/api test`
  - Expected: 退出码 0。
- [ ] **Step 4: 部署并浏览器验收**
  - 重启本地 web/API 开发容器或使用当前挂载环境，确认订单页和车辆任务页分别出现今日/历史两个区域、数量正确、车牌和操作按钮保留。
- [ ] **Step 5: 提交代码**
  - `git add apps/api apps/admin-web docs/superpowers/plans/2026-08-09-daily-record-grouping.md`
  - `git commit -m "feat: group orders and tasks by creation date"`
