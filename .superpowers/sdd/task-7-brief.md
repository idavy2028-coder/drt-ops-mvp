# 车辆位置管理 Task 7 brief：位置运行指标与全链路验收测试

## 目标

为车辆位置管理模块补齐 Micrometer 运行指标、后端全链路集成测试和前端 Playwright 流程测试，形成试点前可复核的技术证据。本任务不新增业务功能，不接入真实高德/GPS，不修改数据库迁移。

## 文件范围

- 新建：`apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationMetrics.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationRecorder.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationQueryService.java`
- 新建：`apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationMetricsTest.java`
- 新建：`apps/api/src/test/java/com/idavy/drtops/e2e/VehicleLocationFlowIntegrationTest.java`
- 新建：`apps/admin-web/e2e/vehicle-location-flow.spec.ts`
- 修改：`apps/api/src/main/resources/application.yml`
- 可按需要最小调整已有测试夹具，但不得改后端业务契约。

## 指标契约

必须实现并测试：

```text
drt.vehicle.location.report.total{result,source}
drt.vehicle.location.recording.delay
drt.vehicle.location.stale.count
drt.vehicle.location.outside_area.total
drt.vehicle.location.correction.total
drt.vehicle.location.missing_task_nodes
drt.vehicle.location.query.duration
```

其中：

- `report.total`：位置上报成功/失败/replay 等结果计数，`source=MANUAL_DISPATCHER`。
- `recording.delay`：`recordedAt - driverReportedAt` 的录入延迟。
- `outside_area.total`：服务区外事件计数。
- `correction.total`：修正事件计数。
- `query.duration`：历史/最新位置查询耗时。
- `stale.count`：超过阈值未更新车辆数，可由查询服务或指标组件按当前快照计算。
- `missing_task_nodes`：任务应有位置节点但缺失的数量，可在全链路测试中验证指标契约存在并按可计算规则更新。

`drt.map.provider.request.duration{operation,result}` 是未来真实高德适配器必须实现的指标契约。本任务只能在手册/报告中记录契约，不得伪造第三方调用数据。

## Actuator

`application.yml` 暴露：

```yaml
management.endpoints.web.exposure.include: health,info,metrics
```

不要新增监控平台依赖。

## 后端全链路集成测试

新增 `VehicleLocationFlowIntegrationTest`，覆盖：

- 使用 4 辆车创建/准备任务数据。
- 至少 1 条任务完整执行六个节点：发车、到达上车点、乘客上车、到达目的地、乘客下车、完成。
- 重复幂等请求不创建第二条事件。
- 服务区外位置保存、可追溯、产生告警/指标。
- 旧事件不回退车辆快照。
- 管理员修正事件，原事件仍可查询。
- 断言任务状态、位置事件历史、车辆快照、审计日志和指标一致。

优先复用已有 H2 集成测试模式和 `MockMvc`。不要依赖 Docker/Testcontainers。

## 前端 Playwright E2E

新增 `apps/admin-web/e2e/vehicle-location-flow.spec.ts`：

- 调度员登录。
- 选择非首条任务或可控任务。
- 依次填写位置确认面板，完成任务动作链。
- 管理员登录后进入位置历史页，查询历史并触发导出。
- 测试不依赖真实高德网络，使用 route mock。
- 如果现有 e2e runner 启动成本较高，仍需保证该 spec 可被 `npm.cmd --prefix apps/admin-web run e2e -- vehicle-location-flow.spec.ts` 执行。

## TDD 与验证

必须先写失败测试，再实现。红测命令：

```powershell
& $env:MAVEN_CMD -q -pl apps/api '-Dtest=VehicleLocationMetricsTest' test
```

最终验证命令：

```powershell
& $env:MAVEN_CMD -q -pl apps/api test
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run test
npm.cmd --prefix apps/admin-web run build
npm.cmd --prefix apps/admin-web run e2e -- vehicle-location-flow.spec.ts
```

本机 Maven 可用路径：

```powershell
$env:MAVEN_CMD = 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd'
```

若 sandbox 阻止 Maven 或 Playwright 运行，按权限流程申请执行，不要把未运行视为通过。

## 提交

```powershell
git add apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationMetrics.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationRecorder.java apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationQueryService.java apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationMetricsTest.java apps/api/src/test/java/com/idavy/drtops/e2e/VehicleLocationFlowIntegrationTest.java apps/api/src/main/resources/application.yml apps/admin-web/e2e/vehicle-location-flow.spec.ts .superpowers/sdd/task-7-brief.md .superpowers/sdd/task-7-report.md
git commit -m "test: verify vehicle location operations"
```

## 不要做

- 不要提交 `.superpowers/sdd/progress.md`；该文件等 Task 8 再提交。
- 不要修改生产数据库迁移。
- 不要接入高德、GPS 或新监控平台。
- 不要伪造 `drt.map.provider.request.duration` 实际观测数据。
- 不要把 Docker/Testcontainers 不可用当作本任务失败；本任务优先使用 H2/MockMvc 和前端 route mock。
