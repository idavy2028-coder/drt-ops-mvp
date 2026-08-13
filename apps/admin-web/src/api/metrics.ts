import { request } from "./http";
import type { AuditLog, OperationsDashboard, OperationsSummary, UUID } from "./types";

export function getOperationsSummary(date?: string): Promise<OperationsSummary> {
  const query = date ? `?date=${encodeURIComponent(date)}` : "";
  return request<OperationsSummary>(`/api/metrics/operations-summary${query}`);
}

export function getOperationsDashboard(endDate: string, days: 7 = 7): Promise<OperationsDashboard> {
  const query = new URLSearchParams({ endDate, days: String(days) });
  return request<OperationsDashboard>(`/api/metrics/operations-dashboard?${query.toString()}`);
}

export function listAuditLogs(entityId?: UUID): Promise<AuditLog[]> {
  const query = entityId ? `?entityId=${encodeURIComponent(entityId)}` : "";
  return request<AuditLog[]>(`/api/audit-logs${query}`);
}
