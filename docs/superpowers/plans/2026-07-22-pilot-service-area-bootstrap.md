# 通渭县试点服务区首建实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在零服务区状态下，让系统管理员通过手绘或边界文本创建“通渭县试点服务区”草稿，随后沿用现有流程发布启用。

**Architecture:** 复用后端现有 `POST /api/service-areas` 和审计事务，不新增数据库结构。前端由 `ServiceAreaMapEditor` 负责采集边界与首建字段，`ResourcesPage` 负责加载规则组、调用创建接口及切换到已有服务区状态；创建成功前不改变资源列表或发布状态。

**Tech Stack:** Java 21、Spring Boot 3.5、Vue 3、TypeScript 5.8、Vitest、Testing Library、Leaflet、PostgreSQL/PostGIS（生产）、H2/H2GIS（接口测试）。

## Global Constraints

- 服务区名称默认 `通渭县试点服务区`。
- 运营时段默认 `06:30:00` 至 `19:00:00`。
- 坐标统一按 GCJ-02 写入业务接口；Leaflet 显示边界继续在既有地图适配层转换为 WGS84。
- 服务区必须绑定已存在的调度规则组，默认选中规则组列表第一项，但允许管理员改选。
- 首建只创建草稿，不自动发布；正式试点边界必须经公交企业人工核对后再发布。
- 服务区为空且没有规则组时，创建按钮必须禁用并明确提示先创建调度规则组。
- 创建失败必须保留名称、运营时段、规则组和边界文本。
- 高德行政区边界导入不是首建依赖；开放瓦片失败时仍可使用 WKT/GeoJSON 创建。
- 权限沿用后端 `RESOURCE_MANAGE`，前端围栏管理入口继续只对 `SYSTEM_ADMIN` 开放。
- 不修改虚拟站点、车辆、驾驶员、订单、调度状态机或数据库迁移。
- 每个任务完成后提交一次，并进入用户审阅检查点。

---

## 文件结构

| 文件 | 职责 |
| --- | --- |
| `apps/admin-web/src/api/types.ts` | 定义服务区创建请求的稳定类型。 |
| `apps/admin-web/src/api/map.ts` | 封装 `POST /api/service-areas`。 |
| `apps/admin-web/src/api/map.test.ts` | 验证创建接口的请求方法和完整请求体。 |
| `apps/admin-web/src/components/ServiceAreaMapEditor.vue` | 在零服务区时显示首建字段，将边界和业务字段组合为创建事件。 |
| `apps/admin-web/src/components/service-area-map-editor.test.ts` | 覆盖默认值、禁用条件、创建载荷、失败后输入保留所需的组件状态。 |
| `apps/admin-web/src/pages/ResourcesPage.vue` | 加载规则组、调用创建接口、刷新资源并自动选中新服务区。 |
| `apps/admin-web/src/pages/resources-page.test.ts` | 覆盖零状态创建成功、失败和已有服务区回归。 |

后端 `ServiceAreaController`、`ServiceAreaCommandService` 和数据库模型保持不变；现有 `ServiceAreaApiTest` 作为接口回归验证。

---

### Task 1：服务区创建 API 客户端

**Files:**
- Modify: `apps/admin-web/src/api/types.ts`
- Modify: `apps/admin-web/src/api/map.ts`
- Create: `apps/admin-web/src/api/map.test.ts`

**Interfaces:**
- Consumes: 后端 `POST /api/service-areas`，成功返回 `ServiceAreaBoundaryView`。
- Produces: `CreateServiceAreaInput` 和 `createServiceArea(input): Promise<ServiceAreaBoundaryView>`，供 Task 3 使用。

- [ ] **Step 1: 写失败测试，锁定请求契约**

创建 `apps/admin-web/src/api/map.test.ts`：

```ts
import { beforeEach, describe, expect, it, vi } from "vitest";

const request = vi.hoisted(() => vi.fn());
vi.mock("./http", () => ({ request }));

import { createServiceArea } from "./map";

describe("service area map API", () => {
  beforeEach(() => request.mockReset());

  it("creates a service area draft with the selected rule set", async () => {
    request.mockResolvedValue({ id: "area-1" });

    const input = {
      name: "通渭县试点服务区",
      boundaryWkt: "POLYGON((105.20 35.18,105.30 35.18,105.30 35.26,105.20 35.18))",
      serviceStart: "06:30:00",
      serviceEnd: "19:00:00",
      ruleSetId: "rule-1"
    };

    await createServiceArea(input);

    expect(request).toHaveBeenCalledWith("/api/service-areas", {
      method: "POST",
      body: JSON.stringify(input)
    });
  });
});
```

- [ ] **Step 2: 运行测试并确认按预期失败**

Run:

```powershell
npm.cmd --prefix apps/admin-web test -- --run src/api/map.test.ts
```

Expected: FAIL，提示 `createServiceArea` 未导出。

- [ ] **Step 3: 增加请求类型和 API 函数**

在 `apps/admin-web/src/api/types.ts` 增加：

```ts
export interface CreateServiceAreaInput {
  name: string;
  boundaryWkt: string;
  serviceStart: string;
  serviceEnd: string;
  ruleSetId: UUID;
}
```

在 `apps/admin-web/src/api/map.ts` 导入该类型并增加：

```ts
export function createServiceArea(input: CreateServiceAreaInput): Promise<ServiceAreaBoundaryView> {
  return request<ServiceAreaBoundaryView>("/api/service-areas", {
    method: "POST",
    body: JSON.stringify(input)
  });
}
```

- [ ] **Step 4: 运行 API 测试和类型检查**

Run:

```powershell
npm.cmd --prefix apps/admin-web test -- --run src/api/map.test.ts
npm.cmd --prefix apps/admin-web run typecheck
```

Expected: API 测试 1/1 通过，类型检查退出码为 0。

- [ ] **Step 5: 提交 Task 1**

```powershell
git add apps/admin-web/src/api/types.ts apps/admin-web/src/api/map.ts apps/admin-web/src/api/map.test.ts
git commit -m "feat: add service area creation client"
```

**审阅检查点:** 请求字段必须与后端现有 DTO 完全一致，不复制后端校验逻辑，不引入新的地图依赖。

---

### Task 2：电子围栏编辑器首建模式

**Files:**
- Modify: `apps/admin-web/src/components/ServiceAreaMapEditor.vue`
- Modify: `apps/admin-web/src/components/service-area-map-editor.test.ts`

**Interfaces:**
- Consumes: `DispatchRuleSet[]`、可选的 `ServiceAreaBoundaryView`、现有地图绘制和 WKT/GeoJSON 文本状态。
- Produces: `create` 事件，载荷为 Task 1 的 `CreateServiceAreaInput`；已有 `save-boundary`、`publish` 事件保持不变。

- [ ] **Step 1: 写零服务区默认状态失败测试**

在组件测试中导入 `DispatchRuleSet`，增加固定规则组：

```ts
const ruleSet: DispatchRuleSet = {
  id: "rule-1",
  name: "通渭县试点动态调度规则",
  maxWaitMinutes: 5,
  maxDetourMinutes: 8,
  bookingWindowMinutes: 60,
  autoDispatchScoreThreshold: 82,
  manualReviewScoreThreshold: 62,
  waitWeight: 0.35,
  detourWeight: 0.2,
  stabilityWeight: 0.3,
  utilizationWeight: 0.15,
  insertionPolicy: "REALTIME_INSERTION",
  enabled: true
};
```

增加测试：

```ts
it("shows Tongwei defaults when no service area exists", async () => {
  render(ServiceAreaMapEditor, {
    props: { serviceArea: undefined, ruleSets: [ruleSet], readonly: false }
  });

  expect(screen.getByLabelText("服务区名称")).toHaveValue("通渭县试点服务区");
  expect(screen.getByLabelText("运营开始时间")).toHaveValue("06:30");
  expect(screen.getByLabelText("运营结束时间")).toHaveValue("19:00");
  expect(screen.getByLabelText("调度规则组")).toHaveValue("rule-1");
  expect(screen.getByRole("button", { name: "创建服务区草稿" })).toBeDisabled();
});
```

- [ ] **Step 2: 写创建载荷失败测试**

```ts
it("emits the drawn boundary and bootstrap fields when creating", async () => {
  const { emitted } = render(ServiceAreaMapEditor, {
    props: { serviceArea: undefined, ruleSets: [ruleSet], readonly: false }
  });

  await fireEvent.update(
    screen.getByLabelText("服务区边界草稿"),
    "POLYGON((105.20 35.18,105.30 35.18,105.30 35.26,105.20 35.18))"
  );
  await fireEvent.click(screen.getByRole("button", { name: "创建服务区草稿" }));

  expect(emitted().create).toEqual([[
    {
      name: "通渭县试点服务区",
      boundaryWkt: "POLYGON((105.20 35.18,105.30 35.18,105.30 35.26,105.20 35.18))",
      serviceStart: "06:30:00",
      serviceEnd: "19:00:00",
      ruleSetId: "rule-1"
    }
  ]]);
});
```

- [ ] **Step 3: 写无规则组提示失败测试**

```ts
it("blocks creation until a dispatch rule set exists", async () => {
  render(ServiceAreaMapEditor, {
    props: { serviceArea: undefined, ruleSets: [], readonly: false }
  });

  expect(screen.getByText("请先创建调度规则组，再创建服务区。")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "创建服务区草稿" })).toBeDisabled();
});
```

- [ ] **Step 4: 运行组件测试并确认失败原因正确**

Run:

```powershell
npm.cmd --prefix apps/admin-web test -- --run src/components/service-area-map-editor.test.ts
```

Expected: 新增测试因缺少创建字段、按钮和 `create` 事件失败；原有 6 项测试仍能执行。

- [ ] **Step 5: 实现首建 props、状态和事件**

在 `ServiceAreaMapEditor.vue`：

```ts
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import type {
  CreateServiceAreaInput,
  DispatchRuleSet,
  ServiceAreaBoundaryDraft,
  ServiceAreaBoundaryView
} from "../api/types";

const props = withDefaults(defineProps<{
  serviceArea?: ServiceAreaBoundaryView;
  ruleSets?: DispatchRuleSet[];
  readonly: boolean;
  feedback?: string;
}>(), {
  serviceArea: undefined,
  ruleSets: () => [],
  feedback: ""
});

const emit = defineEmits<{
  create: [input: CreateServiceAreaInput];
  "import-district": [keyword: string];
  "save-boundary": [draft: ServiceAreaBoundaryDraft];
  publish: [];
}>();

const createForm = reactive({
  name: "通渭县试点服务区",
  serviceStart: "06:30",
  serviceEnd: "19:00",
  ruleSetId: ""
});

watch(
  () => props.ruleSets,
  (rules) => {
    if (!rules.some((rule) => rule.id === createForm.ruleSetId)) {
      createForm.ruleSetId = rules[0]?.id ?? "";
    }
  },
  { immediate: true }
);

const createDisabled = computed(() =>
  props.readonly
  || !createForm.name.trim()
  || !createForm.serviceStart
  || !createForm.serviceEnd
  || !createForm.ruleSetId
  || !boundaryText.value.trim()
);

function createServiceAreaDraft(): void {
  if (createDisabled.value) return;
  const boundaryWkt = boundaryWktForCreate();
  if (!boundaryWkt) return;
  emit("create", {
    name: createForm.name.trim(),
    boundaryWkt,
    serviceStart: `${createForm.serviceStart}:00`,
    serviceEnd: `${createForm.serviceEnd}:00`,
    ruleSetId: createForm.ruleSetId
  });
}

function boundaryWktForCreate(): string | undefined {
  const value = boundaryText.value.trim();
  if (inputFormat.value === "wkt") return value;

  try {
    const geoJson = JSON.parse(value) as {
      type?: string;
      coordinates?: number[][][];
    };
    const ring = geoJson.type === "Polygon" ? geoJson.coordinates?.[0] : undefined;
    if (!ring || ring.length < 3 || ring.some((point) => point.length !== 2 || point.some((number) => !Number.isFinite(number)))) {
      throw new Error("invalid polygon");
    }
    const closed = ring.map(([longitude, latitude]) => [longitude, latitude]);
    const first = closed[0];
    const last = closed[closed.length - 1];
    if (first[0] !== last[0] || first[1] !== last[1]) closed.push([...first]);
    return `POLYGON((${closed.map(([longitude, latitude]) => `${longitude} ${latitude}`).join(", ")}))`;
  } catch {
    mapError.value = "GeoJSON 必须是包含至少三个坐标点的 Polygon。";
    return undefined;
  }
}
```

创建模式模板显示四个字段和“创建服务区草稿”按钮；已有服务区模式继续显示“保存草稿”和“发布并启用”。GeoJSON 按上述 `boundaryWktForCreate()` 明确转换为 WKT；不要把 `geoJson` 直接传给创建接口，因为后端创建 DTO 只接受 `boundaryWkt`。

- [ ] **Step 6: 保留已有模式和失败状态**

确保以下模板分支成立：

```vue
<div v-if="!serviceArea" class="create-fields">
  <label>服务区名称<input v-model="createForm.name" aria-label="服务区名称" /></label>
  <label>运营开始时间<input v-model="createForm.serviceStart" type="time" aria-label="运营开始时间" /></label>
  <label>运营结束时间<input v-model="createForm.serviceEnd" type="time" aria-label="运营结束时间" /></label>
  <label>调度规则组<select v-model="createForm.ruleSetId" aria-label="调度规则组"><option v-for="rule in ruleSets" :key="rule.id" :value="rule.id">{{ rule.name }}</option></select></label>
  <p v-if="ruleSets.length === 0" class="editor-message warning">请先创建调度规则组，再创建服务区。</p>
</div>

<button v-if="!serviceArea" type="button" class="primary-button" :disabled="createDisabled" @click="createServiceAreaDraft">创建服务区草稿</button>
<template v-else>
  <button type="button" class="secondary-button" :disabled="readonly" @click="saveBoundary">保存草稿</button>
  <button type="button" class="primary-button" :disabled="readonly || !serviceArea.draftBoundaryWkt" @click="requestPublish">发布并启用</button>
</template>
```

父组件请求失败时不卸载编辑器，组件内部 `createForm` 和 `boundaryText` 因而保持不变。

- [ ] **Step 7: 运行组件测试和类型检查**

Run:

```powershell
npm.cmd --prefix apps/admin-web test -- --run src/components/service-area-map-editor.test.ts
npm.cmd --prefix apps/admin-web run typecheck
```

Expected: 组件测试 9/9 通过，类型检查退出码为 0。

- [ ] **Step 8: 提交 Task 2**

```powershell
git add apps/admin-web/src/components/ServiceAreaMapEditor.vue apps/admin-web/src/components/service-area-map-editor.test.ts
git commit -m "feat: add service area bootstrap editor"
```

**审阅检查点:** 零状态和已有状态必须清楚区分；创建不能自动发布；底图失败不应禁用边界文本创建。

---

### Task 3：资源页状态集成与回归验收

**Files:**
- Modify: `apps/admin-web/src/pages/ResourcesPage.vue`
- Create: `apps/admin-web/src/pages/resources-page.test.ts`

**Interfaces:**
- Consumes: Task 1 的 `createServiceArea`、Task 2 的 `create` 事件、既有 `listDispatchRuleSets()`。
- Produces: 创建成功后的资源刷新、服务区自动选中、虚拟站点默认所属服务区和明确反馈。

- [ ] **Step 1: 写资源页创建成功失败测试**

创建 `apps/admin-web/src/pages/resources-page.test.ts`，统一准备 API mock、管理员会话和轻量编辑器 stub：

```ts
// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { defineComponent } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";

const mapApi = vi.hoisted(() => ({
  createServiceArea: vi.fn(),
  importDistrictBoundary: vi.fn(),
  publishServiceAreaBoundary: vi.fn(),
  saveServiceAreaBoundary: vi.fn()
}));
const resourcesApi = vi.hoisted(() => ({
  createVirtualStop: vi.fn(),
  importVirtualStops: vi.fn(),
  listDrivers: vi.fn(),
  listServiceAreas: vi.fn(),
  listVehicles: vi.fn(),
  listVirtualStops: vi.fn(),
  updateVirtualStop: vi.fn()
}));
const rulesApi = vi.hoisted(() => ({ listDispatchRuleSets: vi.fn() }));

vi.mock("../api/map", () => mapApi);
vi.mock("../api/resources", () => resourcesApi);
vi.mock("../api/rules", () => rulesApi);
vi.mock("../components/ServiceAreaMapEditor.vue", () => ({
  default: defineComponent({
    props: ["serviceArea", "ruleSets", "readonly", "feedback"],
    emits: ["create", "save-boundary", "publish", "import-district"],
    template: `<section data-testid="service-area-editor">
      <span>{{ feedback }}</span>
      <button @click="$emit('create', {
        name: '通渭县试点服务区',
        boundaryWkt: 'POLYGON((105.20 35.18,105.30 35.18,105.30 35.26,105.20 35.18))',
        serviceStart: '06:30:00',
        serviceEnd: '19:00:00',
        ruleSetId: 'rule-1'
      })">测试创建服务区</button>
      <button @click="$emit('save-boundary', { boundaryWkt: 'POLYGON((105 35,106 35,105 36,105 35))' })">测试保存边界</button>
      <button @click="$emit('publish')">测试发布服务区</button>
    </section>`
  })
}));
vi.mock("../components/VirtualStopImportPanel.vue", () => ({ default: { template: "<div />" } }));
vi.mock("../components/VirtualStopMap.vue", () => ({ default: { template: "<div />" } }));
vi.mock("../components/VirtualStopTable.vue", () => ({ default: { template: "<div />" } }));
vi.mock("../components/VehicleTable.vue", () => ({ default: { template: "<div />" } }));
vi.mock("../components/DriverTable.vue", () => ({ default: { template: "<div />" } }));

import ResourcesPage from "./ResourcesPage.vue";

const boundaryView = {
  id: "area-1",
  name: "通渭县试点服务区",
  boundaryWkt: null,
  boundarySource: null,
  boundaryVersion: 0,
  draftBoundaryWkt: "POLYGON((105.20 35.18,105.30 35.18,105.30 35.26,105.20 35.18))",
  draftBoundarySource: "MANUAL",
  draftBoundaryVersion: 1,
  publishedAt: null,
  updatedAt: "2026-07-22T08:00:00+08:00",
  coordinateSystem: "GCJ02" as const
};
const serviceArea = {
  id: "area-1",
  name: "通渭县试点服务区",
  boundary: null,
  boundarySource: null,
  boundaryVersion: 0,
  draftBoundary: boundaryView.draftBoundaryWkt,
  draftBoundarySource: "MANUAL",
  draftBoundaryVersion: 1,
  publishedAt: null,
  updatedAt: boundaryView.updatedAt,
  coordinateSystem: "GCJ02" as const,
  serviceStart: "06:30:00",
  serviceEnd: "19:00:00",
  ruleSetId: "rule-1",
  enabled: false
};

describe("ResourcesPage service area bootstrap", () => {
  beforeEach(() => {
    authStore.setSessionForTest({
      accessToken: "token",
      user: { id: "admin-1", username: "admin", roles: ["SYSTEM_ADMIN"], mustChangePassword: false }
    });
    resourcesApi.listVirtualStops.mockResolvedValue([]);
    resourcesApi.listVehicles.mockResolvedValue([]);
    resourcesApi.listDrivers.mockResolvedValue([]);
    rulesApi.listDispatchRuleSets.mockResolvedValue([{ id: "rule-1", name: "通渭县试点动态调度规则" }]);
  });

  afterEach(() => {
    cleanup();
    authStore.clearSessionForTest();
    vi.clearAllMocks();
  });
```

在同一 `describe` 中增加成功用例。`listServiceAreas()` 首次返回空数组、刷新后返回新服务区：

```ts
it("creates the first service area and selects it for virtual stops", async () => {
  resourcesApi.listServiceAreas.mockResolvedValueOnce([]).mockResolvedValueOnce([serviceArea]);
  mapApi.createServiceArea.mockResolvedValue(boundaryView);
  render(ResourcesPage);

  await fireEvent.click(await screen.findByRole("button", { name: "测试创建服务区" }));

  expect(mapApi.createServiceArea).toHaveBeenCalledWith(expect.objectContaining({
    name: "通渭县试点服务区",
    serviceStart: "06:30:00",
    serviceEnd: "19:00:00",
    ruleSetId: "rule-1"
  }));
  expect(await screen.findByText("服务区草稿已创建，请核对后发布。")).toBeInTheDocument();
  expect(screen.getByLabelText("所属服务区")).toHaveValue("area-1");
});
```

- [ ] **Step 2: 写创建失败保留编辑器测试**

在同一测试文件增加：

```ts
it("keeps the editor mounted when creation fails", async () => {
  resourcesApi.listServiceAreas.mockResolvedValue([]);
  mapApi.createServiceArea.mockRejectedValue(new Error("服务区名称已存在"));
  render(ResourcesPage);

  await fireEvent.click(await screen.findByRole("button", { name: "测试创建服务区" }));

  expect(await screen.findByText("服务区名称已存在")).toBeInTheDocument();
  expect(screen.getByTestId("service-area-editor")).toBeInTheDocument();
  expect(mapApi.publishServiceAreaBoundary).not.toHaveBeenCalled();
});
```

- [ ] **Step 3: 写已有服务区回归测试**

让首次 `listServiceAreas()` 返回现有服务区，执行 stub 的保存和发布事件：

```ts
it("keeps existing boundary save and publish behavior", async () => {
  resourcesApi.listServiceAreas.mockResolvedValue([serviceArea]);
  mapApi.saveServiceAreaBoundary.mockResolvedValue(boundaryView);
  mapApi.publishServiceAreaBoundary.mockResolvedValue({
    ...boundaryView,
    boundaryWkt: boundaryView.draftBoundaryWkt,
    publishedAt: "2026-07-22T08:10:00+08:00"
  });
  vi.spyOn(window, "confirm").mockReturnValue(true);
  render(ResourcesPage);

  await fireEvent.click(await screen.findByRole("button", { name: "测试保存边界" }));
  expect(mapApi.saveServiceAreaBoundary).toHaveBeenCalledWith("area-1", expect.any(Object));

  await fireEvent.click(screen.getByRole("button", { name: "测试发布服务区" }));
  expect(mapApi.publishServiceAreaBoundary).toHaveBeenCalledWith("area-1");
});
});
```

- [ ] **Step 4: 运行页面测试并确认失败原因正确**

Run:

```powershell
npm.cmd --prefix apps/admin-web test -- --run src/pages/resources-page.test.ts
```

Expected: FAIL，提示资源页尚未加载规则组、监听 `create` 或调用创建 API。

- [ ] **Step 5: 集成规则组加载和创建处理**

在 `ResourcesPage.vue` 增加：

```ts
import { createServiceArea, importDistrictBoundary, publishServiceAreaBoundary, saveServiceAreaBoundary } from "../api/map";
import { listDispatchRuleSets } from "../api/rules";
import type { CreateServiceAreaInput, DispatchRuleSet } from "../api/types";

const dispatchRuleSets = ref<DispatchRuleSet[]>([]);

async function createArea(input: CreateServiceAreaInput): Promise<void> {
  error.value = "";
  serviceAreaFeedback.value = "";
  serviceAreaActionLoading.value = true;
  try {
    const created = await createServiceArea(input);
    await loadResources();
    selectedServiceArea.value = created;
    stopDraft.value.serviceAreaId = created.id;
    serviceAreaFeedback.value = "服务区草稿已创建，请核对后发布。";
  } catch (actionError) {
    error.value = userMessage(actionError, "服务区草稿创建失败");
  } finally {
    serviceAreaActionLoading.value = false;
  }
}
```

`loadResources()` 的并行请求增加 `listDispatchRuleSets()`，并赋值给 `dispatchRuleSets`。编辑器调用调整为：

```vue
<ServiceAreaMapEditor
  :service-area="selectedServiceArea"
  :rule-sets="dispatchRuleSets"
  :readonly="!canManageServiceArea || serviceAreaActionLoading"
  :feedback="serviceAreaFeedback"
  @create="createArea"
  @import-district="importDistrict"
  @save-boundary="saveBoundary"
  @publish="publishBoundary"
/>
```

- [ ] **Step 6: 运行前端目标测试**

Run:

```powershell
npm.cmd --prefix apps/admin-web test -- --run src/api/map.test.ts src/components/service-area-map-editor.test.ts src/pages/resources-page.test.ts
npm.cmd --prefix apps/admin-web run typecheck
npm.cmd --prefix apps/admin-web run build
```

Expected: 目标测试全部通过，类型检查和生产构建退出码为 0；仅允许既有的大包体积警告。

- [ ] **Step 7: 运行后端接口回归**

Run:

```powershell
& 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd' -q -f 'D:\codex-projects\.worktrees\pilot-bootstrap-rules\pom.xml' -pl apps/api '-Dtest=ServiceAreaApiTest,ServiceAreaCommandServiceTest' test
```

Expected: Maven 退出码为 0；创建、非法边界、权限、保存和发布相关测试无失败。

- [ ] **Step 8: 运行前端全量回归**

Run:

```powershell
npm.cmd --prefix apps/admin-web test
```

Expected: 全部前端测试通过，无新增失败。

- [ ] **Step 9: 提交 Task 3**

```powershell
git add apps/admin-web/src/pages/ResourcesPage.vue apps/admin-web/src/pages/resources-page.test.ts
git commit -m "feat: integrate service area bootstrap flow"
```

**审阅检查点:** 创建成功后必须刷新资源并自动选中；失败不能清空编辑器；已有服务区保存和发布不能回归。

---

## 本机真实验收检查点

业务代码三项任务审阅通过后再执行，且不提交测试多边形作为正式服务区：

1. 在隔离端口启动本分支 API 和管理端，确认不影响 `master` 的 8080/5173 服务。
2. 使用系统管理员登录，确认规则组列表存在“通渭县试点动态调度规则”。
3. 在零服务区状态手绘一个临时边界，核对默认名称、`06:30-19:00` 和规则绑定；只验证创建请求前端状态时不得发布临时边界。
4. 由用户在地图上核对并确认正式通渭县试点范围后，创建服务区草稿。
5. 创建成功后核对“发布并启用”可用、虚拟站点所属服务区自动带出、审计日志存在 `SERVICE_AREA_CREATED`。
6. 用户明确确认正式边界后再发布，核对 `SERVICE_AREA_PUBLISHED`、已发布版本和订单服务区校验。
7. 记录自动化结果、真实浏览器结果和未关闭风险，再进入提交、推送和 PR 流程。

## 计划自检

- Spec coverage：零状态首建、默认名称和时段、规则绑定、创建后选中、失败保留、权限、审计和不自动发布均由 Task 1-3 覆盖。
- Type consistency：创建请求统一使用 `CreateServiceAreaInput`；组件 `create` 事件、API 客户端和页面处理函数使用同一类型。
- Scope：后端创建接口和数据库结构不改；虚拟站点、车辆和驾驶员功能不纳入本计划。
- Safety：正式服务区边界必须由用户人工核对，不使用自动化测试多边形替代。
