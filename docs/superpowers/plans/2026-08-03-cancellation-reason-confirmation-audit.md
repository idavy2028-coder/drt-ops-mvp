# 取消原因确认审计实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 为已取消订单追加一次“乘客取消”原因确认审计，不改变订单、任务、车辆或驾驶员状态。

**架构：** 新增订单确认原因接口，由 `OrderExceptionService` 以现有取消审计为依据写入订单和已取消关联任务的确认审计。订单页只对已取消订单展示受既有调度权限保护的确认按钮，固定提交“乘客取消”。

**技术栈：** Spring Boot、JPA、MockMvc、Vue 3、TypeScript、Vitest。

## 全局约束

- 仅追加审计，不修改现有审计、订单、任务、车辆或驾驶员状态。
- 仅允许 `CANCELLED` 订单执行一次确认。
- 确认原因固定为“乘客取消”。
- 使用现有 `DISPATCH_EXECUTE` 权限。

---

### 任务 1：后端追加确认审计

**文件：**
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderController.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/order/OrderExceptionService.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/task/VehicleTaskRepository.java`
- 修改：`apps/api/src/test/java/com/idavy/drtops/domain/order/OrderExceptionApiTest.java`

- [x] 写入失败 MockMvc 测试：先取消单订单，再调用 `POST /api/orders/{id}/cancellation-reason-confirmation`，断言订单仍为 `CANCELLED`、任务仍为 `CANCELLED`、新增订单与任务确认审计原因为“乘客取消”。
- [x] 运行 `mvn -q -pl apps/api -Dtest=OrderExceptionApiTest test`，预期 404。
- [x] 增加受现有取消权限保护的控制器入口、服务方法和按订单查询任务的仓储方法；服务拒绝非取消订单与重复确认，并以原取消审计 ID 作为元数据。
- [x] 重跑相同测试，预期通过。

### 任务 2：前端确认操作

**文件：**
- 修改：`apps/admin-web/src/api/orders.ts`
- 修改：`apps/admin-web/src/pages/OrdersPage.vue`
- 修改：`apps/admin-web/src/pages/orders-page.test.ts`

- [x] 写入失败页面测试：已取消订单显示“确认乘客取消”，点击后仅提交一次确认接口，提交体为 `{ reason: "乘客取消" }`。
- [x] 运行 `npm.cmd test -- --run src/pages/orders-page.test.ts`，预期找不到按钮。
- [x] 增加 API 客户端函数和页面操作；成功后刷新订单列表，失败时显示现有错误反馈。
- [x] 重跑页面测试并执行 `npm.cmd run typecheck`，预期通过。

### 任务 3：部署与本单复核

- [x] 重启本地 API 与前端容器，确认健康检查恢复。
- [x] 在订单 `4c6f54fd` 上执行一次“确认乘客取消”。
- [x] 只读复核两条新增审计、订单任务状态不变，以及甘J00856D 仍为 `IDLE`。
- [ ] 当班人员提供实际待命位置后，在位置历史页上报并复核该车位置快照。
