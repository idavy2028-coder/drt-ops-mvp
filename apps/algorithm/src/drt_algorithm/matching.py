from __future__ import annotations

from .schemas import (
    CandidateRejection,
    CandidateTask,
    DirectionCompatibility,
    DispatchEvaluateRequest,
    InsertionPolicy,
)


def filter_feasible_candidates(
    request: DispatchEvaluateRequest,
) -> tuple[list[CandidateTask], list[CandidateRejection]]:
    feasible: list[CandidateTask] = []
    rejected: list[CandidateRejection] = []

    for task in request.candidate_tasks:
        rejection_reason = first_rejection_reason(request, task)
        if rejection_reason is None:
            feasible.append(task)
        else:
            rejected.append(
                CandidateRejection(task_id=task.task_id, reason=rejection_reason)
            )

    return feasible, rejected


def lowest_activation_cost_tier(candidates: list[CandidateTask]) -> list[CandidateTask]:
    minimum_cost = min(candidate.activation_cost for candidate in candidates)
    return [
        candidate
        for candidate in candidates
        if candidate.activation_cost == minimum_cost
    ]


def first_rejection_reason(
    request: DispatchEvaluateRequest, task: CandidateTask
) -> str | None:
    if task.precheck_rejection_reason is not None:
        return task.precheck_rejection_reason

    if task.available_seats < request.order.passenger_count:
        return "INSUFFICIENT_CAPACITY"

    if task.estimated_wait_minutes > request.rule_set.max_wait_minutes:
        return "WAIT_TIME_EXCEEDED"

    if task.estimated_detour_minutes > request.rule_set.max_detour_minutes:
        return "DETOUR_TIME_EXCEEDED"

    if (
        request.rule_set.insertion_policy == InsertionPolicy.SAME_DIRECTION_ONLY
        and task.direction_compatibility != DirectionCompatibility.SAME_DIRECTION
    ):
        return "DIRECTION_MISMATCH"

    return None
