import { request } from "./http";
import type { LocationReportInput, TaskActionResponse, UUID, VehicleTask } from "./types";

export function listTasks(): Promise<VehicleTask[]> {
  return request<VehicleTask[]>("/api/vehicle-tasks");
}

export function startTask(taskId: UUID, locationReport: LocationReportInput): Promise<TaskActionResponse> {
  return request<TaskActionResponse>(`/api/vehicle-tasks/${taskId}/start`, taskActionOptions(locationReport));
}

export function arriveStop(taskId: UUID, taskStopId: UUID, locationReport: LocationReportInput): Promise<TaskActionResponse> {
  return request<TaskActionResponse>(`/api/vehicle-tasks/${taskId}/stops/${taskStopId}/arrive`, taskActionOptions(locationReport));
}

export function boardStop(taskId: UUID, taskStopId: UUID, locationReport: LocationReportInput): Promise<TaskActionResponse> {
  return request<TaskActionResponse>(`/api/vehicle-tasks/${taskId}/stops/${taskStopId}/board`, taskActionOptions(locationReport));
}

export function alightStop(taskId: UUID, taskStopId: UUID, locationReport: LocationReportInput): Promise<TaskActionResponse> {
  return request<TaskActionResponse>(`/api/vehicle-tasks/${taskId}/stops/${taskStopId}/alight`, taskActionOptions(locationReport));
}

export function completeTask(taskId: UUID, locationReport: LocationReportInput): Promise<TaskActionResponse> {
  return request<TaskActionResponse>(`/api/vehicle-tasks/${taskId}/complete`, taskActionOptions(locationReport));
}

export function markTaskException(taskId: UUID, reason: string): Promise<VehicleTask> {
  return request<VehicleTask>(`/api/vehicle-tasks/${taskId}/exception`, {
    method: "POST",
    body: JSON.stringify({ reason })
  });
}

export function markTaskSevereDelay(taskId: UUID, reason: string): Promise<VehicleTask> {
  return request<VehicleTask>(`/api/vehicle-tasks/${taskId}/delay`, {
    method: "POST",
    body: JSON.stringify({ reason })
  });
}

function taskActionOptions(locationReport: LocationReportInput): RequestInit {
  return {
    method: "POST",
    body: JSON.stringify({ locationReport })
  };
}
