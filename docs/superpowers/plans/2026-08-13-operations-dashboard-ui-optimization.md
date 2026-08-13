# 运营看板界面优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将运营看板升级为“顶部四项核心指标、中部近 7 天趋势、下部三类分布”的高密度运营工作台，所有数值来自统一的真实聚合接口。

**Architecture:** 后端新增单一看板读模型，由 `OperationsMetricsService` 一次读取订单、决策、任务和车辆后，按上海运营日聚合当前值、前 7 日基线、7 日趋势和分类分布。前端通过一个 Store 获取完整快照，把数值格式化、SVG 趋势绘制、环图绘制拆成独立组件，`DashboardPage` 只负责日期、刷新、加载和布局编排。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring Data JPA、JUnit 5、Vue 3.5、TypeScript 5.8、Vitest 4、Testing Library、原生 SVG/CSS。

## Global Constraints

- 独立工作树固定为 `D:\codex-projects\.worktrees\admin-ui-optimization`，分支固定为 `codex/admin-ui-optimization`；不得修改 P6-2 工作树。
- 统计日期一律按 `Asia/Shanghai` 运营日换算，趋势包含 `endDate` 并固定返回连续 7 天。
- 核心指标固定为当日订单量、任务完成率、平均等待时间、车辆利用率。
- 指标卡必须同时展示绝对值与比例或相对变化；无分母的比例使用 `null`，界面显示 `--`。
- 基线固定使用所选运营日前的 7 个完整运营日，即 `[endDate-7, endDate-1]`；无有效基线时状态为 `NO_BASELINE`。
- 当日无有效分母或样本时状态为 `NO_DATA`，优先于 `NO_BASELINE`；所有阈值使用高精度内部值判断，仅 API 输出时缩放。
- 分类图图例必须同时显示数量和百分比；展示百分比保留一位小数并校正合计为 `100.0%`。
- 页面、卡片和图表遵循已确认的深色运营导航、白色高密度面板、青绿色数据强调风格。
- 页面间距以 12px 为基础，卡片内边距 16px；辅助字号不得低于 12px。
- 不引入图表运行时依赖；趋势图和环图使用项目内 Vue、SVG 与 CSS 实现。
- 不展示假数据；首次失败显示可重试空状态，刷新失败保留上一次成功数据并使用全局 Toast。
- 测试与 Maven 不和 Vitest 并发执行，避免本机 worker 启动超时。

---

## File Structure

- Create: `apps/api/src/main/java/com/idavy/drtops/metrics/OperationsDashboard.java` — 看板 API 的不可变读模型、状态枚举、趋势点和分类项。
- Modify: `apps/api/src/main/java/com/idavy/drtops/metrics/OperationsMetricsService.java` — 一次加载聚合源数据并计算当前指标、基线、趋势和分布。
- Modify: `apps/api/src/main/java/com/idavy/drtops/metrics/OperationsMetricsController.java` — 暴露 `/operations-dashboard` 并校验 `days=7`。
- Modify: `apps/api/src/test/java/com/idavy/drtops/metrics/OperationsMetricsServiceTest.java` — 覆盖日期、口径、零分母、基线状态和分类守恒。
- Create: `apps/api/src/test/java/com/idavy/drtops/metrics/OperationsMetricsControllerTest.java` — 覆盖 HTTP 查询参数、响应字段和非法 `days`。
- Modify: `apps/admin-web/src/api/types.ts` — 定义 `OperationsDashboard` 完整前端契约。
- Modify: `apps/admin-web/src/api/metrics.ts` — 新增聚合接口请求函数。
- Create: `apps/admin-web/src/api/metrics.test.ts` — 锁定 URL、日期和 `days` 参数。
- Modify: `apps/admin-web/src/stores/operationsStore.ts` — 保存最后一次成功快照，区分首次加载和刷新。
- Create: `apps/admin-web/src/stores/operations-store.test.ts` — 覆盖成功、刷新失败保留旧数据。
- Create: `apps/admin-web/src/presentation/dashboardMetrics.ts` — 格式化日期、比例、百分点、基线和校正后的分布百分比。
- Create: `apps/admin-web/src/presentation/dashboard-metrics.test.ts` — 覆盖零分母、负变化和 100% 校正。
- Create: `apps/admin-web/src/components/DashboardMetricCard.vue` — 四项指标卡的统一展示。
- Create: `apps/admin-web/src/components/dashboard-metric-card.test.ts` — 覆盖绝对值、比例、状态和无基线。
- Create: `apps/admin-web/src/components/OperationsTrendChart.vue` — 三种模式下的动态标题、坐标轴、图例、SVG 和可访问摘要。
- Create: `apps/admin-web/src/components/operations-trend-chart.test.ts` — 覆盖标题、横坐标、模式切换和空样本断点。
- Create: `apps/admin-web/src/components/DistributionDonut.vue` — 环图、数量与校正百分比图例、空状态。
- Create: `apps/admin-web/src/components/distribution-donut.test.ts` — 覆盖百分比合计和无数据。
- Modify: `apps/admin-web/src/pages/DashboardPage.vue` — 编排新接口、四卡、趋势、三分布、骨架和 Toast。
- Modify: `apps/admin-web/src/pages/dashboard-page.test.ts` — 锁定首屏结构、上海运营日刷新和失败保留数据。
- Modify: `apps/admin-web/src/App.vue` — 只补充看板共用的全局色彩与高密度基础样式，不改变其他页面业务结构。

---

### Task 1: 建立看板聚合读模型与后端统计口径

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/metrics/OperationsDashboard.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/metrics/OperationsMetricsService.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/metrics/OperationsMetricsServiceTest.java`

**Interfaces:**
- Consumes: `RideOrderRepository.findAll()`、`DispatchDecisionRepository.findAll()`、`VehicleTaskRepository.findAll()`、`VehicleRepository.findAll()`。
- Produces: `OperationsDashboard OperationsMetricsService.calculateDashboard(LocalDate endDate, int days)`。
- Produces: `OperationsDashboard` 顶层字段 `operatingDate`、`rangeStart`、`rangeEnd`、`coreMetrics`、`trend`、`distributions`、`generatedAt`。

- [ ] **Step 1: 写失败测试，锁定连续 7 日、上海运营日、分子分母和分类守恒**

在 `OperationsMetricsServiceTest` 增加一个跨 UTC 日期边界的场景，使用手工可核算的两天数据，并断言未写数据的 5 天也存在：

```java
@Test
void buildsSevenDayDashboardWithAbsoluteCountsRatesAndDistributions() {
    OffsetDateTime shanghaiAugust13 = OffsetDateTime.parse("2026-08-12T23:30:00Z");
    RideOrder completed = saveCompletedOrderAt("13800009101", shanghaiAugust13);
    dispatchDecisionRepository.save(dispatchDecision(completed.getId(), DispatchDecisionType.AUTO_DISPATCH,
            VEHICLE_ID, 8, 3));
    saveCompletedTask(VEHICLE_ID, shanghaiAugust13.plusMinutes(5));
    saveDispatchableVehicle(VEHICLE_ID, "甘J·16396");
    saveDispatchableVehicle(OTHER_VEHICLE_ID, "甘J·85211");

    OperationsDashboard dashboard = metricsService.calculateDashboard(LocalDate.parse("2026-08-13"), 7);

    assertThat(dashboard.rangeStart()).isEqualTo(LocalDate.parse("2026-08-07"));
    assertThat(dashboard.trend()).extracting(OperationsDashboard.TrendPoint::date)
            .containsExactly(
                    LocalDate.parse("2026-08-07"), LocalDate.parse("2026-08-08"),
                    LocalDate.parse("2026-08-09"), LocalDate.parse("2026-08-10"),
                    LocalDate.parse("2026-08-11"), LocalDate.parse("2026-08-12"),
                    LocalDate.parse("2026-08-13"));
    assertThat(dashboard.coreMetrics().taskCompletion().completed()).isEqualTo(1);
    assertThat(dashboard.coreMetrics().taskCompletion().total()).isEqualTo(1);
    assertThat(dashboard.coreMetrics().vehicleUtilization().utilized()).isEqualTo(1);
    assertThat(dashboard.coreMetrics().vehicleUtilization().available()).isEqualTo(2);
    assertThat(dashboard.distributions().orders()).extracting(OperationsDashboard.DistributionItem::count)
            .satisfies(counts -> assertThat(counts.stream().mapToLong(Long::longValue).sum()).isEqualTo(1));
}
```

测试文件内新增的两个 fixture helper 使用真实领域对象，不 mock 聚合输入：

同时在测试类 `@Autowired VehicleRepository vehicleRepository`，并在 `setUp()` 最后清理订单后调用 `vehicleRepository.deleteAll()`，保证车辆分母不受其他测试污染。

```java
private RideOrder saveCompletedOrderAt(String phone, OffsetDateTime requestedDepartureAt) {
    RideOrder order = newOrder(phone, requestedDepartureAt);
    order.confirm(new RideOrder.OrderPromise(requestedDepartureAt.plusMinutes(8),
            requestedDepartureAt.plusMinutes(25)));
    order.startExecution();
    order.complete();
    return rideOrderRepository.save(order);
}

private void saveDispatchableVehicle(UUID id, String plateNumber) {
    vehicleRepository.save(Vehicle.create(id, plateNumber, "微型公交", 8,
            "IDLE", "POINT (120.155 30.274)", "通渭示范车队", true));
}
```

生产变更若错误使用 UTC 日期、丢失无数据日期、用任务车辆数作为利用率分母或漏分某个状态，本测试必须失败。

- [ ] **Step 2: 运行测试并确认按预期失败**

Run:

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api '-Dtest=OperationsMetricsServiceTest' test
```

Expected: 编译失败，提示 `OperationsDashboard` 或 `calculateDashboard` 尚不存在。

- [ ] **Step 3: 定义后端读模型**

创建 `OperationsDashboard.java`。类型名和字段名固定如下，比例与无基线字段允许 `null`：

```java
public record OperationsDashboard(
        LocalDate operatingDate,
        LocalDate rangeStart,
        LocalDate rangeEnd,
        CoreMetrics coreMetrics,
        List<TrendPoint> trend,
        Distributions distributions,
        OffsetDateTime generatedAt) {
    public enum MetricStatus { NORMAL, HIGH, LOW, NO_BASELINE, NO_DATA }
    public record OrderVolume(long count, BigDecimal baseline, BigDecimal changeRate, MetricStatus status) {}
    public record TaskCompletion(long completed, long total, BigDecimal rate,
                                 BigDecimal baselineRate, MetricStatus status) {}
    public record AverageWait(BigDecimal minutes, long sampleCount, BigDecimal baselineMinutes,
                              BigDecimal changeRate, MetricStatus status) {}
    public record VehicleUtilization(long utilized, long available, BigDecimal rate,
                                     BigDecimal baselineRate, MetricStatus status) {}
    public record CoreMetrics(OrderVolume orderVolume, TaskCompletion taskCompletion,
                              AverageWait averageWait, VehicleUtilization vehicleUtilization) {}
    public record TrendPoint(LocalDate date, long orderCount, long completedTasks, long totalTasks,
                             BigDecimal taskCompletionRate, BigDecimal averageWaitMinutes,
                             long waitSampleCount, long utilizedVehicles, long availableVehicles,
                             BigDecimal vehicleUtilizationRate) {}
    public record DistributionItem(String key, String label, long count, BigDecimal rate) {}
    public record Distributions(List<DistributionItem> orders, List<DistributionItem> tasks,
                                List<DistributionItem> vehicles) {}
}
```

- [ ] **Step 4: 实现最小聚合逻辑**

在 `OperationsMetricsService` 注入 `VehicleRepository`，新增 `calculateDashboard`。实现时一次加载四类源数据，按 `operatingDateOf` 分组；趋势区间为 `[endDate-6, endDate]`，基线区间为 `[endDate-7, endDate-1]`。关键计算规则：

```java
private BigDecimal nullableRatio(long numerator, long denominator) {
    return denominator == 0 ? null : ratio(numerator, denominator);
}

private MetricStatus relativeStatus(BigDecimal current, BigDecimal baseline, BigDecimal tolerance) {
    if (current == null) return MetricStatus.NO_DATA;
    if (baseline == null) return MetricStatus.NO_BASELINE;
    if (baseline.signum() == 0) return current.signum() == 0 ? MetricStatus.NORMAL : MetricStatus.HIGH;
    BigDecimal change = current.subtract(baseline).divide(baseline, CALCULATION_SCALE, RoundingMode.HALF_UP);
    if (change.compareTo(tolerance) > 0) return MetricStatus.HIGH;
    if (change.compareTo(tolerance.negate()) < 0) return MetricStatus.LOW;
    return MetricStatus.NORMAL;
}
```

完成率阈值使用 `0.03`，车辆利用率使用 `0.05`，订单量和平均等待相对变化使用 `0.10`。基线必须分别使用加权计数或全部样本，而不是对每日百分比简单平均。

- [ ] **Step 5: 增加零分母和无基线失败测试**

```java
@Test
void reportsNullRatesAndNoBaselineWhenThereAreNoValidDenominators() {
    OperationsDashboard dashboard = metricsService.calculateDashboard(LocalDate.parse("2026-08-13"), 7);

    assertThat(dashboard.coreMetrics().taskCompletion().rate()).isNull();
    assertThat(dashboard.coreMetrics().averageWait().minutes()).isNull();
    assertThat(dashboard.coreMetrics().vehicleUtilization().rate()).isNull();
    assertThat(dashboard.coreMetrics().averageWait().status())
            .isEqualTo(OperationsDashboard.MetricStatus.NO_DATA);
}
```

Expected RED: 实现若仍把零分母转换为 `0.0000`，断言失败。

- [ ] **Step 6: 修正零分母与分布口径并运行聚焦测试**

Run: 与 Step 2 相同。

Expected: `OperationsMetricsServiceTest` 全部通过，0 failures，0 errors。

- [ ] **Step 7: 提交 Task 1**

```powershell
git add -- apps/api/src/main/java/com/idavy/drtops/metrics/OperationsDashboard.java apps/api/src/main/java/com/idavy/drtops/metrics/OperationsMetricsService.java apps/api/src/test/java/com/idavy/drtops/metrics/OperationsMetricsServiceTest.java
git commit -m "feat: aggregate operations dashboard metrics"
```

---

### Task 2: 暴露单一看板 API 并校验范围

**Files:**
- Modify: `apps/api/src/main/java/com/idavy/drtops/metrics/OperationsMetricsController.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/metrics/OperationsMetricsControllerTest.java`

**Interfaces:**
- Consumes: `OperationsMetricsService.calculateDashboard(LocalDate endDate, int days)`。
- Produces: `GET /api/metrics/operations-dashboard?endDate=YYYY-MM-DD&days=7`。
- Produces: `ApiResponse<OperationsDashboard>`；`days != 7` 返回 HTTP 400。

- [ ] **Step 1: 写失败的 HTTP 契约测试**

使用 `MockMvcBuilders.standaloneSetup` 和 Mockito，只替换服务边界，不断言 mock 本身；断言真实路由、参数绑定、HTTP 状态和 JSON 包装：

```java
@Test
void returnsDashboardEnvelopeForSevenDayRange() throws Exception {
    OperationsDashboard dashboard = dashboardFixture(LocalDate.parse("2026-08-13"));
    when(metricsService.calculateDashboard(LocalDate.parse("2026-08-13"), 7)).thenReturn(dashboard);

    mockMvc.perform(get("/api/metrics/operations-dashboard")
                    .param("endDate", "2026-08-13")
                    .param("days", "7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rangeStart").value("2026-08-07"))
            .andExpect(jsonPath("$.data.trend.length()").value(7));
}

@Test
void rejectsUnsupportedDashboardRange() throws Exception {
    mockMvc.perform(get("/api/metrics/operations-dashboard").param("days", "30"))
            .andExpect(status().isBadRequest());
}
```

`dashboardFixture` 返回真实可序列化结构，避免部分 mock 漏字段：

```java
private OperationsDashboard dashboardFixture(LocalDate endDate) {
    List<OperationsDashboard.TrendPoint> trend = endDate.minusDays(6).datesUntil(endDate.plusDays(1))
            .map(date -> new OperationsDashboard.TrendPoint(
                    date, 0, 0, 0, null, null, 0, 0, 0, null))
            .toList();
    OperationsDashboard.CoreMetrics core = new OperationsDashboard.CoreMetrics(
            new OperationsDashboard.OrderVolume(0, null, null, OperationsDashboard.MetricStatus.NO_BASELINE),
            new OperationsDashboard.TaskCompletion(0, 0, null, null, OperationsDashboard.MetricStatus.NO_DATA),
            new OperationsDashboard.AverageWait(null, 0, null, null, OperationsDashboard.MetricStatus.NO_DATA),
            new OperationsDashboard.VehicleUtilization(0, 0, null, null, OperationsDashboard.MetricStatus.NO_DATA));
    OperationsDashboard.Distributions distributions = new OperationsDashboard.Distributions(
            List.of(), List.of(), List.of());
    return new OperationsDashboard(endDate, endDate.minusDays(6), endDate,
            core, trend, distributions, OffsetDateTime.parse("2026-08-13T09:32:00+08:00"));
}
```

- [ ] **Step 2: 运行并确认路由不存在而失败**

Run:

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api '-Dtest=OperationsMetricsControllerTest' test
```

Expected: 404 或编译失败，原因是新控制器方法尚不存在。

- [ ] **Step 3: 实现控制器方法**

```java
@GetMapping("/operations-dashboard")
ApiResponse<OperationsDashboard> operationsDashboard(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestParam(defaultValue = "7") int days) {
    if (days != 7) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be 7");
    }
    LocalDate resolvedEndDate = endDate == null ? LocalDate.now(OperationsMetricsService.OPERATING_ZONE) : endDate;
    return ApiResponse.ok(metricsService.calculateDashboard(resolvedEndDate, days));
}
```

- [ ] **Step 4: 运行控制器与服务测试**

Run:

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api '-Dtest=OperationsMetricsControllerTest,OperationsMetricsServiceTest' test
```

Expected: 全部通过。

- [ ] **Step 5: 提交 Task 2**

```powershell
git add -- apps/api/src/main/java/com/idavy/drtops/metrics/OperationsMetricsController.java apps/api/src/test/java/com/idavy/drtops/metrics/OperationsMetricsControllerTest.java
git commit -m "feat: expose operations dashboard API"
```

---

### Task 3: 接入前端数据契约并保留最后成功快照

**Files:**
- Modify: `apps/admin-web/src/api/types.ts`
- Modify: `apps/admin-web/src/api/metrics.ts`
- Create: `apps/admin-web/src/api/metrics.test.ts`
- Modify: `apps/admin-web/src/stores/operationsStore.ts`
- Create: `apps/admin-web/src/stores/operations-store.test.ts`

**Interfaces:**
- Consumes: `GET /api/metrics/operations-dashboard?endDate=<date>&days=7`。
- Produces: `getOperationsDashboard(endDate: string, days?: 7): Promise<OperationsDashboard>`。
- Produces: `loadDashboard(endDate: string): Promise<boolean>`。
- Produces Store 状态：`dashboard`、`loading`、`refreshing`、`error`。

- [ ] **Step 1: 写 API URL 失败测试**

在 `metrics.test.ts` stub `global.fetch` 返回完整 envelope，并断言真实请求 URL：

```ts
it("requests the seven-day dashboard ending on the selected operating day", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(okEnvelope(dashboardFixture)));

  await getOperationsDashboard("2026-08-13");

  expect(fetch).toHaveBeenCalledWith(
    "/api/metrics/operations-dashboard?endDate=2026-08-13&days=7",
    expect.objectContaining({ headers: expect.any(Headers) })
  );
});
```

该测试文件内的 response helper 必须真实执行 `request()` 的 envelope 解包路径：

```ts
function okEnvelope(data: OperationsDashboard): Response {
  return new Response(JSON.stringify({ data }), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });
}
```

`dashboardFixture` 在 API 与 Store 测试中都声明为完整的 `OperationsDashboard` 字面量，包含 7 个 `trend` 点和三组 `distributions`，不得用 `as OperationsDashboard` 绕过缺字段检查。

生产变更若遗漏日期编码、请求错误路由或错误天数，本测试失败。

- [ ] **Step 2: 运行并确认导出不存在而失败**

Run:

```powershell
npm.cmd test -- --run src/api/metrics.test.ts --maxWorkers=1
```

Expected: `getOperationsDashboard` 未导出或类型不存在。

- [ ] **Step 3: 定义完整 TypeScript 契约并实现请求函数**

类型与 Java 字段一一对应；所有可能无分母或无基线的字段使用 `DecimalValue | null`：

```ts
export type DashboardMetricStatus = "NORMAL" | "HIGH" | "LOW" | "NO_BASELINE" | "NO_DATA";
export interface DashboardTrendPoint {
  date: string;
  orderCount: number;
  completedTasks: number;
  totalTasks: number;
  taskCompletionRate: DecimalValue | null;
  averageWaitMinutes: DecimalValue | null;
  waitSampleCount: number;
  utilizedVehicles: number;
  availableVehicles: number;
  vehicleUtilizationRate: DecimalValue | null;
}
```

```ts
export function getOperationsDashboard(endDate: string, days: 7 = 7): Promise<OperationsDashboard> {
  const query = new URLSearchParams({ endDate, days: String(days) });
  return request<OperationsDashboard>(`/api/metrics/operations-dashboard?${query.toString()}`);
}
```

- [ ] **Step 4: 写 Store 刷新失败保留旧数据的失败测试**

```ts
it("keeps the last successful dashboard when refresh fails", async () => {
  mockedGet.mockResolvedValueOnce(dashboardFixture).mockRejectedValueOnce(new Error("offline"));
  const store = useOperationsStore();
  await store.loadDashboard("2026-08-13");
  await store.loadDashboard("2026-08-13");

  expect(store.state.dashboard).toEqual(dashboardFixture);
  expect(store.state.error).toBe("运营数据加载失败");
});
```

- [ ] **Step 5: 实现 Store 最小状态机**

初次请求 `dashboard === null` 时设置 `loading=true`；已有快照时设置 `refreshing=true`。失败只写 `error`，不得清空 `dashboard`。函数返回成功布尔值供页面决定 Toast：

```ts
async function loadDashboard(endDate: string): Promise<boolean> {
  state.dashboard === null ? state.loading = true : state.refreshing = true;
  state.error = "";
  try {
    state.dashboard = await getOperationsDashboard(endDate);
    return true;
  } catch (error) {
    state.error = userMessage(error, "运营数据加载失败");
    return false;
  } finally {
    state.loading = false;
    state.refreshing = false;
  }
}
```

- [ ] **Step 6: 顺序运行 API 与 Store 测试**

Run:

```powershell
npm.cmd test -- --run src/api/metrics.test.ts src/stores/operations-store.test.ts --maxWorkers=1
```

Expected: 全部通过且无 unhandled errors。

- [ ] **Step 7: 提交 Task 3**

```powershell
git add -- apps/admin-web/src/api/types.ts apps/admin-web/src/api/metrics.ts apps/admin-web/src/api/metrics.test.ts apps/admin-web/src/stores/operationsStore.ts apps/admin-web/src/stores/operations-store.test.ts
git commit -m "feat: load operations dashboard snapshot"
```

---

### Task 4: 实现指标卡、趋势图和百分比分布组件

**Files:**
- Create: `apps/admin-web/src/presentation/dashboardMetrics.ts`
- Create: `apps/admin-web/src/presentation/dashboard-metrics.test.ts`
- Create: `apps/admin-web/src/components/DashboardMetricCard.vue`
- Create: `apps/admin-web/src/components/dashboard-metric-card.test.ts`
- Create: `apps/admin-web/src/components/OperationsTrendChart.vue`
- Create: `apps/admin-web/src/components/operations-trend-chart.test.ts`
- Create: `apps/admin-web/src/components/DistributionDonut.vue`
- Create: `apps/admin-web/src/components/distribution-donut.test.ts`

**Interfaces:**
- Produces: `formatNullablePercentage(value)`、`formatSignedRate(value)`、`metricStatusLabel(status)`、`distributionPercentages(items)`、`shortDateRange(start, end)`。
- Produces: `DashboardMetricCard` props `label`、`primary`、`secondary`、`status`、`comparison`、`baseline`、`accent`。
- Produces: `OperationsTrendChart` props `points`、`rangeStart`、`rangeEnd`；内部模式 `combined | wait | utilization`。
- Produces: `DistributionDonut` props `title`、`unit`、`items`。

- [ ] **Step 1: 写格式化和百分比校正失败测试**

```ts
it("corrects displayed distribution percentages to exactly 100.0", () => {
  expect(distributionPercentages([
    { key: "a", label: "A", count: 1, rate: "0.3333" },
    { key: "b", label: "B", count: 1, rate: "0.3333" },
    { key: "c", label: "C", count: 1, rate: "0.3333" }
  ])).toEqual([33.3, 33.3, 33.4]);
});

it("renders missing denominators as double dash", () => {
  expect(formatNullablePercentage(null)).toBe("--");
});
```

- [ ] **Step 2: 运行并确认模块不存在而失败**

Run:

```powershell
npm.cmd test -- --run src/presentation/dashboard-metrics.test.ts --maxWorkers=1
```

Expected: 模块或导出不存在。

- [ ] **Step 3: 实现纯格式化函数并通过测试**

百分比校正以非零项中最后一项承接差值；零总量返回全零，不生成负数：

```ts
export function distributionPercentages(items: DashboardDistributionItem[]): number[] {
  const nonZero = items.map((item, index) => item.count > 0 ? index : -1).filter((index) => index >= 0);
  if (nonZero.length === 0) return items.map(() => 0);
  const last = nonZero.at(-1)!;
  const result = items.map((item) => item.count > 0 ? Math.round(Number(item.rate) * 1000) / 10 : 0);
  const otherTotal = result.reduce((sum, value, index) => index === last ? sum : sum + value, 0);
  result[last] = Math.round((100 - otherTotal) * 10) / 10;
  return result;
}
```

- [ ] **Step 4: 写指标卡失败测试并实现组件**

测试必须找到 `110/119`、`92.4%`、`正常` 和 `基线 90.3%`；另一个 case 使用 `NO_BASELINE` 并找到 `暂无基线`。实现使用语义化 `<article>` 与可见状态文字，颜色只作辅助。

Run:

```powershell
npm.cmd test -- --run src/components/dashboard-metric-card.test.ts --maxWorkers=1
```

Expected RED 后实现，再运行得到 PASS。

- [ ] **Step 5: 写趋势标题与横坐标联动失败测试**

```ts
it("keeps combined title, axes and seven dates aligned", async () => {
  render(OperationsTrendChart, { props: { points, rangeStart: "2026-08-07", rangeEnd: "2026-08-13" } });
  expect(screen.getByRole("heading", { name: "近 7 天订单量与任务完成率趋势（08-07 至 08-13）" })).toBeInTheDocument();
  expect(screen.getByText("订单量（单）")).toBeInTheDocument();
  expect(screen.getByText("任务完成率（%）")).toBeInTheDocument();
  expect(screen.getAllByTestId("trend-date").map((node) => node.textContent))
    .toEqual(["08-07", "08-08", "08-09", "08-10", "08-11", "08-12", "08-13"]);
});

it("updates title and unit when average wait is selected", async () => {
  render(OperationsTrendChart, { props: { points, rangeStart: "2026-08-07", rangeEnd: "2026-08-13" } });
  await screen.getByRole("button", { name: "平均等待" }).click();
  expect(screen.getByRole("heading", { name: "近 7 天平均等待时间趋势（08-07 至 08-13）" })).toBeInTheDocument();
  expect(screen.getByText("平均等待（分钟）")).toBeInTheDocument();
  expect(screen.queryByText("任务完成率（%）")).not.toBeInTheDocument();
});
```

- [ ] **Step 6: 实现原生 SVG 趋势图并通过测试**

将绘图坐标限制在组件内部纯 computed 中。组合模式使用左轴柱形和右轴折线；等待与利用率模式使用单折线。`null` 点不绘制圆点，折线路径在该处断开。SVG 添加动态 `aria-label`，并输出 `sr-only` 数据表。

Run:

```powershell
npm.cmd test -- --run src/components/operations-trend-chart.test.ts --maxWorkers=1
```

Expected: 全部通过。

- [ ] **Step 7: 写分布数量、百分比和空状态失败测试并实现组件**

```ts
expect(screen.getByText("已完成 83 单 · 64.8%")).toBeInTheDocument();
expect(screen.getByText("执行中 23 单 · 18.0%")).toBeInTheDocument();
```

零总量 case 断言 `当日暂无可统计数据`，并断言不存在 `role="img"` 的环图。环图使用 `conic-gradient`，同时保留文字图例。

- [ ] **Step 8: 顺序运行 Task 4 全部测试**

Run:

```powershell
npm.cmd test -- --run src/presentation/dashboard-metrics.test.ts src/components/dashboard-metric-card.test.ts src/components/operations-trend-chart.test.ts src/components/distribution-donut.test.ts --maxWorkers=1
```

Expected: 全部通过，无 unhandled errors。

- [ ] **Step 9: 提交 Task 4**

```powershell
git add -- apps/admin-web/src/presentation/dashboardMetrics.ts apps/admin-web/src/presentation/dashboard-metrics.test.ts apps/admin-web/src/components/DashboardMetricCard.vue apps/admin-web/src/components/dashboard-metric-card.test.ts apps/admin-web/src/components/OperationsTrendChart.vue apps/admin-web/src/components/operations-trend-chart.test.ts apps/admin-web/src/components/DistributionDonut.vue apps/admin-web/src/components/distribution-donut.test.ts
git commit -m "feat: add operations dashboard visualizations"
```

---

### Task 5: 重构运营看板页面并完成视觉与回归验证

**Files:**
- Modify: `apps/admin-web/src/pages/DashboardPage.vue`
- Modify: `apps/admin-web/src/pages/dashboard-page.test.ts`
- Modify: `apps/admin-web/src/App.vue`

**Interfaces:**
- Consumes: `useOperationsStore().loadDashboard()`、`DashboardMetricCard`、`OperationsTrendChart`、`DistributionDonut`、`feedbackStore`。
- Produces: 四卡、趋势、三分布的完整页面；首次骨架、失败重试、刷新保留数据和 Toast。

- [ ] **Step 1: 重写页面测试为新结构并确认失败**

mock 必须镜像完整 `OperationsDashboard`。断言四个卡片标题、绝对值与比例、趋势标题、7 个日期和三张分布标题：

```ts
expect(await screen.findByText("当日订单量")).toBeInTheDocument();
expect(screen.getByText("110/119")).toBeInTheDocument();
expect(screen.getByText("92.4%")).toBeInTheDocument();
expect(screen.getByRole("heading", { name: "近 7 天订单量与任务完成率趋势（08-07 至 08-13）" })).toBeInTheDocument();
expect(screen.getByRole("heading", { name: "订单状态分布" })).toBeInTheDocument();
expect(screen.getByRole("heading", { name: "任务状态分布" })).toBeInTheDocument();
expect(screen.getByRole("heading", { name: "车辆状态分布" })).toBeInTheDocument();
```

Run:

```powershell
npm.cmd test -- --run src/pages/dashboard-page.test.ts --maxWorkers=1
```

Expected: 旧页面缺少新结构而失败。

- [ ] **Step 2: 实现页面编排与全局 Toast**

首次 `onMounted` 静默加载；手动刷新成功调用 `feedbackStore.success("运营数据已更新")`，失败调用 `feedbackStore.error(state.error)`。日期使用现有上海运营日函数，每次刷新重新计算。页面已有数据时即使刷新失败也保留组件树。

- [ ] **Step 3: 落实高密度视觉样式**

在 `DashboardPage.vue` scoped style 中实现四列 KPI、主趋势面板和三列分布。视觉常量写入 `App.vue` 的 `:root`：

```css
--ops-navy: #172433;
--ops-teal: #08a99d;
--ops-blue: #2e7be6;
--ops-warning: #d98a22;
--ops-canvas: #eef3f6;
--ops-line: #d8e3e9;
```

1440px 以上四卡/三分布单行，900px 至 1439px 两列，900px 以下单列；图表容器最小宽度 720px 并允许横向滚动。动效只使用 180ms 的面板淡入和按钮状态，不添加干扰运营阅读的循环动画。

- [ ] **Step 4: 增加加载与刷新失败回归测试**

初次 promise 未完成时断言四个 `aria-label="正在加载核心指标"` 骨架；已有数据后第二次请求拒绝，断言旧值仍在并出现全局错误 Toast。生产变更若失败时清空快照，此测试失败。

- [ ] **Step 5: 运行页面测试、全部前端测试、类型检查和构建**

严格顺序执行：

```powershell
npm.cmd test -- --run src/pages/dashboard-page.test.ts --maxWorkers=1
npm.cmd test -- --run --maxWorkers=1
npm.cmd run typecheck
npm.cmd run build
```

Expected: 全部退出码 0；Vitest 无 unhandled errors；TypeScript 无错误；Vite 构建成功。

- [ ] **Step 6: 运行后端指标测试与完整 API 测试**

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api '-Dtest=OperationsMetricsControllerTest,OperationsMetricsServiceTest' test
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api test
```

Expected: 全部退出码 0。

- [ ] **Step 7: 浏览器视觉复验**

启动本地 API 与前端后，在 1440×900 复核：四张核心指标卡和趋势主体位于首屏；标题日期范围与 7 个横坐标一致；三个模式切换后标题、单位和图例同步；三组分类图均显示数量与百分比。再将视口缩至 820px，确认卡片单列、图表可横向滚动且无文字重叠。

- [ ] **Step 8: 检查 P6-2 基线漂移并提交 Task 5**

```powershell
$p6Head = git -C 'D:\codex-projects\.worktrees\drt-ops-mvp' rev-parse HEAD
git merge-base --is-ancestor 527aca5 $p6Head
git diff --check
git status --short
git add -- apps/admin-web/src/pages/DashboardPage.vue apps/admin-web/src/pages/dashboard-page.test.ts apps/admin-web/src/App.vue
git commit -m "feat: redesign operations dashboard"
```

若 P6-2 已新增提交，先检查这些提交是否触及本计划文件；有重叠则停止并报告冲突风险，无重叠则在最终 PR 前同步基线。

---

## Final Verification

完成所有任务后，调用 `superpowers:verification-before-completion`，从干净状态重新运行：

```powershell
npm.cmd test -- --run --maxWorkers=1
npm.cmd run typecheck
npm.cmd run build
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -pl apps/api test
git diff --check
git status --short --branch
```

全部通过后调用 `superpowers:finishing-a-development-branch`，确认提交范围、推送 `codex/admin-ui-optimization` 并创建草稿 PR。PR 描述必须包含：设计规格链接、四项指标口径、7 日趋势日期规则、分类百分比规则、验证命令与已知依赖审计告警。
