# P3-6 自动派单与运行闭环修复实施计划

> 设计依据：`docs/superpowers/specs/2026-07-30-p3-6-auto-dispatch-repair-design.md`
>
> 实施原则：每个任务先写失败测试并确认失败原因，再做最小实现，最后运行局部和全量回归。

## 任务 1：增加独立本地路由模拟器

**文件：**

- 新增：`apps/route-simulator/server.py`
- 新增：`apps/route-simulator/test_server.py`
- 新增：`apps/route-simulator/Dockerfile`
- 修改：`infra/docker-compose.pilot.yml`

**步骤：**

1. 编写 Python 契约测试，覆盖 `/health`、`/v3/direction/driving`、`/v3/distance`、途经点、非法坐标和相同请求的确定性。
2. 运行 `python -m unittest apps/route-simulator/test_server.py`，确认因服务尚未实现而失败。
3. 使用 Python 标准库实现最小 HTTP 服务和固定道路绕行/速度模型。
4. 再次运行契约测试，确认通过。
5. 添加只包含该服务的 Dockerfile。
6. 在试点 Compose 增加 `route-simulator`，并显式配置 API 的本地 AMap 兼容地址和依赖健康检查。
7. 运行 `docker compose -f infra/docker-compose.pilot.yml config` 验证配置结构。

## 任务 2：清除人工复核订单的历史路由失败原因

**文件：**

- 修改：`apps/api/src/test/java/com/idavy/drtops/domain/order/RideOrderStateTest.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrder.java`

**步骤：**

1. 新增测试：订单进入 `PENDING_MANUAL_REVIEW` 并写入 `MAP_ROUTE_UNAVAILABLE` 后，执行 `confirm` 应变为 `CONFIRMED` 且 `failureReason` 为空。
2. 运行定向测试，确认失败。
3. 在 `RideOrder.confirm` 中清除 `failureReason`。
4. 运行定向测试，确认通过。

## 任务 3：补齐车辆与驾驶员资源生命周期

**文件：**

- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/fleet/Vehicle.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/fleet/Driver.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/fleet/VehicleRepository.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/fleet/DriverRepository.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskRepository.java`
- 新增：`apps/api/src/main/java/com/idavy/drtops/domain/task/TaskResourceCoordinator.java`
- 新增：`apps/api/src/test/java/com/idavy/drtops/domain/task/TaskResourceCoordinatorTest.java`

**步骤：**

1. 编写资源协调器测试：
   - 派发任务把车辆设为 `DISPATCHED`、驾驶员设为 `BUSY`。
   - 开始任务把车辆设为 `IN_SERVICE`。
   - 任务终结且没有其他活动任务时恢复 `IDLE`/`AVAILABLE`。
   - 仍有其他活动任务时不释放资源。
   - 非空闲资源不能被重复占用。
2. 运行定向测试，确认失败。
3. 给车辆和驾驶员增加受约束的领域状态转换方法。
4. 给资源仓储增加悲观锁读取；给任务仓储增加按车辆/驾驶员检查活动任务的方法。
5. 实现 `TaskResourceCoordinator`，把占用、开始和安全释放封装在事务内。
6. 运行定向测试，确认通过。

## 任务 4：把资源生命周期接入自动派单、人工复核和任务执行

**文件：**

- 修改：`apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorTest.java`
- 修改：`apps/api/src/test/java/com/idavy/drtops/domain/dispatch/ManualReviewApiTest.java`
- 修改：`apps/api/src/test/java/com/idavy/drtops/domain/task/TaskExecutionApiTest.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchOrchestrator.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/dispatch/ManualReviewService.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/task/TaskExecutionService.java`

**步骤：**

1. 扩展现有测试，断言自动派单和人工复核新建任务后资源已占用。
2. 扩展任务执行测试，断言开始、完成和异常关闭后的资源状态。
3. 运行定向测试，确认新增断言失败。
4. 新任务创建时调用资源协调器占用车辆/驾驶员；插单不重复占用。
5. 任务开始、完成和异常关闭时调用资源协调器更新或释放。
6. 运行定向测试，确认通过。

## 任务 5：阻止脏状态车辆重复成为新任务候选

**文件：**

- 新增或修改：`apps/api/src/test/java/com/idavy/drtops/domain/dispatch/CandidateTaskAssemblerTest.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/dispatch/CandidateTaskAssembler.java`

**步骤：**

1. 新增测试：车辆显示 `IDLE`，但已有 `DISPATCHED` 任务时，不生成该车辆的新任务候选；其现有任务仍可作为插单候选。
2. 运行定向测试，确认会产生重复新任务候选。
3. 从活动任务集合中提取已占用车辆 ID，并从空闲新任务车辆列表中排除。
4. 运行定向测试，确认通过。

## 任务 6：爽约订单联动任务和停靠点

**文件：**

- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/task/TaskStop.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTask.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskRepository.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/order/OrderExceptionService.java`
- 新增或修改：`apps/api/src/test/java/com/idavy/drtops/domain/order/OrderExceptionApiTest.java`
- 修改：`apps/api/src/test/java/com/idavy/drtops/domain/task/VehicleTaskStateTest.java`

**步骤：**

1. 新增测试：
   - `CANCELLED` 停靠点视为执行完成。
   - 单订单任务爽约后任务变为 `CANCELLED` 且资源释放。
   - 共享任务爽约后仅对应订单停靠点变为 `CANCELLED`，任务保持活动。
   - 爽约联动产生任务级审计记录。
2. 运行定向测试，确认失败。
3. 给 `TaskStop` 增加受约束的取消操作，给 `VehicleTask` 增加按订单取消未完成停靠点及统计活动订单的行为。
4. 增加按订单查询任务及停靠点的仓储方法。
5. 在 `OrderExceptionService.noShow` 中联动任务/停靠点，并在单订单任务终结后安全释放资源。
6. 运行定向测试，确认通过。

## 任务 7：修复任务完成参数

**文件：**

- 修改：`apps/admin-web/src/pages/tasks-page.test.ts`
- 修改：`apps/admin-web/src/pages/TasksPage.vue`

**步骤：**

1. 新增前端测试：点击任务级完成后，提交的位置数据可以沿用末站坐标和地址，但 `virtualStopId` 必须为空。
2. 运行 `npm test -- --run src/pages/tasks-page.test.ts`，确认失败。
3. 构建只保留位置快照字段的任务完成初始值。
4. 重跑测试，确认通过。

## 任务 8：增加首次登录强制改密流程

**文件：**

- 修改：`apps/admin-web/src/api/auth.ts`
- 修改：`apps/admin-web/src/auth/authStore.ts`
- 新增：`apps/admin-web/src/pages/ChangePasswordPage.vue`
- 修改：`apps/admin-web/src/router/index.ts`
- 修改：`apps/admin-web/src/router/router-auth.test.ts`
- 新增：`apps/admin-web/src/pages/change-password-page.test.ts`
- 修改：`apps/admin-web/src/layouts/AppLayout.vue`（如改密页需要绕开主布局）

**步骤：**

1. 新增 API、存储、路由和页面测试：
   - `mustChangePassword=true` 访问业务页会跳到改密页。
   - 改密页在强制改密期间可访问。
   - 已正常登录用户不能被错误重定向。
   - 改密请求携带当前密码和新密码。
   - 改密成功后清除会话并跳回登录页。
2. 运行定向 Vitest，确认失败。
3. 实现改密 API、状态操作、页面和路由守卫。
4. 重跑定向测试，确认通过。

## 任务 9：自动化回归与容器重建

**文件：**

- 根据测试结果修正上述实现，不扩大范围。

**步骤：**

1. 运行路由模拟器测试。
2. 运行后端相关定向测试。
3. 运行 API 全量 Maven 测试。
4. 运行管理端全量单元测试、类型检查和构建。
5. 运行 Compose 配置检查。
6. 重建并启动试点环境。
7. 检查路由模拟器、算法、API 和管理端健康状态。

## 任务 10：执行 P3-6 浏览器与数据验收

**文件：**

- 修改：`docs/pilot/evidence/p3-pilot-readiness-execution-2026-07-30.md`
- 修改：`progress.md`

**步骤：**

1. 使用现有业务接口关闭修复前爽约遗留任务，原因写明“P3-6 修复前爽约遗留任务清理”。
2. 使用模拟验收数据创建新订单并验证自动派单。
3. 完成一条任务执行链，确认任务完成请求不再冲突，订单无历史路由失败原因。
4. 创建或使用一次性测试账号验证首次登录强制改密，不替 `dispatcher02` 设置正式密码。
5. 使用 `dispatcher02` 验收日常调度页面及越权阻断。
6. 使用 `SYSTEM_ADMIN` 验收运营看板和审计页。
7. 通过 API/数据库只读查询复核：
   - 自动派单决策数大于 0。
   - 活动车辆没有重复新任务。
   - 订单、任务、车辆、驾驶员状态一致。
   - 爽约任务/停靠点已闭环。
8. 更新 P3 证据和 `progress.md`，记录测试命令、关键对象 ID、验收账号角色、模拟路由边界和遗留风险。
9. 运行文档格式检查并核对工作树范围。
