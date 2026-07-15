# 车辆位置管理 Task 5 brief：前端位置契约和任务位置确认面板

## 目标

实现前端位置上报契约、任务动作位置确认面板，并把车辆任务页的发车、到站、上车、下车、完成动作改为“先确认位置，再提交任务动作”。本任务只交付经纬度/虚拟站点降级录入与可注入的 `LocationPickerProvider` 接口，不直接接入高德地图全局对象。

## 文件范围

- 修改：`apps/admin-web/src/api/types.ts`
- 修改：`apps/admin-web/src/auth/permissions.ts`
- 新建：`apps/admin-web/src/api/vehicleLocations.ts`
- 修改：`apps/admin-web/src/api/tasks.ts`
- 新建：`apps/admin-web/src/maps/locationProvider.ts`
- 新建：`apps/admin-web/src/components/LocationReportPanel.vue`
- 新建：`apps/admin-web/src/components/location-report-panel.test.ts`
- 修改：`apps/admin-web/src/pages/TasksPage.vue`
- 修改：`apps/admin-web/src/pages/tasks-page.test.ts`

## 前端类型契约

在前端定义：

```ts
export interface LocationCandidate {
  longitude: number;
  latitude: number;
  standardizedAddress: string;
  virtualStopId?: UUID;
  providerDegraded?: boolean;
}

export interface LocationReportInput extends LocationCandidate {
  driverReportedAt: IsoDateTime;
  note?: string;
  idempotencyKey: UUID;
}

export interface LocationPickerProvider {
  search(keyword: string): Promise<LocationCandidate[]>;
  pickOnMap(container: HTMLElement, initial?: LocationCandidate): Promise<LocationCandidate>;
}
```

任务动作 API 必须提交：

```ts
body: JSON.stringify({ locationReport })
```

`startTask`、`arriveStop`、`boardStop`、`alightStop`、`completeTask` 均接收 `LocationReportInput`。车辆故障和严重延误仍保留原有原因请求体，不强制位置。

## 位置确认面板要求

- 打开面板时生成一次 `crypto.randomUUID()` 作为 `idempotencyKey`。
- 提交失败后保留表单内容和同一个 `idempotencyKey`，便于网络重试。
- 只有关闭面板并重新发起动作时才生成新的 `idempotencyKey`。
- 驾驶员反馈时间必填。
- 经纬度必须校验有效范围。
- 支持手工输入经纬度和标准化地址。
- 支持虚拟站点选择，并可带出标准化地址/经纬度。
- 地图提供者不可用时显示降级提示，仍允许经纬度/虚拟站点录入。
- 服务区外警告需要二次确认，不阻止保存。
- 交互失败时不清空面板录入内容。

## 车辆任务页接入要求

- 发车、到站、上车、下车、完成按钮不再直接调用接口，而是设置待执行动作并打开位置确认面板。
- 默认位置规则：
  - 发车：车辆最新位置快照，若当前 DTO 暂无快照则允许为空，由调度员手填。
  - 到站、上车、下车：目标任务节点对应虚拟站点，若当前 DTO 暂无坐标则允许为空。
  - 完成：最后一个任务节点位置。
- 提交成功后用 `TaskActionResponse.task` 或兼容后的任务对象更新页面，关闭面板，并刷新位置相关数据。
- 双击/网络重试必须复用同一个 `idempotencyKey`，不得重复生成。

## 权限要求

- 前端权限表同步 Task 3 后端权限：
  - `LOCATION_READ`
  - `LOCATION_REPORT`
  - `LOCATION_CORRECT`
  - `LOCATION_EXPORT`
- 调度员拥有 `LOCATION_READ`、`LOCATION_REPORT`。
- 系统管理员拥有四项位置权限。
- 其他角色不自动获得位置权限。

## 测试要求

先写失败测试，再实现。至少覆盖：

- 位置面板默认位置展示。
- 驾驶员反馈时间必填。
- 经纬度校验。
- 虚拟站点选择。
- 提交失败后保留输入。
- 服务区外二次确认。
- 同一面板重试复用幂等编号。
- 地图提供者不可用时显示降级提示。
- 任务页点击动作打开面板，而不是直接请求任务动作 API。
- 任务页提交位置后请求体包含 `{ locationReport }`。

验证命令：

```powershell
npm.cmd --prefix apps/admin-web run test -- location-report-panel.test.ts tasks-page.test.ts
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run test
```

提交命令：

```powershell
git add apps/admin-web/src/api/types.ts apps/admin-web/src/api/vehicleLocations.ts apps/admin-web/src/api/tasks.ts apps/admin-web/src/auth/permissions.ts apps/admin-web/src/maps/locationProvider.ts apps/admin-web/src/components/LocationReportPanel.vue apps/admin-web/src/components/location-report-panel.test.ts apps/admin-web/src/pages/TasksPage.vue apps/admin-web/src/pages/tasks-page.test.ts
git commit -m "feat: confirm locations during task actions"
```

## 不要做

- 不要接入真实高德 JS API 或全局 `AMap`。
- 不要实现调度工作台地图轮询或历史页，那是 Task 6。
- 不要修改后端业务代码，除非发现前后端契约确有编译阻塞且必须最小修正。
- 不要提交 `.superpowers/sdd/progress.md`；该文件等 Task 8 再提交。
