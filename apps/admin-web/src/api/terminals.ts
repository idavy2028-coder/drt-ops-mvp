import { request } from "./http";
import type { TerminalActionInput, TerminalDetail, TerminalSummary, UUID } from "./types";

export function listTerminals(): Promise<TerminalSummary[]> {
  return request<TerminalSummary[]>("/api/terminals");
}

export function getTerminalDetail(terminalCode: string): Promise<TerminalDetail> {
  return request<TerminalDetail>(`/api/terminals/${encodeURIComponent(terminalCode)}`);
}

export function presetTerminal(input: { terminalPhone: string; terminalCode: string; manufacturerId: string; model: string; protocolVersion: string; sourceCoordinateSystem: string; reason: string }): Promise<TerminalSummary> {
  return request<TerminalSummary>("/api/terminals", { method: "POST", body: JSON.stringify(input) });
}

export function bindTerminal(terminalCode: string, input: TerminalActionInput & { vehicleId: UUID }): Promise<TerminalSummary> {
  return request<TerminalSummary>(`/api/terminals/${encodeURIComponent(terminalCode)}/bind`, { method: "POST", body: JSON.stringify(input) });
}

export function activateTerminal(terminalCode: string, input: TerminalActionInput): Promise<TerminalSummary> { return action(terminalCode, "activate", input); }
export function suspendTerminal(terminalCode: string, input: TerminalActionInput): Promise<TerminalSummary> { return action(terminalCode, "suspend", input); }
export function retireTerminal(terminalCode: string, input: TerminalActionInput): Promise<TerminalSummary> { return action(terminalCode, "retire", input); }
export function rotateTerminalAuthentication(terminalCode: string, input: TerminalActionInput): Promise<TerminalSummary> { return action(terminalCode, "rotate-auth", input); }
export function disconnectTerminal(terminalCode: string, input: TerminalActionInput): Promise<TerminalSummary> { return action(terminalCode, "disconnect", input); }

export function replaceTerminal(terminalCode: string, input: TerminalActionInput & { replacementTerminalCode: string; replacementExpectedVersion: number }): Promise<TerminalSummary> {
  return request<TerminalSummary>(`/api/terminals/${encodeURIComponent(terminalCode)}/replace`, { method: "POST", body: JSON.stringify(input) });
}

function action(terminalCode: string, name: string, input: TerminalActionInput): Promise<TerminalSummary> {
  return request<TerminalSummary>(`/api/terminals/${encodeURIComponent(terminalCode)}/${name}`, { method: "POST", body: JSON.stringify(input) });
}
