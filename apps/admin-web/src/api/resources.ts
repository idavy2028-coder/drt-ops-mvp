import { request } from "./http";
import type { Driver, ServiceArea, Vehicle, VirtualStop, VirtualStopDraft, VirtualStopImportResult } from "./types";

export function listServiceAreas(): Promise<ServiceArea[]> {
  return request<ServiceArea[]>("/api/service-areas");
}

export function listVirtualStops(filters: { serviceAreaId?: string; enabled?: boolean; keyword?: string } = {}): Promise<VirtualStop[]> {
  const query = new URLSearchParams();
  if (filters.serviceAreaId) query.set("serviceAreaId", filters.serviceAreaId);
  if (filters.enabled !== undefined) query.set("enabled", String(filters.enabled));
  if (filters.keyword?.trim()) query.set("keyword", filters.keyword.trim());
  const suffix = query.size ? `?${query.toString()}` : "";
  return request<VirtualStop[]>(`/api/virtual-stops${suffix}`);
}

export function createVirtualStop(draft: VirtualStopDraft): Promise<VirtualStop> {
  return request<VirtualStop>("/api/virtual-stops", { method: "POST", body: JSON.stringify(draft) });
}

export function updateVirtualStop(stopId: string, draft: VirtualStopDraft): Promise<VirtualStop> {
  return request<VirtualStop>(`/api/virtual-stops/${stopId}`, { method: "PUT", body: JSON.stringify(draft) });
}

export function importVirtualStops(file: File): Promise<VirtualStopImportResult> {
  const form = new FormData();
  form.append("file", file);
  return request<VirtualStopImportResult>("/api/virtual-stops/import", { method: "POST", body: form });
}

export function listVehicles(): Promise<Vehicle[]> {
  return request<Vehicle[]>("/api/vehicles");
}

export function listDrivers(): Promise<Driver[]> {
  return request<Driver[]>("/api/drivers");
}
