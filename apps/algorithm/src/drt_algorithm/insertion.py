from __future__ import annotations

from .explanations import (
    all_rejected_explanation,
    auto_dispatch_explanation,
    low_score_explanation,
    manual_review_explanation,
    no_candidate_explanation,
)
from .matching import filter_feasible_candidates
from .schemas import (
    DispatchDecision,
    DispatchEvaluateRequest,
    DispatchEvaluateResponse,
    DispatchPlan,
)
from .scoring import score_candidate


def evaluate_dispatch(request: DispatchEvaluateRequest) -> DispatchEvaluateResponse:
    if not request.candidate_tasks:
        return DispatchEvaluateResponse(
            decision=DispatchDecision.NO_FEASIBLE_PLAN,
            best_plan=None,
            candidate_count=0,
            rejected_count=0,
            rejected_candidates=[],
            explanation=no_candidate_explanation(),
        )

    feasible_candidates, rejected_candidates = filter_feasible_candidates(request)
    if not feasible_candidates:
        return DispatchEvaluateResponse(
            decision=DispatchDecision.NO_FEASIBLE_PLAN,
            best_plan=None,
            candidate_count=len(request.candidate_tasks),
            rejected_count=len(rejected_candidates),
            rejected_candidates=rejected_candidates,
            explanation=all_rejected_explanation(rejected_candidates),
        )

    best_plan = best_scored_plan(
        [score_candidate(task, request.rule_set) for task in feasible_candidates]
    )

    if best_plan.score >= request.rule_set.auto_dispatch_score_threshold:
        return DispatchEvaluateResponse(
            decision=DispatchDecision.AUTO_DISPATCH,
            best_plan=best_plan,
            candidate_count=len(request.candidate_tasks),
            rejected_count=len(rejected_candidates),
            rejected_candidates=rejected_candidates,
            explanation=auto_dispatch_explanation(
                best_plan, request.rule_set.auto_dispatch_score_threshold
            ),
        )

    if best_plan.score >= request.rule_set.manual_review_score_threshold:
        return DispatchEvaluateResponse(
            decision=DispatchDecision.MANUAL_REVIEW,
            best_plan=best_plan,
            candidate_count=len(request.candidate_tasks),
            rejected_count=len(rejected_candidates),
            rejected_candidates=rejected_candidates,
            explanation=manual_review_explanation(
                best_plan, request.rule_set.manual_review_score_threshold
            ),
        )

    return DispatchEvaluateResponse(
        decision=DispatchDecision.NO_FEASIBLE_PLAN,
        best_plan=best_plan,
        candidate_count=len(request.candidate_tasks),
        rejected_count=len(rejected_candidates),
        rejected_candidates=rejected_candidates,
        explanation=low_score_explanation(
            best_plan, request.rule_set.manual_review_score_threshold
        ),
    )


def best_scored_plan(plans: list[DispatchPlan]) -> DispatchPlan:
    return max(plans, key=lambda plan: plan.score)
