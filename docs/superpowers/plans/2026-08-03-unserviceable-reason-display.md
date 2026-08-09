# 不可服务原因展示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 让订单中心在订单不可服务时显示可读的失败原因，并可展开查看调度候选拒绝与规则诊断。

**Architecture:** 后端扩展订单查询视图，按订单关联最近一次调度决策组装只读 `dispatchFailure` 摘要；前端订单行负责简短原因，展开面板负责详细诊断。调度写入流程不变。

**Tech Stack:** Spring Boot/JPA、PostgreSQL JSONB、Vue 3、TypeScript、Vitest、Testing Library。

## Global Constraints

- 只读组装诊断，不新增订单或调度写入。
- 现有订单字段和非不可服务状态行为保持兼容。
- 原因文案优先使用服务端摘要，未知代码必须可见且不静默丢失。

---

### Task 1: 后端订单诊断摘要

**Files:**
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderView.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderQueryService.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecisionReadService.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/order/RideOrderQueryServiceTest.java`（若不存在则在现有订单查询测试位置新增）

- [ ] **Step 1: Write the failing test**
  构造 `UNSERVICEABLE` 订单和最近调度决策，断言订单视图包含 `dispatchFailure.code`、`summary`、`candidateCount`、`rejectedReasons`、`map` 和规则阈值；无决策时断言 `dispatchFailure` 为 null。
- [ ] **Step 2: Run test to verify it fails**
  Run: `mvn -q -pl apps/api -Dtest=RideOrderQueryServiceTest test`
  Expected: FAIL because the view has no dispatch failure field.
- [ ] **Step 3: Write minimal implementation**
  增加只读记录类型和最近决策查询，解析 JSONB 拒绝原因/解释，组装稳定字段；按 `WAIT_TIME_EXCEEDED`、`ALL_CANDIDATES_REJECTED`、`NO_CANDIDATE_TASK`、`MAP_ROUTE_UNAVAILABLE` 生成简短中文摘要。
- [ ] **Step 4: Run test to verify it passes**
  Run: `mvn -q -pl apps/api -Dtest=RideOrderQueryServiceTest test`
  Expected: PASS。

### Task 2: 前端订单列表摘要与展开详情

**Files:**
- Modify: `apps/admin-web/src/api/types.ts`
- Modify: `apps/admin-web/src/pages/OrdersPage.vue`
- Test: `apps/admin-web/src/pages/orders-page.test.ts`

- [ ] **Step 1: Write the failing test**
  使用包含 `dispatchFailure` 的不可服务订单，断言行内出现“查看原因”和简短摘要；点击后出现候选数、拒绝原因和最大等待阈值。
- [ ] **Step 2: Run test to verify it fails**
  Run: `npm.cmd --prefix apps/admin-web test -- --run src/pages/orders-page.test.ts`
  Expected: FAIL because the page currently renders only the status badge.
- [ ] **Step 3: Write minimal implementation**
  扩展 `RideOrder` 类型；在订单行增加 `details` 展开区域、可访问按钮和原因摘要，保持表格列结构；对缺少诊断字段的旧订单不显示入口。
- [ ] **Step 4: Run test to verify it passes**
  Run: `npm.cmd --prefix apps/admin-web test -- --run src/pages/orders-page.test.ts`
  Expected: PASS。

### Task 3: 回归验证与本地验收

- [ ] **Step 1: Run focused backend tests**
  Run: `mvn -q -pl apps/api test`
- [ ] **Step 2: Run focused frontend tests and build**
  Run: `npm.cmd --prefix apps/admin-web test -- --run src/pages/orders-page.test.ts`
  Run: `npm.cmd --prefix apps/admin-web run build`
- [ ] **Step 3: Restart local frontend and browser-check**
  重启本地前端容器，刷新 `/orders`，确认不可服务订单行直接显示原因，展开后可见拒绝原因与阈值；不触发调度、不修改订单。
