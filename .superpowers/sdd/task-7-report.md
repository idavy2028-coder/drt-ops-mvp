# 车辆位置管理 Task 7 报告

## 变更摘要

- 新增 `VehicleLocationMetrics`，实现 Micrometer 指标：
  - `drt.vehicle.location.report.total{result,source}`
  - `drt.vehicle.location.recording.delay`
  - `drt.vehicle.location.stale.count`
  - `drt.vehicle.location.outside_area.total`
  - `drt.vehicle.location.correction.total`
  - `drt.vehicle.location.missing_task_nodes`
  - `drt.vehicle.location.query.duration`
- `VehicleLocationRecorder` 记录成功、重放、失败、录入延迟、服务区外和修正事件指标。
- `VehicleLocationQueryService` 记录历史、任务历史、最新快照和导出查询耗时。
- `application.yml` 暴露 `health,info,metrics`。
- 新增后端 `VehicleLocationMetricsTest` 和 `VehicleLocationFlowIntegrationTest`。
- 新增前端 Playwright `vehicle-location-flow.spec.ts`，用 route mock 覆盖调度员任务位置链和管理员历史导出流程。

## 红绿测试证据

- RED：`& $env:MAVEN_CMD -q -pl apps/api '-Dtest=VehicleLocationMetricsTest' test`
  - 结果：失败，原因符合预期：`VehicleLocationMetrics` 类不存在。
- GREEN：同一命令重跑通过。
- 后端聚焦回归：`& $env:MAVEN_CMD -q -pl apps/api '-Dtest=VehicleLocationMetricsTest,VehicleLocationFlowIntegrationTest' test`
  - 结果：通过。
- 前端 E2E 初跑暴露选择器和登出 mock 噪声，修正后：
  - `npm.cmd --prefix apps/admin-web run e2e -- vehicle-location-flow.spec.ts`
  - 结果：1 passed。

## 最终验证结果

- `& $env:MAVEN_CMD -q -pl apps/api test`
  - 结果：通过；Surefire 汇总 `Suites=42; Tests=136; Failures=0; Errors=0; Skipped=20`。
- `npm.cmd --prefix apps/admin-web run typecheck`
  - 结果：通过。
- `npm.cmd --prefix apps/admin-web run test`
  - 结果：`17` 文件 / `63` 测试通过。
- `npm.cmd --prefix apps/admin-web run build`
  - 结果：通过；保留既有 `maplibre-gl` chunk 大于 500 kB 警告。
- `npm.cmd --prefix apps/admin-web run e2e -- vehicle-location-flow.spec.ts`
  - 结果：1 passed。

## 自审结论

- 未修改生产数据库迁移。
- 未接入高德、GPS 或新监控平台。
- 未伪造 `drt.map.provider.request.duration` 观测数据；该指标仅作为未来高德适配器契约保留在说明中。
- 未提交 `.superpowers/sdd/progress.md`。
- `VehicleTaskRepository.java` 仍为既有行尾状态，未纳入本任务。

## 提交 SHA

提交后以最终回复中的 Git SHA 为准；Git 提交对象无法在同一提交内自包含自身 SHA。

## 顾虑

- `missing_task_nodes` 本轮已接入查询路径同步计算；后续若要脱离查询访问进行实时生产统计，可再接入任务/调度汇总或定时计算。
- `stale.count` 以“已有关联任务位置快照且超过 30 分钟未更新”的车辆为统计对象，避免把普通空闲车辆快照误计入告警。

## 2026-07-16 修复复核

- 修复 `missing_task_nodes` 从手动写入改为真实计算路径：`VehicleLocationMetrics` 现在基于 `vehicle_tasks` / `task_stops` 读取 IN_PROGRESS、COMPLETED 任务的期望任务节点，并与 `vehicle_location_events` 中的六类任务动作/站点事件比对后刷新 gauge。
- 查询路径会在 `VehicleLocationQueryService` 记录查询耗时的同时刷新缺失节点 gauge；测试不再调用 `updateMissingTaskNodes(...)`。
- `VehicleLocationFlowIntegrationTest` 通过 MockMvc 对任务到站动作提交未来 `driverReportedAt`，断言 HTTP 400，并断言 `drt.vehicle.location.report.total{result=failure,source=MANUAL_DISPATCHER}` 增加。
- 清理了 `VehicleLocationMetrics` 中未使用的事件仓库构造参数问题：事件仓库现在用于缺失任务节点计算，同时移除手动 gauge 更新入口。

### 红绿测试证据

- RED：`& $env:MAVEN_CMD -q -pl apps/api '-Dtest=VehicleLocationMetricsTest,VehicleLocationFlowIntegrationTest' test`
  - 结果：失败，`VehicleLocationMetricsTest.updatesMissingTaskNodesFromTaskHistoryQuery` 与 `VehicleLocationFlowIntegrationTest.verifiesVehicleLocationOperationsAcrossTaskHistorySnapshotAuditAndMetrics` 均期望 `missing_task_nodes=5.0`，实际为 `0.0`。
- GREEN：同一聚焦命令重跑通过。

### 本轮验证结果

- `& $env:MAVEN_CMD -q -pl apps/api '-Dtest=VehicleLocationMetricsTest,VehicleLocationFlowIntegrationTest' test`
  - 结果：通过。
- `& $env:MAVEN_CMD -q -pl apps/api test`
  - 结果：通过；Surefire XML 报告 42 个 test suite。
