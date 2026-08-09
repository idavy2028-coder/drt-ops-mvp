# 待命位置上报与任务追溯整改 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 阻止非空闲车辆上报待命位置，并让位置历史在不暴露乘客隐私的前提下定位关联任务。

**Architecture:** 后端候选查询与命令写入形成双重状态约束；前端只消费安全候选并在位置事件上提供任务深链。任务页通过查询参数完成初始定位，现有任务状态机与订单权限边界保持不变。

**Tech Stack:** Java 21、Spring Boot、Spring Data JPA、JUnit 5、AssertJ、Vue 3、TypeScript、Vue Testing Library、Vitest。

## Global Constraints

- 只有 `IDLE` 车辆可以执行无任务关联的 `MANUAL_REPORT` 待命位置上报。
- 非 `IDLE` 待命上报返回 HTTP 409，且不写位置事件、不推进快照、不改变任务状态。
- 位置历史只显示任务标识，不展示乘客姓名、电话或订单详情。
- 不新增数据库迁移，不改变现有任务状态机和角色权限。
- 每项生产代码修改前必须先运行对应失败测试并确认失败原因。

---

### Task 1: 后端待命候选过滤

**Files:**
- Modify: `apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationApiTest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationQueryService.java`

**Interfaces:**
- Consumes: `VehicleRepository.findAll()` 和 `Vehicle.currentStatus`。
- Produces: `reportableVehicles()` 只返回状态为 `IDLE` 的 `VehicleLocationReportCandidate`。

- [ ] **Step 1: 写失败测试**

在现有 `VehicleLocationApiTest` 的候选车辆接口用例中准备 `IDLE` 与非 `IDLE` 车辆，断言响应只包含 `IDLE` 车辆，并保留其 `dispatchable` 原值。

- [ ] **Step 2: 运行测试确认 RED**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=VehicleLocationApiTest test`

Expected: FAIL，结果仍包含非 `IDLE` 车辆。

- [ ] **Step 3: 最小实现**

在 `reportableVehicles()` 映射前或映射后增加 `currentStatus == IDLE` 过滤，不引入 `dispatchable=true` 条件。

- [ ] **Step 4: 运行测试确认 GREEN**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=VehicleLocationApiTest test`

Expected: PASS。

### Task 2: 后端待命写入防绕过

**Files:**
- Modify: `apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationApiTest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationCommandService.java`

**Interfaces:**
- Consumes: 无任务关联的 `LocationReportRequest`，条件为 `eventType=MANUAL_REPORT`、`vehicleTaskId=null`、`taskStopId=null`。
- Produces: 复用 `VehicleRepository.findByIdForLocationUpdate(UUID)`；非 `IDLE` 抛出状态冲突，消息为“当前车辆正在执行或等待任务，不能上报待命位置”。

- [ ] **Step 1: 写命令服务失败测试**

在 `VehicleLocationApiTest` 现有服务级用例区增加测试：`DISPATCHED` 车辆提交无任务 `MANUAL_REPORT` 时抛出 409；验证位置事件、快照和车辆状态均未改变。另保留 `IDLE` 成功路径。

- [ ] **Step 2: 运行测试确认 RED**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=VehicleLocationApiTest test`

Expected: FAIL，当前服务接受 `DISPATCHED` 车辆。

- [ ] **Step 3: 写 API 失败测试**

在 `VehicleLocationApiTest` 增加非空闲车辆请求，断言 HTTP 409，并断言事件数量、最新快照和车辆状态均未改变。

- [ ] **Step 4: 运行 API 测试确认 RED**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=VehicleLocationApiTest test`

Expected: FAIL，当前返回成功状态。

- [ ] **Step 5: 最小实现**

向命令服务注入 `VehicleRepository`；仅在无任务 `MANUAL_REPORT` 分支复用现有悲观写锁读取并检查 `IDLE`。任务事件与管理员修正不进入该分支。

- [ ] **Step 6: 运行相关测试确认 GREEN**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=VehicleLocationApiTest test`

Expected: PASS。

### Task 3: 前端待命候选和筛选文案

**Files:**
- Modify: `apps/admin-web/src/pages/vehicle-location-history-page.test.ts`
- Modify: `apps/admin-web/src/pages/VehicleLocationHistoryPage.vue`

**Interfaces:**
- Consumes: `listLocationReportVehicles()` 的候选数组。
- Produces: 仅渲染 `currentStatus === "IDLE"` 候选；空列表提示；任务筛选标签和占位符明确。

- [ ] **Step 1: 写失败测试**

新增用例断言：混合候选只出现 `IDLE`；没有 `IDLE` 时显示“当前没有可上报待命位置的空闲车辆”；任务标签为“任务编号（筛选条件）”，占位符为“输入完整任务编号”。

- [ ] **Step 2: 运行测试确认 RED**

Run: `npm.cmd --prefix apps/admin-web test -- vehicle-location-history-page.test.ts`

Expected: FAIL，当前页面展示 `DISPATCHED` 候选且文案不明确。

- [ ] **Step 3: 最小实现**

增加 `idleReportVehicles` 计算属性并用于下拉渲染；补充空状态文案；修改筛选标签和占位符。保留服务端 409 的既有 `userMessage` 展示和表单不关闭行为。

- [ ] **Step 4: 运行测试确认 GREEN**

Run: `npm.cmd --prefix apps/admin-web test -- vehicle-location-history-page.test.ts`

Expected: PASS。

### Task 4: 位置事件任务关联展示

**Files:**
- Modify: `apps/admin-web/src/pages/vehicle-location-history-page.test.ts`
- Modify: `apps/admin-web/src/pages/VehicleLocationHistoryPage.vue`

**Interfaces:**
- Consumes: `VehicleLocationEventView.vehicleTaskId?: UUID`。
- Produces: 有任务事件渲染 `任务 <前8位>` 和 `/tasks?taskId=<UUID>`；无任务事件渲染“无任务关联”。

- [ ] **Step 1: 写失败测试**

使用完整事件夹具验证任务短编号、精确链接和无任务提示；同时断言页面不包含夹具中的乘客姓名或电话。

- [ ] **Step 2: 运行测试确认 RED**

Run: `npm.cmd --prefix apps/admin-web test -- vehicle-location-history-page.test.ts`

Expected: FAIL，当前时间线没有任务关联区域。

- [ ] **Step 3: 最小实现**

在每条时间线事件中增加任务关联行；只读取 `vehicleTaskId`，不请求订单接口。

- [ ] **Step 4: 运行测试确认 GREEN**

Run: `npm.cmd --prefix apps/admin-web test -- vehicle-location-history-page.test.ts`

Expected: PASS。

### Task 5: 任务页查询参数定位

**Files:**
- Modify: `apps/admin-web/src/pages/tasks-page.test.ts`
- Modify: `apps/admin-web/src/pages/TasksPage.vue`

**Interfaces:**
- Consumes: 当前路由查询参数 `taskId` 和已加载 `VehicleTask[]`。
- Produces: 有效完整 UUID 在任务加载后成为 `selectedTaskId`；无效或不存在参数不误选。

- [ ] **Step 1: 写失败测试**

新增三项行为测试：有效 `taskId` 自动选中；不存在参数保持默认选择；未知 `taskId` 不覆盖默认任务。断言目标任务的站点时间线和动作按钮状态，而不是内部变量。

- [ ] **Step 2: 运行测试确认 RED**

Run: `npm.cmd --prefix apps/admin-web test -- tasks-page.test.ts`

Expected: FAIL，当前页面忽略查询参数。

- [ ] **Step 3: 最小实现**

使用现有 Vue Router 路由状态读取 `taskId`；任务加载完成后仅在精确匹配时选择目标任务，随后允许用户手工切换。

- [ ] **Step 4: 运行测试确认 GREEN**

Run: `npm.cmd --prefix apps/admin-web test -- tasks-page.test.ts`

Expected: PASS。

### Task 6: 回归、文档与本地部署验收准备

**Files:**
- Create: `docs/pilot/evidence/p5-standby-location-task-traceability-2026-08-02.md`

**Interfaces:**
- Consumes: Tasks 1–5 的实现与测试结果。
- Produces: 可部署构建和一份不含敏感信息、可独立审阅的修复验证记录；不改写既有试运行记录。

- [ ] **Step 1: 运行后端全量测试**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api test`

Expected: 失败 0、错误 0。

- [ ] **Step 2: 运行前端全量验证**

Run: `npm.cmd --prefix apps/admin-web test`

Run: `npm.cmd --prefix apps/admin-web run typecheck`

Run: `npm.cmd --prefix apps/admin-web run build`

Expected: 全部退出码 0。

- [ ] **Step 3: 更新记录**

记录 RED/GREEN 证据、回归结果、部署前状态和后续浏览器验收清单；不写乘客姓名、电话或精确坐标。

- [ ] **Step 4: 差异与敏感信息检查**

Run: `git diff --check`

Run: `git status --short`

Expected: 无格式错误；仅本任务文件和用户原有未提交文件存在差异。
