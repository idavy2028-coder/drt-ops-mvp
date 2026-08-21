# 订单中心与车辆任务页界面优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有管理后台中实现高密度订单中心和车辆任务工作台，补充权威车辆状态、可靠站点步骤条、分页、状态保持与统一 Toast。

**Architecture:** 前端保留现有 Vue 3 页面和 API 模块，以小型展示组件、上海时区工具、分页组件和页面缓存组合函数拆分职责；订单和任务接口仍一次性读取，分组、排序和分页在前端完成。后端仅扩展 `VehicleTaskView`，由控制器一次读取车辆实体后同时输出车牌与 `vehicleStatus`，不修改数据库或任务状态机。

**Tech Stack:** Vue 3、Vue Router 4、TypeScript 5、Vitest、Testing Library、Spring Boot、Spring MVC、JUnit 5、MockMvc、Maven。

## Global Constraints

- 仅在 `codex/admin-ui-optimization` 独立工作树实施，不触碰 P6-2 工作树。
- 页面区块间距为 12px，卡片内边距为 16px，表格表头和数据行目标高度为 44px。
- 订单中心每页固定 8 条；车辆任务每页固定 6 条。
- 日期分组和时间展示固定使用 `Asia/Shanghai`，不得依赖浏览器本地时区。
- 车辆状态必须来自后端 `vehicleStatus`，不得从任务状态推断。
- 不新增数据库字段或数据库迁移，不增加后端分页协议，不改变订单和任务状态机。
- 所有变更类操作结果使用全局 Toast；取消订单必须先确认并校验 2–100 字原因。
- `ORDER_READ`、`DISPATCH_EXECUTE`、`TASK_READ`、`TASK_EXECUTE` 等现有权限门禁必须保留。
- 每项业务行为严格遵循 RED → GREEN → REFACTOR；没有观察到预期失败测试前不得写对应生产代码。
- 每个任务只暂存其列出的文件，避免混入其他工作树或无关修改。
- Chrome 下必须完成 1366×768 与当前桌面视口验收，并确认无页面级横向溢出。

---

### Task 1: 上海时区展示工具与紧凑分页组件

**Files:**
- Create: `apps/admin-web/src/presentation/dateTime.ts`
- Create: `apps/admin-web/src/presentation/date-time.test.ts`
- Create: `apps/admin-web/src/components/RecordPagination.vue`
- Create: `apps/admin-web/src/components/record-pagination.test.ts`

**Interfaces:**
- Produces: `shanghaiDateKey(value?: string): string | null`
- Produces: `formatShanghaiDateTime(value?: string, mode?: "table" | "time"): string`
- Produces: `RecordPagination` props `{ currentPage: number; totalItems: number; pageSize: number }` and event `update:currentPage`.

- [ ] **Step 1: Write failing time-format tests**

Add literal, hand-checked cases that catch browser-timezone grouping and invalid-date regressions:

```ts
import { describe, expect, it } from "vitest";
import { formatShanghaiDateTime, shanghaiDateKey } from "./dateTime";

describe("Shanghai date presentation", () => {
  it("groups an instant by the Shanghai calendar day", () => {
    expect(shanghaiDateKey("2026-08-12T16:30:00.000Z")).toBe("2026-08-13");
  });

  it("formats table and same-day times without exposing ISO strings", () => {
    expect(formatShanghaiDateTime("2026-08-13T01:06:00.000Z", "table")).toBe("08-13 09:06");
    expect(formatShanghaiDateTime("2026-08-13T01:06:00.000Z", "time")).toBe("09:06");
  });

  it("returns explicit fallbacks for missing and invalid values", () => {
    expect(shanghaiDateKey("invalid")).toBeNull();
    expect(formatShanghaiDateTime(undefined)).toBe("--");
    expect(formatShanghaiDateTime("invalid")).toBe("--");
  });
});
```

- [ ] **Step 2: Run the time tests and verify RED**

Run: `npm.cmd test -- src/presentation/date-time.test.ts`

Expected: FAIL because `dateTime.ts` and its exports do not exist.

- [ ] **Step 3: Implement the minimal time formatter**

Create a formatter using `Intl.DateTimeFormat` with `timeZone: "Asia/Shanghai"`; use `formatToParts` so the output is exactly `YYYY-MM-DD`, `MM-DD HH:mm`, or `HH:mm` rather than locale-dependent punctuation.

```ts
const SHANGHAI_TIME_ZONE = "Asia/Shanghai";

export function shanghaiDateKey(value?: string): string | null {
  const parts = dateParts(value, { year: "numeric", month: "2-digit", day: "2-digit" });
  return parts === null ? null : `${parts.year}-${parts.month}-${parts.day}`;
}

export function formatShanghaiDateTime(value?: string, mode: "table" | "time" = "table"): string {
  const parts = dateParts(value, { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false });
  if (parts === null) return "--";
  return mode === "time" ? `${parts.hour}:${parts.minute}` : `${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`;
}

function dateParts(value: string | undefined, options: Intl.DateTimeFormatOptions): Record<string, string> | null {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return Object.fromEntries(new Intl.DateTimeFormat("en-US", {
    timeZone: SHANGHAI_TIME_ZONE,
    ...options
  }).formatToParts(date).map(({ type, value: partValue }) => [type, partValue]));
}
```

- [ ] **Step 4: Run the time tests and verify GREEN**

Run: `npm.cmd test -- src/presentation/date-time.test.ts`

Expected: 3 tests pass with no warnings.

- [ ] **Step 5: Write failing pagination behavior tests**

Test real rendered controls, not implementation details:

```ts
it("reports the current range and emits bounded page changes", async () => {
  const view = render(RecordPagination, { props: { currentPage: 2, totalItems: 17, pageSize: 8 } });
  expect(screen.getByText("第 2 / 3 页 · 共 17 条")).toBeInTheDocument();
  await fireEvent.click(screen.getByRole("button", { name: "下一页" }));
  expect(view.emitted("update:currentPage")).toEqual([[3]]);
});

it("disables directions at the first and last page", () => {
  render(RecordPagination, { props: { currentPage: 1, totalItems: 0, pageSize: 8 } });
  expect(screen.getByRole("button", { name: "上一页" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "下一页" })).toBeDisabled();
  expect(screen.getByText("第 1 / 1 页 · 共 0 条")).toBeInTheDocument();
});
```

- [ ] **Step 6: Run pagination tests and verify RED**

Run: `npm.cmd test -- src/components/record-pagination.test.ts`

Expected: FAIL because `RecordPagination.vue` does not exist.

- [ ] **Step 7: Implement the minimal pagination component**

Use computed `pageCount = Math.max(1, Math.ceil(totalItems / pageSize))`; emit only `currentPage - 1` or `currentPage + 1` within bounds. Render a semantic `nav` named“记录分页” with “上一页”“下一页” buttons:

```vue
<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{ currentPage: number; totalItems: number; pageSize: number }>();
const emit = defineEmits<{ "update:currentPage": [page: number] }>();
const pageCount = computed(() => Math.max(1, Math.ceil(props.totalItems / props.pageSize)));
function move(page: number): void {
  if (page >= 1 && page <= pageCount.value && page !== props.currentPage) emit("update:currentPage", page);
}
</script>

<template>
  <nav class="record-pagination" aria-label="记录分页">
    <span>第 {{ currentPage }} / {{ pageCount }} 页 · 共 {{ totalItems }} 条</span>
    <div class="record-pagination-actions">
      <button type="button" class="secondary-button" :disabled="currentPage <= 1" @click="move(currentPage - 1)">上一页</button>
      <button type="button" class="secondary-button" :disabled="currentPage >= pageCount" @click="move(currentPage + 1)">下一页</button>
    </div>
  </nav>
</template>
```

- [ ] **Step 8: Run targeted and existing presentation tests**

Run: `npm.cmd test -- src/presentation/date-time.test.ts src/components/record-pagination.test.ts src/presentation/operations.test.ts`

Expected: all selected tests pass.

- [ ] **Step 9: Commit Task 1**

```powershell
git add -- apps/admin-web/src/presentation/dateTime.ts apps/admin-web/src/presentation/date-time.test.ts apps/admin-web/src/components/RecordPagination.vue apps/admin-web/src/components/record-pagination.test.ts
git commit -m "feat: add compact record presentation utilities"
```

---

### Task 2: 订单与任务页面路由缓存及滚动恢复

**Files:**
- Create: `apps/admin-web/src/composables/usePageScrollRetention.ts`
- Create: `apps/admin-web/src/composables/use-page-scroll-retention.test.ts`
- Modify: `apps/admin-web/src/layouts/AppLayout.vue`
- Modify: `apps/admin-web/src/layouts/app-layout.test.ts`
- Modify: `apps/admin-web/src/router/index.ts`
- Modify: `apps/admin-web/src/router/router-auth.test.ts`

**Interfaces:**
- Produces: `usePageScrollRetention(): void`
- Produces: route meta `keepAlive: true` on route names `orders` and `tasks`.
- Consumes: Vue `onActivated`, `onDeactivated`, `nextTick` and RouterView slot `{ Component, route }`.

- [ ] **Step 1: Write a failing cached-state integration test**

Mount `AppLayout` with a memory router whose `/orders` component contains a real input and whose `/tasks` component is distinct. Fill the input, navigate away and back, then assert the input value remains:

```ts
const OrdersProbe = defineComponent({ template: '<label>筛选条件<input aria-label="筛选条件" /></label>' });
const TasksProbe = defineComponent({ template: "<p>车辆任务探针</p>" });
const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: "/orders", name: "orders-probe", component: OrdersProbe, meta: { keepAlive: true } },
    { path: "/tasks", name: "tasks-probe", component: TasksProbe, meta: { keepAlive: true } }
  ]
});
await router.push("/orders");
await router.isReady();
render(AppLayout, { global: { plugins: [router] } });
await fireEvent.update(screen.getByRole("textbox", { name: "筛选条件" }), "待调度");
await router.push("/tasks");
await nextTick();
await router.push("/orders");
await nextTick();
expect(screen.getByRole("textbox", { name: "筛选条件" })).toHaveValue("待调度");
```

Production change caught: replacing the slot-based cached rendering with the existing direct `<RouterView />` destroys the input and fails this assertion.

- [ ] **Step 2: Run the layout test and verify RED**

Run: `npm.cmd test -- src/layouts/app-layout.test.ts`

Expected: the new cached-state test fails because the input value is reset after navigation.

- [ ] **Step 3: Implement route cache rendering and route metadata**

Mark only `orders` and `tasks` with `meta.keepAlive = true`. Replace the direct view with a slot-based split:

```vue
<RouterView v-slot="{ Component, route }">
  <KeepAlive>
    <component v-if="route.meta.keepAlive" :is="Component" :key="String(route.name)" />
  </KeepAlive>
  <component v-if="!route.meta.keepAlive" :is="Component" :key="route.fullPath" />
</RouterView>
```

- [ ] **Step 4: Run the layout test and verify GREEN**

Run: `npm.cmd test -- src/layouts/app-layout.test.ts src/router/router-auth.test.ts`

Expected: existing navigation/authorization tests and the new cache test pass.

- [ ] **Step 5: Write the failing scroll-retention test**

Mount a real component that calls `usePageScrollRetention()` inside `KeepAlive`. Set `window.scrollY` to 420, deactivate it, reactivate it, and assert `window.scrollTo({ top: 420, behavior: "auto" })` is called.

- [ ] **Step 6: Run the composable test and verify RED**

Run: `npm.cmd test -- src/composables/use-page-scroll-retention.test.ts`

Expected: FAIL because the composable is missing.

- [ ] **Step 7: Implement minimal scroll retention**

```ts
import { nextTick, onActivated, onDeactivated } from "vue";

export function usePageScrollRetention(): void {
  let scrollTop = 0;
  onDeactivated(() => { scrollTop = window.scrollY; });
  onActivated(() => {
    void nextTick(() => window.scrollTo({ top: scrollTop, behavior: "auto" }));
  });
}
```

- [ ] **Step 8: Run Task 2 tests and commit**

Run: `npm.cmd test -- src/layouts/app-layout.test.ts src/router/router-auth.test.ts src/composables/use-page-scroll-retention.test.ts`

Expected: all selected tests pass.

```powershell
git add -- apps/admin-web/src/composables/usePageScrollRetention.ts apps/admin-web/src/composables/use-page-scroll-retention.test.ts apps/admin-web/src/layouts/AppLayout.vue apps/admin-web/src/layouts/app-layout.test.ts apps/admin-web/src/router/index.ts apps/admin-web/src/router/router-auth.test.ts
git commit -m "feat: retain cached operations page state"
```

---

### Task 3: 订单高密度表格、分区分页与详情抽屉

**Files:**
- Create: `apps/admin-web/src/components/OrderDetailDrawer.vue`
- Create: `apps/admin-web/src/components/order-detail-drawer.test.ts`
- Modify: `apps/admin-web/src/pages/OrdersPage.vue`
- Modify: `apps/admin-web/src/pages/orders-page.test.ts`
- Modify: `apps/admin-web/src/App.vue`
- Modify: `apps/admin-web/src/presentation/operations.ts`
- Modify: `apps/admin-web/src/presentation/operations.test.ts`

**Interfaces:**
- Consumes: `shanghaiDateKey`, `formatShanghaiDateTime`, `RecordPagination`, `usePageScrollRetention`.
- Produces: `OrderDetailDrawer` props `{ order: RideOrder }`, event `close`.
- Produces page state: `activeOrderGroup: "today" | "history"`, `orderPageByGroup: Record<"today" | "history", number>`, `selectedOrder: RideOrder | null`.

- [ ] **Step 1: Add failing order-list behavior tests**

Use complete `RideOrder` fixtures. Add separate tests that catch missing columns, wrong grouping and absent pagination:

```ts
it("shows passenger phone and route in the active order table", async () => {
  installFetch([orderFixture({ passengerName: "张敏", passengerPhone: "13800001201", originAddress: "通渭县汽车站", destinationAddress: "通渭县人民医院" })]);
  renderOrdersPage();
  expect(await screen.findByRole("link", { name: "拨打 13800001201" })).toHaveAttribute("href", "tel:13800001201");
  expect(screen.getByText("通渭县汽车站")).toBeInTheDocument();
  expect(screen.getByText("通渭县人民医院")).toBeInTheDocument();
});

it("shows only eight orders per page and retains independent group pages", async () => {
  installFetch(orderPaginationFixture());
  renderOrdersPage();
  expect(await screen.findAllByRole("row")).toHaveLength(9);
  expect(screen.getByText("第 1 / 2 页 · 共 9 条")).toBeInTheDocument();
  await fireEvent.click(screen.getByRole("button", { name: "下一页" }));
  expect(screen.getByText("第 2 / 2 页 · 共 9 条")).toBeInTheDocument();
});
```

`orderPaginationFixture()` must return nine literal today orders plus at least one literal history order; assertions must not reuse the production pagination helper.

- [ ] **Step 2: Run order tests and verify RED**

Run: `npm.cmd test -- src/pages/orders-page.test.ts`

Expected: new phone/route and pagination assertions fail against the current six-column, two-table page.

- [ ] **Step 3: Implement one active group and client pagination**

- Replace browser-local date helpers with `shanghaiDateKey`.
- Keep the existing `createdAt` descending sort.
- Render segmented buttons with `aria-pressed` and counts.
- Derive the active group and slice `(page - 1) * 8` through `page * 8`.
- Render the seven specified columns and use `RecordPagination`.
- Clamp each stored page when refreshed data shrinks.
- Call `usePageScrollRetention()` from the page.

- [ ] **Step 4: Run order tests and verify the table GREEN**

Run: `npm.cmd test -- src/pages/orders-page.test.ts`

Expected: new list/pagination tests and all existing order behavior tests pass before adding the drawer.

- [ ] **Step 5: Write failing detail-drawer tests**

Test the real component with an unserviceable order:

```ts
it("shows complete order and dispatch failure details", async () => {
  render(OrderDetailDrawer, { props: { order: unserviceableOrderFixture() } });
  expect(screen.getByRole("dialog", { name: "订单详情" })).toBeInTheDocument();
  expect(screen.getByText("候选车辆均不满足调度约束")).toBeInTheDocument();
  expect(screen.getByText("候选方案数：3")).toBeInTheDocument();
  expect(screen.getByText("诊断代码：ALL_CANDIDATES_REJECTED")).toBeInTheDocument();
});
```

Add a page-level test that clicks “查看详情”, closes the drawer, and verifies focus returns to the same row's button.

- [ ] **Step 6: Run drawer tests and verify RED**

Run: `npm.cmd test -- src/components/order-detail-drawer.test.ts src/pages/orders-page.test.ts`

Expected: FAIL because the drawer component and row action do not exist.

- [ ] **Step 7: Implement the drawer and row action**

- Render a fixed overlay with `role="dialog"`, `aria-modal="true"`, labelled heading and close button.
- Display only fields already present in `RideOrder`; do not add an API request.
- Move current inline dispatch-failure expansion content into the drawer.
- Close on the button, overlay click and `Escape`.
- Store the trigger element before opening; after close, call `nextTick` and return focus.

- [ ] **Step 8: Apply density styles and verify targeted tests**

- Set `.page` gap to 12px for these pages without globally shrinking unrelated pages.
- Set table cell vertical padding so one-line rows reach 44px; allow route/person cells to use two compact lines.
- Use a horizontal table container below the desktop breakpoint rather than page-level overflow.

Run: `npm.cmd test -- src/pages/orders-page.test.ts src/components/order-detail-drawer.test.ts src/components/record-pagination.test.ts src/presentation/operations.test.ts`

Expected: all selected tests pass.

- [ ] **Step 9: Commit Task 3**

```powershell
git add -- apps/admin-web/src/components/OrderDetailDrawer.vue apps/admin-web/src/components/order-detail-drawer.test.ts apps/admin-web/src/pages/OrdersPage.vue apps/admin-web/src/pages/orders-page.test.ts apps/admin-web/src/App.vue apps/admin-web/src/presentation/operations.ts apps/admin-web/src/presentation/operations.test.ts
git commit -m "feat: redesign order center records"
```

---

### Task 4: 安全取消订单确认与 Toast

**Files:**
- Create: `apps/admin-web/src/components/OrderCancelDialog.vue`
- Create: `apps/admin-web/src/components/order-cancel-dialog.test.ts`
- Modify: `apps/admin-web/src/pages/OrdersPage.vue`
- Modify: `apps/admin-web/src/pages/orders-page.test.ts`

**Interfaces:**
- Produces: `OrderCancelDialog` props `{ order: RideOrder; submitting: boolean; submitError: string }`.
- Produces events: `close` and `confirm` with payload `{ reason: string }`.
- Consumes: existing `cancelOrder(orderId, reason)` and `feedbackStore`.

- [ ] **Step 1: Write failing dialog validation tests**

```ts
it("requires a cancellation reason between 2 and 100 characters", async () => {
  const view = render(OrderCancelDialog, { props: { order: orderFixture(), submitting: false, submitError: "" } });
  await fireEvent.update(screen.getByRole("textbox", { name: "取消原因" }), " ");
  await fireEvent.click(screen.getByRole("button", { name: "确认取消" }));
  expect(screen.getByText("请输入 2–100 字取消原因")).toBeInTheDocument();
  expect(view.emitted("confirm")).toBeUndefined();
  await fireEvent.update(screen.getByRole("textbox", { name: "取消原因" }), "乘客临时调整行程");
  await fireEvent.click(screen.getByRole("button", { name: "确认取消" }));
  expect(view.emitted("confirm")).toEqual([[{ reason: "乘客临时调整行程" }]]);
});
```

- [ ] **Step 2: Run dialog tests and verify RED**

Run: `npm.cmd test -- src/components/order-cancel-dialog.test.ts`

Expected: FAIL because the dialog component does not exist.

- [ ] **Step 3: Implement minimal dialog behavior**

Use a local `reason` ref so an open cached page retains the input. Trim on submit, enforce length 2–100, disable inputs and both close paths while `submitting` is true, and surface `submitError` without discarding the reason.

- [ ] **Step 4: Run dialog tests and verify GREEN**

Run: `npm.cmd test -- src/components/order-cancel-dialog.test.ts`

Expected: validation, emit and submitting-state tests pass.

- [ ] **Step 5: Write failing page-level cancellation tests**

Add tests proving no mutation occurs before confirmation, the typed reason is sent, success uses Toast, failure keeps the dialog, and terminal rows cannot cancel:

```ts
expect(postedRequests).toHaveLength(0);
await fireEvent.click(screen.getByRole("button", { name: "确认取消" }));
expect(JSON.parse(postedRequests[0]!.body)).toEqual({ reason: "乘客临时调整行程" });
expect(await screen.findByText("订单已取消")).toBeInTheDocument();
```

Production changes caught: reintroducing immediate cancellation, restoring the hard-coded reason, omitting Toast, or enabling a completed-order cancellation.

- [ ] **Step 6: Run page tests and verify RED**

Run: `npm.cmd test -- src/pages/orders-page.test.ts`

Expected: confirmation/reason assertions fail against the current immediate `cancel(order)` path.

- [ ] **Step 7: Wire the dialog into OrdersPage**

- Replace immediate cancellation with `cancelOrderTarget` selection.
- On confirm, call `cancelOrder(target.id, reason)`.
- Keep the dialog open and set `cancelSubmitError` on failure.
- Close on success, send global Toast, reload orders and preserve active group/page.
- For authorized users, keep the cancel button position; terminal states render it disabled with an `aria-describedby` reason.

- [ ] **Step 8: Run all order tests and commit**

Run: `npm.cmd test -- src/pages/orders-page.test.ts src/components/order-cancel-dialog.test.ts src/components/order-detail-drawer.test.ts`

Expected: all order tests pass.

```powershell
git add -- apps/admin-web/src/components/OrderCancelDialog.vue apps/admin-web/src/components/order-cancel-dialog.test.ts apps/admin-web/src/pages/OrdersPage.vue apps/admin-web/src/pages/orders-page.test.ts
git commit -m "feat: confirm order cancellations safely"
```

---

### Task 5: 后端车辆任务 `vehicleStatus` 契约

**Files:**
- Modify: `apps/api/src/test/java/com/idavy/drtops/domain/task/TaskExecutionApiTest.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/task/VehicleTaskControllerTest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskView.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskController.java`

**Interfaces:**
- Produces: JSON field `vehicleStatus: string | null` on every `VehicleTaskView`.
- Changes factory to `VehicleTaskView.from(VehicleTask task, String vehiclePlateNumber, String vehicleStatus)`.

- [ ] **Step 1: Add failing API contract assertions**

Extend the list and lifecycle test with literal expectations:

```java
.andExpect(jsonPath("$.data[0].vehicleStatus").value("DISPATCHED"));

.andExpect(jsonPath("$.data.task.vehicleStatus").value("IN_SERVICE"));

.andExpect(jsonPath("$.data.task.vehicleStatus").value("IDLE"));
```

The first assertion follows `createConfirmedTaskWithOneOrder()`, the second follows `/start`, and the third follows `/complete`. These values are independently verified by existing repository assertions in the same test.

- [ ] **Step 2: Run TaskExecutionApiTest and verify RED**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=TaskExecutionApiTest test`

Expected: FAIL because `vehicleStatus` is absent from JSON.

- [ ] **Step 3: Add a failing missing-vehicle unit test**

Construct `VehicleTaskController` with a repository returning one task and a vehicle repository returning `Optional.empty()`. Invoke `list()` and assert the returned view keeps the task while both presentation fields are null. Mock only repositories; exercise the real controller mapping.

- [ ] **Step 4: Run the controller test and verify RED**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=VehicleTaskControllerTest test`

Expected: FAIL because the view has no `vehicleStatus` accessor or three-argument factory.

- [ ] **Step 5: Implement the minimal backend mapping**

Add `String vehicleStatus` immediately after `vehiclePlateNumber` in the record. Update mapping without a second vehicle lookup:

```java
private VehicleTaskView toView(VehicleTask task) {
    Vehicle vehicle = vehicleRepository.findById(task.getVehicleId()).orElse(null);
    return VehicleTaskView.from(
            task,
            vehicle == null ? null : vehicle.getPlateNumber(),
            vehicle == null ? null : vehicle.getCurrentStatus());
}
```

Import the existing `Vehicle` class. Update `VehicleTaskView.from` to copy both arguments.

- [ ] **Step 6: Run targeted backend tests and verify GREEN**

Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=TaskExecutionApiTest,VehicleTaskControllerTest test`

Expected: both classes pass, including DISPATCHED → IN_SERVICE → IDLE response values and missing-vehicle null behavior.

- [ ] **Step 7: Commit Task 5**

```powershell
git add -- apps/api/src/test/java/com/idavy/drtops/domain/task/TaskExecutionApiTest.java apps/api/src/test/java/com/idavy/drtops/domain/task/VehicleTaskControllerTest.java apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskView.java apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskController.java
git commit -m "feat: expose vehicle status on tasks"
```

---

### Task 6: 车辆状态标签与可靠站点步骤条

**Files:**
- Create: `apps/admin-web/src/components/VehicleStatusBadge.vue`
- Create: `apps/admin-web/src/components/vehicle-status-badge.test.ts`
- Modify: `apps/admin-web/src/components/TaskStopTimeline.vue`
- Create: `apps/admin-web/src/components/task-stop-timeline.test.ts`
- Modify: `apps/admin-web/src/api/types.ts`

**Interfaces:**
- Changes: `VehicleTask.vehicleStatus?: string`.
- Produces: `VehicleStatusBadge` prop `{ code?: string }` with domain-specific labels.
- Changes: `TaskStopTimeline` props `{ stops: TaskStop[]; stopNameById: Readonly<Record<string, string>> }`.

- [ ] **Step 1: Write failing vehicle-status badge tests**

Render literal statuses and assert both labels and tone classes:

```ts
it.each([
  ["IDLE", "空闲", "status-success"],
  ["DISPATCHED", "已派单", "status-active"],
  ["IN_SERVICE", "执行中", "status-active"],
  ["OFFLINE", "离线", "status-danger"],
  [undefined, "状态未知", "status-neutral"]
])("renders %s as %s", (code, label, tone) => {
  render(VehicleStatusBadge, { props: { code } });
  expect(screen.getByText(label)).toHaveClass(tone);
});
```

- [ ] **Step 2: Run badge tests and verify RED**

Run: `npm.cmd test -- src/components/vehicle-status-badge.test.ts`

Expected: FAIL because the vehicle-specific badge does not exist.

- [ ] **Step 3: Implement the minimal vehicle badge**

Keep vehicle semantics separate from generic `StatusBadge` so task `DISPATCHED = 待发车` does not conflict with vehicle `DISPATCHED = 已派单`. Map `IDLE`, `AVAILABLE`, `DISPATCHED`, `IN_SERVICE`, `OFFLINE`, `UNAVAILABLE`; default to “状态未知”.

- [ ] **Step 4: Run badge tests and verify GREEN**

Run: `npm.cmd test -- src/components/vehicle-status-badge.test.ts`

Expected: all table-driven cases pass.

- [ ] **Step 5: Write failing timeline behavior tests**

Use deliberately shuffled stops and a name map whose insertion order differs from task order:

```ts
const stops = [
  { id: "stop-2", virtualStopId: "hospital", sequenceNumber: 2, stopType: "ALIGHTING", plannedArrivalAt: "2026-08-13T01:28:00Z", status: "PLANNED" },
  { id: "stop-1", virtualStopId: "station", sequenceNumber: 1, stopType: "BOARDING", plannedArrivalAt: "2026-08-13T01:05:00Z", actualArrivalAt: "2026-08-13T01:06:00Z", status: "BOARDED" }
];
render(TaskStopTimeline, { props: { stops, stopNameById: { hospital: "通渭县人民医院", station: "通渭县汽车站" } } });
expect(screen.getAllByRole("listitem").map((item) => item.textContent)).toEqual([
  expect.stringContaining("通渭县汽车站"),
  expect.stringContaining("通渭县人民医院")
]);
expect(screen.getByText("已到站 09:06 · 已上车")).toBeInTheDocument();
expect(screen.getByText("计划到站 09:28")).toBeInTheDocument();
```

Add cases for `ARRIVED`, `ALIGHTED`, missing station ID and invalid time.

- [ ] **Step 6: Run timeline tests and verify RED**

Run: `npm.cmd test -- src/components/task-stop-timeline.test.ts`

Expected: assertions fail because the existing component renders raw ISO time and no station name.

- [ ] **Step 7: Implement the timeline**

- Clone and sort stops by `sequenceNumber`.
- Resolve each name by its own `virtualStopId`.
- Fallback to `未知站点 · ${virtualStopId.slice(0, 8)}`.
- Use `formatShanghaiDateTime(stop.actualArrivalAt, "time")` for actual text and `formatShanghaiDateTime(stop.plannedArrivalAt, "time")` for planned text.
- Use `actualArrivalAt` for arrived/boarded/alighted text and never substitute `plannedArrivalAt` as an actual time.
- Apply semantic classes `is-complete`, `is-current`, `is-upcoming` while preserving ordered-list accessibility.

- [ ] **Step 8: Run Task 6 tests and commit**

Run: `npm.cmd test -- src/components/vehicle-status-badge.test.ts src/components/task-stop-timeline.test.ts src/presentation/date-time.test.ts`

Expected: all selected tests pass.

```powershell
git add -- apps/admin-web/src/components/VehicleStatusBadge.vue apps/admin-web/src/components/vehicle-status-badge.test.ts apps/admin-web/src/components/TaskStopTimeline.vue apps/admin-web/src/components/task-stop-timeline.test.ts apps/admin-web/src/api/types.ts
git commit -m "feat: clarify vehicle and stop states"
```

---

### Task 7: 车辆任务统计、分页与双栏执行工作台

**Files:**
- Modify: `apps/admin-web/src/pages/TasksPage.vue`
- Modify: `apps/admin-web/src/pages/tasks-page.test.ts`
- Modify: `apps/admin-web/src/App.vue`

**Interfaces:**
- Consumes: `RecordPagination`, `VehicleStatusBadge`, `TaskStopTimeline.stopNameById`, `shanghaiDateKey`, `formatShanghaiDateTime`, `usePageScrollRetention`.
- Produces state: `activeTaskGroup`, `taskPageByGroup`, `selectedTaskIdByGroup`.
- Preserves: existing location confirmation actions and `taskId` query deep link.

- [ ] **Step 1: Write failing four-card statistic tests**

Use four literal tasks, one per domain status, and assert count plus percentage:

```ts
expect(await screen.findByRole("article", { name: "执行中任务" })).toHaveTextContent("1 项 · 25.0%");
expect(screen.getByRole("article", { name: "待发车任务" })).toHaveTextContent("1 项 · 25.0%");
expect(screen.getByRole("article", { name: "异常任务" })).toHaveTextContent("1 项 · 25.0%");
expect(screen.getByRole("article", { name: "已完成任务" })).toHaveTextContent("1 项 · 25.0%");
```

- [ ] **Step 2: Run task tests and verify statistic RED**

Run: `npm.cmd test -- src/pages/tasks-page.test.ts`

Expected: FAIL because only three legacy cards exist and ratios are absent.

- [ ] **Step 3: Implement minimal task statistics**

Create a computed summary from all loaded tasks. Use this exact percentage rule and render four labelled articles:

```ts
function taskRate(count: number): string {
  return tasks.value.length === 0
    ? "0.0%"
    : `${((count / tasks.value.length) * 100).toFixed(1)}%`;
}
```

- [ ] **Step 4: Run task tests and verify statistic GREEN**

Run: `npm.cmd test -- src/pages/tasks-page.test.ts`

Expected: the four-card test and existing execution-control tests pass.

- [ ] **Step 5: Write failing task table/pagination/status tests**

Add complete API fixtures with `vehicleStatus`. Assert:

- only six rows appear from seven today tasks;
- the footer reads “第 1 / 2 页 · 共 7 条”;
- vehicle `DISPATCHED` renders “已派单” while task `DISPATCHED` renders “待发车”;
- switching to history and back restores the today page;
- an incoming `taskId` selects the correct history page;
- no latest location renders “暂无位置上报”.

- [ ] **Step 6: Run task tests and verify table RED**

Run: `npm.cmd test -- src/pages/tasks-page.test.ts`

Expected: pagination and vehicle-status assertions fail against the current page.

- [ ] **Step 7: Implement task grouping, pagination and selection state**

- Replace local date functions with `shanghaiDateKey`.
- Store page and selected task separately for today/history.
- If `route.query.taskId` matches, derive its group and page before selecting it.
- Clamp page/selection after refresh and action updates.
- Render `VehicleStatusBadge` beside the plate and keep `StatusBadge` for task status.
- Pass `Object.fromEntries(virtualStops.map(stop => [stop.id, stop.name]))` to `TaskStopTimeline`.
- Use `formatShanghaiDateTime` for planned start and latest location time.
- Call `usePageScrollRetention()`.

- [ ] **Step 8: Write failing independent-degradation tests**

Add three tests:

1. task list failure renders a retry button and no statistics;
2. virtual-stop failure keeps tasks and renders “未知站点”;
3. latest-location failure keeps action controls and renders “暂无位置上报”.

Assert the page's visible behavior; do not assert that a mock was called merely because it exists.

- [ ] **Step 9: Run degradation tests and verify RED**

Run: `npm.cmd test -- src/pages/tasks-page.test.ts`

Expected: at least the retry and independent error-state assertions fail because reference requests currently collapse into one silent catch.

- [ ] **Step 10: Implement independent load state and dual-column layout**

- Track task error separately from stop/location reference warnings.
- Keep last successful tasks on user refresh failure; show a global failure Toast.
- Move execution controls into the right current-task panel.
- Keep normal actions and exception actions visually separated without changing their existing enablement computed values.
- Use a `minmax(0, 1.55fr) minmax(300px, .75fr)` desktop grid, 2×2 metric cards below 900px, and stacked task/detail sections on narrow screens.

- [ ] **Step 11: Run all task tests and commit**

Run: `npm.cmd test -- src/pages/tasks-page.test.ts src/components/task-stop-timeline.test.ts src/components/vehicle-status-badge.test.ts src/components/location-report-panel.test.ts`

Expected: all selected tests pass.

```powershell
git add -- apps/admin-web/src/pages/TasksPage.vue apps/admin-web/src/pages/tasks-page.test.ts apps/admin-web/src/App.vue
git commit -m "feat: redesign vehicle task workspace"
```

---

### Task 8: 全量验证、浏览器验收、进度记录与 PR 更新

**Files:**
- Modify only if evidence changes: `progress.md`
- Modify only if browser QA exposes a tested defect: the relevant source and regression test from Tasks 1–7.

**Interfaces:**
- Consumes all deliverables from Tasks 1–7.
- Produces verified branch commits and an updated existing pull request.

- [ ] **Step 1: Run frontend targeted regression**

Run:

```powershell
npm.cmd test -- src/pages/orders-page.test.ts src/pages/tasks-page.test.ts src/components/order-detail-drawer.test.ts src/components/order-cancel-dialog.test.ts src/components/task-stop-timeline.test.ts src/components/vehicle-status-badge.test.ts src/components/record-pagination.test.ts src/layouts/app-layout.test.ts src/composables/use-page-scroll-retention.test.ts
```

Expected: all selected tests pass with zero failures.

- [ ] **Step 2: Run full frontend verification**

Run:

```powershell
npm.cmd test
npm.cmd run typecheck
npm.cmd run build
```

Expected: Vitest has zero failing files/tests; `vue-tsc` exits 0; Vite production build exits 0. Record any existing non-blocking chunk-size warning separately rather than claiming pristine output.

- [ ] **Step 3: Run backend targeted and full verification**

Run:

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=TaskExecutionApiTest,VehicleTaskControllerTest test
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api test
```

Expected: both Maven invocations exit 0 with no test failures.

- [ ] **Step 4: Start current-branch review services with fictitious data**

Use an ignored temporary fixture server only for visual review. Do not connect to or mutate the pilot database. Start Vite on an unused loopback port and record the actual port selected.

- [ ] **Step 5: Perform Chrome visual and interaction QA**

At 1366×768 and the current desktop viewport verify:

- order page header, segment control, eight-row page and pagination fit without page-level horizontal overflow;
- phone link, route cell, status tags, detail drawer focus, cancel validation and Toast work;
- task page shows four metric cards, six-row page and right timeline together;
- `DISPATCHED` vehicle/task labels remain distinct;
- shuffled station fixtures render correct names/order and Shanghai times;
- menu switching restores page/group/selection/form/scroll state;
- browser console has no new error and all failed reference calls use designed fallbacks.

If QA exposes a bug, add a failing automated regression test, confirm RED, implement the minimal correction, confirm GREEN, then repeat Steps 1–5.

- [ ] **Step 6: Update progress evidence**

Append a dated section to `progress.md` containing:

- outcome and scope;
- exception causes or “无新增异常”；
- targeted/full test counts and build result;
- Chrome viewport and fictitious-data disclosure;
- branch, HEAD and uncommitted file list;
- next action: push branch and update PR #18.

- [ ] **Step 7: Verify and commit progress evidence**

Run: `git diff --check` and `git status --short`.

```powershell
git add -- progress.md
git commit -m "docs: record order and task UI verification"
```

- [ ] **Step 8: Final branch verification**

Run:

```powershell
git status --short --branch
git log --oneline --decorate -10
git diff origin/codex/admin-ui-optimization...HEAD --check
```

Expected: working tree is clean; all commits are on `codex/admin-ui-optimization`; diff check has no output.

- [ ] **Step 9: Push and verify the existing PR**

Run: `git push origin codex/admin-ui-optimization`.

Then inspect PR #18 and confirm its head is `codex/admin-ui-optimization`, its base remains `codex/p6-2-jt-active-safety-spec`, and it includes the new commits. Update the PR title/body only if the current text no longer describes the expanded order/task scope.
