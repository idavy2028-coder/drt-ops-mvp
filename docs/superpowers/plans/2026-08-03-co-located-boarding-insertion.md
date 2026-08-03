# 同站点多人上车与插单排序实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复既有任务的同站点插单顺序，使一次到站联动相邻同站点上车节点、每笔订单仍逐单确认上车，并安全纠正试运行任务 `1626d98d`。

**Architecture:** 在任务领域层新增统一 `TaskStopInsertionPolicy`，由自动派单和人工复核共同调用；`VehicleTask` 负责受控插入和连续重编号。`TaskExecutionService` 在一次上车站到站事件内推进连续同站点节点，并为每个节点分别追加到站审计。当前真实任务使用带严格前置条件的一次性 SQL 事务纠正，不增加长期管理接口。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring Data JPA、JUnit 5、AssertJ、Mockito、PostgreSQL/PostGIS、Vue 3（仅回归验证，不修改前端）。

## Global Constraints

- 严格执行 RED-GREEN-REFACTOR；任何生产代码之前必须有对应失败测试。
- 同站点共享一次到站位置事件，但每笔订单必须分别确认上车并保留独立审计。
- 不修改数据库表结构，不开放任意越序任务操作，不增加长期数据纠正接口。
- 自动派单与人工复核必须调用同一个插入策略。
- 不覆盖或提交当前工作区中与本任务无关的未提交改动。
- 代码测试和部署验证全部通过前，不修改真实任务 `1626d98d`。
- 真实任务纠正前必须备份，事务前置条件不一致时必须整体回滚。

---

### Task 1: 任务领域同站点插入策略

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStopInsertionPolicy.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/task/TaskStopInsertionPolicyTest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStop.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTask.java`

**Interfaces:**
- Consumes: `VehicleTask` 的已排序节点列表、订单上下车站点 ID、订单 ID 和计划时间。
- Produces: `TaskStopInsertionPolicy.insertOrderStops(VehicleTask, UUID, UUID, UUID, OffsetDateTime, OffsetDateTime)`；`VehicleTask.insertStop(int, TaskStop)`；`TaskStop.resequence(int)`。

- [ ] **Step 1: 写入同站点插入失败测试**

```java
@Test
void insertsNewBoardingBesideIncompleteBoardingAtSameStop() {
    VehicleTask task = taskWithStops(
            stop(HIGH_SPEED_RAIL_STOP, FIRST_ORDER, 1, "BOARDING"),
            stop(ANCHUAN_STOP, FIRST_ORDER, 2, "ALIGHTING"));

    policy.insertOrderStops(task, HIGH_SPEED_RAIL_STOP, LONGYANG_STOP, SECOND_ORDER, BOARDING_AT, ALIGHTING_AT);

    assertThat(task.getStops())
            .extracting(TaskStop::getRideOrderId, TaskStop::getStopType, TaskStop::getSequenceNumber)
            .containsExactly(
                    tuple(FIRST_ORDER, "BOARDING", 1),
                    tuple(SECOND_ORDER, "BOARDING", 2),
                    tuple(FIRST_ORDER, "ALIGHTING", 3),
                    tuple(SECOND_ORDER, "ALIGHTING", 4));
}
```

同时增加两个独立测试：不同上车站仍末尾追加；只有已完成的历史同站点时不得插回历史位置。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api "-Dtest=TaskStopInsertionPolicyTest" test
```

Expected: 编译或断言失败，明确指出 `TaskStopInsertionPolicy`/受控重排能力尚不存在；不得因测试装配错误失败。

- [ ] **Step 3: 实现最小插入策略**

`TaskStop` 增加包内可见重编号：

```java
void resequence(int sequenceNumber) {
    if (sequenceNumber <= 0) {
        throw new IllegalArgumentException("sequenceNumber must be positive");
    }
    this.sequenceNumber = sequenceNumber;
}
```

`VehicleTask` 增加受控插入并统一重编号：

```java
public void insertStop(int index, TaskStop stop) {
    if (index < 0 || index > stops.size()) {
        throw new IllegalArgumentException("stop index out of range");
    }
    stop.assignTo(this);
    stops.add(index, stop);
    for (int position = 0; position < stops.size(); position++) {
        stops.get(position).resequence(position + 1);
    }
    this.updatedAt = OffsetDateTime.now();
}
```

新增策略组件：

```java
@Component
public class TaskStopInsertionPolicy {
    public void insertOrderStops(
            VehicleTask task,
            UUID boardingStopId,
            UUID alightingStopId,
            UUID rideOrderId,
            OffsetDateTime boardingAt,
            OffsetDateTime alightingAt) {
        int originalSize = task.getStops().size();
        TaskStop boarding = TaskStop.planned(
                boardingStopId, rideOrderId, originalSize + 1, "BOARDING", boardingAt);
        TaskStop alighting = TaskStop.planned(
                alightingStopId, rideOrderId, originalSize + 2, "ALIGHTING", alightingAt);
        int insertionIndex = sameStopInsertionIndex(task.getStops(), boardingStopId);
        if (insertionIndex < 0) {
            task.addStop(boarding);
        } else {
            task.insertStop(insertionIndex, boarding);
        }
        task.addStop(alighting);
    }
}
```

`sameStopInsertionIndex` 只匹配状态为 `PLANNED` 或 `ARRIVED` 的同站点 `BOARDING` 节点，并返回连续同站点上车组末尾的零基索引；找不到时返回 `-1`。

- [ ] **Step 4: 运行 Task 1 测试并确认 GREEN**

Run:

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api "-Dtest=TaskStopInsertionPolicyTest,VehicleTaskStateTest" test
```

Expected: 全部通过，节点序号连续且不同站点行为不变。

- [ ] **Step 5: 仅暂存 Task 1 文件并提交**

```powershell
git add -- apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStop.java apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTask.java apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStopInsertionPolicy.java apps/api/src/test/java/com/idavy/drtops/domain/task/TaskStopInsertionPolicyTest.java
git commit -m "fix: insert co-located boarding stops together"
```

---

### Task 2: 自动派单和人工复核共用插入策略

**Files:**
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchOrchestrator.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewService.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorTest.java`
- Modify: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/ManualReviewApiTest.java`

**Interfaces:**
- Consumes: Task 1 的 `TaskStopInsertionPolicy.insertOrderStops(...)`。
- Produces: 自动派单和人工复核对同一输入产生一致的 `BOARDING, BOARDING, ALIGHTING, ALIGHTING` 序列。

- [ ] **Step 1: 强化两个既有插单测试并确认它们捕获错误顺序**

在 `autoDispatchCanInsertOrderIntoExistingInProgressTask` 和 `approveManualReviewCanInsertOrderIntoExistingTask` 中，使用同一个上车站点并对完整顺序做字面量断言：

```java
assertThat(task.getStops())
        .extracting(TaskStop::getRideOrderId, TaskStop::getStopType)
        .containsExactly(
                tuple(existingOrderId, "BOARDING"),
                tuple(insertedOrder.getId(), "BOARDING"),
                tuple(existingOrderId, "ALIGHTING"),
                tuple(insertedOrder.getId(), "ALIGHTING"));
```

- [ ] **Step 2: 运行两个测试并确认 RED**

Run:

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api "-Dtest=DispatchOrchestratorTest#autoDispatchCanInsertOrderIntoExistingInProgressTask,ManualReviewApiTest#approveManualReviewCanInsertOrderIntoExistingTask" test
```

Expected: 断言得到旧顺序 `BOARDING, ALIGHTING, BOARDING, ALIGHTING`。

- [ ] **Step 3: 注入并调用统一策略**

两个服务构造函数均增加：

```java
private final TaskStopInsertionPolicy taskStopInsertionPolicy;
```

两个 `insertIntoExistingTask` 方法删除 `max(sequenceNumber) + 1` 和两次直接 `addStop`，统一替换为：

```java
taskStopInsertionPolicy.insertOrderStops(
        task,
        order.getBoardingStopId(),
        order.getAlightingStopId(),
        order.getId(),
        boardingAt,
        alightingAt);
```

自动派单使用 `estimatedBoardingAt`/`estimatedArrivalAt`，人工复核使用 `boardingAt`/`alightingAt`。

- [ ] **Step 4: 运行专项及派单回归并确认 GREEN**

Run:

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api "-Dtest=TaskStopInsertionPolicyTest,DispatchOrchestratorTest,ManualReviewApiTest" test
```

Expected: 全部通过；容量不足、任务缺失、人工拒绝等既有分支保持通过。

- [ ] **Step 5: 仅暂存 Task 2 文件并提交**

```powershell
git add -- apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchOrchestrator.java apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewService.java apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorTest.java apps/api/src/test/java/com/idavy/drtops/domain/dispatch/ManualReviewApiTest.java
git commit -m "fix: share insertion policy across dispatch paths"
```

---

### Task 3: 一次到站联动、逐单上车

**Files:**
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/task/CoLocatedBoardingTaskExecutionTest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStop.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/TaskExecutionService.java`

**Interfaces:**
- Consumes: 已排序的相邻同站点 `BOARDING` 节点。
- Produces: `TaskStop.arriveAt(OffsetDateTime)`；一次 `TaskExecutionService.arrive(...)` 将连续同站点上车节点全部置为 `ARRIVED`，每个节点写一条到站审计；现有 `board(...)` 仍一次处理一个节点。

- [ ] **Step 1: 写共享到站失败测试**

使用真实 `VehicleTask`/`TaskStop` 和真实 `VehicleLocationEvent`，仅模拟仓储及位置记录边界：

```java
@Test
void oneArrivalMarksAdjacentBoardingStopsAtSameVirtualStop() {
    VehicleTask task = inProgressTask(
            stop(HIGH_SPEED_RAIL_STOP, FIRST_ORDER, 1, "BOARDING"),
            stop(HIGH_SPEED_RAIL_STOP, SECOND_ORDER, 2, "BOARDING"),
            stop(ANCHUAN_STOP, FIRST_ORDER, 3, "ALIGHTING"));
    when(vehicleTaskRepository.findByIdForExecution(task.getId())).thenReturn(Optional.of(task));
    when(locationRecorder.findReplay(any())).thenReturn(Optional.empty());
    when(locationRecorder.append(any())).thenReturn(freshArrivalResult(task, task.getStops().getFirst()));

    service.arrive(ACTOR_ID, task.getId(), task.getStops().getFirst().getId(), arrivalRequest());

    assertThat(task.getStops()).extracting(TaskStop::getStatus)
            .containsExactly("ARRIVED", "ARRIVED", "PLANNED");
    assertThat(task.getStops().get(0).getActualArrivalAt())
            .isEqualTo(task.getStops().get(1).getActualArrivalAt());
    verify(auditLogRepository, times(2)).save(any(AuditLog.class));
}
```

增加两个独立测试：不同站点不联动；连续同站点节点到站后，两次 `board(...)` 分别生成两条 `PASSENGER_BOARDED` 审计并逐个变为 `BOARDED`。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api "-Dtest=CoLocatedBoardingTaskExecutionTest" test
```

Expected: 第二个同站点节点仍为 `PLANNED`，或 `arriveAt` 尚不存在。

- [ ] **Step 3: 实现共享到站最小逻辑**

`TaskStop` 增加：

```java
public void arriveAt(OffsetDateTime actualArrivalAt) {
    requireStatus("PLANNED");
    this.actualArrivalAt = Objects.requireNonNull(actualArrivalAt, "actualArrivalAt");
    this.status = "ARRIVED";
}
```

现有 `arrive()` 委托给 `arriveAt(OffsetDateTime.now())`。

`TaskExecutionService.arrive` 在写入一次位置事件后，计算联动节点：

```java
List<TaskStop> arrivalStops = coLocatedArrivalStops(task, stop);
OffsetDateTime actualArrivalAt = result.event().getDriverReportedAt();
for (TaskStop arrivalStop : arrivalStops) {
    arrivalStop.arriveAt(actualArrivalAt);
    audit(actorId, task.getId(), "TASK_STOP_ARRIVED",
            arrivalStop.getId().toString(), result.event().getId());
}
```

`coLocatedArrivalStops` 对下车节点只返回触发节点；对上车节点从触发位置向后收集连续、同 `virtualStopId`、类型为 `BOARDING`、状态为 `PLANNED` 的节点，遇到任一不匹配即停止。

- [ ] **Step 4: 运行专项与任务执行回归并确认 GREEN**

Run:

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api "-Dtest=CoLocatedBoardingTaskExecutionTest,TaskExecutionApiTest,TaskLocationTransactionTest" test
```

Expected: 全部通过；单订单执行、幂等冲突和事务回滚保持不变。

- [ ] **Step 5: 仅暂存 Task 3 文件并提交**

```powershell
git add -- apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStop.java apps/api/src/main/java/com/idavy/drtops/domain/task/TaskExecutionService.java apps/api/src/test/java/com/idavy/drtops/domain/task/CoLocatedBoardingTaskExecutionTest.java
git commit -m "fix: share arrival across co-located boarding stops"
```

---

### Task 4: 全量验证、部署与当前任务安全纠正

**Files:**
- Create: `docs/pilot/evidence/p5-colocated-boarding-correction-backup-2026-08-03.md`
- Create: `docs/pilot/evidence/p5-colocated-boarding-correction-2026-08-03.sql`
- Create: `docs/pilot/evidence/p5-colocated-boarding-correction-validation-2026-08-03.md`

**Interfaces:**
- Consumes: 通过测试的 API 包、当前任务 `1626d98d-d792-4a7d-91eb-40f6ccbba5be`、两笔订单和现有高铁站到站/上车事件。
- Produces: 已部署修复；目标节点顺序 `BOARDING, BOARDING, ALIGHTING, ALIGHTING`；第二笔纠正型 `PASSENGER_BOARDED` 事件；追加式 `PASSENGER_BOARDED` 与 `TASK_COLOCATED_BOARDING_CORRECTED` 审计；完整备份和验收记录。

- [ ] **Step 1: 运行 API 全量测试**

Run:

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api test
```

Expected: 退出码 0，无失败测试。

- [ ] **Step 2: 构建并部署本地 API，验证健康状态**

使用当前试点 compose/调试容器的既有部署方式重新构建 API，随后验证：

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:18081/actuator/health' -TimeoutSec 10
```

Expected: `status` 为 `UP`。部署过程中不重建 PostgreSQL 数据卷。

- [ ] **Step 3: 只读导出纠正前证据**

按任务 ID 查询并记录：`ride_orders`、`vehicle_tasks`、`task_stops`、`dispatch_decisions`、`vehicle_location_events`、`audit_logs`。输出写入 `p5-colocated-boarding-correction-backup-2026-08-03.md`，仅包含目标任务及两笔订单，不包含密码和无关乘客信息。

备份中必须明确记录四个节点 ID、原顺序、状态、共享高铁站 ID、原始到站事件 ID、第一笔上车事件 ID和操作者 `dispatcher02` 的用户 ID。

- [ ] **Step 4: 编写并以回滚模式验证纠正事务**

SQL 文件先使用 `ROLLBACK` 结尾。事务必须：

```sql
BEGIN;
SELECT id FROM vehicle_tasks
 WHERE id = '1626d98d-d792-4a7d-91eb-40f6ccbba5be'::uuid
   AND status = 'IN_PROGRESS'
 FOR UPDATE;

-- DO 块断言：目标任务恰有四个节点；第一笔上车 BOARDED；
-- 第二笔上车及两个下车 PLANNED；两个上车 virtual_stop_id 相同；
-- 两笔订单均为 IN_PROGRESS；车辆为甘J00856D；dispatcher02 唯一存在。

-- 为避免序号碰撞，先把原序号 2 临时改为 20，再把第二笔上车 3 改为 2，最后把 20 改为 3。
-- 第二笔上车节点复制第一笔实际到站时间并改为 BOARDED。
-- 从第一笔 PASSENGER_BOARDED 位置事件复制经纬度、地址和 driver_reported_at，
-- 生成新的纠正型 PASSENGER_BOARDED 事件，task_stop_id 指向第二笔上车节点，recorded_at 使用 now()。
-- 追加 PASSENGER_BOARDED 审计和 TASK_COLOCATED_BOARDING_CORRECTED 审计；不更新或删除既有审计。

ROLLBACK;
```

Run: 将 SQL 复制进 PostgreSQL 容器后以 `psql -v ON_ERROR_STOP=1 -f` 执行。

Expected: 所有断言通过，事务显示回滚；回滚后数据库状态与备份完全一致。

- [ ] **Step 5: 将事务末尾改为 COMMIT 并执行一次**

仅在 Step 1–4 全部通过后，用 `apply_patch` 把最后一行从 `ROLLBACK;` 改为 `COMMIT;`，再次执行同一 SQL 文件。禁止重复执行；脚本中的前置状态断言应使第二次执行失败。

- [ ] **Step 6: 只读复核数据库与浏览器**

数据库必须满足：

```text
1 BOARDING  a9c1c313  BOARDED  高铁站
2 BOARDING  0440ec69  BOARDED  高铁站
3 ALIGHTING a9c1c313  PLANNED  安川
4 ALIGHTING 0440ec69  PLANNED  陇阳镇
```

并确认：两笔订单仍为 `IN_PROGRESS`；任务仍为 `IN_PROGRESS`；甘J00856D 未释放；第二笔存在纠正型 `PASSENGER_BOARDED` 事件；两条新增审计均引用该事件；原审计仍存在。

浏览器必须显示两个高铁站上车节点均已上车，下一动作是安川下车站“到站”。

- [ ] **Step 7: 写入验收记录并单独提交证据**

```powershell
git add -- docs/pilot/evidence/p5-colocated-boarding-correction-backup-2026-08-03.md docs/pilot/evidence/p5-colocated-boarding-correction-2026-08-03.sql docs/pilot/evidence/p5-colocated-boarding-correction-validation-2026-08-03.md
git commit -m "docs: record co-located boarding correction"
```

验收记录必须包含测试命令与退出码、部署健康结果、纠正事务执行时间、纠正前后节点表、位置事件 ID、审计 ID和浏览器检查结论。
