# 调度工作台地图可用性修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复调度地图尺寸和图层同步问题，并交付地图主导、车辆侧栏联动的 A+C 混合工作台。

**Architecture:** 页面组件保存订单、任务和选中车辆状态；地图组件独占 Leaflet 生命周期和真实地理图层；新增车辆侧栏只负责展示和选择。地图运行时提供尺寸同步与坐标聚焦接口，避免页面直接依赖 Leaflet 细节。

**Tech Stack:** Vue 3、TypeScript、Leaflet、Vitest、Testing Library、Playwright、CSS Grid。

## Global Constraints

- 所有后端业务坐标按 GCJ-02 输入，并统一通过 `toLeafletLatLng` 转为 Leaflet 使用的 WGS84。
- 地理对象不得使用 CSS 百分比模拟位置。
- 任务号对调度员最多显示前 8 位；不得显示乘客信息或密钥。
- 普通轮询和图层刷新不得重置调度员当前视野。
- 不修改 API、数据库、位置事件或 P6-1 评估证据。
- 生产代码必须先有能够稳定复现缺陷的失败测试。

---

### Task 1: 地图运行时尺寸同步与车辆聚焦

**Files:**
- Modify: `apps/admin-web/src/maps/tileMapTypes.ts:8-16`
- Modify: `apps/admin-web/src/maps/tileMapRuntime.ts:11-62`
- Modify: `apps/admin-web/src/maps/tile-map-runtime.test.ts`

**Interfaces:**
- Consumes: `GeoPoint`（GCJ-02 经度、纬度）。
- Produces: `TileMapHandle.invalidateSize(): void`、`TileMapHandle.focusPoint(point: GeoPoint, zoom?: number): void`。

- [ ] **Step 1: 写尺寸同步失败测试**

在 `tile-map-runtime.test.ts` 中对真实运行时句柄调用：

```ts
handle.invalidateSize();
expect(map.invalidateSize).toHaveBeenCalledWith({ pan: false });
```

该测试捕获“父容器增高后 Leaflet 画布仍保留旧尺寸”的回归。

- [ ] **Step 2: 运行 RED 测试**

Run: `npm.cmd test -- src/maps/tile-map-runtime.test.ts`

Expected: FAIL，提示 `invalidateSize` 不存在。

- [ ] **Step 3: 写聚焦真实坐标失败测试**

```ts
handle.focusPoint({ longitude: 105.250351, latitude: 35.207657 }, 15);
expect(map.flyTo).toHaveBeenCalledWith(
  [expect.closeTo(35.20, 1), expect.closeTo(105.24, 1)],
  15,
  { animate: true, duration: 0.45 }
);
```

断言坐标先经过 `toLeafletLatLng`，而不是把 GCJ-02 直接传给 Leaflet。

- [ ] **Step 4: 运行第二个 RED 测试**

Run: `npm.cmd test -- src/maps/tile-map-runtime.test.ts`

Expected: FAIL，提示 `focusPoint` 不存在。

- [ ] **Step 5: 实现最小运行时接口**

在返回的 `TileMapHandle` 中增加：

```ts
invalidateSize(): void {
  map.invalidateSize({ pan: false });
},
focusPoint(point: GeoPoint, zoom = Math.max(map.getZoom(), 15)): void {
  map.flyTo(toLeafletLatLng(point), zoom, { animate: true, duration: 0.45 });
}
```

- [ ] **Step 6: 运行 GREEN 测试和类型检查**

Run: `npm.cmd test -- src/maps/tile-map-runtime.test.ts`

Expected: PASS。

Run: `npm.cmd run typecheck`

Expected: PASS。

- [ ] **Step 7: 提交运行时修复**

```powershell
git add apps/admin-web/src/maps/tileMapTypes.ts apps/admin-web/src/maps/tileMapRuntime.ts apps/admin-web/src/maps/tile-map-runtime.test.ts
git commit -m "fix: synchronize dispatch map viewport"
```

### Task 2: 真实车辆标记、状态样式和轻量浮层

**Files:**
- Modify: `apps/admin-web/src/components/DispatchMap.vue:1-198`
- Modify: `apps/admin-web/src/components/dispatch-map.test.ts`

**Interfaces:**
- Consumes: `locations`、`selectedVehicleId`、服务区、站点和任务链。
- Produces: `selectVehicle(vehicleId: string)` 事件；真实经纬度 marker；短任务号 popup。

- [ ] **Step 1: 写车辆 marker 坐标和状态失败测试**

扩展 Leaflet 测试替身，记录 `divIcon`、`marker`、`bindPopup` 和 `on`。断言：

```ts
expect(leaflet.marker).toHaveBeenCalledWith(
  [expect.closeTo(35.1909), expect.closeTo(104.6278)],
  expect.objectContaining({ icon: expect.anything(), title: "甘G-T001" })
);
expect(leaflet.divIcon).toHaveBeenCalledWith(
  expect.objectContaining({ className: expect.stringContaining("is-active") })
);
```

该测试捕获“车辆显示位置由数组索引和 CSS 百分比决定”的回归。

- [ ] **Step 2: 运行 marker RED 测试**

Run: `npm.cmd test -- src/components/dispatch-map.test.ts`

Expected: FAIL，因为当前 marker 没有状态 icon，页面仍存在绝对定位车辆卡片。

- [ ] **Step 3: 写 popup 脱敏和双向选择失败测试**

```ts
expect(vehicleMarker.bindPopup).toHaveBeenCalledWith(expect.stringContaining("任务 12345678"));
expect(vehicleMarker.bindPopup).not.toHaveBeenCalledWith(expect.stringContaining("12345678-1234-4234-8234-123456789abc"));
vehicleMarkerClick();
expect(view.emitted("selectVehicle")).toEqual([["vehicle-1"]]);
```

测试数据使用完整 UUID，期望只出现前 8 位；测试 marker 点击发出选择事件。

- [ ] **Step 4: 运行 popup RED 测试**

Run: `npm.cmd test -- src/components/dispatch-map.test.ts`

Expected: FAIL，因为当前 marker 只有 tooltip，且 DOM 卡片显示完整任务 ID。

- [ ] **Step 5: 实现地理 marker 和浮层**

在 `DispatchMap.vue`：

- 删除 `markerStyle` 和 `.vehicle-location-card` 模板/CSS。
- 为 IDLE 使用 `is-idle`，为 DISPATCHED/IN_SERVICE 使用 `is-active`，为 OFFLINE、EXCEPTION 或 `outsideServiceArea=true` 使用 `is-alert`，其他状态使用 `is-unknown`。
- 使用 `L.divIcon` 创建紧凑“车”标记。
- 使用 `escapeHtml` 后的车牌、状态、短任务号和时间构造 popup；不包含完整 UUID、地址或乘客字段。
- 保存 `Map<vehicleId, L.Marker>`，支持后续选中车辆打开 popup。

- [ ] **Step 6: 统一图层刷新与视野规则**

将 `renderMapLayers()` 调整为：

- 服务区、站点、车辆和路线全部通过 `toLeafletLatLng` 创建 Leaflet 图层。
- 首次有有效图层时调用一次 `fitLayers`。
- 轮询、图层切换和任务链刷新只更新图层，不自动 `fitLayers`。
- 增加“回到全局”按钮，点击时显式执行 `fitLayers(renderedLayers)`。

- [ ] **Step 7: 增加 ResizeObserver**

挂载地图后观察 `.dispatch-map`：

```ts
resizeObserver = new ResizeObserver(() => {
  window.requestAnimationFrame(() => tileMap.value?.invalidateSize());
});
resizeObserver.observe(mapContainer.value);
```

卸载时 `disconnect()`，避免页面切换后继续触发。

- [ ] **Step 8: 运行 GREEN 测试**

Run: `npm.cmd test -- src/components/dispatch-map.test.ts src/maps/tile-map-runtime.test.ts`

Expected: PASS。

- [ ] **Step 9: 提交地图核心修复**

```powershell
git add apps/admin-web/src/components/DispatchMap.vue apps/admin-web/src/components/dispatch-map.test.ts
git commit -m "fix: anchor vehicle markers to dispatch map"
```

### Task 3: 车辆侧栏与 A+C 三栏工作台

**Files:**
- Create: `apps/admin-web/src/components/VehicleLocationSidebar.vue`
- Create: `apps/admin-web/src/components/vehicle-location-sidebar.test.ts`
- Modify: `apps/admin-web/src/pages/DispatchWorkbenchPage.vue:1-103`
- Modify: `apps/admin-web/src/pages/dispatch-workbench.test.ts`
- Modify: `apps/admin-web/src/components/RealtimeOrderList.vue`
- Modify: `apps/admin-web/src/components/VehicleTaskList.vue`

**Interfaces:**
- Consumes: `VehicleLocationSnapshotItem[]`、`selectedVehicleId`、现有订单和任务。
- Produces: `VehicleLocationSidebar` 的 `select(vehicleId)` 事件；页面向地图传递 `selectedVehicleId`。

- [ ] **Step 1: 写车辆侧栏失败测试**

```ts
render(VehicleLocationSidebar, {
  props: { locations: [latestLocation], selectedVehicleId: "vehicle-1" }
});
expect(screen.getByText("甘G-T001")).toBeInTheDocument();
expect(screen.getByText("任务 12345678")).toBeInTheDocument();
expect(screen.queryByText("12345678-1234-4234-8234-123456789abc")).not.toBeInTheDocument();
expect(screen.getByRole("button", { name: /定位车辆 甘G-T001/ })).toHaveAttribute("aria-pressed", "true");
```

点击列表项后断言发出 `select`。

- [ ] **Step 2: 运行侧栏 RED 测试**

Run: `npm.cmd test -- src/components/vehicle-location-sidebar.test.ts`

Expected: FAIL，因为组件尚不存在。

- [ ] **Step 3: 实现车辆侧栏**

侧栏按“执行中/已派单、空闲、异常/离线”排序，显示状态色点、车牌、短任务号、最后反馈时间；位置无效时禁用定位按钮并显示“位置不可用”。

- [ ] **Step 4: 写页面联动失败测试**

更新 `DispatchMapStub` 支持 `selectedVehicleId` 和 `selectVehicle`。断言：

```ts
await fireEvent.click(screen.getByRole("button", { name: /定位车辆 甘G-T001/ }));
expect(lastMapProps().selectedVehicleId).toBe("vehicle-1");
```

再由 stub 发出 marker 选择事件，断言右栏按钮进入 `aria-pressed=true`。

- [ ] **Step 5: 运行页面 RED 测试**

Run: `npm.cmd test -- src/pages/dispatch-workbench.test.ts src/components/vehicle-location-sidebar.test.ts`

Expected: FAIL，因为页面尚未保存和传递车辆选中状态。

- [ ] **Step 6: 实现三栏控制台**

在 `DispatchWorkbenchPage.vue`：

- 新增 `selectedVehicleId`，首次加载后选择第一个具有有效位置的活动车辆。
- 左栏组合实时订单、人工复核和车辆任务，三个分区内部滚动；保留全部现有操作。
- 中栏为地图主视图，增加待调度、待复核、执行中车辆的轻量浮层指标。
- 右栏挂载 `VehicleLocationSidebar`。
- CSS 使用 `grid-template-columns: minmax(230px, 270px) minmax(0, 1fr) minmax(260px, 310px)`。
- `.dispatch-console` 使用 `height: max(560px, calc(100dvh - 220px))`；三个子栏为 `min-height: 0`，侧栏 `overflow-y: auto`，地图组件 `height: 100%`。

- [ ] **Step 7: 收敛列表信息密度**

为 `RealtimeOrderList.vue` 和 `VehicleTaskList.vue` 增加紧凑模式属性 `compact?: boolean`：紧凑模式保留短 ID、中文状态、人数/车辆和“查看地图”操作，避免左栏出现横向滚动。默认模式不变，防止影响订单中心和任务页。

- [ ] **Step 8: 运行 GREEN 测试和响应式检查**

Run: `npm.cmd test -- src/pages/dispatch-workbench.test.ts src/components/vehicle-location-sidebar.test.ts src/components/dispatch-map.test.ts`

Expected: PASS。

Run: `npm.cmd run typecheck`

Expected: PASS。

- [ ] **Step 9: 提交界面布局**

```powershell
git add apps/admin-web/src/components/VehicleLocationSidebar.vue apps/admin-web/src/components/vehicle-location-sidebar.test.ts apps/admin-web/src/components/RealtimeOrderList.vue apps/admin-web/src/components/VehicleTaskList.vue apps/admin-web/src/pages/DispatchWorkbenchPage.vue apps/admin-web/src/pages/dispatch-workbench.test.ts
git commit -m "feat: redesign dispatch map workspace"
```

### Task 4: 浏览器回归和最终验证

**Files:**
- Modify: `apps/admin-web/e2e/dispatch-flow.spec.ts`
- Modify: `progress.md`

**Interfaces:**
- Consumes: 调度工作台页面、E2E 会话和现有 API fixtures。
- Produces: 地图无空白、图层控制和车辆联动的浏览器回归证据。

- [ ] **Step 1: 写浏览器失败用例**

为 `/dispatch` fixture 补齐服务区、站点和车辆位置。用例在 1366×768 视口执行：

```ts
const mapBox = await page.getByLabel("调度地图").boundingBox();
const canvasBox = await page.locator(".leaflet-container").boundingBox();
expect(Math.abs((mapBox?.height ?? 0) - (canvasBox?.height ?? 0))).toBeLessThanOrEqual(1);

await page.getByRole("button", { name: /定位车辆 甘G-T001/ }).click();
await expect(page.getByText("任务 12345678")).toBeVisible();
await expect(page.getByText("12345678-1234-4234-8234-123456789abc")).toHaveCount(0);
```

继续依次关闭和开启三个图层开关，断言目标 pane/marker 数量变化。

- [ ] **Step 2: 运行 E2E RED**

Run: `npm.cmd run e2e -- dispatch-flow.spec.ts --workers=1`

Expected: 新用例 FAIL，失败点为画布高度、车辆侧栏或短任务号。

- [ ] **Step 3: 完成必要的测试夹具调整**

只补充该页面真实调用的资源和位置 API fixture；不放宽已有业务流程断言，不使用固定过期登录时间。

- [ ] **Step 4: 运行专项和全量验证**

Run: `npm.cmd test -- src/maps/tile-map-runtime.test.ts src/components/dispatch-map.test.ts src/components/vehicle-location-sidebar.test.ts src/pages/dispatch-workbench.test.ts`

Expected: PASS。

Run: `npm.cmd test`

Expected: 32 个以上测试文件全部通过。

Run: `npm.cmd run typecheck`

Expected: PASS。

Run: `npm.cmd run e2e -- dispatch-flow.spec.ts --workers=1`

Expected: 调度工作台用例全部通过。

- [ ] **Step 5: 真实浏览器视觉复核**

在 1366×768 和 1920×1080 下检查：地图底边无空白；拖动/缩放后服务区、站点、车辆同步；侧栏与 marker 双向联动；长任务 UUID 不显示；窄屏无横向溢出。

- [ ] **Step 6: 更新进度记录并提交**

在 `progress.md` 记录修复范围、测试结果和仍属于人工上报位置的业务边界。

```powershell
git add apps/admin-web/e2e/dispatch-flow.spec.ts progress.md
git commit -m "test: verify dispatch map workspace"
```

## 执行顺序与检查点

1. Task 1、Task 2 优先解决地图空白、图层视野和车辆真实位置。
2. Task 2 通过专项测试后先进行一次浏览器检查，再进入三栏布局。
3. Task 3 完成 A+C 界面与联动。
4. Task 4 完成全量回归、视觉验收和进度记录。
5. 本计划确认前不修改生产代码；确认后按 RED → GREEN → REFACTOR 顺序执行。
