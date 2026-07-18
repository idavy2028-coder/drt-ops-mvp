# 开放瓦片地图迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 Leaflet 与合规开放瓦片替换管理端高德 JS 地图依赖，使通渭县试点无需地图 Key 仍可完成围栏、站点、订单选点和调度工作台地图操作。

**Architecture:** 前端新增 Leaflet 运行时、开放瓦片配置和 GCJ-02/WGS84 转换边界。所有 API、WKT、虚拟站点和车辆位置仍以 GCJ-02 读写；仅在地图渲染和点击交互边界转换为 WGS84。后端高德 Web Service 适配器保持默认关闭，未来可选，不参与本计划的前端运行路径。

**Tech Stack:** Vue 3、TypeScript、Leaflet 1.9.4、@geoman-io/leaflet-geoman-free 2.20.0、Vitest、Vite。

## Global Constraints

- 默认瓦片仅使用 `https://tile.openstreetmap.org/{z}/{x}/{y}.png`，显示 `© OpenStreetMap contributors` 归属。
- 不实现瓦片预取、离线下载、服务端代理、批量缓存或地址内容上传。
- `VITE_TILE_URL`、`VITE_TILE_ATTRIBUTION`、`VITE_TILE_MAX_ZOOM` 可覆盖默认值；无地图 Key 配置。
- 业务接口、数据库 WKT 和经纬度字段保持 GCJ-02；Leaflet 画布统一使用 WGS84/Web Mercator。
- 瓦片失败不得阻断服务区 WKT 输入、虚拟站点/订单手工坐标、车辆位置和任务状态操作。
- 高德后端配置 `drt.map.amap` 保留但默认禁用；前端不得继续依赖 `VITE_AMAP_*`。
- 每项任务结束前运行对应测试；提交时不包含 `.vite`、前后端运行日志或任何密钥。

---

## 文件结构

- `apps/admin-web/src/maps/coordinateTransform.ts`：GCJ-02/WGS84 双向转换和中国境内边界判断。
- `apps/admin-web/src/maps/tileMapRuntime.ts`：Leaflet 初始化、可配置瓦片层、归属、底图错误事件和公共覆盖物辅助函数。
- `apps/admin-web/src/maps/tileMapTypes.ts`：地图坐标和运行状态类型。
- `apps/admin-web/src/components/ServiceAreaMapEditor.vue`：Leaflet-Geoman 围栏绘制与编辑。
- `apps/admin-web/src/components/VirtualStopMap.vue`：虚拟站点渲染与点击选点。
- `apps/admin-web/src/components/AddressCoordinateField.vue`：地址文本、虚拟站点、坐标输入和 Leaflet 选点。
- `apps/admin-web/src/components/DispatchMap.vue`：调度工作台的服务区、站点、任务和人工位置图层。
- `apps/admin-web/src/pages/ResourcesPage.vue`、`apps/admin-web/src/pages/DispatchWorkbenchPage.vue`：移除前端高德开关与地址搜索入口。
- `apps/admin-web/src/maps/amapLoader.ts`、`apps/admin-web/src/maps/amapTypes.ts`：在最终清理任务删除。

---

### Task 1: 开放瓦片基础层与坐标转换

**Files:**
- Create: `apps/admin-web/src/maps/coordinateTransform.ts`
- Create: `apps/admin-web/src/maps/coordinate-transform.test.ts`
- Create: `apps/admin-web/src/maps/tileMapRuntime.ts`
- Create: `apps/admin-web/src/maps/tile-map-runtime.test.ts`
- Create: `apps/admin-web/src/maps/tileMapTypes.ts`
- Modify: `apps/admin-web/package.json`
- Modify: `apps/admin-web/package-lock.json`

**Interfaces:**
- Produces `gcj02ToWgs84(point: GeoPoint): GeoPoint` and `wgs84ToGcj02(point: GeoPoint): GeoPoint`.
- Produces `createTileMap(container: HTMLElement, centerGcj02: GeoPoint, zoom: number): TileMapHandle`.
- `TileMapHandle` exposes `map`, `baseLayerFailed`, `destroy()`, `fitLayers(layers)` and `onClick(listener: (gcj02: GeoPoint) => void)`.

- [ ] **Step 1: 安装确定版本的地图依赖**

运行：

```powershell
npm.cmd --prefix apps/admin-web install leaflet@1.9.4 @geoman-io/leaflet-geoman-free@2.20.0
npm.cmd --prefix apps/admin-web install -D @types/leaflet@1.9.21
```

预期：`package.json` 同时声明 Leaflet、Geoman 和 Leaflet 类型，不安装高德 SDK。

- [ ] **Step 2: 写坐标转换失败测试**

```ts
it("converts Tongwei GCJ-02 points to WGS84 and back", () => {
  const gcj = { longitude: 105.2421, latitude: 35.2103 };
  const wgs = gcj02ToWgs84(gcj);

  expect(wgs).not.toEqual(gcj);
  expect(wgs84ToGcj02(wgs).longitude).toBeCloseTo(gcj.longitude, 5);
  expect(wgs84ToGcj02(wgs).latitude).toBeCloseTo(gcj.latitude, 5);
});

it("keeps coordinates outside China unchanged", () => {
  expect(gcj02ToWgs84({ longitude: 2.3522, latitude: 48.8566 })).toEqual({ longitude: 2.3522, latitude: 48.8566 });
});
```

- [ ] **Step 3: 运行测试确认失败**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- coordinate-transform
```

预期：FAIL，提示无法解析 `coordinateTransform`。

- [ ] **Step 4: 实现坐标转换和运行时**

```ts
export interface GeoPoint { longitude: number; latitude: number; }

export function toLeafletLatLng(point: GeoPoint): [number, number] {
  const wgs = gcj02ToWgs84(point);
  return [wgs.latitude, wgs.longitude];
}

export function fromLeafletLatLng(latitude: number, longitude: number): GeoPoint {
  return wgs84ToGcj02({ longitude, latitude });
}
```

`tileMapRuntime.ts` 必须：加载 `leaflet.css` 和 Geoman CSS；使用 `VITE_TILE_URL ?? "https://tile.openstreetmap.org/{z}/{x}/{y}.png"`；设置默认归属；监听 `tileerror` 并将 `baseLayerFailed` 置为 `true`；不发起预取或缓存请求。

- [ ] **Step 5: 写运行时测试并验证通过**

```ts
it("uses the configurable tile URL and visible OSM attribution", () => {
  const handle = createTileMap(container, { longitude: 105.2421, latitude: 35.2103 }, 12);

  expect(mockTileLayer).toHaveBeenCalledWith(
    "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    expect.objectContaining({ attribution: expect.stringContaining("OpenStreetMap") })
  );
  handle.destroy();
});
```

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- coordinate-transform tile-map-runtime
npm.cmd --prefix apps/admin-web run typecheck
```

预期：两个测试文件通过，类型检查通过。

- [ ] **Step 6: 提交任务**

```powershell
git add apps/admin-web/package.json apps/admin-web/package-lock.json apps/admin-web/src/maps
git commit -m "feat: add open tile map foundation"
```

---

### Task 2: 服务区电子围栏 Leaflet 绘制与编辑

**Files:**
- Modify: `apps/admin-web/src/components/ServiceAreaMapEditor.vue`
- Modify: `apps/admin-web/src/components/service-area-map-editor.test.ts`

**Interfaces:**
- Consumes `createTileMap`, `toLeafletLatLng`, `fromLeafletLatLng` and现有 `ServiceAreaBoundaryDraft`。
- Produces现有事件 `import-district`、`save-boundary`、`publish`，其中 `save-boundary.boundaryWkt` 坐标恒为 GCJ-02。

- [ ] **Step 1: 写 Leaflet 绘制坐标转换失败测试**

```ts
it("converts a Leaflet drawn WGS84 polygon back to GCJ-02 WKT", async () => {
  render(ServiceAreaMapEditor, { props: { serviceArea, readonly: false } });
  emitGeomanCreate([[35.2103, 105.2421], [35.2203, 105.2421], [35.2103, 105.2521]]);
  await fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));

  expect(emitted()["save-boundary"][0][0].boundaryWkt).toContain("105.24");
});
```

- [ ] **Step 2: 运行测试确认失败**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- service-area-map-editor
```

预期：FAIL，原组件仍要求 `amapEnabled` 或无法响应 Geoman 事件。

- [ ] **Step 3: 替换围栏地图实现**

```ts
const tileMap = createTileMap(mapContainer.value, { longitude: 105.24, latitude: 35.21 }, 11);
tileMap.map.pm.addControls({ position: "topleft", drawMarker: false, drawPolyline: false, drawCircle: false });
tileMap.map.on("pm:create", ({ layer }) => updateBoundaryFromLeaflet(layer.getLatLngs()[0]));
tileMap.map.on("pm:edit", ({ layer }) => updateBoundaryFromLeaflet(layer.getLatLngs()[0]));
```

将既有 WKT 在渲染前逐点 `toLeafletLatLng`；`updateBoundaryFromLeaflet` 逐点 `fromLeafletLatLng`，闭合后生成现有 WKT。移除 `amapEnabled` 属性、AMap 类型和“配置高德 Key”提示；瓦片错误时保留围栏绘制、文本录入和发布按钮，并显示“开放底图暂不可用”。

- [ ] **Step 4: 验证通过**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- service-area-map-editor
npm.cmd --prefix apps/admin-web run typecheck
```

预期：现有 WKT 导入、保存、发布测试及新增绘制转换测试全部通过。

- [ ] **Step 5: 提交任务**

```powershell
git add apps/admin-web/src/components/ServiceAreaMapEditor.vue apps/admin-web/src/components/service-area-map-editor.test.ts
git commit -m "feat: draw service areas on open tiles"
```

---

### Task 3: 虚拟站点地图选点迁移

**Files:**
- Modify: `apps/admin-web/src/components/VirtualStopMap.vue`
- Modify: `apps/admin-web/src/components/virtual-stop-map.test.ts`
- Modify: `apps/admin-web/src/pages/ResourcesPage.vue`
- Modify: `apps/admin-web/src/pages/resources-page.test.ts`

**Interfaces:**
- Consumes `createTileMap`、坐标转换和 `VirtualStop`。
- Produces `pick(longitude: number, latitude: number)`，参数为 GCJ-02。

- [ ] **Step 1: 写地图点击返回 GCJ-02 的失败测试**

```ts
it("emits GCJ-02 coordinates when an operator clicks the open tile map", () => {
  const { emitted } = render(VirtualStopMap, { props: { stops: [], readonly: false } });
  emitLeafletClick(35.2103, 105.2421);

  expect(emitted().pick[0][0]).not.toBe(105.2421);
  expect(emitted().pick[0][1]).not.toBe(35.2103);
});
```

- [ ] **Step 2: 运行测试确认失败**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- virtual-stop-map resources-page
```

预期：FAIL，原组件在无 `amapEnabled` 时只显示静态提示。

- [ ] **Step 3: 实现站点图层与选点**

```ts
const handle = createTileMap(container.value, { longitude: 105.2421, latitude: 35.2103 }, 13);
handle.onClick((point) => { if (!props.readonly) emit("pick", point.longitude, point.latitude); });

for (const stop of props.stops.filter(hasCoordinates)) {
  L.marker(toLeafletLatLng({ longitude: Number(stop.longitude), latitude: Number(stop.latitude) }))
    .bindTooltip(`${stop.name} · ${stop.enabled ? "已启用" : "未启用"}`)
    .addTo(handle.map);
}
```

移除 `amapEnabled` 属性和页面计算值。`ResourcesPage` 的地图点选仍写入既有 `stopDraft.longitude/latitude`，不改变保存 API。

- [ ] **Step 4: 验证通过**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- virtual-stop-map resources-page
npm.cmd --prefix apps/admin-web run typecheck
```

预期：地图选点、站点编辑和资源页测试通过。

- [ ] **Step 5: 提交任务**

```powershell
git add apps/admin-web/src/components/VirtualStopMap.vue apps/admin-web/src/components/virtual-stop-map.test.ts apps/admin-web/src/pages/ResourcesPage.vue apps/admin-web/src/pages/resources-page.test.ts
git commit -m "feat: select virtual stops on open tiles"
```

---

### Task 4: 订单地址坐标字段去高德化

**Files:**
- Modify: `apps/admin-web/src/components/AddressCoordinateField.vue`
- Modify: `apps/admin-web/src/components/address-coordinate-field.test.ts`
- Modify: `apps/admin-web/src/components/OrderCreateDialog.vue`
- Modify: `apps/admin-web/src/components/order-create-dialog.test.ts`
- Modify: `apps/admin-web/src/pages/ResourcesPage.vue`
- Modify: `apps/admin-web/src/pages/resources-page.test.ts`

**Interfaces:**
- Consumes现有 `checkServiceAreaContainment` 与 `VirtualStop`。
- Produces原有 `AddressCoordinateValue`，其中地图选点值为 GCJ-02；不再调用 `searchAddressSuggestions`。

- [ ] **Step 1: 写无地址搜索服务的失败测试**

```ts
it("keeps address text, virtual-stop selection and map picking without address suggestions", async () => {
  render(AddressCoordinateField, { props: { label: "上车点", purpose: "BOARDING", modelValue: emptyValue, virtualStops: [stop] } });
  await fireEvent.update(screen.getByLabelText("上车点地址"), "通渭县人民医院北门");

  expect(mapApi.searchAddressSuggestions).not.toHaveBeenCalled();
  expect(screen.getByRole("button", { name: "地图选点" })).toBeEnabled();
});
```

- [ ] **Step 2: 运行测试确认失败**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- address-coordinate-field order-create-dialog resources-page
```

预期：FAIL，旧实现仍在输入时调用地址提示 API 或渲染高德地图。

- [ ] **Step 3: 移除公开地址联想并接入 Leaflet 选点**

```ts
function onAddressInput(): void {
  update({ address: keyword.value, virtualStopId: undefined });
}

function applyMapPoint(point: GeoPoint): void {
  update({ address: keyword.value, longitude: point.longitude, latitude: point.latitude, virtualStopId: undefined });
  mapOpen.value = false;
}
```

删除 `searchAddressSuggestions`、建议列表和资源页站点地址“搜索”按钮。保留地址文本、手工经纬度、虚拟站点选择、服务区即时校验和地图选点。地图瓦片失败时，选点面板改为中文提示并继续允许手工坐标录入。

- [ ] **Step 4: 验证通过**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- address-coordinate-field order-create-dialog resources-page
npm.cmd --prefix apps/admin-web run typecheck
```

预期：订单起终点、围栏提示和手工/站点录入测试通过，测试中不再 mock 地址提示 API。

- [ ] **Step 5: 提交任务**

```powershell
git add apps/admin-web/src/components/AddressCoordinateField.vue apps/admin-web/src/components/address-coordinate-field.test.ts apps/admin-web/src/components/OrderCreateDialog.vue apps/admin-web/src/components/order-create-dialog.test.ts apps/admin-web/src/pages/ResourcesPage.vue apps/admin-web/src/pages/resources-page.test.ts
git commit -m "feat: use open tiles for order coordinate picking"
```

---

### Task 5: 调度工作台开放瓦片图层

**Files:**
- Modify: `apps/admin-web/src/components/DispatchMap.vue`
- Modify: `apps/admin-web/src/components/dispatch-map.test.ts`
- Modify: `apps/admin-web/src/pages/DispatchWorkbenchPage.vue`
- Modify: `apps/admin-web/src/pages/dispatch-workbench.test.ts`

**Interfaces:**
- Consumes `ServiceArea`、`VirtualStop[]`、`VehicleLocationSnapshotItem[]`、`VehicleLocationEventView[]`、`VehicleTask`。
- Produces不变的服务区、虚拟站点、任务路线、车辆位置四个图层控制；输入均为 GCJ-02。

- [ ] **Step 1: 写工作台真实底图失败降级测试**

```ts
it("keeps service area, stops and manual location cards visible when the tile layer errors", async () => {
  render(DispatchMap, { props: dispatchMapProps });
  emitTileError();

  expect(screen.getByText("开放底图暂不可用")).toBeInTheDocument();
  expect(screen.getByLabelText("车辆位置 甘E-T001")).toBeInTheDocument();
  expect(screen.getByText("县医院北门")).toBeInTheDocument();
});
```

- [ ] **Step 2: 运行测试确认失败**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- dispatch-map dispatch-workbench
```

预期：FAIL，原组件仍依赖 `loadAmap` 或 `amapEnabled`。

- [ ] **Step 3: 实现四层 Leaflet 覆盖物**

```ts
if (layers.value.serviceArea && props.serviceArea?.boundary) {
  polygon = L.polygon(parseWkt(props.serviceArea.boundary).map(toLeafletLatLng), serviceAreaStyle).addTo(map);
}
if (layers.value.route) {
  L.polyline(selectedTaskStops.value.map(stopToLeafletLatLng), { dashArray: "8 6" }).addTo(map);
  L.polyline(eventChainPoints.value.map(toLeafletLatLng), { dashArray: "4 8" }).addTo(map);
}
```

移除 `amapEnabled` 属性和所有 AMap 构造器。车辆标记保留车牌、任务状态、最后反馈时间和“人工上报”；图层开关在瓦片成功或失败时都可独立工作。`DispatchWorkbenchPage` 删除 `VITE_AMAP_ENABLED` 计算和对应传参，保留 15 秒快照轮询。

- [ ] **Step 4: 验证通过**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- dispatch-map dispatch-workbench
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run build
```

预期：工作台地图、任务选择、轮询错误保留上次快照和生产构建全部通过。

- [ ] **Step 5: 提交任务**

```powershell
git add apps/admin-web/src/components/DispatchMap.vue apps/admin-web/src/components/dispatch-map.test.ts apps/admin-web/src/pages/DispatchWorkbenchPage.vue apps/admin-web/src/pages/dispatch-workbench.test.ts
git commit -m "feat: render dispatch layers on open tiles"
```

---

### Task 6: 清理前端高德依赖与试点验收

**Files:**
- Delete: `apps/admin-web/src/maps/amapLoader.ts`
- Delete: `apps/admin-web/src/maps/amapTypes.ts`
- Delete: `apps/admin-web/src/maps/amap-loader.test.ts`
- Modify: `apps/admin-web/package.json`
- Modify: `apps/admin-web/package-lock.json`
- Modify: `README.md`
- Modify: `docs/pilot/tongwei-amap-virtual-stop-readiness.md`
- Modify: `docs/pilot/tongwei-map-quota-checklist.md`
- Modify: `docs/pilot/tongwei-map-and-stop-acceptance-record.md`
- Modify: `apps/admin-web/src/**/*.test.ts`（仅移除 AMap/MapLibre mock 与更新断言）

**Interfaces:**
- Produces无 `VITE_AMAP_*` 前端必填变量的管理端构建产物。
- Produces试点验收记录中的“开放瓦片归属、无预取、底图失败降级、坐标转换”检查项。

- [ ] **Step 1: 写前端依赖清理失败测试**

```ts
it("does not require AMap environment variables to render map-enabled pages", async () => {
  vi.stubEnv("VITE_AMAP_ENABLED", "");
  render(ResourcesPage, { global: { plugins: [router] } });

  expect(screen.queryByText(/配置高德 Key/)).not.toBeInTheDocument();
});
```

- [ ] **Step 2: 运行测试确认失败**

运行：

```powershell
npm.cmd --prefix apps/admin-web run test -- amap-loader service-area-map-editor virtual-stop-map dispatch-map
```

预期：FAIL，因为旧测试与旧文件仍存在，或页面仍显示高德 Key 提示。

- [ ] **Step 3: 删除旧前端依赖并更新文档**

```powershell
npm.cmd --prefix apps/admin-web uninstall maplibre-gl
```

删除 AMap Loader、类型和测试，移除残留的 MapLibre mock。README 将地图配置替换为 `VITE_TILE_URL`、`VITE_TILE_ATTRIBUTION`、`VITE_TILE_MAX_ZOOM` 说明与 OSM 归属链接。试点文档将高德 Key/配额从当前阻断项移至“未来官方增强”，新增禁止预取、无 SLA 和受许可瓦片切换的验收项。

- [ ] **Step 4: 写并运行全量验证**

```powershell
rg -n "VITE_AMAP|loadAmap|window\.AMap|webrd[0-9]|appmaptile" apps/admin-web/src apps/admin-web/package.json
npm.cmd --prefix apps/admin-web run test
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run build
git diff --check
```

预期：第一条命令无匹配；前端全量测试、类型检查、构建和差异检查通过。

- [ ] **Step 5: 执行本机浏览器验收并填写记录**

```powershell
VITE_API_PROXY_TARGET=http://127.0.0.1:8081 npm.cmd --prefix apps/admin-web run dev
```

在隔离 PostGIS 与 API 已启动后，逐项验收围栏绘制、站点选点、订单选点、工作台四图层、瓦片失败降级和 GCJ-02 坐标保存；将实际结果、截图与未关闭的生产风险写入 `docs/pilot/tongwei-map-and-stop-acceptance-record.md`。

- [ ] **Step 6: 提交任务**

```powershell
git add README.md docs/pilot apps/admin-web/package.json apps/admin-web/package-lock.json apps/admin-web/src
git commit -m "feat: remove frontend amap key dependency"
```

---

## 计划自检

- 设计覆盖：Task 1 覆盖 Leaflet、瓦片配置与坐标边界；Task 2-5 覆盖四类地图入口；Task 6 覆盖清理、合规文档和验收。
- 边界一致性：所有 Leaflet 输入使用 WGS84，所有 `pick`、WKT、订单与站点 API 输出使用 GCJ-02。
- 合规：计划没有未鉴权高德瓦片、瓦片下载、预取或离线缓存步骤；默认 OSM 归属始终可见。
- 运行风险：生产 PostGIS、备份恢复和开放瓦片 SLA 不因本计划而被宣称通过，仍在最终浏览器验收记录中单列。
