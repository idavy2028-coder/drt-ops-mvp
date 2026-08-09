# 路线感知合乘调度实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在等待不超过 5 分钟、所有乘客额外绕行不超过 8 分钟、分段容量与节点顺序可执行时，始终优先把新订单插入已有任务，只有不存在可行插单时才启用新车辆。

**Architecture:** API 服务新增唯一的 `TaskInsertionPlanner`，基于车辆当前位置、剩余任务节点和真实路线耗时枚举合法上/下车插入位置；候选组装、自动派单和人工复核都消费同一规划结果。算法服务先按 `activationCost`（已有任务为 0、新任务为 1）分层，再在最低成本可行层内使用现有权重评分；任务写入前在锁内重算，避免采用过期顺序。

**Tech Stack:** Java 21、Spring Boot 3.5.3、Spring Data JPA、JUnit 5、Python 3.12、FastAPI、Pydantic、pytest。

## 全局约束

- 保持现有 `REALTIME_INSERTION` 策略及 5/8/60 分钟、82/62 阈值、0.35/0.20/0.30/0.15 权重不变。
- 已有任务启用成本固定为 `0`，新建车辆任务启用成本固定为 `1`；只在不存在可行已有任务时比较新车候选。
- 真实路线降级时只能进入人工复核，不能用直线估算自动派单。
- 自动派单和人工复核在持久化前必须锁定已有任务并重新规划；过期候选返回 `DISPATCH_CANDIDATE_STALE`，不得静默改派新车。
- 不重算、不修改已确认、已发车或执行中的历史订单；改动只影响部署后的新调度请求。
- 算法合同版本由 `0.1.0` 升级为 `0.2.0`，API 与算法服务必须同时部署。
- 决策解释和审计只记录订单、任务、车辆、决策标识与运营指标，不记录乘客电话。
- 当前工作区已有其他未提交改动；每次提交只暂存本计划列出的本任务文件。

---

## 文件结构与职责

- `apps/algorithm/src/drt_algorithm/schemas.py`：定义候选类型、启用成本、预检拒绝原因和最佳方案回显合同。
- `apps/algorithm/src/drt_algorithm/matching.py`：先执行硬约束过滤，再选择最低启用成本候选层。
- `apps/algorithm/src/drt_algorithm/scoring.py`：保留现有权重评分，并把候选类型、启用成本、选择原因写入方案。
- `apps/algorithm/src/drt_algorithm/explanations.py`：输出可审计的选择层级与路线指标。
- `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/TaskInsertionPlan.java`：不可变的插单可行性、节点顺序、到达时间和运营指标结果。
- `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/TaskInsertionPlanner.java`：唯一的路线枚举、等待、绕行和分段容量计算入口。
- `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/TravelEstimateService.java`：提供任意相邻坐标间可缓存的路线估算。
- `apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStopInsertionPolicy.java`：严格按规划结果落地新节点并重排未完成节点。
- `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/CandidateTaskAssembler.java`：把规划结果转换成算法候选，并保留任务 ID 到规划结果的映射。
- `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchOrchestrator.java`：自动派单选中已有任务后锁内重算并落地。
- `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewService.java`：人工确认时使用同一规划器锁内重算并落地。

### Task 1：算法合同与启用成本分层

**Files:**
- Modify: `apps/algorithm/src/drt_algorithm/schemas.py`
- Modify: `apps/algorithm/src/drt_algorithm/matching.py`
- Modify: `apps/algorithm/src/drt_algorithm/scoring.py`
- Modify: `apps/algorithm/src/drt_algorithm/explanations.py`
- Modify: `apps/algorithm/src/drt_algorithm/main.py`
- Modify: `apps/algorithm/pyproject.toml`
- Test: `apps/algorithm/tests/test_dispatch_evaluation.py`

**Interfaces:**
- Produces: `CandidateType.EXISTING_TASK | NEW_TASK`。
- Produces: `CandidateTask.candidate_type: CandidateType`、`activation_cost: Literal[0, 1]`、`precheck_rejection_reason: str | None`、`task_disruption_score: float`。
- Produces: `DispatchPlan.candidate_type`、`activation_cost`、`selection_reason`，其中选择原因只允许 `EXISTING_TASK_PREFERRED` 或 `NEW_VEHICLE_REQUIRED`。
- Preserves: 评分阈值仍决定 `AUTO_DISPATCH`、`MANUAL_REVIEW`、`NO_FEASIBLE_PLAN`。

- [ ] **Step 1：先写启用成本优先的失败测试**

  在 `test_dispatch_evaluation.py` 给现有候选样例补齐新字段，并新增：已有任务分数低于新车但仍被选中；多个已有任务选最高分；已有任务全部被拒后选择新车；`precheckRejectionReason` 优先成为拒绝原因；响应解释回显候选类型、启用成本和选择原因。

  ```python
  def test_feasible_existing_task_wins_over_higher_scored_new_vehicle() -> None:
      existing = candidate(candidate_type="EXISTING_TASK", activation_cost=0,
                           wait=5, detour=8, disruption=65)
      new_vehicle = candidate(candidate_type="NEW_TASK", activation_cost=1,
                              wait=1, detour=0, disruption=100)
      body = client.post(
          "/dispatch/evaluate",
          json=sample_request([existing, new_vehicle]),
      ).json()

      assert body["bestPlan"]["taskId"] == existing["taskId"]
      assert body["bestPlan"]["activationCost"] == 0
      assert body["bestPlan"]["selectionReason"] == "EXISTING_TASK_PREFERRED"
  ```

- [ ] **Step 2：运行算法测试，确认因新合同尚不存在而失败**

  Run: `python -m pytest apps/algorithm/tests/test_dispatch_evaluation.py -q`

  Expected: FAIL，Pydantic 拒绝 `candidateType`/`activationCost`，或响应缺少新增字段。

- [ ] **Step 3：实现最小合同与两级选择**

  ```python
  class CandidateType(StrEnum):
      EXISTING_TASK = "EXISTING_TASK"
      NEW_TASK = "NEW_TASK"

  class CandidateTask(ApiModel):
      candidate_type: CandidateType
      activation_cost: Literal[0, 1]
      precheck_rejection_reason: str | None = None
      task_disruption_score: float = Field(ge=0, le=100)
      # 保留现有字段

  def lowest_activation_cost_tier(candidates: list[CandidateTask]) -> list[CandidateTask]:
      minimum = min(candidate.activation_cost for candidate in candidates)
      return [candidate for candidate in candidates if candidate.activation_cost == minimum]
  ```

  `first_rejection_reason` 首先返回 `precheck_rejection_reason`；评分入口只给 `lowest_activation_cost_tier(feasible)` 评分。`DispatchPlan` 回显字段，解释详情包含最低启用成本和被选层级。把 `pyproject.toml` 版本改为 `0.2.0`，并同步 `/health` 或根接口暴露的版本常量。

- [ ] **Step 4：运行算法测试并确认全绿**

  Run: `python -m pytest apps/algorithm/tests -q`

  Expected: PASS，且新增五种分层/解释场景全部通过。

- [ ] **Step 5：提交算法合同改动**

  ```powershell
  git add apps/algorithm/src/drt_algorithm apps/algorithm/tests/test_dispatch_evaluation.py apps/algorithm/pyproject.toml
  git commit -m "feat: prioritize reusable dispatch tasks"
  ```

### Task 2：任意路段估算与路线插入规划器

**Files:**
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/TaskInsertionPlan.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/TaskInsertionPlanner.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/TravelEstimateService.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/TravelEstimateServiceTest.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/TaskInsertionPlannerTest.java`

**Interfaces:**
- Consumes: `TravelEstimateService.estimateBetween(Coordinate origin, Coordinate destination)`。
- Produces: `TaskInsertionPlanner.plan(RideOrder order, Vehicle vehicle, VehicleTask task, DispatchRuleSet ruleSet): TaskInsertionPlan`。
- Produces: `TaskInsertionPlan` 字段 `feasible`、`rejectionReason`、`orderedStops`、`boardingIndex`、`alightingIndex`、`estimatedWaitMinutes`、`maxPassengerDetourMinutes`、`peakOccupiedSeats`、`utilizationAfterInsert`、`baselineRouteDurationSeconds`、`plannedRouteDurationSeconds`、`degraded`、`degradedReason`。
- Produces: `PlannedTaskStop` 字段 `virtualStopId`、`rideOrderId`、`stopType`、`plannedArrivalAt`、`existingStopId`。

- [ ] **Step 1：先写任意路段估算的失败测试**

  ```java
  @Test
  void estimatesAndCachesAnArbitrarySegment() {
      TravelEstimate first = service.estimateBetween(origin, destination);
      TravelEstimate second = service.estimateBetween(origin, destination);
      assertThat(first.durationSeconds()).isEqualTo(360);
      assertThat(routeProvider.invocations()).isEqualTo(1);
      assertThat(second).isEqualTo(first);
  }
  ```

- [ ] **Step 2：运行目标测试确认方法缺失**

  Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=TravelEstimateServiceTest test`

  Expected: FAIL，编译提示 `estimateBetween` 不存在。

- [ ] **Step 3：实现公共路段估算入口**

  ```java
  public TravelEstimate estimateBetween(Coordinate origin, Coordinate destination) {
      return estimate("ROUTE_SEGMENT", origin, destination);
  }
  ```

- [ ] **Step 4：先写插入规划器的失败测试矩阵**

  使用固定路段耗时的 `FakeTravelEstimateService`，覆盖：最小绕行插入、同站上车连续、历史节点不回插、等待正好 5 分钟/超过 5 分钟、绕行正好 8 分钟/超过 8 分钟、峰值容量等于/超过 8、已上车乘客形成初始占用、地图降级返回 `MAP_ROUTE_UNAVAILABLE`。

  ```java
  @Test
  void choosesFeasibleInsertionWithLowestMaximumPassengerDetour() {
      TaskInsertionPlan plan = planner.plan(newOrder, vehicle, activeTask, ruleSet(5, 8));
      assertThat(plan.feasible()).isTrue();
      assertThat(plan.boardingIndex()).isEqualTo(1);
      assertThat(plan.alightingIndex()).isEqualTo(3);
      assertThat(plan.maxPassengerDetourMinutes()).isLessThanOrEqualTo(8);
      assertThat(plan.orderedStops()).extracting(TaskInsertionPlan.PlannedTaskStop::virtualStopId)
              .containsExactly(existingPickup, newPickup, existingDropoff, newDropoff);
  }
  ```

- [ ] **Step 5：运行规划器测试确认类尚不存在**

  Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=TaskInsertionPlannerTest test`

  Expected: FAIL，编译提示 `TaskInsertionPlanner`/`TaskInsertionPlan` 不存在。

- [ ] **Step 6：实现最小路线规划器**

  规划器只保留未完成节点；从车辆最新位置开始，枚举 `i < j` 的插入边界并优先保持同站上车组；用每段路线耗时累计到达时间。对已有乘客比较插入前后下车到达时间，对新乘客比较车内时间和直达时间；逐节点增减乘客数得到峰值，按顺序返回第一项硬约束拒绝原因，最终用“最大乘客绕行、总路线时长、上车索引、下车索引”稳定排序选择最优方案。

  ```java
  public TaskInsertionPlan plan(
          RideOrder order, Vehicle vehicle, VehicleTask task, DispatchRuleSet ruleSet) {
      List<TaskInsertionPlan> alternatives = enumerateLegalInsertions(order, vehicle, task, ruleSet);
      return alternatives.stream()
              .filter(TaskInsertionPlan::feasible)
              .min(comparingInt(TaskInsertionPlan::maxPassengerDetourMinutes)
                      .thenComparingInt(TaskInsertionPlan::plannedRouteDurationSeconds)
                      .thenComparingInt(TaskInsertionPlan::boardingIndex)
                      .thenComparingInt(TaskInsertionPlan::alightingIndex))
              .orElseGet(() -> primaryRejection(alternatives));
  }
  ```

- [ ] **Step 7：运行规划器和路线估算测试并确认全绿**

  Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=TravelEstimateServiceTest,TaskInsertionPlannerTest test`

  Expected: PASS。

- [ ] **Step 8：提交规划器改动**

  ```powershell
  git add apps/api/src/main/java/com/idavy/drtops/domain/dispatch/TaskInsertionPlan.java apps/api/src/main/java/com/idavy/drtops/domain/dispatch/TaskInsertionPlanner.java apps/api/src/main/java/com/idavy/drtops/domain/dispatch/TravelEstimateService.java apps/api/src/test/java/com/idavy/drtops/domain/dispatch/TaskInsertionPlannerTest.java apps/api/src/test/java/com/idavy/drtops/domain/dispatch/TravelEstimateServiceTest.java
  git commit -m "feat: plan route-aware task insertions"
  ```

### Task 3：按规划结果落地任务节点

**Files:**
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStop.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStopInsertionPolicy.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/task/TaskStopInsertionPolicyTest.java`

**Interfaces:**
- Consumes: `TaskInsertionPlan.orderedStops()`。
- Produces: `TaskStop.reschedule(OffsetDateTime plannedArrivalAt): void`，仅允许未完成节点改计划时间。
- Produces: `TaskStopInsertionPolicy.applyPlan(VehicleTask task, RideOrder order, TaskInsertionPlan plan): void`。
- Preserves: 既有六参数 `insertOrderStops` 的同站点兼容行为及相关测试。

- [ ] **Step 1：先写精确落地的失败测试**

  ```java
  @Test
  void appliesPlannedOrderAndReschedulesOnlyRemainingStops() {
      policy.applyPlan(task, newOrder, insertionPlan);
      assertThat(task.getStops()).extracting(TaskStop::getVirtualStopId)
              .containsExactly(completedStop, newPickup, existingDropoff, newDropoff);
      assertThat(task.getStops()).extracting(TaskStop::getSequenceNumber)
              .containsExactly(1, 2, 3, 4);
      assertThat(task.getStops().getFirst().getPlannedArrivalAt()).isEqualTo(originalCompletedTime);
  }
  ```

- [ ] **Step 2：运行测试确认新入口缺失**

  Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=TaskStopInsertionPolicyTest test`

  Expected: FAIL，编译提示 `applyPlan` 或 `reschedule` 不存在。

- [ ] **Step 3：实现精确落地和未完成节点重排**

  `applyPlan` 保留所有已完成/取消节点原对象和原顺序；按 `existingStopId` 复用未完成节点并调用 `reschedule`，为新订单创建上/下车节点；最后连续重编号。若规划里缺少任一既有未完成节点、上车不早于下车或计划不可行，则抛出 `IllegalArgumentException`，不部分写入。

- [ ] **Step 4：运行任务节点测试确认全绿**

  Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=TaskStopInsertionPolicyTest test`

  Expected: PASS，包括既有同站点多人上车回归。

- [ ] **Step 5：提交任务落地改动**

  ```powershell
  git add apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStop.java apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStopInsertionPolicy.java apps/api/src/test/java/com/idavy/drtops/domain/task/TaskStopInsertionPolicyTest.java
  git commit -m "feat: apply planned task stop order"
  ```

### Task 4：用路线规划结果组装算法候选

**Files:**
- Modify: `apps/api/src/main/java/com/idavy/drtops/integration/algorithm/DispatchEvaluateRequest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/integration/algorithm/DispatchEvaluateResponse.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/CandidateTaskAssembler.java`
- Create: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/CandidateTaskAssemblerTest.java`

**Interfaces:**
- Consumes: `TaskInsertionPlanner.plan(...)` 和 Task 1 的 `candidateType`、`activationCost`、`precheckRejectionReason`、`taskDisruptionScore`。
- Produces: `CandidateTaskAssembler.Assembly.insertionPlanFor(UUID taskId): TaskInsertionPlan`。
- Produces: API `BestPlan` 回显 `candidateType`、`activationCost`、`selectionReason`。
- Preserves: 新任务使用合成任务 ID；`Assembly.isNewTaskCandidate` 可继续识别旧调用。

- [ ] **Step 1：先写候选组装失败测试**

  覆盖已有任务候选 `activationCost=0` 且指标来自规划器、新车候选 `activationCost=1`、不可行已有任务携带 `precheckRejectionReason`、分段峰值生成利用率、规划结果按任务 ID 可取回。

  ```java
  @Test
  void assemblesExistingTaskFromItsRouteAwarePlan() {
      CandidateTask candidate = assembler.assembleWithTravelEstimates(order, rules)
              .request().candidateTasks().stream()
              .filter(it -> it.taskId().equals(activeTask.getId()))
              .findFirst().orElseThrow();
      assertThat(candidate.candidateType()).isEqualTo("EXISTING_TASK");
      assertThat(candidate.activationCost()).isZero();
      assertThat(candidate.estimatedWaitMinutes()).isEqualTo(plan.estimatedWaitMinutes());
      assertThat(candidate.estimatedDetourMinutes()).isEqualTo(plan.maxPassengerDetourMinutes());
  }
  ```

- [ ] **Step 2：运行测试确认旧硬编码指标导致失败**

  Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=CandidateTaskAssemblerTest test`

  Expected: FAIL，合同字段缺失或仍得到固定 3 分钟绕行。

- [ ] **Step 3：实现候选合同和规划结果映射**

  删除已有任务的固定绕行、总订单人数容量和“站点已存在才同向”判断。已有任务一律调用规划器；不可行规划仍发送给算法，但以 `precheckRejectionReason` 硬拒绝。新车候选以车辆位置→上车点→下车点计算，降级信息继续触发人工复核保护。

- [ ] **Step 4：运行组装、算法合同相关 Java 测试**

  Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=CandidateTaskAssemblerTest,DispatchOrchestratorMapEstimateTest test`

  Expected: PASS。

- [ ] **Step 5：提交候选组装改动**

  ```powershell
  git add apps/api/src/main/java/com/idavy/drtops/integration/algorithm/DispatchEvaluateRequest.java apps/api/src/main/java/com/idavy/drtops/integration/algorithm/DispatchEvaluateResponse.java apps/api/src/main/java/com/idavy/drtops/domain/dispatch/CandidateTaskAssembler.java apps/api/src/test/java/com/idavy/drtops/domain/dispatch/CandidateTaskAssemblerTest.java
  git commit -m "feat: assemble route-aware dispatch candidates"
  ```

### Task 5：自动派单锁内重算与审计解释

**Files:**
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchOrchestrator.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskRepository.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorTest.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorMapEstimateTest.java`

**Interfaces:**
- Consumes: `Assembly.insertionPlanFor(taskId)`、`TaskInsertionPlanner.plan(...)`、`TaskStopInsertionPolicy.applyPlan(...)`。
- Produces: `VehicleTaskRepository.findByIdForUpdate(UUID id): Optional<VehicleTask>`，使用悲观写锁。
- Produces: 409 `DISPATCH_CANDIDATE_STALE`，并保持订单为可重试状态。
- Produces: 决策解释/审计字段 `candidateType`、`activationCost`、`selectionReason`、基线/规划时长、等待、最大绕行、峰值占用、插入位置、路线提供方与降级原因。

- [ ] **Step 1：先写自动派单失败测试**

  新增：自动选择已有任务不占用第二辆车/驾驶员；按规划节点顺序落地；评估后任务状态变化导致 409 且订单/资源回滚；解释中包含成本、选择原因和路线指标。

  ```java
  @Test
  void reusesLockedExistingTaskAndDoesNotReserveIdleResources() {
      dispatch(orderId);
      VehicleTask updated = taskRepository.findById(existingTaskId).orElseThrow();
      assertThat(updated.getStops()).extracting(TaskStop::getVirtualStopId)
              .containsExactly(existingPickup, newPickup, existingDropoff, newDropoff);
      assertThat(vehicleRepository.findById(idleVehicleId).orElseThrow().getCurrentStatus())
              .isEqualTo("IDLE");
  }
  ```

- [ ] **Step 2：运行自动派单测试确认旧追加逻辑失败**

  Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=DispatchOrchestratorTest,DispatchOrchestratorMapEstimateTest test`

  Expected: FAIL，节点顺序、资源占用或解释字段断言不满足。

- [ ] **Step 3：实现锁内重算、精确落地与审计**

  选中新车时保留现有建任务流程；选中已有任务时 `findByIdForUpdate`，重新读取车辆和规则并调用规划器。重算失败抛出 409，不回退新车；成功后 `applyPlan`。算法请求头/版本断言改为 `0.2.0`，解释和审计元数据只写非敏感运营字段。

- [ ] **Step 4：运行自动派单测试确认全绿**

  Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=DispatchOrchestratorTest,DispatchOrchestratorMapEstimateTest test`

  Expected: PASS。

- [ ] **Step 5：提交自动派单改动**

  ```powershell
  git add apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchOrchestrator.java apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskRepository.java apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorTest.java apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorMapEstimateTest.java
  git commit -m "feat: revalidate pooled dispatch under lock"
  ```

### Task 6：人工复核复用同一规划器

**Files:**
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewService.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/ManualReviewApiTest.java`

**Interfaces:**
- Consumes: `VehicleTaskRepository.findByIdForUpdate`、`TaskInsertionPlanner.plan`、`TaskStopInsertionPolicy.applyPlan`。
- Produces: 候选过期时 409 `DISPATCH_CANDIDATE_STALE`，人工复核记录保持待确认。
- Preserves: 人工拒绝流程和新任务确认流程不变。

- [ ] **Step 1：先写人工复核失败测试**

  覆盖：确认已有任务产生与自动派单相同顺序；确认前容量/节点变化时返回 409 且复核项未确认；成功确认的审计包含重算后的指标。

  ```java
  @Test
  void manualConfirmationUsesFreshInsertionPlan() throws Exception {
      confirmDecision(decisionId);
      assertThat(taskRepository.findById(taskId).orElseThrow().getStops())
              .extracting(TaskStop::getVirtualStopId)
              .containsExactly(existingPickup, newPickup, existingDropoff, newDropoff);
  }
  ```

- [ ] **Step 2：运行人工复核测试确认旧追加逻辑失败**

  Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=ManualReviewApiTest test`

  Expected: FAIL，节点顺序或过期保护断言不满足。

- [ ] **Step 3：实现人工确认锁内重算**

  确认已有任务时锁定任务并调用规划器；成功才落地并确认复核条目，失败抛 409 且事务回滚。确认新任务继续沿用原资源分配流程。

- [ ] **Step 4：运行人工复核与任务落地回归**

  Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=ManualReviewApiTest,TaskStopInsertionPolicyTest test`

  Expected: PASS。

- [ ] **Step 5：提交人工复核改动**

  ```powershell
  git add apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewService.java apps/api/src/test/java/com/idavy/drtops/domain/dispatch/ManualReviewApiTest.java
  git commit -m "feat: revalidate manual pooled dispatch"
  ```

### Task 7：三单合乘回归、全量验证与验收记录

**Files:**
- Create: `apps/api/src/test/java/com/idavy/drtops/e2e/RouteAwareRidePoolingIntegrationTest.java`
- Modify: `docs/pilot/tongwei-map-and-stop-acceptance-record.md`

**Interfaces:**
- Consumes: Tasks 1–6 的完整合同与编排链路。
- Produces: 受控路线夹具下三笔订单复用一个 8 座任务的回归证据。
- Produces: 验收记录包含测试命令、结果、未部署说明和真实订单不追溯说明。

- [ ] **Step 1：先写三单合乘失败回归**

  用受控路线耗时构造客观满足 5/8 分钟约束的“小袁、高士、刘小光”同构路线。逐笔调度后断言三单属于同一任务/同一车辆、陇阳两笔上车连续、峰值 4 人、第二/三单启用成本为 0、没有创建第二/第三个任务。

  ```java
  @Test
  void threeSequentialOrdersReuseOneEightSeatVehicleTask() {
      dispatch(xiaoYuanOrder);
      dispatch(gaoShiOrder);
      dispatch(liuXiaoGuangOrder);

      assertThat(activeTasks()).hasSize(1);
      assertThat(orderTaskIds()).containsOnly(activeTasks().getFirst().getId());
      assertThat(latestDecisions()).extracting(DispatchDecision::getActivationCost)
              .containsExactly(1, 0, 0);
      assertThat(peakOccupiedSeats(activeTasks().getFirst())).isEqualTo(4);
  }
  ```

- [ ] **Step 2：运行回归确认旧行为无法满足单任务断言**

  Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=RouteAwareRidePoolingIntegrationTest test`

  Expected: 在完整实现前 FAIL；Tasks 1–6 完成后 PASS。

- [ ] **Step 3：运行算法与 API 目标回归**

  Run: `python -m pytest apps/algorithm/tests -q`

  Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=TravelEstimateServiceTest,TaskInsertionPlannerTest,TaskStopInsertionPolicyTest,CandidateTaskAssemblerTest,DispatchOrchestratorTest,DispatchOrchestratorMapEstimateTest,ManualReviewApiTest,RouteAwareRidePoolingIntegrationTest test`

  Expected: 两组命令均 PASS。

- [ ] **Step 4：运行 API 全量测试和算法生产构建验证**

  Run: `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api test`

  Run: `python -m pytest apps/algorithm/tests -q`

  Expected: PASS，无新增失败或跳过的关键测试。

- [ ] **Step 5：检查变更边界和敏感信息**

  Run: `git diff --check`

  Run: `git diff -- apps/api apps/algorithm docs/pilot/tongwei-map-and-stop-acceptance-record.md`

  Expected: 无空白错误；不存在乘客电话、密码或对历史订单/运行数据库的写入脚本。

- [ ] **Step 6：补写验收记录**

  在 `docs/pilot/tongwei-map-and-stop-acceptance-record.md` 新增“路线感知合乘调度”条目，记录算法版本 `0.2.0`、目标/全量测试结果、三单受控回归结论；明确代码尚未部署且既有三辆已发车任务未追溯调整。

- [ ] **Step 7：提交回归和验收记录**

  ```powershell
  git add apps/api/src/test/java/com/idavy/drtops/e2e/RouteAwareRidePoolingIntegrationTest.java docs/pilot/tongwei-map-and-stop-acceptance-record.md
  git commit -m "test: verify route-aware ride pooling"
  ```

## 最终验收门槛

- 可行已有任务即使加权分低于新车，也必须被选择。
- 所有已有任务不可行时，仍能正常选择新车候选。
- 插入前后路线、等待、最大乘客绕行和分段容量均由同一规划器计算。
- 自动派单和人工复核写入完全相同的规划节点顺序，并在锁内拒绝过期方案。
- 同站点多人上车、5 分钟未出现门禁、任务执行和既有调度回归继续通过。
- 受控三单场景只产生一个任务、一辆车，峰值 4 人，第二/三单启用成本为 0。
- 在部署前不操作当前真实订单，不修改本地试运行数据库。
