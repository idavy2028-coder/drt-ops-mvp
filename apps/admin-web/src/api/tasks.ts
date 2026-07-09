import { request } from "./http";
import type { UUID, VehicleTask } from "./types";

export function listTasks(): Promise<VehicleTask[]> {
  return request<VehicleTask[]>("/api/vehicle-tasks");
}

export function startTask(taskId: UUID): Promise<VehicleTask> {
  return request<VehicleTask>(`/api/vehicle-tasks/${taskId}/start`, { method: "POST" });
}

export function arriveStop(taskId: UUID, taskStopId: UUID): Promise<VehicleTask> {
  return request<VehicleTask>(`/api/vehicle-tasks/${taskId}/stops/${taskStopId}/arrive`, { method: "POST" });
}

export function boardStop(taskId: UUID, taskStopId: UUID): Promise<VehicleTask> {
  return request<VehicleTask>(`/api/vehicle-tasks/${taskId}/stops/${taskStopId}/board`, { method: "POST" });
}

export function alightStop(taskId: UUID, taskStopId: UUID): Promise<VehicleTask> {
  return request<VehicleTask>(`/api/vehicle-tasks/${taskId}/stops/${taskStopId}/alight`, { method: "POST" });
}

export function completeTask(taskId: UUID): Promise<VehicleTask> {
  return request<VehicleTask>(`/api/vehicle-tasks/${taskId}/complete`, { method: "POST" });
}

export function markTaskException(taskId: UUID, reason: string): Promise<VehicleTask> {
  return request<VehicleTask>(`/api/vehicle-tasks/${taskId}/exception`, {
    method: "POST",
    body: JSON.stringify({ reason })
  });
}
