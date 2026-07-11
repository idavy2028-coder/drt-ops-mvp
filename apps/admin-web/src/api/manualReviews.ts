import { request } from "./http";
import type { DispatchResult, ManualReviewQueueItem, UUID } from "./types";

export function listManualReviews(): Promise<ManualReviewQueueItem[]> {
  return request<ManualReviewQueueItem[]>("/api/dispatch-decisions/manual-review");
}

export function approveManualReview(decisionId: UUID): Promise<DispatchResult> {
  return request<DispatchResult>(`/api/dispatch-decisions/${decisionId}/approve`, {
    method: "POST"
  });
}

export function rejectManualReview(decisionId: UUID, reason: string): Promise<DispatchResult> {
  return request<DispatchResult>(`/api/dispatch-decisions/${decisionId}/reject`, {
    method: "POST",
    body: JSON.stringify({ reason })
  });
}
