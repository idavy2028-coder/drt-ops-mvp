from __future__ import annotations

from .schemas import CandidateRejection, DispatchExplanation, DispatchPlan


def no_candidate_explanation() -> DispatchExplanation:
    return DispatchExplanation(
        reason="NO_CANDIDATE_TASK",
        message="No running vehicle task was provided for dispatch evaluation.",
    )


def all_rejected_explanation(
    rejected_candidates: list[CandidateRejection],
) -> DispatchExplanation:
    return DispatchExplanation(
        reason="ALL_CANDIDATES_REJECTED",
        message="All candidate tasks failed dispatch constraints.",
        details={
            "rejectionReasons": [item.reason for item in rejected_candidates],
        },
    )


def auto_dispatch_explanation(best_plan: DispatchPlan, threshold: float) -> DispatchExplanation:
    return DispatchExplanation(
        reason="AUTO_DISPATCH_THRESHOLD_REACHED",
        message="Best plan reached the auto-dispatch score threshold.",
        details={
            "score": best_plan.score,
            "threshold": threshold,
        },
    )


def manual_review_explanation(best_plan: DispatchPlan, threshold: float) -> DispatchExplanation:
    return DispatchExplanation(
        reason="MANUAL_REVIEW_THRESHOLD_REACHED",
        message="Best plan needs dispatcher confirmation before assignment.",
        details={
            "score": best_plan.score,
            "threshold": threshold,
        },
    )


def low_score_explanation(best_plan: DispatchPlan, threshold: float) -> DispatchExplanation:
    return DispatchExplanation(
        reason="LOW_SCORE",
        message="Best plan did not reach the manual review threshold.",
        details={
            "score": best_plan.score,
            "threshold": threshold,
        },
    )
