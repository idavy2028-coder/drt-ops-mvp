# 车辆位置管理 Task 5 执行报告

## 变更摘要

- 增加前端位置上报类型契约：`LocationCandidate`、`LocationReportInput`、`TaskActionResponse`、车辆位置事件和快照 DTO。
- 任务动作 API 改为提交 `{ locationReport }`，并兼容后端 `TaskActionResponse.task`。
- 增加 `LocationPickerProvider` 接口导出和位置快照查询 API。
- 增加 `LocationReportPanel`，支持经纬度降级录入、虚拟站点带出、地址搜索 provider 注入、驾驶员反馈时间必填、经纬度校验、服务区外二次确认、失败后保留输入和同一面板复用幂等编号。
- 车辆任务页发车、到站、上车、下车、完成改为先打开位置确认面板，再提交任务动作；车辆故障和严重延误保持原接口。
- 同步前端权限：`LOCATION_READ`、`LOCATION_REPORT`、`LOCATION_CORRECT`、`LOCATION_EXPORT`。

## Review 修复摘要

- 任务动作 API 客户端统一归一化 wrapper 响应和兼容的纯 `VehicleTask` 响应，并把缺省 `warnings` 处理为 `[]`。
- 任务页把服务区外降级判断传入位置确认面板；当前仅使用候选位置或未来 provider 返回的 `outsideServiceArea` 标记，缺省为 false。
- 任务页在任务列表位置列展示最新位置来源“人工上报”、驾驶员反馈时间和标准化地址；无位置快照时显示“无位置上报”。
- provider `search` / `pickOnMap` 失败时显示可恢复提示，并保留手工录入内容。

## 红绿测试证据

RED：

```powershell
npm.cmd --prefix apps/admin-web run test -- location-report-panel.test.ts tasks-page.test.ts
```

结果：失败，符合预期。原始 Task 5 红灯失败点包括 `LocationReportPanel.vue` 缺失、任务页点击发车仍未出现“确认发车位置”、任务动作请求尚未携带 `locationReport`。

Review 修复 RED：

```powershell
npm.cmd --prefix apps/admin-web run test -- location-report-panel.test.ts tasks-page.test.ts
```

结果：失败，符合预期；2 个测试文件中 4 条新增回归测试失败，覆盖 provider reject 提示、纯 `VehicleTask` 响应兼容、服务区外二次确认传入链路、最新位置展示。

GREEN：

```powershell
npm.cmd --prefix apps/admin-web run test -- location-report-panel.test.ts tasks-page.test.ts
```

结果：通过，2 个测试文件、17 条测试通过。

## 最终验证命令结果

```powershell
npm.cmd --prefix apps/admin-web run test -- location-report-panel.test.ts tasks-page.test.ts
```

结果：通过，2 个测试文件、17 条测试通过。

```powershell
npm.cmd --prefix apps/admin-web run typecheck
```

结果：通过。

```powershell
npm.cmd --prefix apps/admin-web run test
```

结果：通过，14 个测试文件、51 条测试通过。

```powershell
git diff --check
```

结果：通过，仅有既有/平台行尾提示，无空白错误。

## 提交 SHA

提交完成后由最终回复给出。Git 提交哈希依赖本文件内容，无法在同一个提交内写入自身最终 SHA。

## 顾虑

- 本任务未接入真实高德 JS API，也未实现工作台地图轮询和位置历史页，按计划留给 Task 6。
- 服务区外二次确认已通过可注入判断函数支持；当前未接真实电子围栏判断，仅消费 `outsideServiceArea` 标记，后续接地图/围栏模块时应注入真实判断或由 provider 返回该标记。
