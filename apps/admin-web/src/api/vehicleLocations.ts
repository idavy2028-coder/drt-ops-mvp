import { request } from "./http";
import { authStore } from "../auth/authStore";
import { apiErrorFromResponse } from "./errors";
import type { VehicleLocationEventFilters, VehicleLocationEventView, VehicleLocationSnapshotItem } from "./types";

export function listLatestVehicleLocations(): Promise<VehicleLocationSnapshotItem[]> {
  return request<VehicleLocationSnapshotItem[]>("/api/vehicles/locations/latest");
}

export function listVehicleLocationEvents(filters: VehicleLocationEventFilters): Promise<VehicleLocationEventView[]> {
  if (filters.taskId) {
    return request<VehicleLocationEventView[]>(`/api/vehicle-tasks/${filters.taskId}/location-events${queryString(filters, ["taskId", "vehicleId"])}`);
  }
  if (filters.vehicleId) {
    return request<VehicleLocationEventView[]>(`/api/vehicles/${filters.vehicleId}/location-events${queryString(filters, ["vehicleId"])}`);
  }
  return Promise.resolve([]);
}

export async function exportVehicleLocationEvents(filters: VehicleLocationEventFilters): Promise<void> {
  const response = await fetch(buildUrl(`/api/vehicle-locations/export.csv${queryString(filters)}`), {
    headers: authStore.accessToken === null ? undefined : { Authorization: `Bearer ${authStore.accessToken}` }
  });
  if (!response.ok) {
    throw await apiErrorFromResponse(response);
  }
  await response.blob();
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
