import { request } from "./http";
import type { Driver, ServiceArea, Vehicle, VirtualStop } from "./types";

export function listServiceAreas(): Promise<ServiceArea[]> {
  return request<ServiceArea[]>("/api/service-areas");
}

export function listVirtualStops(serviceAreaId?: string): Promise<VirtualStop[]> {
  const query = serviceAreaId ? `?serviceAreaId=${encodeURIComponent(serviceAreaId)}` : "";
  return request<VirtualStop[]>(`/api/virtual-stops${query}`);
}

export function listVehicles(): Promise<Vehicle[]> {
  return request<Vehicle[]>("/api/vehicles");
}

export function listDrivers(): Promise<Driver[]> {
  return request<Driver[]>("/api/drivers");
}
