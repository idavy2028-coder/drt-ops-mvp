// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import { feedbackStore } from "../stores/feedbackStore";
import OnboardSystemManagementPage from "./OnboardSystemManagementPage.vue";

const onboardApi = vi.hoisted(() => ({
  listOnboardSystems: vi.fn(),
  getOnboardSystem: vi.fn(),
  previewOnboardConfiguration: vi.fn(),
  applyOnboardConfiguration: vi.fn()
}));

vi.mock("../api/onboardSystems", () => onboardApi);

describe("OnboardSystemManagementPage", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    for (const item of [...feedbackStore.items]) feedbackStore.dismiss(item.id);
    authStore.setSessionForTest({
      accessToken: "admin-token",
      user: { id: "admin-1", username: "admin", roles: ["SYSTEM_ADMIN"], mustChangePassword: false }
    });
    onboardApi.listOnboardSystems.mockResolvedValue(pageFixture([summaryFixture()]));
    onboardApi.getOnboardSystem.mockResolvedValue(detailFixture());
    onboardApi.previewOnboardConfiguration.mockResolvedValue({
      onboardSystemId: "44444444-4444-4444-4444-444444444444",
      vehicleId: "33333333-3333-3333-3333-333333333331",
      currentVersion: 7,
      changedFields: ["operatingMode"],
      warnings: []
    });
    onboardApi.applyOnboardConfiguration.mockResolvedValue({
      onboardSystemId: "44444444-4444-4444-4444-444444444444",
      vehicleId: "33333333-3333-3333-3333-333333333331",
      currentVersion: 8,
      changedFields: ["operatingMode"],
      warnings: []
    });
  });

  afterEach(() => {
    cleanup();
    authStore.clearSessionForTest();
    for (const item of [...feedbackStore.items]) feedbackStore.dismiss(item.id);
    vi.restoreAllMocks();
    vi.clearAllMocks();
  });

  it("shows independent readiness, authoritative source, WAN and masked device facts", async () => {
    // Mutations caught: flattening readiness into one badge, deriving source from primary role,
    // hiding an independent device failure, or rendering a raw terminal identity.
    render(OnboardSystemManagementPage);

    expect(await screen.findByText("整体：DEGRADED")).toBeInTheDocument();
    expect(screen.getByText("调度：READY")).toBeInTheDocument();
    expect(screen.getByText("位置：READY")).toBeInTheDocument();
    expect(screen.getByText("主动安全：UNAVAILABLE")).toBeInTheDocument();
    expect(screen.getByText("位置主源：调度终端")).toBeInTheDocument();
    expect(screen.getByText("广域网：调度终端")).toBeInTheDocument();
    expect(screen.getByText("已安装 2 台")).toBeInTheDocument();
    expect(screen.getByText("device-aaaaaaaaaaaa")).toBeInTheDocument();
    expect(screen.getByText("共享网络客户端")).toBeInTheDocument();
    expect(screen.getByText("未接入")).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("JT-RAW-001");
    expect(document.body).not.toHaveTextContent("13800000000");
    expect(document.body).not.toHaveTextContent("secret-auth-token");
  });

  it("shows masked detail but no configuration controls to a read-only terminal operator", async () => {
    // Mutation caught: treating TERMINAL_READ as TERMINAL_MANAGE or rendering an apply form
    // merely because aggregate detail is visible.
    vi.spyOn(authStore, "has").mockImplementation((permission) => permission === "TERMINAL_READ");

    render(OnboardSystemManagementPage);

    expect(await screen.findByText("整体：DEGRADED")).toBeInTheDocument();
    expect(screen.getByText("device-aaaaaaaaaaaa")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "编辑期望配置" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("运行模式")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "应用配置" })).not.toBeInTheDocument();
  });

  it("requires reason and confirmation then reloads and applies the fresh version with aliases only", async () => {
    // Mutations caught: allowing a blank/unconfirmed action, submitting the list version,
    // skipping the immediate detail reload, or sending raw terminalCode selectors.
    render(OnboardSystemManagementPage);
    await screen.findByText("整体：DEGRADED");
    await fireEvent.click(screen.getByRole("button", { name: "编辑期望配置" }));

    const applyButton = screen.getByRole("button", { name: "应用配置" });
    expect(applyButton).toBeDisabled();
    await fireEvent.update(screen.getByLabelText("运行模式"), "SAFETY_MONITOR_ONLY");
    await fireEvent.update(screen.getByLabelText("操作原因"), "双设备角色核对");
    expect(applyButton).toBeDisabled();
    await fireEvent.click(screen.getByLabelText("我已核对当前车辆、版本和设备角色，确认应用。"));
    expect(applyButton).toBeEnabled();
    await fireEvent.click(applyButton);

    await waitFor(() => expect(onboardApi.getOnboardSystem).toHaveBeenCalledTimes(3));
    await waitFor(() => expect(onboardApi.applyOnboardConfiguration).toHaveBeenCalledTimes(1));
    const [vehicleId, payload] = onboardApi.applyOnboardConfiguration.mock.calls[0];
    expect(vehicleId).toBe("33333333-3333-3333-3333-333333333331");
    expect(payload).toEqual(expect.objectContaining({
      expectedVersion: 7,
      operatingMode: "SAFETY_MONITOR_ONLY",
      reason: "双设备角色核对"
    }));
    expect(payload.devices.map((device: { deviceAlias: string }) => device.deviceAlias))
      .toEqual(["device-aaaaaaaaaaaa", "device-bbbbbbbbbbbb"]);
    expect(JSON.stringify(payload)).not.toContain("terminalCode");
  });

  it("marks the draft stale and forbids apply when the selection changes or fresh version differs", async () => {
    // Mutations caught: letting a draft follow a new vehicle selection or applying after
    // optimistic version drift detected by the mandatory fresh detail read.
    onboardApi.listOnboardSystems.mockResolvedValue(pageFixture([
      summaryFixture(),
      summaryFixture("33333333-3333-3333-3333-333333333332")
    ]));
    onboardApi.getOnboardSystem.mockImplementation(async (vehicleId: string) =>
      detailFixture(vehicleId, vehicleId.endsWith("332") ? 3 : 7));
    render(OnboardSystemManagementPage);
    await screen.findByText("整体：DEGRADED");
    await fireEvent.click(screen.getByRole("button", { name: "编辑期望配置" }));
    await fireEvent.click(screen.getByRole("button", { name: /33333333-3333-3333-3333-333333333332/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("草稿已失效，请重新开始编辑");
    expect(screen.queryByRole("button", { name: "应用配置" })).not.toBeInTheDocument();

    await fireEvent.click(screen.getByRole("button", { name: "编辑期望配置" }));
    await fireEvent.update(screen.getByLabelText("操作原因"), "版本核对");
    await fireEvent.click(screen.getByLabelText("我已核对当前车辆、版本和设备角色，确认应用。"));
    onboardApi.getOnboardSystem.mockResolvedValueOnce(detailFixture(
      "33333333-3333-3333-3333-333333333332", 4));
    await fireEvent.click(screen.getByRole("button", { name: "应用配置" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("配置版本已变化，请重新开始编辑");
    expect(onboardApi.applyOnboardConfiguration).not.toHaveBeenCalled();
  });

  it("clears actionable detail and forbids apply when detail reload fails", async () => {
    // Mutation caught: retaining old actionable detail after a failed fresh reload or
    // exposing an unsafe response body instead of the standard safe page message.
    render(OnboardSystemManagementPage);
    await screen.findByText("整体：DEGRADED");
    await fireEvent.click(screen.getByRole("button", { name: "编辑期望配置" }));
    await fireEvent.update(screen.getByLabelText("操作原因"), "链路异常保护");
    await fireEvent.click(screen.getByLabelText("我已核对当前车辆、版本和设备角色，确认应用。"));
    onboardApi.getOnboardSystem.mockRejectedValueOnce(new Error("raw secret response"));
    await fireEvent.click(screen.getByRole("button", { name: "应用配置" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("车载系统详情暂时不可用");
    expect(screen.getByRole("alert")).not.toHaveTextContent("raw secret response");
    expect(onboardApi.applyOnboardConfiguration).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "应用配置" })).not.toBeInTheDocument();
  });

  it("replaces all visible facts and the list summary from the post-apply server detail", async () => {
    // Mutations caught: patching only operatingMode/version after apply or reusing the
    // optimistic draft instead of replacing roles/network/profiles/readiness from the server.
    const initial = detailFixture();
    const serverFacts = detailFixture(undefined, 8);
    serverFacts.activeLocationDeviceAlias = "device-bbbbbbbbbbbb";
    serverFacts.wanDeviceAlias = "device-bbbbbbbbbbbb";
    serverFacts.readiness.overallStatus = "OPERATIONAL";
    serverFacts.readiness.activeSafety = "READY";
    serverFacts.devices = [{
      ...serverFacts.devices[1],
      networkMode: "DIRECT_CELLULAR",
      roles: ["LOCATION_PRIMARY", "ACTIVE_SAFETY", "WAN_UPLINK"],
      protocolProfiles: {
        transportProfile: "JT808_2013",
        businessProfile: "NONE",
        safetyProfile: "NONE",
        mediaProfile: "NONE",
        activePositionIntervalSeconds: 45,
        idlePositionIntervalSeconds: 90
      }
    }];
    onboardApi.getOnboardSystem
      .mockResolvedValueOnce(initial)
      .mockResolvedValueOnce(initial)
      .mockResolvedValueOnce(serverFacts);

    render(OnboardSystemManagementPage);
    await screen.findByText("整体：DEGRADED");
    await fireEvent.click(screen.getByRole("button", { name: "编辑期望配置" }));
    await fireEvent.update(
      screen.getByLabelText("device-aaaaaaaaaaaa 网络模式"), "SHARED_LAN_CLIENT");
    await fireEvent.click(screen.getByLabelText("device-aaaaaaaaaaaa 角色 WAN_UPLINK"));
    await fireEvent.update(screen.getAllByLabelText("传输协议")[0], "JT808_2013");
    await fireEvent.update(screen.getByLabelText("操作原因"), "应用后读取服务器事实");
    await fireEvent.click(screen.getByLabelText("我已核对当前车辆、版本和设备角色，确认应用。"));
    await fireEvent.click(screen.getByRole("button", { name: "应用配置" }));

    expect(await screen.findByText("整体：OPERATIONAL")).toBeInTheDocument();
    expect(screen.getByText("主动安全：READY")).toBeInTheDocument();
    expect(screen.getByText("位置主源：主动安全记录仪")).toBeInTheDocument();
    expect(screen.getByText("广域网：主动安全记录仪")).toBeInTheDocument();
    expect(screen.getByText("已安装 1 台")).toBeInTheDocument();
    expect(screen.getByText("JT808_2013 · NONE · NONE · NONE")).toBeInTheDocument();
    expect(screen.getByText("v8 · 1 台设备")).toBeInTheDocument();
    expect(onboardApi.getOnboardSystem).toHaveBeenCalledTimes(3);
  });

  it("clears stale facts safely when the post-apply detail reload fails", async () => {
    // Mutation caught: keeping the pre-apply detail actionable or reporting success when
    // apply succeeded but the authoritative post-apply detail cannot be reloaded.
    const initial = detailFixture();
    onboardApi.getOnboardSystem
      .mockResolvedValueOnce(initial)
      .mockResolvedValueOnce(initial)
      .mockRejectedValueOnce(new Error("unsafe post-apply response"));

    render(OnboardSystemManagementPage);
    await screen.findByText("整体：DEGRADED");
    await fireEvent.click(screen.getByRole("button", { name: "编辑期望配置" }));
    await fireEvent.update(screen.getByLabelText("操作原因"), "应用后重载保护");
    await fireEvent.click(screen.getByLabelText("我已核对当前车辆、版本和设备角色，确认应用。"));
    await fireEvent.click(screen.getByRole("button", { name: "应用配置" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("配置已应用，但最新车载系统详情暂时不可用");
    expect(screen.getByRole("alert")).not.toHaveTextContent("unsafe post-apply response");
    expect(screen.queryByText("整体：DEGRADED")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "应用配置" })).not.toBeInTheDocument();
  });

  it("locks refresh and selection until authoritative post-apply facts replace the draft", async () => {
    // Mutations caught: allowing refresh/selection to advance the token while applying,
    // keeping activeDraft on token mismatch, or announcing success before full server facts.
    const firstVehicle = "33333333-3333-3333-3333-333333333331";
    const secondVehicle = "33333333-3333-3333-3333-333333333332";
    const initial = detailFixture(firstVehicle, 7);
    const authoritative = detailFixture(firstVehicle, 8);
    authoritative.readiness.overallStatus = "OPERATIONAL";
    authoritative.devices = [authoritative.devices[1]];
    const applyResult = deferred<{
      onboardSystemId: string;
      vehicleId: string;
      currentVersion: number;
      changedFields: string[];
      warnings: string[];
    }>();
    const authoritativeReload = deferred<ReturnType<typeof detailFixture>>();
    onboardApi.listOnboardSystems.mockResolvedValue(pageFixture([
      summaryFixture(firstVehicle), summaryFixture(secondVehicle)
    ]));
    onboardApi.getOnboardSystem
      .mockResolvedValueOnce(initial)
      .mockResolvedValueOnce(initial)
      .mockReturnValueOnce(authoritativeReload.promise);
    onboardApi.applyOnboardConfiguration.mockReturnValueOnce(applyResult.promise);

    render(OnboardSystemManagementPage);
    await screen.findByText("整体：DEGRADED");
    await fireEvent.click(screen.getByRole("button", { name: "编辑期望配置" }));
    await fireEvent.update(screen.getByLabelText("操作原因"), "应用期间锁定导航");
    await fireEvent.click(screen.getByLabelText("我已核对当前车辆、版本和设备角色，确认应用。"));
    await fireEvent.click(screen.getByRole("button", { name: "应用配置" }));
    await waitFor(() => expect(onboardApi.applyOnboardConfiguration).toHaveBeenCalledTimes(1));

    const refresh = screen.getByRole("button", { name: "刷新" });
    const secondSystem = screen.getByRole("button", { name: new RegExp(secondVehicle) });
    expect(refresh).toBeDisabled();
    expect(secondSystem).toBeDisabled();
    await fireEvent.click(refresh);
    await fireEvent.click(secondSystem);
    expect(onboardApi.listOnboardSystems).toHaveBeenCalledTimes(1);
    expect(onboardApi.getOnboardSystem).toHaveBeenCalledTimes(2);

    applyResult.resolve({
      onboardSystemId: "44444444-4444-4444-4444-444444444444",
      vehicleId: firstVehicle,
      currentVersion: 8,
      changedFields: ["devices"],
      warnings: []
    });
    await waitFor(() => expect(onboardApi.getOnboardSystem).toHaveBeenCalledTimes(3));
    expect(feedbackStore.items.some((item) => item.message === "车载系统期望配置已应用")).toBe(false);

    authoritativeReload.resolve(authoritative);
    expect(await screen.findByText("整体：OPERATIONAL")).toBeInTheDocument();
    expect(screen.getByText("已安装 1 台")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "应用配置" })).not.toBeInTheDocument();
    expect(feedbackStore.items.some((item) => item.message === "车载系统期望配置已应用")).toBe(true);
  });

  it("keeps system selection locked while the authoritative post-apply reload is pending", async () => {
    // Mutation caught: allowing selection to advance the token while authoritative
    // post-apply detail is pending, which would strand an actionable old draft.
    const firstVehicle = "33333333-3333-3333-3333-333333333331";
    const secondVehicle = "33333333-3333-3333-3333-333333333332";
    onboardApi.listOnboardSystems.mockResolvedValue(pageFixture([
      summaryFixture(firstVehicle),
      summaryFixture(secondVehicle)
    ]));
    let resolveLateReload!: (value: ReturnType<typeof detailFixture>) => void;
    const lateReload = new Promise<ReturnType<typeof detailFixture>>((resolve) => {
      resolveLateReload = resolve;
    });
    let firstReads = 0;
    onboardApi.getOnboardSystem.mockImplementation((vehicleId: string) => {
      if (vehicleId === secondVehicle) return Promise.resolve(detailFixture(secondVehicle, 3));
      firstReads += 1;
      if (firstReads === 3) return lateReload;
      return Promise.resolve(detailFixture(firstVehicle, 7));
    });

    render(OnboardSystemManagementPage);
    await screen.findByText("整体：DEGRADED");
    await fireEvent.click(screen.getByRole("button", { name: "编辑期望配置" }));
    await fireEvent.update(screen.getByLabelText("操作原因"), "迟到响应保护");
    await fireEvent.click(screen.getByLabelText("我已核对当前车辆、版本和设备角色，确认应用。"));
    await fireEvent.click(screen.getByRole("button", { name: "应用配置" }));
    await waitFor(() => expect(onboardApi.getOnboardSystem).toHaveBeenCalledTimes(3));
    const secondSystem = screen.getByRole("button", { name: new RegExp(secondVehicle) });
    expect(secondSystem).toBeDisabled();
    await fireEvent.click(secondSystem);
    expect(onboardApi.getOnboardSystem).toHaveBeenCalledTimes(3);

    resolveLateReload(detailFixture(firstVehicle, 8));
    expect(await screen.findByText("配置版本 v8 · 调度服务")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: firstVehicle })).toBeInTheDocument();
  });

  it("keeps the selected vehicle on list refresh and invalidates a drifted same-vehicle draft", async () => {
    // Mutations caught: forcing list refresh back to the first vehicle or retaining a draft
    // after any successful reload of the selected vehicle reports a different version.
    const firstVehicle = "33333333-3333-3333-3333-333333333331";
    const secondVehicle = "33333333-3333-3333-3333-333333333332";
    onboardApi.listOnboardSystems.mockResolvedValue(pageFixture([
      summaryFixture(firstVehicle),
      summaryFixture(secondVehicle)
    ]));
    let secondReads = 0;
    onboardApi.getOnboardSystem.mockImplementation(async (vehicleId: string) => {
      if (vehicleId === firstVehicle) return detailFixture(firstVehicle, 7);
      secondReads += 1;
      return detailFixture(secondVehicle, secondReads === 1 ? 3 : 4);
    });

    render(OnboardSystemManagementPage);
    await screen.findByText("整体：DEGRADED");
    await fireEvent.click(screen.getByRole("button", { name: new RegExp(secondVehicle) }));
    expect(await screen.findByRole("heading", { name: secondVehicle })).toBeInTheDocument();
    await fireEvent.click(screen.getByRole("button", { name: "编辑期望配置" }));
    await fireEvent.update(screen.getByLabelText("操作原因"), "刷新版本检查");
    await fireEvent.click(screen.getByRole("button", { name: "刷新" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("配置版本已变化，请重新开始编辑");
    expect(onboardApi.getOnboardSystem).toHaveBeenLastCalledWith(secondVehicle);
    expect(screen.getByRole("heading", { name: secondVehicle })).toBeInTheDocument();
    expect(screen.getByText("配置版本 v4 · 调度服务")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "应用配置" })).not.toBeInTheDocument();
  });
});

function pageFixture(items: ReturnType<typeof summaryFixture>[]) {
  return { items, page: 0, size: 20, totalElements: items.length, totalPages: 1 };
}

function summaryFixture(
  vehicleId = "33333333-3333-3333-3333-333333333331"
) {
  return {
    onboardSystemId: "44444444-4444-4444-4444-444444444444",
    vehicleId,
    status: "ACTIVE",
    operatingMode: "DISPATCH_SERVICE",
    version: 7,
    activeLocationDeviceAlias: "device-aaaaaaaaaaaa",
    wanDeviceAlias: "device-aaaaaaaaaaaa",
    devices: detailFixture(vehicleId).devices
  };
}

function detailFixture(
  vehicleId = "33333333-3333-3333-3333-333333333331",
  version = 7
) {
  return {
    onboardSystemId: "44444444-4444-4444-4444-444444444444",
    vehicleId,
    status: "ACTIVE",
    operatingMode: "DISPATCH_SERVICE",
    version,
    activeLocationDeviceAlias: "device-aaaaaaaaaaaa",
    wanDeviceAlias: "device-aaaaaaaaaaaa",
    readiness: {
      connectivity: "DEGRADED",
      dispatch: "READY",
      location: "READY",
      activeSafety: "UNAVAILABLE",
      video: "DEGRADED",
      dispatchEligible: true,
      overallStatus: "DEGRADED"
    },
    devices: [
      {
        deviceAlias: "device-aaaaaaaaaaaa",
        networkMode: "DIRECT_CELLULAR",
        terminalStatus: "ACTIVE",
        authenticationPresent: true,
        lastRegisteredAt: "2026-08-29T08:00:00Z",
        lastAuthenticatedAt: "2026-08-29T08:01:00Z",
        lastSeenAt: "2026-08-29T08:02:00Z",
        roles: ["DISPATCH", "LOCATION_PRIMARY", "WAN_UPLINK"],
        verifiedCapabilities: ["GBT28787_DISPATCH", "JT808_LOCATION"],
        protocolProfiles: {
          transportProfile: "JT808_2019",
          businessProfile: "GBT28787_2023",
          safetyProfile: "NONE",
          mediaProfile: "NONE",
          activePositionIntervalSeconds: 30,
          idlePositionIntervalSeconds: 60
        }
      },
      {
        deviceAlias: "device-bbbbbbbbbbbb",
        networkMode: "SHARED_LAN_CLIENT",
        terminalStatus: "ACTIVE",
        authenticationPresent: false,
        lastRegisteredAt: null,
        lastAuthenticatedAt: null,
        lastSeenAt: null,
        roles: ["LOCATION_BACKUP", "ACTIVE_SAFETY", "VIDEO"],
        verifiedCapabilities: ["JT808_LOCATION", "ADAS", "VIDEO"],
        protocolProfiles: {
          transportProfile: "JT808_2019",
          businessProfile: "NONE",
          safetyProfile: "JSATL12_2017",
          mediaProfile: "JT1078_2016",
          activePositionIntervalSeconds: 30,
          idlePositionIntervalSeconds: 60
        }
      }
    ]
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}
