# 车辆位置管理 Task 5 执行报告

## 变更摘要

- 增加前端位置上报类型契约：`LocationCandidate`、`LocationReportInput`、`TaskActionResponse`、车辆位置事件和快照 DTO。
- 任务动作 API 改为提交 `{ locationReport }`，并兼容后端 `TaskActionResponse.task`。
- 增加 `LocationPickerProvider` 接口导出和位置快照查询 API。
- 增加 `LocationReportPanel`，支持经纬度降级录入、虚拟站点带出、地址搜索 provider 注入、驾驶员反馈时间必填、经纬度校验、服务区外二次确认、失败后保留输入和同一面板复用幂等编号。
- 车辆任务页发车、到站、上车、下车、完成改为先打开位置确认面板，再提交任务动作；车辆故障和严重延误保持原接口。
- 同步前端权限：`LOCATION_READ`、`LOCATION_REPORT`、`LOCATION_CORRECT`、`LOCATION_EXPORT`。

## 红绿测试证据

RED：

```powershell
npm.cmd --prefix apps/admin-web run test -- location-report-panel.test.ts tasks-page.test.ts
```

结果：失败，符合预期。失败点包括 `LocationReportPanel.vue` 缺失、任务页点击发车仍未出现“确认发车位置”、任务动作请求尚未携带 `locationReport`。

GREEN：

```powershell
npm.cmd --prefix apps/admin-web run test -- location-report-panel.test.ts tasks-page.test.ts
```

结果：通过，2 个测试文件、13 条测试通过。

## 最终验证命令结果

```powershell
npm.cmd --prefix apps/admin-web run test -- location-report-panel.test.ts tasks-page.test.ts
```

结果：通过，2 个测试文件、13 条测试通过。

```powershell
npm.cmd --prefix apps/admin-web run typecheck
```

结果：通过。

```powershell
npm.cmd --prefix apps/admin-web run test
```

结果：通过，14 个测试文件、47 条测试通过。

```powershell
git diff --check
```

结果：通过，仅有既有/平台行尾提示，无空白错误。

## 提交 SHA

提交完成后由最终回复给出。Git 提交哈希依赖本文件内容，无法在同一个提交内写入自身最终 SHA。

## 顾虑

- 本任务未接入真实高德 JS API，也未实现工作台地图轮询和位置历史页，按计划留给 Task 6。
- 服务区外二次确认已通过可注入判断函数支持；当前未接真实电子围栏判断，后续接地图/围栏模块时应注入真实判断。
