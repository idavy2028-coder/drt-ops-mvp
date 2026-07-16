import { request } from "./http";
import { authStore } from "../auth/authStore";
import { apiErrorFromResponse } from "./errors";
import type { VehicleLocationEventFilters, VehicleLocationEventView, VehicleLocationSnapshotItem } from "./types";

export function listLatestVehicleLocations(): Promise<VehicleLocationSnapshotItem[]> {
  return request<VehicleLocationSnapshotItem[]>("/api/vehicles/locations/latest");
}

export async function listVehicleLocationEvents(filters: VehicleLocationEventFilters): Promise<VehicleLocationEventView[]> {
  if (filters.taskId) {
    const events = await request<VehicleLocationEventView[]>(`/api/vehicle-tasks/${filters.taskId}/location-events${queryString(filters, ["taskId", "vehicleId", "eventType"])}`);
    return filterTaskHistoryEvents(events, filters);
  }
  if (filters.vehicleId) {
    return request<VehicleLocationEventView[]>(`/api/vehicles/${filters.vehicleId}/location-events${queryString(filters, ["vehicleId"])}`);
  }
  return Promise.resolve([]);
}

export async function exportVehicleLocationEvents(filters: VehicleLocationEventFilters): Promise<void> {
  const response = await fetch(buildUrl(`/api/vehicle-locations/export.csv${queryString(filters, ["vehicleId"])}`), {
    headers: authStore.accessToken === null ? undefined : { Authorization: `Bearer ${authStore.accessToken}` }
  });
  if (!response.ok) {
    throw await apiErrorFromResponse(response);
  }
  await response.blob();
}

function filterTaskHistoryEvents(events: VehicleLocationEventView[], filters: VehicleLocationEventFilters): VehicleLocationEventView[] {
  return events.filter((event) => {
    if (filters.eventType && event.eventType !== filters.eventType) {
      return false;
    }
    if (filters.vehicleId && event.vehicleId !== filters.vehicleId) {
      return false;
    }
    return true;
  });
}

function queryString(filters: VehicleLocationEventFilters, omittedKeys: Array<keyof VehicleLocationEventFilters> = []): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (value === undefined || value === "" || omittedKeys.includes(key as keyof VehicleLocationEventFilters)) {
      continue;
    }
    params.set(key, value);
  }
  const serialized = params.toString();
  return serialized === "" ? "" : `?${serialized}`;
}

function buildUrl(path: string): string {
  const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? "";
  const normalizedBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.slice(0, -1) : apiBaseUrl;
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${normalizedBaseUrl}${normalizedPath}`;
}
