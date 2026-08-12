// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { authStore } from "../auth/authStore";
import TerminalManagementPage from "./TerminalManagementPage.vue";

const terminalApi = vi.hoisted(() => ({
  listTerminals: vi.fn(),
  getTerminalDetail: vi.fn(),
  presetTerminal: vi.fn(),
  bindTerminal: vi.fn(),
  activateTerminal: vi.fn(),
  suspendTerminal: vi.fn(),
  retireTerminal: vi.fn(),
  replaceTerminal: vi.fn(),
  rotateTerminalAuthentication: vi.fn(),
  disconnectTerminal: vi.fn()
}));
const resourceApi = vi.hoisted(() => ({ listVehicles: vi.fn() }));

vi.mock("../api/terminals", () => terminalApi);
vi.mock("../api/resources", () => resourceApi);

describe("TerminalManagementPage", () => {
  beforeEach(() => {
    authStore.setSessionForTest({ accessToken: "admin-token", user: { id: "admin-1", username: "admin", roles: ["SYSTEM_ADMIN"], mustChangePassword: false } });
    terminalApi.listTerminals.mockResolvedValue([{ terminalCode: "JT-001", terminalPhoneMasked: "****9012", manufacturerId: "MFG", model: "X1", protocolVersion: "JT808_2019", sourceCoordinateSystem: "GCJ02", status: "ACTIVE", registrationCompleted: true, version: 4 }]);
    terminalApi.getTerminalDetail.mockResolvedValue(detail());
    resourceApi.listVehicles.mockResolvedValue([{ id: "vehicle-1", plateNumber: "甘J-D001", vehicleType: "MINIBUS", capacity: 8, currentStatus: "IDLE", fleetName: "试点车队", dispatchable: true }]);
  });

  afterEach(() => {
    cleanup();
    authStore.clearSessionForTest();
    vi.clearAllMocks();
  });

  it("renders safe terminal status, capabilities and explicit missing gateway data", async () => {
    render(TerminalManagementPage);

    expect(await screen.findByText("JT-001")).toBeInTheDocument();
    expect(screen.getByText("****9012")).toBeInTheDocument();
    expect(screen.getByText("JT/T 1078：支持")).toBeInTheDocument();
    expect(screen.getByText("最近鉴权：尚无数据")).toBeInTheDocument();
    expect(screen.getByText("SESSION_ESTABLISHED")).toBeInTheDocument();
    expect(screen.getByText("所有操作须填写原因，并在提交前进行第二次确认；提交前会重新读取最新版本。")).toBeInTheDocument();
    expect(screen.queryByText("PHONE-9012")).not.toBeInTheDocument();
    expect(screen.queryByText("auth-token-digest")).not.toBeInTheDocument();
    expect(screen.queryByText("raw-payload")).not.toBeInTheDocument();
    expect(screen.queryByText("11111111-1111-1111-1111-111111111111")).not.toBeInTheDocument();
  });

  it("shows management actions only to a terminal manager", async () => {
    render(TerminalManagementPage);
    expect(await screen.findByRole("button", { name: "暂停终端" })).toBeInTheDocument();

    authStore.setSessionForTest({ accessToken: "auditor-token", user: { id: "auditor-1", username: "auditor", roles: ["AUDITOR"], mustChangePassword: false } });
    await Promise.resolve();
    expect(screen.queryByRole("button", { name: "暂停终端" })).not.toBeInTheDocument();
  });

  it("uses freshly loaded versions for replacement and blocks an unconfirmed action", async () => {
    terminalApi.listTerminals.mockResolvedValue([
      summary("JT-001", 4), summary("JT-002", 9)
    ]);
    terminalApi.getTerminalDetail.mockImplementation(async (code: string) => detail(code, code === "JT-002" ? 11 : 6));
    render(TerminalManagementPage);
    await screen.findByRole("button", { name: "换机" });
    await fireEvent.click(screen.getByRole("button", { name: "换机" }));
    await fireEvent.click(screen.getByRole("button", { name: "确认执行" }));
    expect(terminalApi.replaceTerminal).not.toHaveBeenCalled();
    await fireEvent.update(screen.getByLabelText("操作原因"), "换机原因");
    await fireEvent.update(screen.getByLabelText("替换终端"), "JT-002");
    await fireEvent.click(screen.getByLabelText("我已核对风险与原因，确认执行。"));
    await fireEvent.click(screen.getByRole("button", { name: "确认执行" }));
    await waitFor(() => expect(terminalApi.replaceTerminal).toHaveBeenCalledWith("JT-001", expect.objectContaining({ expectedVersion: 6, replacementTerminalCode: "JT-002", replacementExpectedVersion: 11, reason: "换机原因" })));
  });

  it("cancels a replacement when its selected target changes while target detail is loading", async () => {
    let resolveTarget: (value: ReturnType<typeof detail>) => void;
    terminalApi.listTerminals.mockResolvedValue([summary("JT-001", 4), summary("JT-002", 8), summary("JT-003", 9)]);
    let sourceReads = 0;
    terminalApi.getTerminalDetail.mockImplementation((code: string) => {
      if (code === "JT-001") return Promise.resolve(detail("JT-001", sourceReads++ === 0 ? 4 : 12));
      if (code === "JT-002") return new Promise((resolve) => { resolveTarget = resolve; });
      return Promise.resolve(detail("JT-003", 13));
    });
    render(TerminalManagementPage);
    await screen.findByRole("button", { name: "换机" });
    await fireEvent.click(screen.getByRole("button", { name: "换机" }));
    await fireEvent.update(screen.getByLabelText("操作原因"), "换机原因");
    await fireEvent.update(screen.getByLabelText("替换终端"), "JT-002");
    await fireEvent.click(screen.getByLabelText("我已核对风险与原因，确认执行。"));
    await fireEvent.click(screen.getByRole("button", { name: "确认执行" }));
    await waitFor(() => expect(terminalApi.getTerminalDetail).toHaveBeenCalledWith("JT-002"));
    await fireEvent.update(screen.getByLabelText("替换终端"), "JT-003");
    resolveTarget!(detail("JT-002", 15));
    await Promise.resolve();
    expect(terminalApi.replaceTerminal).not.toHaveBeenCalled();
  });

  it("does not send a management request when confirmation is checked but the reason is blank", async () => {
    render(TerminalManagementPage);
    await screen.findByRole("button", { name: "暂停终端" });
    await fireEvent.click(screen.getByRole("button", { name: "暂停终端" }));
    await fireEvent.click(screen.getByLabelText("我已核对风险与原因，确认执行。"));
    await fireEvent.submit(screen.getByRole("button", { name: "确认执行" }).closest("form")!);
    expect(terminalApi.suspendTerminal).not.toHaveBeenCalled();
  });

  it("does not send a management request when the reason is valid but confirmation is unchecked", async () => {
    render(TerminalManagementPage);
    await screen.findByRole("button", { name: "暂停终端" });
    await fireEvent.click(screen.getByRole("button", { name: "暂停终端" }));
    await fireEvent.update(screen.getByLabelText("操作原因"), "安全停用");
    await fireEvent.submit(screen.getByRole("button", { name: "确认执行" }).closest("form")!);
    expect(terminalApi.suspendTerminal).not.toHaveBeenCalled();
  });

  it("keeps the newest terminal selection when an earlier detail request resolves late", async () => {
    let resolveFirst: (value: ReturnType<typeof detail>) => void;
    terminalApi.listTerminals.mockResolvedValue([summary("JT-001", 4), summary("JT-002", 5)]);
    terminalApi.getTerminalDetail.mockImplementation((code: string) => code === "JT-001"
      ? new Promise((resolve) => { resolveFirst = resolve; })
      : Promise.resolve(detail("JT-002", 5)));
    render(TerminalManagementPage);
    await screen.findByText("JT-002");
    await fireEvent.click(screen.getByRole("button", { name: /JT-002/ }));
    expect(await screen.findByRole("heading", { name: "JT-002" })).toBeInTheDocument();
    resolveFirst!(detail("JT-001", 4));
    await Promise.resolve();
    expect(screen.getByRole("heading", { name: "JT-002" })).toBeInTheDocument();
  });

  it("clears old detail and disables actions when the current detail load fails", async () => {
    terminalApi.listTerminals.mockResolvedValue([summary("JT-001", 4), summary("JT-002", 5)]);
    terminalApi.getTerminalDetail.mockImplementation((code: string) => code === "JT-002"
      ? Promise.reject(new Error("unavailable")) : Promise.resolve(detail("JT-001", 4)));
    render(TerminalManagementPage);
    await screen.findByRole("button", { name: "暂停终端" });
    await fireEvent.click(screen.getByRole("button", { name: /JT-002/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("终端详情暂时不可用");
    expect(screen.queryByRole("button", { name: "暂停终端" })).not.toBeInTheDocument();
  });

  it.each([
    ["暂停终端", "suspendTerminal"], ["退役终端", "retireTerminal"], ["轮换鉴权", "rotateTerminalAuthentication"], ["强制断开", "disconnectTerminal"]
  ])("refreshes the source version before %s", async (label, method) => {
    terminalApi.getTerminalDetail.mockResolvedValue(detail("JT-001", 12));
    render(TerminalManagementPage);
    await screen.findByRole("button", { name: label });
    await fireEvent.click(screen.getByRole("button", { name: label }));
    await fireEvent.update(screen.getByLabelText("操作原因"), "安全原因");
    await fireEvent.click(screen.getByLabelText("我已核对风险与原因，确认执行。"));
    await fireEvent.click(screen.getByRole("button", { name: "确认执行" }));
    await waitFor(() => expect(terminalApi[method as keyof typeof terminalApi]).toHaveBeenCalledWith("JT-001", { expectedVersion: 12, reason: "安全原因" }));
  });
});

function summary(terminalCode = "JT-001", version = 4) { return { terminalCode, terminalPhoneMasked: "****9012", manufacturerId: "MFG", model: "X1", protocolVersion: "JT808_2019", sourceCoordinateSystem: "GCJ02", status: "ACTIVE", registrationCompleted: true, version }; }
function detail(terminalCode = "JT-001", version = 4) {
  return {
    terminalCode,
    terminalPhoneMasked: "****9012",
    manufacturerId: "MFG",
    model: "X1",
    protocolVersion: "JT808_2019",
    sourceCoordinateSystem: "GCJ02",
    activeSafetyStandard: null,
    activeSafetyModules: [],
    jt1078Enabled: true,
    status: "ACTIVE",
    onlineStatus: "NEVER_SEEN",
    registrationCompleted: true,
    version,
    lastRegisteredAt: "2026-08-12T08:00:00Z",
    lastAuthenticatedAt: null,
    lastValidMessageAt: null,
    lastHeartbeatAt: null,
    lastLocationAt: null,
    offlineAt: null,
    currentBinding: { plateNumber: "甘J-D001", status: "ACTIVE", validFrom: "2026-08-11T08:00:00Z", validTo: null },
    bindingHistory: [{ plateNumber: "甘J-D001", status: "ACTIVE", validFrom: "2026-08-11T08:00:00Z", validTo: null }],
    securityAudits: [{ eventType: "ONLINE", result: "APPLIED", reasonCode: "SESSION_ESTABLISHED", protocolVersion: "JT808_2019", messageId: 2, occurredAt: "2026-08-12T08:00:00Z" }]
  };
}
