import { request } from "./http";
import type {
  AddressSuggestion,
  ServiceAreaBoundaryDraft,
  ServiceAreaBoundaryView,
  ServiceAreaContainment,
  UUID
} from "./types";

export function searchAddressSuggestions(keyword: string, city: string): Promise<AddressSuggestion[]> {
  const query = new URLSearchParams({ keyword, city });
  return request<AddressSuggestion[]>(`/api/map/address-suggestions?${query.toString()}`);
}

export function checkServiceAreaContainment(
  serviceAreaId: UUID,
  longitude: number,
  latitude: number
): Promise<ServiceAreaContainment> {
  return request<ServiceAreaContainment>(`/api/service-areas/${serviceAreaId}/contains`, {
    method: "POST",
    body: JSON.stringify({ longitude, latitude })
  });
}

export function importDistrictBoundary(keyword: string): Promise<ServiceAreaBoundaryView> {
  const query = new URLSearchParams({ keyword });
  return request<ServiceAreaBoundaryView>(`/api/service-areas/import-district-boundary?${query.toString()}`, {
    method: "POST"
  });
}

export function saveServiceAreaBoundary(
  serviceAreaId: string,
  draft: ServiceAreaBoundaryDraft
): Promise<ServiceAreaBoundaryView> {
  return request<ServiceAreaBoundaryView>(`/api/service-areas/${serviceAreaId}/boundary`, {
    method: "PUT",
    body: JSON.stringify(draft)
  });
}

export function publishServiceAreaBoundary(serviceAreaId: string): Promise<ServiceAreaBoundaryView> {
  return request<ServiceAreaBoundaryView>(`/api/service-areas/${serviceAreaId}/publish`, {
    method: "POST"
  });
}
