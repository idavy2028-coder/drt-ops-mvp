// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/vue";
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
});

function detail() {
  return {
    terminalCode: "JT-001",
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
    version: 4,
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
