import { request } from "./http";
import type { AuditLog, OperationsSummary, UUID } from "./types";

export function getOperationsSummary(date?: string): Promise<OperationsSummary> {
  const query = date ? `?date=${encodeURIComponent(date)}` : "";
  return request<OperationsSummary>(`/api/metrics/operations-summary${query}`);
}

export function listAuditLogs(entityId?: UUID): Promise<AuditLog[]> {
  const query = entityId ? `?entityId=${encodeURIComponent(entityId)}` : "";
  return request<AuditLog[]>(`/api/audit-logs${query}`);
}
