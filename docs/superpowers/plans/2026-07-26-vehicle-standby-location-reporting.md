# 车辆待命位置上报实施计划

> **供执行代理使用：** 必须使用 `superpowers:subagent-driven-development` 或 `superpowers:executing-plans` 按任务执行。本计划以任务为最小审阅单位，每项完成后等待人工审阅。

**目标：** 为调度员提供无需先创建车辆任务的“车辆待命位置上报”入口，使试点车辆可通过后台页面创建合法的初始位置事件和最新位置快照，进而进入算法候选车辆池。

**架构：** 复用既有的 `POST /api/vehicles/{vehicleId}/location-reports`、位置事件、快照、服务区校验和审计日志，不直接写数据库。新增一个只面向位置上报权限的车辆候选列表接口；在“位置历史”页增加待命位置上报面板，复用现有位置确认组件、虚拟站点和 Leaflet 开放瓦片地图。

**技术栈：** Java 21 / Spring Boot / Spring Security / JPA / PostGIS；Vue 3 / TypeScript / Vitest / Testing Library；Leaflet 开放瓦片底图。

## 全局约束

- 坐标统一使用 GCJ-02；页面显示可以写作“GCJ-02”，接口值沿用现有 `GCJ02`。
- 首次待命位置使用 `MANUAL_REPORT` 和 `MANUAL_DISPATCHER`，不得伪造任务、任务节点或 GPS 来源。
- 位置事件不可编辑、不可删除；本功能只创建新事件，最新快照只能由时间较新的有效事件推进。
- 调度员只能上报普通位置；管理员可上报并修正；运营员仍不得访问位置上报接口。
- 待命位置必须通过后台接口保存，禁止直接更新 `vehicles.current_location` 或直接插入事件表。
- 服务区外位置可保存但必须在提交前二次确认，并在成功提示中保留“服务区外”警告。
- 地图底图不可用时，虚拟站点选择和手工 GCJ-02 经纬度录入必须仍可完成。
- 不新增数据库迁移，不改变现有任务节点位置上报接口，不改变已发布服务区边界。
- 每个任务先写失败测试，测试转绿后提交给人工审阅；未获审阅通过不得进入下一任务或提交/推送。

---

## 文件与职责

- 修改 `apps/api/src/main/java/com/idavy/drtops/config/SecurityConfiguration.java`：为位置上报车辆候选列表增加精确的权限路由，且置于通用 `/api/vehicles/**` 资源管理规则之前。
- 修改 `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationController.java`：暴露调度员可调用的候选车辆列表接口。
- 修改 `apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationQueryService.java`：返回全部可上报车辆及其最新快照，包含尚无快照的车辆。
- 修改 `apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationApiTest.java`：覆盖候选列表的内容、权限和既有独立位置上报回归。
- 修改 `apps/admin-web/src/api/types.ts`：增加候选车辆、独立位置上报响应的显式类型。
- 修改 `apps/admin-web/src/api/vehicleLocations.ts`：增加候选车辆查询和待命位置上报客户端函数。
- 修改 `apps/admin-web/src/api/vehicleLocations.test.ts`：覆盖请求路径、请求体、令牌和响应解析。
- 修改 `apps/admin-web/src/components/LocationReportPanel.vue`：支持服务区包含校验、虚拟站点/地图点选和服务区外二次确认；继续被车辆任务页复用。
- 修改 `apps/admin-web/src/components/location-report-panel.test.ts`：覆盖服务区外确认、虚拟站点选点、地图降级和失败后保留表单。
- 修改 `apps/admin-web/src/pages/VehicleLocationHistoryPage.vue`：从纯查询页升级为“待命位置上报 + 历史查询”页。
- 修改 `apps/admin-web/src/pages/vehicle-location-history-page.test.ts`：覆盖调度员完整上报流程、无车辆选择拦截和成功后刷新历史。
- 修改 `apps/admin-web/e2e/vehicle-location-flow.spec.ts`：覆盖真实浏览器中的待命位置上报和服务区外警告。
- 修改 `docs/release/tongwei-vehicle-location-runbook.md`：记录试点调度员的实际操作步骤和验收记录模板。

## 接口约定

### 新增候选车辆查询

`GET /api/vehicles/location-reporting-candidates`

- 权限：`LOCATION_REPORT`。
- 返回：全部可上报位置的车辆，不以已有快照为筛选条件。

```json
{
  "data": [
    {
      "vehicleId": "12efe967-0be5-485e-a8dc-836fd64a2516",
      "plateNumber": "甘J00856D",
      "currentStatus": "IDLE",
      "dispatchable": true,
      "latestLocation": null
    }
  ]
}
```

### 复用的待命位置上报

`POST /api/vehicles/{vehicleId}/location-reports`

- 页面固定提交：`vehicleTaskId = null`、`taskStopId = null`、`eventType = "MANUAL_REPORT"`、`correctsEventId = null`。
- 页面传入：`virtualStopId`、经纬度、标准化地址、驾驶员反馈时间、备注和每次打开面板生成的 `idempotencyKey`。
- 成功返回既有 `LocationReportResponse`；`warnings` 中包含 `OUTSIDE_SERVICE_AREA` 时，页面提示“已保存服务区外位置，车辆快照是否推进以接口返回为准”。

---

### Task 1：提供调度员可用的待命位置车辆候选接口

**文件：**
- 修改：`apps/api/src/main/java/com/idavy/drtops/config/SecurityConfiguration.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationController.java`
- 修改：`apps/api/src/main/java/com/idavy/drtops/domain/location/VehicleLocationQueryService.java`
- 测试：`apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationApiTest.java`

**接口：**
- 新增 `VehicleLocationQueryService.reportableVehicles(): List<VehicleLocationReportCandidate>`。
- 新增 `VehicleLocationReportCandidate(UUID vehicleId, String plateNumber, String currentStatus, boolean dispatchable, VehicleLocationSnapshotView latestLocation)`。
- 新增 `GET /api/vehicles/location-reporting-candidates`。

- [ ] **步骤 1：先写失败的 API 测试。**

在 `VehicleLocationApiTest` 增加如下断言：调度员可读取两辆测试车；未有位置快照的车辆仍出现；运营员得到 `403`。

```java
mockMvc.perform(get("/api/vehicles/location-reporting-candidates")
        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken)))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.length()").value(2))
    .andExpect(jsonPath("$.data[0].vehicleId").value(VEHICLE_ID.toString()))
    .andExpect(jsonPath("$.data[0].latestLocation").doesNotExist());

mockMvc.perform(get("/api/vehicles/location-reporting-candidates")
        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
    .andExpect(status().isForbidden());
```

- [ ] **步骤 2：运行测试，确认接口尚不存在而失败。**

运行：

```powershell
.\\.tools\\apache-maven-3.9.11\\bin\\mvn.cmd -q -pl apps/api -Dtest=VehicleLocationApiTest test
```

预期：候选接口返回 `404` 或权限规则导致断言失败；既有位置上报测试不应被修改为绿灯。

- [ ] **步骤 3：实现最小后端能力。**

在 `VehicleLocationQueryService` 增加记录类型和方法，使用 `vehicleRepository.findAll()`，不排除 `latestLocation == null` 的车辆：

```java
@PreAuthorize("hasAuthority('LOCATION_REPORT')")
public List<VehicleLocationReportCandidate> reportableVehicles() {
    return metrics.recordQuery(() -> vehicleRepository.findAll().stream()
            .map(vehicle -> new VehicleLocationReportCandidate(
                    vehicle.getId(), vehicle.getPlateNumber(), vehicle.getCurrentStatus(),
                    vehicle.isDispatchable(), VehicleLocationSnapshotView.from(vehicle)))
            .toList());
}

record VehicleLocationReportCandidate(
        UUID vehicleId, String plateNumber, String currentStatus,
        boolean dispatchable, VehicleLocationSnapshotView latestLocation) { }
```

在 `VehicleLocationController` 增加 `@GetMapping("/vehicles/location-reporting-candidates")`，并返回 `queryService.reportableVehicles()`。在 `SecurityConfiguration` 的通用 `/api/vehicles/**` 规则之前增加：

```java
.requestMatchers(HttpMethod.GET, "/api/vehicles/location-reporting-candidates")
    .hasAuthority("LOCATION_REPORT")
```

- [ ] **步骤 4：运行后端测试并检查权限回归。**

运行：

```powershell
.\\.tools\\apache-maven-3.9.11\\bin\\mvn.cmd -q -pl apps/api -Dtest=VehicleLocationApiTest test
```

预期：新增候选查询通过；调度员仍可普通上报，运营员仍不能读取或上报位置。

- [ ] **步骤 5：提交审阅检查点。**

提交前只展示 Task 1 的 4 个文件差异和测试结果，等待人工审核，不提交、不推送。

### Task 2：补齐前端位置候选与上报 API 客户端

**文件：**
- 修改：`apps/admin-web/src/api/types.ts`
- 修改：`apps/admin-web/src/api/vehicleLocations.ts`
- 测试：`apps/admin-web/src/api/vehicleLocations.test.ts`

**接口：**
- 新增 `VehicleLocationReportCandidate`、`LocationReportResponse`。
- 新增 `listLocationReportVehicles(): Promise<VehicleLocationReportCandidate[]>`。
- 新增 `reportVehicleStandbyLocation(vehicleId: UUID, input: LocationReportInput): Promise<LocationReportResponse>`。

- [ ] **步骤 1：先写失败的前端 API 测试。**

测试应精确验证：

```ts
await reportVehicleStandbyLocation("vehicle-1", {
  longitude: 105.2421,
  latitude: 35.2103,
  standardizedAddress: "通渭县试点待命点",
  driverReportedAt: "2026-07-26T01:30:00.000Z",
  idempotencyKey: "request-1"
});

expect(request).toHaveBeenCalledWith("/api/vehicles/vehicle-1/location-reports", {
  method: "POST",
  body: JSON.stringify(expect.objectContaining({
    vehicleTaskId: null,
    taskStopId: null,
    eventType: "MANUAL_REPORT",
    correctsEventId: null,
    longitude: 105.2421,
    latitude: 35.2103
  }))
});
```

同时验证候选列表请求地址为 `/api/vehicles/location-reporting-candidates`。

- [ ] **步骤 2：运行 Vitest，确认函数未定义而失败。**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- vehicleLocations.test.ts
```

预期：导入失败或函数不存在。

- [ ] **步骤 3：增加类型与 API 函数。**

在 `types.ts` 增加：

```ts
export interface VehicleLocationReportCandidate {
  vehicleId: UUID;
  plateNumber: string;
  currentStatus: string;
  dispatchable: boolean;
  latestLocation: VehicleLocationSnapshot | null;
}

export interface LocationReportResponse {
  event: VehicleLocationView;
  snapshotApplied: boolean;
  warnings: string[];
  replayed: boolean;
}
```

在 `vehicleLocations.ts` 使用现有 `request` 函数，固定组装独立 `MANUAL_REPORT` 请求体；前端不传 `source`、`recordedBy`、任务节点或快照字段。

- [ ] **步骤 4：运行前端 API 测试。**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- vehicleLocations.test.ts
```

预期：请求路径、`MANUAL_REPORT` 请求体和响应解析全部通过。

- [ ] **步骤 5：提交审阅检查点。**

提交前只展示 Task 2 的 3 个文件差异与测试结果，等待人工审核，不提交、不推送。

### Task 3：让位置确认面板支持服务区地图点选与提交前校验

**文件：**
- 修改：`apps/admin-web/src/components/LocationReportPanel.vue`
- 测试：`apps/admin-web/src/components/location-report-panel.test.ts`

**接口：**
- 新增组件属性 `serviceArea?: ServiceAreaBoundaryView`。
- 继续输出 `submit: LocationReportInput`，不改变车辆任务页的调用接口。
- 复用 `VirtualStopMap`、`checkServiceAreaContainment` 和现有 `LocationReportPanel` 表单。

- [ ] **步骤 1：先写失败的组件测试。**

覆盖三个场景：

```ts
// 选择虚拟站点后，提交值携带该站点 ID、站点坐标和站点名称。
// 地图点选后，输入框更新为 GCJ-02 经纬度，地址可继续编辑。
// 服务区包含接口返回 inside: false 时，第一次提交只显示确认框；勾选确认后才 emit("submit", ...)。
```

另增加地图初始化失败场景，断言经纬度输入和虚拟站点选择仍可用。

- [ ] **步骤 2：运行组件测试，确认新属性和交互尚不存在。**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- location-report-panel.test.ts
```

预期：地图点选或服务区确认相关断言失败。

- [ ] **步骤 3：扩展组件，保持任务页兼容。**

实现规则：

1. `serviceArea` 存在时渲染 `VirtualStopMap`，传入同一服务区边界和虚拟站点，`readonly=false`。
2. 地图 `pick` 事件写入 `longitude`、`latitude`，默认地址写为“地图点选位置”，允许调度员补充更准确的文字地址。
3. `submit()` 变为异步：服务区存在时调用 `checkServiceAreaContainment(serviceArea.id, longitude, latitude)`；若 `inside=false` 且未勾选确认，不 emit 上报事件。
4. 虚拟站点选择仍自动填充坐标、名称和 `virtualStopId`；手工坐标仍经过现有经纬度范围校验。
5. 失败时不清空已填表单；每次重新打开组件保留一个新的幂等键。

- [ ] **步骤 4：运行组件测试。**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- location-report-panel.test.ts
```

预期：虚拟站点、地图点选、服务区外二次确认和地图降级全部通过；车辆任务页现有测试继续通过。

- [ ] **步骤 5：提交审阅检查点。**

提交前只展示 Task 3 的 2 个文件差异与测试结果，等待人工审核，不提交、不推送。

### Task 4：在位置历史页实现待命位置上报闭环

**文件：**
- 修改：`apps/admin-web/src/pages/VehicleLocationHistoryPage.vue`
- 测试：`apps/admin-web/src/pages/vehicle-location-history-page.test.ts`

**接口：**
- 页面加载 `listLocationReportVehicles()`、`listServiceAreas()` 和已启用 `listVirtualStops({ enabled: true })`。
- 页面调用 `reportVehicleStandbyLocation(selectedVehicleId, input)`。

- [ ] **步骤 1：先写失败的页面测试。**

测试必须覆盖：

```ts
// dispatcher01 能看到“上报待命位置”和车牌下拉项，而非手工输入 UUID。
// 未选择车辆时，不能打开或提交上报面板，并显示“请先选择车辆”。
// 选择甘J00856D与虚拟站点后，页面调用 reportVehicleStandbyLocation(vehicleId, input)。
// 成功后，显示“待命位置已上报”，将该车 ID 写入历史筛选并重新查询。
// warnings 含 OUTSIDE_SERVICE_AREA 时，显示成功但含服务区外告警，不把结果改写成失败。
```

测试数据中至少包含一辆 `latestLocation: null` 的车，以验证首个待命位置可通过页面创建。

- [ ] **步骤 2：运行页面测试，确认入口不存在而失败。**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- vehicle-location-history-page.test.ts
```

预期：找不到“上报待命位置”按钮、车辆下拉项或上报 API 调用。

- [ ] **步骤 3：实现页面交互。**

在历史筛选面板前增加“车辆待命位置上报”工作面板：

1. 只在 `authStore.has("LOCATION_REPORT")` 时显示入口。
2. 使用候选接口返回的 `vehicleId` 作为 value、`plateNumber + 当前状态 + 可调度标记` 作为显示文本；禁止用车牌替代后端 UUID。
3. 选择当前已启用服务区；没有已启用服务区时禁用提交并显示“未找到已启用服务区，无法校验待命位置”。
4. 把所选车辆、已启用虚拟站点、服务区边界传给 `LocationReportPanel`；将 `actionLabel` 传为“待命”。
5. 上报成功后关闭面板、刷新候选车辆数据、将历史筛选 `vehicleId` 设为已上报车辆、调用现有 `search()`；使用 `feedbackStore` 显示成功、重放或服务区外警告。
6. 请求失败时保持面板和填写内容，显示 `userMessage(error, "待命位置上报失败")`，不得假设快照已更新。

- [ ] **步骤 4：运行前端页面回归。**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- vehicle-location-history-page.test.ts location-report-panel.test.ts vehicleLocations.test.ts
npm.cmd --prefix apps/admin-web run typecheck
```

预期：新增流程与既有历史查询、导出、管理员修正入口均通过；TypeScript 无类型错误。

- [ ] **步骤 5：提交审阅检查点。**

提交前只展示 Task 4 的 2 个文件差异与前端验证结果，等待人工审核，不提交、不推送。

### Task 5：端到端验收、运行手册与回归收口

**文件：**
- 修改：`apps/admin-web/e2e/vehicle-location-flow.spec.ts`
- 修改：`docs/release/tongwei-vehicle-location-runbook.md`
- 回归：`apps/api/src/test/java/com/idavy/drtops/domain/location/VehicleLocationApiTest.java`

- [ ] **步骤 1：先补充失败的端到端场景。**

场景使用调度员账号：打开“位置历史”，选择一辆无最新快照的车辆，选择服务区内虚拟站点，填写驾驶员反馈时间，提交后断言：

1. 页面显示“待命位置已上报”；
2. 历史时间线出现一条 `MANUAL_REPORT`；
3. API 返回的最新快照事件 ID 等于新事件 ID；
4. 审计日志存在 `VEHICLE_LOCATION_REPORTED`；
5. 服务区外地图点第一次提交被确认框拦截，二次确认后成功并显示警告。

- [ ] **步骤 2：运行端到端测试，确认新页面流程尚未被覆盖而失败。**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test:e2e -- vehicle-location-flow.spec.ts
```

预期：因缺少待命位置上报入口或断言元素而失败。

- [ ] **步骤 3：完成端到端测试与运行手册。**

在 `tongwei-vehicle-location-runbook.md` 写明以下固定操作：

1. 调度员登录后进入“位置历史”。
2. 选择车辆和服务区内待命点，优先选择已启用虚拟站点。
3. 填写驾驶员实际反馈时间，不填写未来时间。
4. 服务区外仅在确认真实位置确在区外时二次确认保存。
5. 回到调度工作台确认车辆位置来源为“人工上报”、更新时间为刚才的反馈时间。
6. 对 4 辆车逐一完成后，创建全新即时需求，验证候选方案数量大于 0；旧的“候选方案 0 个”人工复核决策不作为验收订单。

- [ ] **步骤 4：运行全量验证。**

运行：

```powershell
.\\.tools\\apache-maven-3.9.11\\bin\\mvn.cmd -q -pl apps/api test
npm.cmd --prefix apps/admin-web run test
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run build
git diff --check
```

预期：全部通过；若 Docker/Testcontainers 在本机不可用，单独记录为环境限制，不把失败解释为业务测试通过。

- [ ] **步骤 5：本机人工验收与审阅检查点。**

在不直接修改数据库的前提下，用后台页面为 4 辆车录入服务区内真实待命点。人工核对每辆车均有：一条新的 `MANUAL_REPORT` 事件、一条审计记录、与最后有效事件一致的快照、服务区内状态。完成后只提交验收记录与变更清单，等待人工审核；未经明确授权不得提交、推送或创建 PR。

## 自检结果

- 需求覆盖：补齐了调度员无法选择车辆、无任务时无法录入首个位置、服务区内外确认、虚拟站点与地图点选、审计/快照/幂等复用、权限隔离和试点验收。
- 不新增表或迁移：复用现有位置事件、车辆快照、服务区校验和审计模型。
- 接口一致性：页面只调用既有位置上报接口；新候选接口使用 `LOCATION_REPORT`，不会放宽通用资源管理 API。
- 占位符检查：每个任务均定义了接口、文件、失败测试、实现路径和验证命令。

## 执行交接

计划已保存。推荐采用“子代理逐任务执行”：每个任务独立测试、展示变更、人工审核通过后再进行下一任务。也可在当前会话按同一审阅检查点串行执行。
