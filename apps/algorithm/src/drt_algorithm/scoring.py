from __future__ import annotations

from .schemas import CandidateTask, DirectionCompatibility, DispatchPlan, DispatchRuleSet


def score_candidate(task: CandidateTask, rule_set: DispatchRuleSet) -> DispatchPlan:
    wait_score = bounded_score(
        100 - ratio(task.estimated_wait_minutes, rule_set.max_wait_minutes) * 30
    )
    detour_score = bounded_score(
        100 - ratio(task.estimated_detour_minutes, rule_set.max_detour_minutes) * 30
    )
    stability_score = stability_component(task)
    utilization_score = utilization_component(task.utilization_after_insert)

    weights = rule_set.weights
    total_weight = weights.wait + weights.detour + weights.stability + weights.utilization
    weighted_score = (
        wait_score * weights.wait
        + detour_score * weights.detour
        + stability_score * weights.stability
        + utilization_score * weights.utilization
    ) / total_weight

    return DispatchPlan(
        task_id=task.task_id,
        vehicle_id=task.vehicle_id,
        score=round(weighted_score, 2),
        estimated_wait_minutes=task.estimated_wait_minutes,
        estimated_detour_minutes=task.estimated_detour_minutes,
        direction_compatibility=task.direction_compatibility,
        utilization_after_insert=task.utilization_after_insert,
    )


def ratio(value: int, limit: int) -> float:
    if limit <= 0:
        return 0 if value <= 0 else 1
    return min(value / limit, 1)


def bounded_score(value: float) -> float:
    return max(0, min(value, 100))


def stability_component(task: CandidateTask) -> float:
    if task.direction_compatibility == DirectionCompatibility.SAME_DIRECTION:
        return bounded_score(95 - task.estimated_detour_minutes * 1.25)
    if task.direction_compatibility == DirectionCompatibility.UNKNOWN:
        return 65
    return 40


def utilization_component(utilization_after_insert: float) -> float:
    target_utilization = 0.75
    return bounded_score(100 - abs(target_utilization - utilization_after_insert) * 80)
