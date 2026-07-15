import { request } from "./http";
import type { LocationReportInput, TaskActionResponse, UUID, VehicleTask } from "./types";

type RawTaskActionResponse = VehicleTask | (Partial<TaskActionResponse> & { task?: VehicleTask });

export function listTasks(): Promise<VehicleTask[]> {
  return request<VehicleTask[]>("/api/vehicle-tasks");
}

export function startTask(taskId: UUID, locationReport: LocationReportInput): Promise<TaskActionResponse> {
  return taskActionRequest(`/api/vehicle-tasks/${taskId}/start`, locationReport);
}

export function arriveStop(taskId: UUID, taskStopId: UUID, locationReport: LocationReportInput): Promise<TaskActionResponse> {
  return taskActionRequest(`/api/vehicle-tasks/${taskId}/stops/${taskStopId}/arrive`, locationReport);
}

export function boardStop(taskId: UUID, taskStopId: UUID, locationReport: LocationReportInput): Promise<TaskActionResponse> {
  return taskActionRequest(`/api/vehicle-tasks/${taskId}/stops/${taskStopId}/board`, locationReport);
}

export function alightStop(taskId: UUID, taskStopId: UUID, locationReport: LocationReportInput): Promise<TaskActionResponse> {
  return taskActionRequest(`/api/vehicle-tasks/${taskId}/stops/${taskStopId}/alight`, locationReport);
}

export function completeTask(taskId: UUID, locationReport: LocationReportInput): Promise<TaskActionResponse> {
  return taskActionRequest(`/api/vehicle-tasks/${taskId}/complete`, locationReport);
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

async function taskActionRequest(path: string, locationReport: LocationReportInput): Promise<TaskActionResponse> {
  return normalizeTaskActionResponse(await request<RawTaskActionResponse>(path, taskActionOptions(locationReport)));
}

function normalizeTaskActionResponse(response: RawTaskActionResponse): TaskActionResponse {
  if (isWrappedTaskActionResponse(response)) {
    return {
      task: response.task,
      locationEvent: response.locationEvent,
      snapshotApplied: response.snapshotApplied ?? response.locationEvent?.snapshotApplied ?? false,
      warnings: response.warnings ?? [],
      replayed: response.replayed ?? false
    };
  }
  if (!isVehicleTask(response)) {
    throw new Error("任务动作响应格式不正确");
  }
  return {
    task: response,
    snapshotApplied: false,
    warnings: [],
    replayed: false
  };
}

function isWrappedTaskActionResponse(response: RawTaskActionResponse): response is Partial<TaskActionResponse> & { task: VehicleTask } {
  return typeof response === "object" && response !== null && "task" in response && response.task !== undefined;
}

function isVehicleTask(response: RawTaskActionResponse): response is VehicleTask {
  return typeof response === "object" && response !== null && "id" in response && "vehicleId" in response && "stops" in response;
}
