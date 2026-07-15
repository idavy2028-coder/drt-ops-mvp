import { request } from "./http";
import type { VehicleLocationSnapshotItem } from "./types";

export function listLatestVehicleLocations(): Promise<VehicleLocationSnapshotItem[]> {
  return request<VehicleLocationSnapshotItem[]>("/api/vehicles/locations/latest");
}
