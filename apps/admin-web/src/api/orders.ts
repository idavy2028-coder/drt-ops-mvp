import { request } from "./http";
import type { DispatchResult, RideOrder, UUID } from "./types";

export interface CreateRideOrderInput {
  passengerName: string;
  passengerPhone: string;
  passengerCount: number;
  requestType: string;
  originLng: number;
  originLat: number;
  destinationLng: number;
  destinationLat: number;
  requestedDepartureAt: string;
}

export function listOrders(): Promise<RideOrder[]> {
  return request<RideOrder[]>("/api/orders");
}

export function createOrder(input: CreateRideOrderInput): Promise<RideOrder> {
  return request<RideOrder>("/api/orders", {
    method: "POST",
    body: JSON.stringify(input)
  });
}

export function dispatchOrder(orderId: UUID): Promise<DispatchResult> {
  return request<DispatchResult>(`/api/orders/${orderId}/dispatch`, {
    method: "POST"
  });
}

export function cancelOrder(orderId: UUID, reason: string): Promise<RideOrder> {
  return request<RideOrder>(`/api/orders/${orderId}/cancel`, {
    method: "POST",
    body: JSON.stringify({ reason })
  });
}

export function markOrderNoShow(orderId: UUID, reason: string): Promise<RideOrder> {
  return request<RideOrder>(`/api/orders/${orderId}/no-show`, {
    method: "POST",
    body: JSON.stringify({ reason })
  });
}
