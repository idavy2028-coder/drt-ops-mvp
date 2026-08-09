# 乘客取消释放任务资源实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 乘客取消已确认订单时，单订单任务自动取消并释放车辆/驾驶员；拼载任务仅取消该订单站点。

**架构：** 在 `OrderExceptionService.cancel` 的现有事务中查询关联活跃任务。根据 `VehicleTask.activeOrderIds()` 决定取消整项任务并调用 `TaskResourceCoordinator.releaseIfUnused`，或只取消当前订单站点。控制器与 URL 不变。

**技术栈：** Spring Boot、Spring Data JPA、MockMvc、JUnit 5、AssertJ。

## 全局约束

- 取消原因使用“乘客取消”，不写入车辆故障或严重延误审计。
- 订单保持 `CANCELLED`，不修改数据库结构或派单规则。
- 资源释放仅通过 `TaskResourceCoordinator.releaseIfUnused` 执行。
- 多订单任务保留其他订单的站点、任务状态和资源占用。

---

### 任务 1：单订单取消自动释放资源

**文件：**
- 修改：`apps/api/src/test/java/com/idavy/drtops/domain/order/OrderExceptionApiTest.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/order/OrderExceptionService.java`

**接口：**
- 使用：`OrderExceptionService.cancel(UUID actorId, UUID orderId, String reason)`。
- 产生：单订单活动任务变为 `CANCELLED`，车辆为 `IDLE`，驾驶员为 `AVAILABLE`，任务审计动作为 `TASK_CANCELLED_PASSENGER_CANCELLED`。

- [ ] **步骤 1：写入失败测试**

```java
@Test
void cancelOrderCancelsSingleOrderTaskAndReleasesResources() throws Exception {
    UUID orderId = createConfirmedOrder();
    UUID taskId = createTask(orderId);
    cancel(orderId, "乘客取消");
    assertThat(vehicleTaskRepository.findById(taskId).orElseThrow().getStatus())
            .isEqualTo(TaskStatus.CANCELLED);
    assertThat(vehicleRepository.findById(VEHICLE_ID).orElseThrow().getCurrentStatus()).isEqualTo("IDLE");
    assertThat(driverRepository.findById(DRIVER_ID).orElseThrow().getCurrentStatus()).isEqualTo("AVAILABLE");
}
```

- [ ] **步骤 2：运行并确认红灯**

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=OrderExceptionApiTest test
```

预期：任务仍为 `DISPATCHED`，测试因未实现任务取消而失败。

- [ ] **步骤 3：最小实现**

在 `OrderExceptionService.cancel` 的订单取消和订单审计后，查询 `TaskResourceCoordinator.ACTIVE_STATUSES` 中关联该订单的任务。单订单任务调用 `cancelStopsForOrder`、`cancel`、`releaseIfUnused`，并写入 `TASK_CANCELLED_PASSENGER_CANCELLED` 审计。

- [ ] **步骤 4：运行并确认绿灯**

重复步骤 2 的命令；预期全部通过。

### 任务 2：拼载取消不释放共享资源

**文件：**
- 修改：`apps/api/src/test/java/com/idavy/drtops/domain/order/OrderExceptionApiTest.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/order/OrderExceptionService.java`

**接口：**
- 使用：同一取消入口。
- 产生：仅当前订单的站点为 `CANCELLED`；共享任务保持 `DISPATCHED`，车辆和驾驶员保持占用，任务审计动作为 `TASK_STOPS_CANCELLED_PASSENGER_CANCELLED`。

- [ ] **步骤 1：写入失败测试**

```java
@Test
void cancelOrderCancelsOnlyMatchingStopsInSharedTask() throws Exception {
    UUID cancelledOrderId = createConfirmedOrder();
    UUID remainingOrderId = createConfirmedOrder();
    UUID taskId = createTask(cancelledOrderId, remainingOrderId);
    cancel(cancelledOrderId, "乘客取消");
    VehicleTask task = vehicleTaskRepository.findWithStopsById(taskId).orElseThrow();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.DISPATCHED);
    assertThat(task.getStops()).filteredOn(stop -> cancelledOrderId.equals(stop.getRideOrderId()))
            .extracting(TaskStop::getStatus).containsOnly("CANCELLED");
    assertThat(vehicleRepository.findById(VEHICLE_ID).orElseThrow().getCurrentStatus()).isEqualTo("DISPATCHED");
}
```

- [ ] **步骤 2：运行并确认红灯**

使用任务 1 的 Maven 命令；预期站点仍为 `PLANNED`。

- [ ] **步骤 3：最小实现**

对仍有其他 `activeOrderIds()` 的任务仅调用 `cancelStopsForOrder`，不调用任务取消或资源释放，并写入 `TASK_STOPS_CANCELLED_PASSENGER_CANCELLED` 审计。

- [ ] **步骤 4：运行并确认绿灯**

使用任务 1 的 Maven 命令；预期单订单和拼载路径均通过。

### 任务 3：审计回归与真实订单处置

**文件：**
- 修改：`apps/api/src/test/java/com/idavy/drtops/domain/order/OrderExceptionApiTest.java`
- 运行时复核：订单 `4c6f54fd`、任务 `4e526376`、车辆 `甘J00856D`。

**接口：**
- 产生：订单审计 `ORDER_CANCELLED` 与对应任务审计；真实任务取消后车辆可接收位置上报。

- [ ] **步骤 1：写入失败审计断言**

```java
assertThat(auditLogRepository.findByEntityId(taskId))
        .anyMatch(log -> log.getAction().equals("TASK_CANCELLED_PASSENGER_CANCELLED")
                && log.getReason().equals("乘客取消"));
```

- [ ] **步骤 2：运行并确认红灯**

使用任务 1 的 Maven 命令；预期缺少新的任务审计动作。

- [ ] **步骤 3：实现审计并运行定向回归**

```powershell
.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=OrderExceptionApiTest,TaskResourceCoordinatorTest test
```

- [ ] **步骤 4：部署并处置本单**

重启本地 API 容器，调用订单取消入口传入“乘客取消”，然后只读复核订单为 `CANCELLED`、任务为 `CANCELLED`、甘J00856D 为 `IDLE`。再由当班人员在位置历史页上报该车当前待命位置，并复核位置快照更新。

- [ ] **步骤 5：提交**

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/order/OrderExceptionService.java apps/api/src/test/java/com/idavy/drtops/domain/order/OrderExceptionApiTest.java docs/superpowers/plans/2026-08-03-passenger-cancellation-resource-release.md
git commit -m "feat: release resources on passenger cancellation"
```
