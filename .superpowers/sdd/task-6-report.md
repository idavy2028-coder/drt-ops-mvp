# 车辆位置管理 Task 6 报告：工作台最新位置与车辆位置历史页

## 变更摘要

- 工作台挂载时加载车辆最新位置快照，并每 15 秒轮询 `/api/vehicles/locations/latest`。
- 工作台卸载时清理轮询定时器；执行中车辆超过配置阈值未更新位置时显示“位置较久未更新”告警。
- `DispatchMap` 接收最新位置快照和可选人工事件链，在静态降级地图上展示车牌、任务、最后反馈时间、地址和“人工上报”来源。
- 新增 `/vehicle-locations` 位置历史页，支持车辆、任务、日期、事件类型筛选；日期按 `Asia/Shanghai` 自然日转换为 UTC 左闭右开区间。
- 历史页展示驾驶员反馈时间、系统录入时间、录入延迟、操作人和修正关系；管理员可见导出与修正入口，调度员不可见。
- 新增位置历史/导出 API 客户端和路由、侧边导航入口。
- 未接入真实高德 JS API、全局 `AMap`、GPS 或连续轨迹；未修改后端。

## 红测证据

命令：

```powershell
npm.cmd --prefix apps/admin-web run test -- dispatch-map.test.ts dispatch-workbench.test.ts vehicle-location-history-page.test.ts app-layout.test.ts
```

结果：失败符合预期。失败点包括：

- `VehicleLocationHistoryPage.vue` 不存在。
- `DispatchMap` 未渲染车辆位置标记、人工上报来源和离散节点链说明。
- `DispatchWorkbenchPage` 未加载/轮询最新车辆位置，也未显示过期告警。
- `AppLayout` 未显示“位置历史”导航入口。

## 绿测与最终验证

聚焦测试：

```powershell
npm.cmd --prefix apps/admin-web run test -- dispatch-map.test.ts dispatch-workbench.test.ts vehicle-location-history-page.test.ts app-layout.test.ts
```

结果：4 个测试文件，11 条测试全部通过。

类型检查：

```powershell
npm.cmd --prefix apps/admin-web run typecheck
```

结果：通过。

前端全量测试：

```powershell
npm.cmd --prefix apps/admin-web run test
```

结果：16 个测试文件，60 条测试全部通过。

生产构建：

```powershell
npm.cmd --prefix apps/admin-web run build
```

结果：通过。Vite 输出 `maplibre-gl` chunk 超过 500 kB 的体积提示，属于构建警告，不影响本任务功能。

## 自审结论

- 修改范围限定在 Task 6 brief 允许的前端文件和 `task-6-report.md`。
- 未修改后端。
- 未提交 `.superpowers/sdd/progress.md`。
- 未接入高德、全局 `AMap`、GPS 或连续轨迹表达。
- `VehicleTaskRepository.java` 仍为既有行尾状态，`git diff --ignore-space-at-eol` 无内容差异，未纳入本任务。

## 提交 SHA

报告文件会随任务提交一起进入提交对象；同一提交内无法预先写入自身最终 SHA。最终提交 SHA 以本任务最终回复为准。

## 顾虑

- 位置历史导出客户端会调用 CSV 接口并消费响应 Blob，但本任务未解析文件内容，符合 brief。
- 如果后端要求“无 vehicleId/taskId 的历史查询”，当前页面会提示至少输入车辆或任务编号；这是基于现有后端历史接口路径需要车辆或任务上下文的保守处理。
