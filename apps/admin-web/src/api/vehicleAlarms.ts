import { request } from "./http";
import type { DecimalValue, IsoDateTime, UUID } from "./types";

export type VehicleAlarmAction = "ACKNOWLEDGE" | "TAKE_OVER" | "RESOLVE" | "MARK_FALSE_POSITIVE" | "REOPEN";

export interface VehicleAlarmView {
  publicId: UUID;
  vehicleId: UUID;
  standard: string;
  module: "ADAS" | "DMS" | string;
  alarmTypeCode: number;
  alarmType: string;
  level: number;
  status: string;
  occurredAt: IsoDateTime;
  endedAt: IsoDateTime | null;
  locationQualityStatus: string;
  hasAttachment: boolean;
  version: number;
  plateNumber: string | null;
  longitude: DecimalValue | null;
  latitude: DecimalValue | null;
  speedKph: DecimalValue | null;
}

export interface VehicleAlarmFilters {
  level?: number;
  status?: string;
  vehicleId?: UUID;
  module?: "ADAS" | "DMS";
  hasAttachment?: boolean;
}

export interface VehicleAlarmActionInput {
  action: VehicleAlarmAction;
  expectedVersion: number;
  reason: string;
  confirmed: true;
}

export function listVehicleAlarms(filters: VehicleAlarmFilters = {}): Promise<VehicleAlarmView[]> {
  const query = new URLSearchParams();
  if (filters.level !== undefined) query.set("level", String(filters.level));
  if (filters.status) query.set("status", filters.status);
  if (filters.vehicleId) query.set("vehicleId", filters.vehicleId);
  if (filters.module) query.set("module", filters.module);
  if (filters.hasAttachment !== undefined) query.set("hasAttachment", String(filters.hasAttachment));
  const serialized = query.toString();
  return request<VehicleAlarmView[]>(`/api/vehicle-alarms${serialized ? `?${serialized}` : ""}`);
}

export function getVehicleAlarm(publicId: UUID): Promise<VehicleAlarmView> {
  return request<VehicleAlarmView>(`/api/vehicle-alarms/${publicId}`);
}

export function submitVehicleAlarmAction(publicId: UUID, input: VehicleAlarmActionInput): Promise<VehicleAlarmView> {
  return request<VehicleAlarmView>(`/api/vehicle-alarms/${publicId}/actions`, {
    method: "POST",
    body: JSON.stringify(input)
  });
}
