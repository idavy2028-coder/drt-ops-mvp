import { request } from "./http";
import type { ServiceAreaBoundaryDraft, ServiceAreaBoundaryView } from "./types";

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
