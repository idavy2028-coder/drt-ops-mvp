// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { CreateServiceAreaInput, DispatchRuleSet, ServiceArea, ServiceAreaBoundaryView } from "../api/types";
import { authStore } from "../auth/authStore";
import { ApiError } from "../api/errors";

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

vi.mock("../components/ServiceAreaMapEditor.vue", async () => {
  const { defineComponent, h } = await import("vue");
  return {
    default: defineComponent({
      props: ["serviceArea", "ruleSets", "readonly", "feedback"],
      emits: ["create", "save-boundary", "publish"],
      setup(_props, { emit }) {
        return () => h("section", { "data-testid": "service-area-editor" }, [
          h("button", { type: "button", onClick: () => emit("create", {
            name: "通渭县试点服务区",
            boundaryWkt: "POLYGON((105.2 35.18, 105.3 35.18, 105.3 35.26, 105.2 35.18))",
            serviceStart: "06:30:00",
            serviceEnd: "19:00:00",
            ruleSetId: "rule-1"
          }) }, "测试创建服务区"),
          h("button", { type: "button", onClick: () => emit("save-boundary", { boundaryWkt: "POLYGON((105.2 35.18, 105.3 35.18, 105.3 35.26, 105.2 35.18))" }) }, "测试保存边界"),
          h("button", { type: "button", onClick: () => emit("publish") }, "测试发布服务区"),
          h("p", _props.feedback),
          h("p", { "data-testid": "selected-service-area" }, _props.serviceArea?.id)
        ]);
      }
    })
  };
});
vi.mock("../components/DriverTable.vue", () => ({ default: { template: "<div />" } }));
vi.mock("../components/VehicleTable.vue", () => ({ default: { template: "<div />" } }));
vi.mock("../components/VirtualStopImportPanel.vue", () => ({ default: { template: "<div />" } }));
vi.mock("../components/VirtualStopMap.vue", () => ({ default: { template: "<div />" } }));
vi.mock("../components/VirtualStopTable.vue", () => ({ default: { template: "<div />" } }));

import ResourcesPage from "./ResourcesPage.vue";

const createInput: CreateServiceAreaInput = {
  name: "通渭县试点服务区",
  boundaryWkt: "POLYGON((105.2 35.18, 105.3 35.18, 105.3 35.26, 105.2 35.18))",
  serviceStart: "06:30:00",
  serviceEnd: "19:00:00",
  ruleSetId: "rule-1"
};
const draftBoundaryWkt = "POLYGON((105.2 35.18, 105.3 35.18, 105.3 35.26, 105.2 35.18))";
const ruleSet = { id: "rule-1", name: "通渭县试点规则组" } as DispatchRuleSet;
const serviceArea = {
  id: "area-1",
  name: "通渭县试点服务区",
  boundary: null,
  boundarySource: null,
  boundaryVersion: 0,
  draftBoundary: draftBoundaryWkt,
  draftBoundarySource: "MANUAL",
  draftBoundaryVersion: 1,
  publishedAt: null,
  updatedAt: "2026-07-22T00:00:00Z",
  coordinateSystem: "GCJ02",
  serviceStart: "06:30:00",
  serviceEnd: "19:00:00",
  ruleSetId: "rule-1",
  enabled: false
} as ServiceArea;
const boundaryView = {
  id: serviceArea.id,
  name: serviceArea.name,
  boundaryWkt: null,
  boundarySource: null,
  boundaryVersion: 0,
  draftBoundaryWkt,
  draftBoundarySource: "MANUAL",
  draftBoundaryVersion: 1,
  publishedAt: null,
  updatedAt: serviceArea.updatedAt,
  coordinateSystem: "GCJ02"
} as ServiceAreaBoundaryView;

function mockResourceLoad(areas: ServiceArea[]): void {
  resourcesApi.listServiceAreas.mockResolvedValue(areas);
  resourcesApi.listVirtualStops.mockResolvedValue([]);
  resourcesApi.listVehicles.mockResolvedValue([]);
  resourcesApi.listDrivers.mockResolvedValue([]);
  rulesApi.listDispatchRuleSets.mockResolvedValue([ruleSet]);
}

describe("ResourcesPage", () => {
  afterEach(() => {
    cleanup();
    authStore.clearSessionForTest();
    vi.clearAllMocks();
  });

  it("creates the first service area, refreshes resources, and uses it as the virtual stop default", async () => {
    authStore.setSessionForTest({ accessToken: "token", user: { id: "admin-1", username: "admin", roles: ["SYSTEM_ADMIN"], mustChangePassword: false } });
    mockResourceLoad([]);
    resourcesApi.listServiceAreas.mockResolvedValueOnce([]).mockResolvedValueOnce([serviceArea]);
    mapApi.createServiceArea.mockResolvedValue(boundaryView);

    render(ResourcesPage);
    await screen.findByRole("button", { name: "测试创建服务区" });
    await fireEvent.click(screen.getByRole("button", { name: "测试创建服务区" }));

    await vi.waitFor(() => expect(mapApi.createServiceArea).toHaveBeenCalledWith(createInput));
    expect(await screen.findByText("服务区草稿已创建，请核对后发布。")).toBeInTheDocument();
    expect(screen.getByLabelText("所属服务区")).toHaveValue("area-1");
  });

  it("keeps the editor visible and shows the create error without publishing", async () => {
    authStore.setSessionForTest({ accessToken: "token", user: { id: "admin-1", username: "admin", roles: ["SYSTEM_ADMIN"], mustChangePassword: false } });
    mockResourceLoad([]);
    mapApi.createServiceArea.mockRejectedValue(new ApiError(409, "服务区名称已存在"));

    render(ResourcesPage);
    await screen.findByRole("button", { name: "测试创建服务区" });
    await fireEvent.click(screen.getByRole("button", { name: "测试创建服务区" }));

    expect(await screen.findByText("服务区名称已存在")).toBeInTheDocument();
    expect(screen.getByTestId("service-area-editor")).toBeInTheDocument();
    expect(mapApi.publishServiceAreaBoundary).not.toHaveBeenCalled();
  });

  it("keeps the created service area selected when the resource refresh fails", async () => {
    authStore.setSessionForTest({ accessToken: "token", user: { id: "admin-1", username: "admin", roles: ["SYSTEM_ADMIN"], mustChangePassword: false } });
    mockResourceLoad([]);
    resourcesApi.listServiceAreas.mockResolvedValueOnce([]).mockRejectedValueOnce(new ApiError(503));
    mapApi.createServiceArea.mockResolvedValue(boundaryView);

    render(ResourcesPage);
    await screen.findByRole("button", { name: "测试创建服务区" });
    await fireEvent.click(screen.getByRole("button", { name: "测试创建服务区" }));

    expect(await screen.findByText("服务区草稿已创建，请核对后发布。")).toBeInTheDocument();
    expect(screen.getByTestId("selected-service-area")).toHaveTextContent("area-1");
    expect(screen.getByText("服务暂时不可用，请稍后重试")).toBeInTheDocument();
  });

  it("keeps existing service area save and publish actions available", async () => {
    authStore.setSessionForTest({ accessToken: "token", user: { id: "admin-1", username: "admin", roles: ["SYSTEM_ADMIN"], mustChangePassword: false } });
    mockResourceLoad([serviceArea]);
    mapApi.saveServiceAreaBoundary.mockResolvedValue(boundaryView);
    mapApi.publishServiceAreaBoundary.mockResolvedValue({ ...boundaryView, publishedAt: "2026-07-22T00:01:00Z" });

    render(ResourcesPage);
    await screen.findByRole("button", { name: "测试保存边界" });
    await screen.findByRole("option", { name: "通渭县试点服务区" });
    await fireEvent.click(screen.getByRole("button", { name: "测试保存边界" }));
    await vi.waitFor(() => expect(mapApi.saveServiceAreaBoundary).toHaveBeenCalledWith("area-1", { boundaryWkt: draftBoundaryWkt }));

    await fireEvent.click(screen.getByRole("button", { name: "测试发布服务区" }));
    await vi.waitFor(() => expect(mapApi.publishServiceAreaBoundary).toHaveBeenCalledWith("area-1"));
  });
});
