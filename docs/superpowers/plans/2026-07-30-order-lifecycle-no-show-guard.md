# 订单全流程与“乘客未到”防误操作 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为“乘客未到”增加车辆到站、订单/任务/站点状态和 5 分钟等候期的前后端双重门禁，并保留幂等、资源释放和完整审计。

**Architecture:** 后端新增纯领域策略 `NoShowEligibilityPolicy`，统一计算资格、最早处理时间和阻断原因；`OrderExceptionService` 在事务内重新加载并锁定事实数据后执行策略。订单列表通过独立响应模型暴露资格快照，前端据此显示原因、倒计时和二次确认，但执行接口仍重新校验。

**Tech Stack:** Java 21、Spring Boot、Spring Data JPA、JUnit 5、MockMvc、Vue 3、TypeScript、Vitest、Testing Library。

## Global Constraints

- 默认等候期固定为 5 分钟。
- 可等待起点为 `max(estimatedBoardingAt, actualArrivalAt)`。
- 资格判断使用服务端时间，前端时间仅用于展示。
- 必须存在匹配任务和上车站点的 `PICKUP_ARRIVED` 位置事件。
- 不引入主管逐单审批，不自动判定爽约，不提供终态订单原地回滚。
- 后端门禁必须先于前端交互发布。
- 不修改既有调度阈值、权重或 `REALTIME_INSERTION`。
- 审计不得保存乘客电话或精确坐标。

---

### Task 1: 爽约资格领域策略

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/order/NoShowEligibility.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/order/NoShowEligibilityPolicy.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/order/NoShowEligibilityPolicyTest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStop.java`

**Interfaces:**
- Consumes: `RideOrder`, `VehicleTask`, `TaskStop`, `boolean pickupArrivalEventExists`, `OffsetDateTime now`
- Produces: `NoShowEligibilityPolicy.evaluate(...) -> NoShowEligibility`
- Produces: `NoShowEligibility(boolean eligible, OffsetDateTime eligibleAt, long waitedSeconds, String reasonCode, String reasonMessage)`
- Produces: `TaskStop.getActualArrivalAt()`

- [x] **Step 1: 写纯策略失败测试**

覆盖以下独立行为，预期值使用固定时间字面量：

```java
private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-30T12:10:00+08:00");

@Test
void rejectsConfirmedOrderBeforeTaskStarts() {
    NoShowEligibility result = policy.evaluate(
            OrderStatus.CONFIRMED, TaskStatus.DISPATCHED, "PLANNED",
            OffsetDateTime.parse("2026-07-30T12:00:00+08:00"),
            null, false, NOW);
    assertThat(result.reasonCode()).isEqualTo("NO_SHOW_ORDER_NOT_IN_PROGRESS");
}

@Test
void earlyArrivalWaitsFiveMinutesAfterEstimatedBoarding() {
    NoShowEligibility result = policy.evaluate(
            OrderStatus.IN_PROGRESS, TaskStatus.IN_PROGRESS, "ARRIVED",
            OffsetDateTime.parse("2026-07-30T12:05:00+08:00"),
            OffsetDateTime.parse("2026-07-30T12:00:00+08:00"),
            true, OffsetDateTime.parse("2026-07-30T12:09:59+08:00"));
    assertThat(result.eligible()).isFalse();
    assertThat(result.eligibleAt()).isEqualTo("2026-07-30T12:10:00+08:00");
}

@Test
void lateArrivalAllowsAtExactlyFiveMinutes() {
    NoShowEligibility result = policy.evaluate(
            OrderStatus.IN_PROGRESS, TaskStatus.IN_PROGRESS, "ARRIVED",
            OffsetDateTime.parse("2026-07-30T12:00:00+08:00"),
            OffsetDateTime.parse("2026-07-30T12:05:00+08:00"),
            true, OffsetDateTime.parse("2026-07-30T12:10:00+08:00"));
    assertThat(result.eligible()).isTrue();
    assertThat(result.waitedSeconds()).isEqualTo(300);
}
```

继续分别覆盖：任务非 `IN_PROGRESS`、站点非 `ARRIVED`、缺少到站事件、站点已 `BOARDED`、订单终态。

策略使用固定阻断码：`NO_SHOW_ORDER_NOT_IN_PROGRESS`、`NO_SHOW_TASK_NOT_IN_PROGRESS`、`NO_SHOW_PICKUP_NOT_ARRIVED`、`NO_SHOW_PICKUP_EVENT_MISSING`、`NO_SHOW_PASSENGER_ALREADY_BOARDED`、`NO_SHOW_WAITING_PERIOD_NOT_ELAPSED`、`NO_SHOW_ORDER_ALREADY_TERMINAL`。

- [x] **Step 2: 运行测试并确认 RED**

Run:

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api -Dtest=NoShowEligibilityPolicyTest test
```

Expected: 编译失败，提示 `NoShowEligibilityPolicy`/`NoShowEligibility` 不存在。

- [x] **Step 3: 实现最小纯策略**

```java
public final class NoShowEligibilityPolicy {
    static final Duration WAITING_PERIOD = Duration.ofMinutes(5);

    public NoShowEligibility evaluate(
            OrderStatus orderStatus,
            TaskStatus taskStatus,
            String pickupStopStatus,
            OffsetDateTime estimatedBoardingAt,
            OffsetDateTime actualArrivalAt,
            boolean pickupArrivalEventExists,
            OffsetDateTime now) {
        // 按订单、任务、站点、事件、上车状态、时间顺序返回第一个稳定阻断码。
    }
}
```

在 `TaskStop` 增加 `getActualArrivalAt()`，不改变现有状态转换。

- [x] **Step 4: 运行策略测试并确认 GREEN**

Run 同 Step 2。Expected: `NoShowEligibilityPolicyTest` 全部通过。

- [x] **Step 5: 提交**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/order/NoShowEligibility.java apps/api/src/main/java/com/idavy/drtops/domain/order/NoShowEligibilityPolicy.java apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStop.java apps/api/src/test/java/com/idavy/drtops/domain/order/NoShowEligibilityPolicyTest.java
git commit -m "feat: add no-show eligibility policy"
```

---

### Task 2: 后端强制门禁、幂等与拒绝审计

**Files:**
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/order/OrderExceptionService.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderController.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskRepository.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationEventRepository.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/order/NoShowRejectedAuditService.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/order/OrderExceptionApiTest.java`

**Interfaces:**
- Consumes: `NoShowEligibilityPolicy`
- Produces: `POST /api/orders/{id}/no-show` body `{"reason":"...","idempotencyKey":"<uuid>"}`
- Produces: `409` error codes from the policy
- Produces: `ORDER_NO_SHOW_REJECTED` in an independent transaction
- Produces: repository method `existsByVehicleTaskIdAndTaskStopIdAndEventType(...)`

- [x] **Step 1: 改写现有成功用例为合法执行链并添加失败用例**

将现有直接对 `CONFIRMED` 订单调用爽约的测试改为：任务开始、上车站点到达、存在 `PICKUP_ARRIVED`、时间已满 5 分钟。新增：

```java
@Test
void noShowBeforeTaskStartsReturnsConflictWithoutMutation() throws Exception {
    UUID orderId = createConfirmedOrder();
    UUID taskId = createTask(orderId);

    mockMvc.perform(noShowRequest(orderId, UUID.randomUUID()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.data.code").value("NO_SHOW_ORDER_NOT_IN_PROGRESS"));

    assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.CONFIRMED);
    assertThat(vehicleTaskRepository.findById(taskId).orElseThrow().getStatus())
            .isEqualTo(TaskStatus.DISPATCHED);
    assertThat(auditLogRepository.findByEntityId(orderId))
            .anyMatch(log -> log.getAction().equals("ORDER_NO_SHOW_REJECTED"));
}
```

增加 4:59 被拒绝、5:00 成功、缺少事件被拒绝、已上车被拒绝、相同幂等键只写一次成功审计、共享任务只取消本单站点。

- [x] **Step 2: 运行接口测试并确认 RED**

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api -Dtest=OrderExceptionApiTest test
```

Expected: 新门禁用例失败；当前接口错误地关闭订单或无法解析 `idempotencyKey`。

- [x] **Step 3: 实现锁定查询和事件查询**

在 `VehicleTaskRepository` 增加按订单查找活动任务并施加写锁的查询；在 `VehicleLocationEventRepository` 增加：

```java
boolean existsByVehicleTaskIdAndTaskStopIdAndEventType(
        UUID vehicleTaskId, UUID taskStopId, LocationEventType eventType);
```

- [x] **Step 4: 实现事务内强制门禁**

`OrderExceptionService.noShow(...)` 接收 `reason` 和 `idempotencyKey`，重新加载任务和上车站点，调用策略。阻断时调用 `NoShowRejectedAuditService` 的 `REQUIRES_NEW` 方法后抛出包含稳定代码的 `409`；成功时保持现有任务/站点/资源联动，并在元数据写入 `estimatedBoardingAt`、`pickupArrivedAt`、`eligibleAt`、`waitedSeconds`、`idempotencyKey`、释放资源标记和取消站点数。

- [x] **Step 5: 实现请求校验与幂等**

新增 `NoShowRequest`：

```java
public record NoShowRequest(
        @NotBlank String reason,
        @NotNull UUID idempotencyKey) {}
```

对已存在的相同成功幂等键返回当前订单，不重复改变状态或写审计；不同键对终态订单返回 `409`。

- [x] **Step 6: 运行接口和相关任务测试并确认 GREEN**

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api -Dtest=OrderExceptionApiTest,TaskExecutionApiTest test
```

Expected: 全部通过。

- [x] **Step 7: 提交**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/order apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskRepository.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationEventRepository.java apps/api/src/test/java/com/idavy/drtops/domain/order/OrderExceptionApiTest.java
git commit -m "feat: enforce no-show operation guard"
```

---

### Task 3: 订单列表资格快照

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderView.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderQueryService.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderController.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/order/RideOrderApiTest.java`

**Interfaces:**
- Produces: `RideOrderView` 保留原订单字段并增加 `canMarkNoShow`、`noShowEligibleAt`、`noShowWaitedSeconds`、`noShowBlockReason`
- Consumes: Task 1 的策略和 Task 2 的事实查询

- [x] **Step 1: 添加列表资格失败测试**

创建两个订单：一个尚未到站，一个到站但剩余 5 分钟；断言列表分别返回阻断原因和 `noShowEligibleAt`，且不暴露新敏感字段。

- [x] **Step 2: 运行测试并确认 RED**

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api -Dtest=RideOrderApiTest test
```

Expected: JSON 中缺少 `canMarkNoShow`。

- [x] **Step 3: 实现查询服务和响应模型**

`RideOrderQueryService.list()` 批量读取订单后，为每单加载关联活动任务、上车站点和事件资格；无活动任务时返回对应阻断原因。控制器列表端点返回 `List<RideOrderView>`。

- [x] **Step 4: 运行测试并确认 GREEN**

Run 同 Step 2。Expected: `RideOrderApiTest` 通过。

- [x] **Step 5: 提交**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderView.java apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderQueryService.java apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderController.java apps/api/src/test/java/com/idavy/drtops/domain/order/RideOrderApiTest.java
git commit -m "feat: expose no-show eligibility on orders"
```

---

### Task 4: 前端倒计时和高风险二次确认

**Files:**
- Modify: `apps/admin-web/src/api/types.ts`
- Modify: `apps/admin-web/src/api/orders.ts`
- Create: `apps/admin-web/src/components/NoShowConfirmDialog.vue`
- Create: `apps/admin-web/src/components/no-show-confirm-dialog.test.ts`
- Modify: `apps/admin-web/src/pages/OrdersPage.vue`
- Modify: `apps/admin-web/src/pages/orders-page.test.ts`

**Interfaces:**
- Consumes: `RideOrder.canMarkNoShow`, `noShowEligibleAt`, `noShowWaitedSeconds`, `noShowBlockReason`
- Produces: `markOrderNoShow(orderId, reason, idempotencyKey)`
- Produces: 对话框事件 `confirm({reason, idempotencyKey})`、`close`

- [x] **Step 1: 写页面和对话框失败测试**

覆盖：

```ts
expect(screen.queryByRole("button", { name: "乘客未到" })).not.toBeInTheDocument();
expect(screen.getByText("车辆尚未到达上车点")).toBeInTheDocument();
```

以及：等待期内显示禁用按钮和剩余时间；资格成立时按钮可用；点击后展示订单短号、计划/实际时间、影响说明；未选择原因不能确认；提交中不能重复点击；`409` 后刷新并展示后端原因。

- [x] **Step 2: 运行前端测试并确认 RED**

```powershell
npm.cmd --prefix apps/admin-web test -- orders-page.test.ts no-show-confirm-dialog.test.ts
```

Expected: 对话框不存在，当前页面对 `CONFIRMED`/`IN_PROGRESS` 直接显示危险按钮。

- [x] **Step 3: 扩展 API 类型和方法**

```ts
export function markOrderNoShow(
  orderId: UUID,
  reason: string,
  idempotencyKey: UUID
): Promise<RideOrder> {
  return request(`/api/orders/${orderId}/no-show`, {
    method: "POST",
    body: JSON.stringify({ reason, idempotencyKey })
  });
}
```

- [x] **Step 4: 实现确认对话框**

对话框使用语义化 `role="dialog"`，仅允许固定原因“乘客在等待期内未出现”，确认按钮文案为“确认乘客未到并关闭订单”，关闭影响清晰展示。

- [x] **Step 5: 实现订单列表门禁展示**

删除仅凭订单状态计算的 `canCloseNoShow()`；完全使用服务端资格字段。倒计时归零后刷新列表获取服务端资格，不在前端自行授权。

- [x] **Step 6: 运行前端专项测试并确认 GREEN**

Run 同 Step 2。Expected: 全部通过。

- [x] **Step 7: 提交**

```powershell
git add apps/admin-web/src/api apps/admin-web/src/components/NoShowConfirmDialog.vue apps/admin-web/src/components/no-show-confirm-dialog.test.ts apps/admin-web/src/pages/OrdersPage.vue apps/admin-web/src/pages/orders-page.test.ts
git commit -m "feat: add no-show confirmation guard"
```

---

### Task 5: 全量回归、文档和浏览器验收准备

**Files:**
- Modify: `docs/pilot/evidence/p5-day-1-operation-log-2026-07-30.md`
- Modify: `progress.md`
- Modify: `docs/superpowers/plans/2026-07-30-order-lifecycle-no-show-guard.md`

**Interfaces:**
- Verifies: 后端、前端、构建和状态机主链
- Produces: 可供真实浏览器验收的执行记录

- [x] **Step 1: 运行后端全量测试**

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api test
```

Expected: 失败 0、错误 0。

- [x] **Step 2: 运行前端全量验证**

```powershell
npm.cmd --prefix apps/admin-web test
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run build
```

Expected: 测试、类型检查、构建全部退出 0。

- [x] **Step 3: 检查差异和敏感信息**

```powershell
git diff --check
rg -n "(?<!\\d)1\\d{10}(?!\\d)" docs/superpowers docs/pilot/evidence/p5-day-1-operation-log-2026-07-30.md progress.md
```

Expected: 无空白错误，新增记录不包含真实手机号或精确坐标。

- [x] **Step 4: 更新实施记录**

在本计划勾选已完成步骤；在运营日志和 `progress.md` 记录实现提交、测试数量、已知限制，并明确尚未使用真实订单执行浏览器验收。

- [x] **Step 5: 提交**

```powershell
git add docs/superpowers/plans/2026-07-30-order-lifecycle-no-show-guard.md docs/pilot/evidence/p5-day-1-operation-log-2026-07-30.md progress.md
git commit -m "docs: record no-show guard implementation"
```

- [ ] **Step 6: 真实浏览器验收门禁**

只有用户明确授权使用测试订单或下一笔真实订单后，才执行设计文档第 9.4 节的浏览器验收；未获授权时报告为待验收，不自行创建订单。
