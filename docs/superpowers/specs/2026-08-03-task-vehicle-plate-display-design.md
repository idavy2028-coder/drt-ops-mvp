# 车辆任务车牌展示设计

## 目标

车辆任务页的“车辆”列展示可运营辨识的车牌号，不再展示内部车辆 UUID；任务选择、状态流转和接口参数仍使用 UUID。

## 现状与根因

`VehicleTaskController` 直接序列化 `VehicleTask` 实体，响应只有 `vehicleId`。`TasksPage.vue` 因此将 `task.vehicleId` 直接渲染到表格。虽然位置快照接口含 `plateNumber`，但它依赖车辆存在位置快照，不能作为所有任务的稳定展示来源。

## 方案

在车辆任务 API 边界引入展示 DTO：

- `VehicleTaskView` 包含现有任务字段和只读 `vehiclePlateNumber`。
- 任务列表、任务动作响应、故障和严重延误响应均转换为该 DTO，保证页面动作后更新的任务仍带车牌。
- DTO 通过 `VehicleRepository` 按内部 `vehicleId` 查询车牌。找不到车辆或车牌为空时，`vehiclePlateNumber` 返回空值。
- 前端 `VehicleTask` 类型增加可选 `vehiclePlateNumber`；表格优先显示该字段，空值显示“未登记车牌”，绝不回退显示 UUID。

## 数据与兼容性

- 不修改数据库结构、车辆 ID、任务 ID、调度算法或任何任务动作请求。
- 保留原有 `vehicleId` JSON 字段，兼容现有内部关联和调用方。
- 新字段为响应扩展，不携带驾驶员或乘客敏感信息。

## 验收标准

1. `GET /api/vehicle-tasks` 对已登记车辆返回 `vehiclePlateNumber`。
2. 任务动作、故障和严重延误响应中的 `task` 同样返回车牌。
3. 任务页车辆列显示车牌，且不可在该列看到 UUID。
4. 车辆资料缺失时显示“未登记车牌”，任务选择和动作仍正常。
5. 现有任务状态、站点时间线和位置展示无回归。
