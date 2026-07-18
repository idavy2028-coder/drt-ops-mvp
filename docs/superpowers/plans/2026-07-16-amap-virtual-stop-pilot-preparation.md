# 高德地图与虚拟站点试点准备 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不引入乘客端和司机端的前提下，为通渭县试点补齐高德地图免费 API 接入、服务区电子围栏、订单地址解析、驾车距离/时间计算、虚拟站点采集导入和地图展示能力。

**Architecture:** 后端统一封装高德 Web 服务 API，负责密钥、配额保护、错误语义、审计和服务区校验；前端只使用高德 JS API 做地图呈现、绘制、点选和地址交互。服务区和虚拟站点仍以本系统 PostgreSQL/PostGIS 为事实源，高德能力作为外部能力适配层，失败时降级到经纬度/虚拟站点手工录入。

**Tech Stack:** Java Spring Boot、Vue 3/TypeScript、PostgreSQL/PostGIS、高德 Web 服务 API、 高德 JS API 2.0、Flyway、Vitest、Maven。

## Global Constraints

- 默认面向试点区域：甘肃省通渭县。
- 试点周期：1 个月。
- 运营时段：6:30-19:00。
- 车辆/驾驶员：4 辆 / 4 名驾驶员；调度员：2 名。
- 试点期间即时需求由调度员根据电话/微信手工录入，不接入乘客端或小程序。
- 试点期间驾驶员通过电话/微信接收任务并反馈节点，调度员在后台手动更新任务状态。
- 车辆位置为人工节点上报，不接入车载 GPS，不支持连续轨迹追踪。
- 坐标体系统一为 GCJ-02；数据库继续使用 `geography(..., 4326)` 存储，经业务字段标记 `coordinateSystem=GCJ02`。
- 高德地图能力优先使用免费/基础配额；不得擅自启用付费服务或购买配额。
- 高德 API Key、安全密钥、服务端 Web Service Key 不得提交到仓库。
- 前端 JS API Key 只用于地图展示和交互；后端 Web Service Key 只在 API 服务端使用。
- 高德服务失败时，不阻断经纬度或虚拟站点方式录入；必须给出中文降级提示。
- 服务区外车辆位置允许保存并告警；订单起终点默认必须在启用服务区内。
- 每个任务完成后设置人工审阅检查点，审阅通过后才能进入下一任务。

---

## 当前代码边界

**已有后端边界**

- `apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceArea.java`：服务区实体，当前已有边界、服务时间和规则集字段。
- `apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceAreaController.java`：服务区列表和创建接口。
- `apps/api/src/main/java/com/idavy/drtops/domain/area/VirtualStop.java`：虚拟站点实体，当前已有站点坐标、半径、上下车能力和安全说明。
- `apps/api/src/main/java/com/idavy/drtops/domain/area/VirtualStopController.java`：虚拟站点列表和创建接口。
- `apps/api/src/main/java/com/idavy/drtops/domain/location/PostgisServiceAreaLocationChecker.java`：服务区内外位置判断。
- `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderService.java`：订单创建和调度触发入口。
- `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchOrchestrator.java`：调度编排入口。
- `apps/api/src/main/resources/db/migration/V1__create_core_schema.sql`：服务区、虚拟站点、订单和任务核心表。
- `apps/api/src/main/resources/db/migration/V6__create_vehicle_location_events.sql`：车辆位置事件和快照。

**已有前端边界**

- `apps/admin-web/src/components/OrderCreateDialog.vue`：订单录入弹窗，当前仍以经纬度字段为主。
- `apps/admin-web/src/components/DispatchMap.vue`：调度工作台地图展示组件。
- `apps/admin-web/src/components/LocationReportPanel.vue`：车辆节点位置确认面板。
- `apps/admin-web/src/pages/ResourcesPage.vue`：资源配置页，适合承载服务区和虚拟站点管理。
- `apps/admin-web/src/components/VirtualStopTable.vue`：虚拟站点列表组件。
- `apps/admin-web/src/api/resources.ts`：服务区、虚拟站点、车辆、驾驶员 API 客户端。
- `apps/admin-web/src/api/types.ts`：前端 DTO 类型。
- `apps/admin-web/src/maps/locationProvider.ts`：位置选择提供者边界，目前只是类型导出。

**高德官方能力依据**

- 地理编码/逆地理编码：`https://lbs.amap.com/api/webservice/guide/api/georegeo`
- 输入提示：`https://lbs.amap.com/api/webservice/guide/api-advanced/inputtips`
- 驾车路径规划和距离测量：`https://lbs.amap.com/api/webservice/guide/api/direction`
- 行政区边界：`https://lbs.amap.com/api/webservice/guide/api/district`
- JS API 2.0 加载和安全密钥：`https://lbs.amap.com/api/javascript-api-v2/guide/abc/load`
- Web 服务配额需以高德控制台和官方配额页为准：`https://lbs.amap.com/api/webservice/guide/tools/flowlevel`

---

## Task 1: 高德配置与地图能力抽象

**目标:** 建立后端 Web Service 配置、前端 JS API 配置和统一地图能力接口；默认可关闭，便于本机无 Key 时继续开发和测试。

**Files:**

- Create: `apps/api/src/main/java/com/idavy/drtops/integration/amap/AmapProperties.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/integration/amap/AmapClientConfig.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/map/MapProviderStatus.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/map/MapProviderException.java`
- Create: `apps/admin-web/src/maps/amapLoader.ts`
- Create: `apps/admin-web/src/maps/amapTypes.ts`
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/admin-web/src/api/types.ts`
- Modify: `README.md`
- Test: `apps/api/src/test/java/com/idavy/drtops/integration/amap/AmapPropertiesTest.java`
- Test: `apps/admin-web/src/maps/amap-loader.test.ts`

**Interfaces:**

- Produces backend config prefix: `drt.map.amap.enabled`, `drt.map.amap.web-service-key`, `drt.map.amap.base-url`, `drt.map.amap.connect-timeout-ms`, `drt.map.amap.read-timeout-ms`.
- Produces frontend env names: `VITE_AMAP_ENABLED`, `VITE_AMAP_JS_API_KEY`, `VITE_AMAP_SECURITY_JS_CODE`.
- Produces frontend loader: `loadAmap(): Promise<AmapRuntime>`.

**Steps:**

- [ ] 新增 `AmapProperties`，用 `@ConfigurationProperties(prefix = "drt.map.amap")` 绑定配置；`webServiceKey` 为空时保持禁用。
- [ ] 新增 `AmapClientConfig`，集中创建带超时的 HTTP 客户端；不要在业务类里拼接密钥。
- [ ] 新增 `MapProviderStatus`，至少包含 `enabled`, `provider`, `degradedReason`。
- [ ] 在 `application.yml` 加入默认禁用配置和环境变量占位：`DRT_AMAP_ENABLED=false`、`DRT_AMAP_WEB_SERVICE_KEY=`。
- [ ] 新增 `amapLoader.ts`，只在 `VITE_AMAP_ENABLED=true` 且 JS Key 存在时加载 `@amap/amap-jsapi-loader`。
- [ ] README 增加本机配置说明：如何申请高德 Web 服务 Key、JS API Key、安全密钥；明确不得提交真实 Key。
- [ ] 写后端配置绑定测试，覆盖 enabled false、Key 缺失、超时默认值。
- [ ] 写前端 loader 测试，覆盖禁用状态返回降级原因，启用状态调用 loader。
- [ ] 运行 `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=AmapPropertiesTest test`。
- [ ] 运行 `npm.cmd --prefix apps/admin-web run test -- amap-loader`。
- [ ] 审阅检查点：确认没有真实 Key、没有付费服务调用、无 Key 时系统可启动。

---

## Task 2: 高德 Web Service API 适配层

**目标:** 后端封装地址提示、地理编码、驾车路径和距离计算能力，业务模块只依赖内部接口，不直接依赖高德响应结构。

**Files:**

- Create: `apps/api/src/main/java/com/idavy/drtops/domain/map/AddressSuggestion.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/map/GeocodeResult.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/map/RoutePlanResult.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/map/DistanceResult.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/map/MapSearchProvider.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/map/RoutePlanningProvider.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/integration/amap/AmapMapSearchProvider.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/integration/amap/AmapRoutePlanningProvider.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/map/MapProviderController.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/integration/amap/AmapMapSearchProviderTest.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/integration/amap/AmapRoutePlanningProviderTest.java`

**Interfaces:**

- `MapSearchProvider.suggest(String keyword, String city): List<AddressSuggestion>`
- `MapSearchProvider.geocode(String address, String city): GeocodeResult`
- `RoutePlanningProvider.drivingRoute(Coordinate origin, Coordinate destination, List<Coordinate> waypoints): RoutePlanResult`
- `RoutePlanningProvider.distance(Coordinate origin, Coordinate destination): DistanceResult`
- Internal API:
  - `GET /api/map/address-suggestions?keyword=&city=通渭县`
  - `GET /api/map/geocode?address=&city=通渭县`
  - `POST /api/map/driving-route`
  - `POST /api/map/distance`

**Steps:**

- [ ] 先写 `AmapMapSearchProviderTest`，用模拟 HTTP 返回高德 `status=1` 的 inputtips/geocode JSON，断言转换为内部 DTO。
- [ ] 写失败测试：`status=0`、网络超时、Key 未配置，断言返回中文业务错误或降级状态，不泄露高德原始错误。
- [ ] 实现 inputtips 调用：`/v3/assistant/inputtips`，固定传 `key`、`keywords`、`city`、`citylimit=true`。
- [ ] 实现 geocode 调用：`/v3/geocode/geo`，固定传 `key`、`address`、`city`。
- [ ] 写 `AmapRoutePlanningProviderTest`，覆盖驾车路径距离、预计时间、途经点。
- [ ] 实现 driving route 调用：`/v3/direction/driving`，最多传 16 个途经点。
- [ ] 实现 distance 调用：`/v3/distance`，用于轻量距离/时间计算。
- [ ] 新增 `MapProviderController`，只暴露给已登录运营角色，返回内部 DTO。
- [ ] 增加基础指标：调用次数、成功/失败、耗时、降级次数。
- [ ] 运行 `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=AmapMapSearchProviderTest,AmapRoutePlanningProviderTest test`。
- [ ] 审阅检查点：确认业务层没有直接解析高德 JSON，Key 只在服务端使用，错误均为中文业务语义。

---

## Task 3: 服务区电子围栏后端能力

**目标:** 支持通渭县服务区边界导入、人工绘制/发布、PostGIS 校验和订单起终点围栏判断。

**Files:**

- Create: `apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceAreaCommandService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceAreaBoundaryImportService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceAreaView.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceAreaBoundaryRequest.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceArea.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/area/ServiceAreaController.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/location/PostgisServiceAreaLocationChecker.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderService.java`
- Create: `apps/api/src/main/resources/db/migration/V7__enhance_service_area_boundaries.sql`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/area/ServiceAreaCommandServiceTest.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/order/RideOrderServiceAreaValidationTest.java`

**Interfaces:**

- `POST /api/service-areas/import-district-boundary`：输入 `keyword=通渭县`，从高德行政区接口导入边界草稿。
- `PUT /api/service-areas/{id}/boundary`：保存前端绘制边界。
- `POST /api/service-areas/{id}/publish`：发布启用版本。
- `POST /api/service-areas/{id}/contains`：判断坐标是否在围栏内。

**Steps:**

- [ ] 写服务区边界保存测试：合法 Polygon 可保存，非闭合 Polygon、少于 3 个点、经纬度越界会返回中文错误。
- [ ] 写订单校验测试：起点在区内终点区外时订单创建失败；起终点均在区内时创建成功。
- [ ] 新增迁移，补充服务区字段：`boundary_source`、`boundary_version`、`published_at`、`updated_at`、`coordinate_system`。
- [ ] 实现 `ServiceAreaCommandService`，统一处理 WKT/GeoJSON 转换、边界版本和发布状态。
- [ ] 实现 `ServiceAreaBoundaryImportService`，调用高德 district API，`keywords=通渭县`、`extensions=all`、`subdistrict=0`。
- [ ] 扩展 `PostgisServiceAreaLocationChecker`，返回 `inside`, `serviceAreaId`, `distanceToBoundaryMeters`。
- [ ] 在订单创建流程加入服务区校验：普通调度员录入区外起终点时阻止提交并返回中文提示。
- [ ] 服务区外车辆位置仍按既有规则保存并告警，不与订单起终点规则混淆。
- [ ] 对服务区导入、发布、订单区外拒绝写审计日志。
- [ ] 运行 `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=ServiceAreaCommandServiceTest,RideOrderServiceAreaValidationTest test`。
- [ ] 审阅检查点：确认服务区事实源是本系统数据库，不依赖前端临时图形判断。

---

## Task 4: 服务区电子围栏前端绘制与发布

**目标:** 在资源配置页提供通渭县服务区地图展示、行政区边界导入、人工绘制/编辑、保存草稿和发布启用流程。

**Files:**

- Create: `apps/admin-web/src/api/map.ts`
- Create: `apps/admin-web/src/components/ServiceAreaMapEditor.vue`
- Create: `apps/admin-web/src/components/service-area-map-editor.test.ts`
- Modify: `apps/admin-web/src/api/resources.ts`
- Modify: `apps/admin-web/src/api/types.ts`
- Modify: `apps/admin-web/src/pages/ResourcesPage.vue`
- Modify: `apps/admin-web/src/maps/amapLoader.ts`

**Interfaces:**

- Consumes Task 3 service area endpoints.
- Produces `ServiceAreaMapEditor` props: `serviceArea`, `readonly`, `amapEnabled`。
- Emits `save-boundary`, `publish`, `import-district`。

**Steps:**

- [ ] 写组件测试：无高德 Key 时显示“地图不可用，可粘贴边界数据或稍后配置高德 Key”，页面不崩溃。
- [ ] 写组件测试：点击“导入通渭县边界”后调用后端 import 接口，并在返回后显示草稿状态。
- [ ] 写组件测试：保存边界成功后显示“服务区草稿已保存”，发布成功后显示“已启用”。
- [ ] 实现 `api/map.ts` 和 `resources.ts` 的服务区边界 API 客户端。
- [ ] 实现 `ServiceAreaMapEditor.vue`：高德可用时加载底图、Polygon、编辑控件；不可用时显示降级输入区域。
- [ ] `ResourcesPage.vue` 增加“服务区电子围栏”分区，默认展示通渭县试点服务区。
- [ ] 地图上明确显示坐标系 `GCJ-02` 和当前版本号。
- [ ] 发布前提示：发布后订单录入将按该边界校验起终点。
- [ ] 运行 `npm.cmd --prefix apps/admin-web run test -- service-area-map-editor`。
- [ ] 审阅检查点：确认前端只是编辑和呈现，后端仍做最终校验。

---

## Task 5: 订单录入地址搜索、坐标解析与围栏反馈

**目标:** 调度员录入即时需求时，支持地址输入提示、自动坐标解析、地图点选、虚拟站点候选和服务区内外即时反馈。

**Files:**

- Create: `apps/admin-web/src/components/AddressCoordinateField.vue`
- Create: `apps/admin-web/src/components/address-coordinate-field.test.ts`
- Modify: `apps/admin-web/src/components/OrderCreateDialog.vue`
- Modify: `apps/admin-web/src/components/order-create-dialog.test.ts`
- Modify: `apps/admin-web/src/api/orders.ts`
- Modify: `apps/admin-web/src/api/types.ts`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrder.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderController.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/order/RideOrderService.java`
- Create: `apps/api/src/main/resources/db/migration/V9__add_order_address_fields.sql`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/order/RideOrderAddressApiTest.java`

**Interfaces:**

- Order create request adds:
  - `originAddress`, `originLng`, `originLat`, `originVirtualStopId?`
  - `destinationAddress`, `destinationLng`, `destinationLat`, `destinationVirtualStopId?`
  - `coordinateSystem=GCJ02`
- Backend response includes standardized addresses and service area validation result.

**Steps:**

- [ ] 写后端 API 测试：用地址和坐标创建订单成功，响应保留标准化地址。
- [ ] 写后端 API 测试：起点或终点区外返回中文错误，不创建订单。
- [ ] 新增订单地址字段迁移：起点标准化地址、终点标准化地址、坐标系、地址来源。
- [ ] 扩展订单创建 DTO 和实体，保持旧经纬度字段兼容。
- [ ] 实现 `AddressCoordinateField.vue`，输入关键词后调用 `/api/map/address-suggestions`。
- [ ] 支持选择建议项、地图点选、手动经纬度录入三种方式。
- [ ] 选择地址后调用 `/api/service-areas/{id}/contains`，在表单内显示“服务区内/服务区外”。
- [ ] 匹配最近虚拟站点，显示“推荐上车点/下车点”，允许调度员确认或改选。
- [ ] 高德失败时保留表单内容，提示“地图服务暂不可用，可手工输入经纬度或选择虚拟站点”。
- [ ] 运行 `npm.cmd --prefix apps/admin-web run test -- address-coordinate-field order-create-dialog`。
- [ ] 运行 `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=RideOrderAddressApiTest test`。
- [ ] 审阅检查点：调度员可以不用看原始经纬度也能完成即时需求录入。

---

## Task 6: 路径、距离、ETA 与调度编排接入

**目标:** 调度匹配时使用高德驾车距离/时间评估车辆到上车点、上车点到目的地的 ETA，并在高德不可用时进入可解释降级。

**Files:**

- Create: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/TravelEstimateService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/TravelEstimate.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/CandidateTaskAssembler.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchOrchestrator.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/dispatch/DispatchDecision.java`
- Create: `apps/api/src/main/resources/db/migration/V10__add_dispatch_map_estimates.sql`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/TravelEstimateServiceTest.java`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/dispatch/DispatchOrchestratorMapEstimateTest.java`

**Interfaces:**

- `TravelEstimateService.estimateVehicleToPickup(vehicleId, pickupCoordinate): TravelEstimate`
- `TravelEstimateService.estimatePickupToDestination(pickup, destination): TravelEstimate`
- `TravelEstimate` fields: `distanceMeters`, `durationSeconds`, `provider`, `degraded`, `degradedReason`。

**Steps:**

- [ ] 写 `TravelEstimateServiceTest`：高德可用时返回驾车距离和预计时间。
- [ ] 写降级测试：高德禁用/超时后使用 PostGIS 直线距离估算，并标记 `degraded=true`。
- [ ] 实现 `TravelEstimateService`，集中调用 Task 2 的 `RoutePlanningProvider`。
- [ ] 加入轻量缓存，缓存键为起终点坐标四舍五入到 5 位小数和请求类型，降低免费配额压力。
- [ ] 扩展 `DispatchDecision` 保存 ETA 来源、是否降级、降级原因。
- [ ] 调整 `CandidateTaskAssembler`，候选车辆优先使用车辆最新位置快照，没有快照时进入人工复核。
- [ ] 调度决策说明里展示车辆到上车点预计时间、上车点到目的地预计时间、是否地图降级。
- [ ] 高德不可用时不自动派单，默认进入人工复核，避免用粗估距离误派。
- [ ] 运行 `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=TravelEstimateServiceTest,DispatchOrchestratorMapEstimateTest test`。
- [ ] 审阅检查点：确认免费配额保护存在，且地图降级不会伪装成精确 ETA。

---

## Task 7: 虚拟站点数据模型、导入、编辑与地图展示

**目标:** 支持通渭县 30-50 个虚拟站点的采集模板、CSV/Excel 导入、手动新增、编辑启停、列表筛选和地图展示。

**Files:**

- Create: `apps/api/src/main/java/com/idavy/drtops/domain/area/VirtualStopCommandService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/area/VirtualStopImportService.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/area/VirtualStopImportResult.java`
- Create: `apps/api/src/main/java/com/idavy/drtops/domain/area/VirtualStopView.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/area/VirtualStop.java`
- Modify: `apps/api/src/main/java/com/idavy/drtops/domain/area/VirtualStopController.java`
- Create: `apps/api/src/main/resources/db/migration/V11__enhance_virtual_stops_for_pilot.sql`
- Modify: `apps/admin-web/src/api/resources.ts`
- Modify: `apps/admin-web/src/api/types.ts`
- Modify: `apps/admin-web/src/components/VirtualStopTable.vue`
- Create: `apps/admin-web/src/components/VirtualStopMap.vue`
- Create: `apps/admin-web/src/components/VirtualStopImportPanel.vue`
- Modify: `apps/admin-web/src/pages/ResourcesPage.vue`
- Create: `docs/pilot/tongwei-virtual-stops-template.csv`
- Test: `apps/api/src/test/java/com/idavy/drtops/domain/area/VirtualStopImportServiceTest.java`
- Test: `apps/admin-web/src/components/virtual-stop-import-panel.test.ts`
- Test: `apps/admin-web/src/components/virtual-stop-map.test.ts`

**Interfaces:**

- `POST /api/virtual-stops/import` accepts CSV first and XLSX when Apache POI dependency is approved in this task.
- `PUT /api/virtual-stops/{id}` edits name, address, coordinate, enabled, boarding/alighting flags.
- `GET /api/virtual-stops?serviceAreaId=&enabled=&keyword=` filters list.
- Import template columns: `站点名称,地址,经度,纬度,所属区域,服务半径米,允许上车,允许下车,安全说明`。

**Steps:**

- [ ] 写导入服务测试：合法 CSV 3 行导入成功，返回新增数量、跳过数量、错误明细。
- [ ] 写导入失败测试：缺少站点名称、经纬度越界、重复站点名、服务区外坐标均返回行号和中文错误。
- [ ] 新增迁移，补充字段：`address`、`area_name`、`coordinate_system`、`source`、`verified_at`、`verified_by`、`updated_at`。
- [ ] 实现 `VirtualStopCommandService`，统一新增/编辑/启停校验。
- [ ] 实现 `VirtualStopImportService`，CSV 必做；XLSX 通过 Apache POI 支持，新增依赖需在 PR 中单独说明。
- [ ] 导入时自动调用服务区校验，区外站点允许暂存为未启用并给出告警。
- [ ] 新增导入模板 `docs/pilot/tongwei-virtual-stops-template.csv`，填 5 行示例数据，明确示例不是实际采集结果。
- [ ] 前端 `VirtualStopImportPanel` 支持上传、预校验结果展示、错误行下载。
- [ ] `VirtualStopTable` 增加区域、状态、关键词筛选和启用/停用操作。
- [ ] `VirtualStopMap` 在高德地图上展示虚拟站点，支持地图点选新增和拖动修正坐标。
- [ ] 站点新增/编辑时支持高德地址搜索转坐标，也支持手动经纬度降级。
- [ ] 运行 `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api -Dtest=VirtualStopImportServiceTest test`。
- [ ] 运行 `npm.cmd --prefix apps/admin-web run test -- virtual-stop-import-panel virtual-stop-map`。
- [ ] 审阅检查点：确认试点 30-50 个站点可以批量导入、地图核验、启用发布。

---

## Task 8: 调度工作台地图升级

**目标:** 调度工作台展示服务区、虚拟站点、任务路线、车辆最新位置和人工位置链路，并保持无高德 Key 时的可用降级。

**Files:**

- Modify: `apps/admin-web/src/components/DispatchMap.vue`
- Modify: `apps/admin-web/src/components/dispatch-map.test.ts`
- Modify: `apps/admin-web/src/pages/DispatchWorkbenchPage.vue`
- Modify: `apps/admin-web/src/api/vehicleLocations.ts`
- Modify: `apps/admin-web/src/api/resources.ts`

**Interfaces:**

- Consumes latest vehicle snapshots, virtual stops, service area boundary, task stops and route estimates.
- Produces map layers: service area polygon, virtual stop markers, vehicle markers, selected task route, manual location chain.

**Steps:**

- [ ] 写 `dispatch-map.test.ts`：无高德 Key 时显示静态运营态摘要，不报错。
- [ ] 写地图层测试：服务区、站点、车辆位置、任务路线四类图层可独立开关。
- [ ] 实现高德地图加载后绘制服务区 Polygon。
- [ ] 绘制虚拟站点 Marker，区分启用、停用、上车/下车能力。
- [ ] 绘制车辆 Marker，展示车牌、任务状态、最后反馈时间、位置来源“人工上报”。
- [ ] 每 15 秒轮询最新快照；超过 30 分钟未更新显示告警。
- [ ] 选中任务后绘制路线；若高德路线不可用，用虚线连接离散任务节点。
- [ ] 地图请求失败时保留已有页面数据，不提前移动车辆标记。
- [ ] 运行 `npm.cmd --prefix apps/admin-web run test -- dispatch-map dispatch-workbench`。
- [ ] 审阅检查点：确认工作台地图能支撑调度员“看服务区、看站点、看车辆、看任务”的试点需求。

---

## Task 9: 试点采集流程、运行手册与全链路验收

**目标:** 固化通渭县试点前准备清单、虚拟站点采集流程、高德免费配额检查、故障降级手册和本机验收脚本。

**Files:**

- Create: `docs/pilot/tongwei-amap-virtual-stop-readiness.md`
- Create: `docs/pilot/tongwei-virtual-stop-collection-guide.md`
- Create: `docs/pilot/tongwei-map-quota-checklist.md`
- Create: `docs/pilot/tongwei-map-and-stop-acceptance-record.md`
- Modify: `README.md`

**Acceptance Flow:**

1. 配置本机高德 Web Service Key 和 JS API Key。
2. 导入通渭县行政区边界，人工校验服务区范围并发布。
3. 上传虚拟站点模板，导入 30-50 个站点。
4. 在地图上抽查医院、学校、小区、公交站等高频点位。
5. 用地址搜索录入一条即时需求，确认起终点在服务区内。
6. 触发调度，检查车辆到上车点 ETA、上车点到目的地 ETA。
7. 在车辆任务页完成发车、到站、上车、到站、完成，检查位置链路。
8. 关闭高德配置后重启，验证手工经纬度/虚拟站点方式仍能录入。
9. 查看审计日志，确认服务区发布、站点导入、订单创建、任务位置动作均可追溯。

**Steps:**

- [ ] 写试点准备文档，列出高德 Key 申请、域名白名单、安全密钥、服务端环境变量。
- [ ] 写虚拟站点采集指南，覆盖站点来源、字段口径、现场校验和发布流程。
- [ ] 写免费配额检查清单，要求在高德控制台确认 Web 服务和 JS API 的当日调用限制、QPS 和告警方式。
- [ ] 写验收记录模板，按 Acceptance Flow 逐项记录结果、截图、发现问题、试点前风险。
- [ ] README 增加“地图与虚拟站点试点准备”入口。
- [ ] 本机运行后端全量测试：`.\.tools\apache-maven-3.9.11\bin\mvn.cmd -q -pl apps/api test`。
- [ ] 本机运行前端测试：`npm.cmd --prefix apps/admin-web run test`。
- [ ] 本机运行前端构建：`npm.cmd --prefix apps/admin-web run build`。
- [ ] 审阅检查点：确认即使没有真实高德 Key，也有清晰的配置、降级、验收和风险说明。

---

## 子代理执行顺序

1. Task 1：高德配置与地图能力抽象。
2. Task 2：高德 Web Service API 适配层。
3. Task 3：服务区电子围栏后端能力。
4. Task 4：服务区电子围栏前端绘制与发布。
5. Task 5：订单录入地址搜索、坐标解析与围栏反馈。
6. Task 6：路径、距离、ETA 与调度编排接入。
7. Task 7：虚拟站点数据模型、导入、编辑与地图展示。
8. Task 8：调度工作台地图升级。
9. Task 9：试点采集流程、运行手册与全链路验收。

每个任务完成后必须：

- 提交独立 commit。
- 报告涉及文件、测试命令和测试结果。
- 进行任务级代码审阅。
- 更新 `.superpowers/sdd/progress.md`。
- 等待用户审阅通过后再进入下一任务。

---

## 已知风险与处理策略

- **免费配额风险:** 高德免费/基础配额以控制台为准，开发时必须加入降级、缓存和调用量监控；试点前必须完成配额检查。
- **坐标系风险:** 高德返回 GCJ-02，系统字段必须明确标记，避免与 WGS-84/GPS 坐标混用。
- **行政区边界精度风险:** 高德行政区边界适合作为初始草稿，最终服务区仍需公交企业人工修正和发布。
- **地图服务不可用风险:** 地图不可用不得阻断试点主流程，必须保留虚拟站点和经纬度手工录入。
- **虚拟站点质量风险:** 站点导入不是一次性技术动作，需要人工校验医院、学校、小区、公交站等高频点位。
- **调度精度风险:** 试点期间车辆位置为人工节点更新，ETA 只能作为辅助参考，不能作为最终准点率硬依据。
- **密钥泄露风险:** 所有 Key 只允许环境变量注入，测试和文档使用占位符。

---

## Plan Self-Review

- Spec coverage: 已覆盖高德 API 接入、电子围栏、地址搜索、地理编码、驾车路径、距离/ETA、车辆和任务地图展示、虚拟站点批量导入、手动新增、编辑、筛选和地图展示。
- Placeholder scan: 未使用占位表达；每个任务均有明确文件、接口、步骤、测试和审阅检查点。
- Type consistency: 坐标字段统一使用 longitude/latitude 或 lng/lat 输入，业务 DTO 明确 `coordinateSystem=GCJ02`；服务区校验和虚拟站点校验均由后端负责。
