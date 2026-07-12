# 人工复核队列 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在调度工作台中提供待人工复核调度决策的查询、确认派单和拒绝闭环。

**Architecture:** 后端以 `DispatchDecision` 为队列主记录，并以 `RideOrder.status == PENDING_MANUAL_REVIEW` 过滤有效项；队列项显式携带决策 ID，避免前端从订单列表推断。前端工作台统一加载订单、任务和复核队列，复核面板只负责输入与事件，页面负责 API 调用、刷新和错误状态。

**Tech Stack:** Spring Boot 3.5、Spring Data JPA、JUnit 5/MockMvc、Vue 3、TypeScript、Vitest、Testing Library。

## Global Constraints

- 默认中文界面与中文开发文档。
- 复用既有 `POST /api/dispatch-decisions/{decisionId}/approve` 和 `POST /api/dispatch-decisions/{decisionId}/reject`。
- 队列仅包含订单状态为 `PENDING_MANUAL_REVIEW`，且决策结果为 `MANUAL_REVIEW` 或 `PENDING_MANUAL_REVIEW` 的记录。
- 拒绝操作必须在前端校验非空原因；后端既有状态校验继续生效。
- 不改算法评分阈值、数据库 schema、乘客端、驾驶员端或权限体系。

---

## 文件结构

- `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewQueueItem.java`：队列接口的稳定响应模型。
- `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewQueueService.java`：决策和订单状态的联合筛选。
- `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewController.java`：新增只读队列端点，保留既有 approve/reject 路由。
- `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecision.java`：补齐队列响应所需的候选数量 getter。
- `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecisionRepository.java`：按多个决策结果和创建时间查询。
- `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/ManualReviewQueueApiTest.java`：MockMvc 队列筛选回归测试。
- `apps/admin-web/src/api/manualReviews.ts`：复核队列、确认、拒绝 API 客户端。
- `apps/admin-web/src/api/types.ts`：`ManualReviewQueueItem` 类型。
- `apps/admin-web/src/components/ManualReviewQueuePanel.vue`：复核列表、拒绝原因输入和事件发射。
- `apps/admin-web/src/components/manual-review-queue-panel.test.ts`：面板交互测试。
- `apps/admin-web/src/pages/DispatchWorkbenchPage.vue`：统一加载数据，处理复核动作与刷新。
- `apps/admin-web/src/pages/dispatch-workbench.test.ts`：工作台 API 编排测试。

### Task 1: 后端人工复核队列接口

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewQueueItem.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewQueueService.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/ManualReviewQueueApiTest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecision.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecisionRepository.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewController.java`

**Interfaces:**
- Consumes: `DispatchDecisionRepository.findByDecisionResultInOrderByCreatedAtAsc(Collection<String>)` 和 `RideOrderRepository.findById(UUID)`。
- Produces: `GET /api/dispatch-decisions/manual-review -> ApiResponse<List<ManualReviewQueueItem>>`。
- `ManualReviewQueueItem` 字段：`UUID decisionId, UUID orderId, String passengerName, int passengerCount, OffsetDateTime requestedDepartureAt, UUID bestVehicleId, int candidateCount`。

- [ ] **Step 1: 写失败的队列 API 测试**

```java
mockMvc.perform(get("/api/dispatch-decisions/manual-review"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", hasSize(2)))
        .andExpect(jsonPath("$.data[0].decisionId").value(currentDecision.getId().toString()))
        .andExpect(jsonPath("$.data[0].orderId").value(currentOrder.getId().toString()))
        .andExpect(jsonPath("$.data[0].passengerName").value("Manual review rider"))
        .andExpect(jsonPath("$.data[1].decisionId").value(legacyDecision.getId().toString()));
```

测试准备三个订单：两个调用 `order.markPendingManualReview("score")`，分别保存 `MANUAL_REVIEW` 和 `PENDING_MANUAL_REVIEW` 决策；第三个订单保持 `PENDING_DISPATCH`，即使有人工复核决策也不得出现在响应中。

- [ ] **Step 2: 运行测试确认失败**

Run: `\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=ManualReviewQueueApiTest test`

Expected: FAIL，原因是 `/api/dispatch-decisions/manual-review` 尚未映射。

- [ ] **Step 3: 实现最小队列模型和查询**

```java
public record ManualReviewQueueItem(
        UUID decisionId,
        UUID orderId,
        String passengerName,
        int passengerCount,
        OffsetDateTime requestedDepartureAt,
        UUID bestVehicleId,
        int candidateCount) {
}
```

```java
List<DispatchDecision> findByDecisionResultInOrderByCreatedAtAsc(Collection<String> decisionResults);
```

```java
public List<ManualReviewQueueItem> list() {
    return dispatchDecisionRepository.findByDecisionResultInOrderByCreatedAtAsc(
                    List.of("MANUAL_REVIEW", "PENDING_MANUAL_REVIEW"))
            .stream()
            .flatMap(decision -> rideOrderRepository.findById(decision.getRideOrderId())
                    .filter(order -> order.getStatus() == OrderStatus.PENDING_MANUAL_REVIEW)
                    .map(order -> new ManualReviewQueueItem(
                            decision.getId(), order.getId(), order.getPassengerName(),
                            order.getPassengerCount(), order.getRequestedDepartureAt(),
                            decision.getBestVehicleId(), decision.getCandidateCount()))
                    .stream())
            .toList();
}
```

在 `ManualReviewController` 注入 `ManualReviewQueueService` 并新增：

```java
@GetMapping("/manual-review")
ApiResponse<List<ManualReviewQueueItem>> listManualReviewQueue() {
    return ApiResponse.ok(manualReviewQueueService.list());
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=ManualReviewQueueApiTest,ManualReviewApiTest test`

Expected: PASS；队列筛选、既有确认派单、拒绝派单测试全部通过。

- [ ] **Step 5: 提交后端队列接口**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewQueueItem.java apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewQueueService.java apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewController.java apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecision.java apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecisionRepository.java apps/api/src/test/java/com/idavy/drtops/domain/dispatch/ManualReviewQueueApiTest.java
git commit -m "feat: add manual review queue API"
```

### Task 2: 前端复核 API 与队列面板

**Files:**
- Create: `apps/admin-web/src/api/manualReviews.ts`
- Create: `apps/admin-web/src/components/ManualReviewQueuePanel.vue`
- Create: `apps/admin-web/src/components/manual-review-queue-panel.test.ts`
- Modify: `apps/admin-web/src/api/types.ts`

**Interfaces:**
- Consumes: `GET /api/dispatch-decisions/manual-review`、approve 和 reject 既有路由。
- Produces: `listManualReviews(): Promise<ManualReviewQueueItem[]>`、`approveManualReview(decisionId)`、`rejectManualReview(decisionId, reason)`。
- `ManualReviewQueuePanel` props：`items: ManualReviewQueueItem[]`、`processingDecisionId?: UUID`、`error?: string`；事件：`approve(decisionId: UUID)`、`reject(payload: { decisionId: UUID; reason: string })`。

- [ ] **Step 1: 写失败的面板交互测试**

```ts
const { emitted } = render(ManualReviewQueuePanel, {
  props: { items: [review] }
});

await userEvent.click(screen.getByRole("button", { name: "拒绝" }));
await userEvent.click(screen.getByRole("button", { name: "确认拒绝" }));
expect(emitted().reject).toBeUndefined();
expect(screen.getByText("请填写拒绝原因")).toBeInTheDocument();

await userEvent.type(screen.getByLabelText("拒绝原因"), "车辆临时不可用");
await userEvent.click(screen.getByRole("button", { name: "确认拒绝" }));
expect(emitted().reject?.[0]).toEqual([{ decisionId: review.decisionId, reason: "车辆临时不可用" }]);
```

同时断言队列项显示乘客、人数、候选车辆；空数组显示“暂无待复核订单”。

- [ ] **Step 2: 运行测试确认失败**

Run: `npm.cmd run test -- src/components/manual-review-queue-panel.test.ts`

Expected: FAIL，原因是面板组件和复核 API 客户端不存在。

- [ ] **Step 3: 实现类型、API 客户端与面板**

```ts
export interface ManualReviewQueueItem {
  decisionId: UUID;
  orderId: UUID;
  passengerName: string;
  passengerCount: number;
  requestedDepartureAt: IsoDateTime;
  bestVehicleId?: UUID;
  candidateCount: number;
}
```

```ts
export function listManualReviews(): Promise<ManualReviewQueueItem[]> {
  return request<ManualReviewQueueItem[]>("/api/dispatch-decisions/manual-review");
}

export function approveManualReview(decisionId: UUID): Promise<DispatchResult> {
  return request<DispatchResult>(`/api/dispatch-decisions/${decisionId}/approve`, { method: "POST" });
}

export function rejectManualReview(decisionId: UUID, reason: string): Promise<DispatchResult> {
  return request<DispatchResult>(`/api/dispatch-decisions/${decisionId}/reject`, {
    method: "POST",
    body: JSON.stringify({ reason })
  });
}
```

面板中用 `reactive<Record<string, string>>({})` 保存每个 decisionId 的拒绝原因；只有 `reason.trim()` 非空才 emit `reject`。确认与拒绝按钮在 `processingDecisionId === item.decisionId` 时禁用。

- [ ] **Step 4: 运行组件测试确认通过**

Run: `npm.cmd run test -- src/components/manual-review-queue-panel.test.ts`

Expected: PASS，拒绝原因校验和事件载荷均正确。

- [ ] **Step 5: 提交前端复核面板**

```powershell
git add apps/admin-web/src/api/types.ts apps/admin-web/src/api/manualReviews.ts apps/admin-web/src/components/ManualReviewQueuePanel.vue apps/admin-web/src/components/manual-review-queue-panel.test.ts
git commit -m "feat: add manual review queue panel"
```

### Task 3: 接入调度工作台并验证刷新闭环

**Files:**
- Modify: `apps/admin-web/src/pages/DispatchWorkbenchPage.vue`
- Modify: `apps/admin-web/src/pages/dispatch-workbench.test.ts`
- Modify: `apps/admin-web/e2e/dispatch-flow.spec.ts`

**Interfaces:**
- Consumes: `listOrders()`、`listTasks()`、`listManualReviews()`、`approveManualReview()`、`rejectManualReview()`。
- Produces: 工作台中的复核队列操作；成功后统一调用 `loadWorkbench()` 刷新订单、任务和队列。

- [ ] **Step 1: 写失败的工作台测试**

```ts
vi.mock("../api/manualReviews", () => ({
  listManualReviews: vi.fn().mockResolvedValue([review]),
  approveManualReview: vi.fn().mockResolvedValue({ vehicleTaskId: "task-1" }),
  rejectManualReview: vi.fn()
}));

render(DispatchWorkbenchPage);
expect(await screen.findByText("人工复核队列")).toBeInTheDocument();
await userEvent.click(screen.getByRole("button", { name: "确认派单" }));
expect(approveManualReview).toHaveBeenCalledWith(review.decisionId);
await waitFor(() => expect(listTasks).toHaveBeenCalledTimes(2));
```

增加拒绝分支：填写原因后点击确认拒绝，断言 `rejectManualReview(review.decisionId, reason)` 被调用；模拟 reject 拒绝时断言错误文案可见且队列项仍可见。

- [ ] **Step 2: 运行测试确认失败**

Run: `npm.cmd run test -- src/pages/dispatch-workbench.test.ts`

Expected: FAIL，原因是工作台尚未加载队列或绑定复核事件。

- [ ] **Step 3: 实现工作台数据编排**

```ts
const reviews = ref<ManualReviewQueueItem[]>([]);
const processingDecisionId = ref<UUID>();
const actionError = ref("");

async function loadWorkbench() {
  status.value = "";
  const [loadedOrders, loadedTasks, loadedReviews] = await Promise.all([
    listOrders(), listTasks(), listManualReviews()
  ]);
  orders.value = loadedOrders;
  tasks.value = loadedTasks;
  reviews.value = loadedReviews;
}

async function approve(decisionId: UUID) {
  processingDecisionId.value = decisionId;
  actionError.value = "";
  try {
    await approveManualReview(decisionId);
    await loadWorkbench();
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : "人工确认失败";
  } finally {
    processingDecisionId.value = undefined;
  }
}
```

实现 `reject({ decisionId, reason })` 时采用同样的 `try/finally` 结构并调用 `rejectManualReview`。模板用 `<ManualReviewQueuePanel>` 替换静态 `DispatchDecisionPanel`，传入 `reviews`、`processingDecisionId`、`actionError`，并绑定 `@approve` 与 `@reject`。

- [ ] **Step 4: 运行工作台和端到端测试确认通过**

Run: `npm.cmd run test -- src/pages/dispatch-workbench.test.ts src/components/manual-review-queue-panel.test.ts`

Expected: PASS，确认、拒绝、刷新和错误保留均通过。

Run: `npm.cmd run e2e -- dispatch-flow.spec.ts`

Expected: PASS，路由 mock 增加 `GET /api/dispatch-decisions/manual-review` 与 approve 响应后，页面可完成确认派单并显示生成的任务。

- [ ] **Step 5: 完整验证并提交工作台闭环**

Run: `\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api test`

Run: `python -m pytest -v`

Run: `npm.cmd run typecheck`

Run: `npm.cmd run test`

Run: `npm.cmd run build`

```powershell
git add apps/admin-web/src/pages/DispatchWorkbenchPage.vue apps/admin-web/src/pages/dispatch-workbench.test.ts apps/admin-web/e2e/dispatch-flow.spec.ts
git commit -m "feat: complete manual review workbench flow"
```

## 验收清单

- 待人工复核订单在工作台显示决策 ID、乘客、人数、候选车辆和候选数。
- 确认派单后订单从复核队列移除，车辆任务列表出现对应任务。
- 拒绝原因为空时不能调用 API；填写原因后拒绝成功，订单从队列移除。
- 接口失败时页面不丢失队列项和已输入的拒绝原因。
- 真实 PostGIS 验收使用 `GET /api/dispatch-decisions/manual-review`、approve/reject 接口和 `127.0.0.1:5173/dispatch` 页面完成一次闭环。
