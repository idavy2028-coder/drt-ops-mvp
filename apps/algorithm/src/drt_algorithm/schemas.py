from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator


def to_camel(value: str) -> str:
    parts = value.split("_")
    return parts[0] + "".join(part.title() for part in parts[1:])


class ApiModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class RequestType(StrEnum):
    IMMEDIATE = "IMMEDIATE"
    RESERVATION = "RESERVATION"


class InsertionPolicy(StrEnum):
    SAME_DIRECTION_ONLY = "SAME_DIRECTION_ONLY"
    FLEXIBLE = "FLEXIBLE"


class DirectionCompatibility(StrEnum):
    SAME_DIRECTION = "SAME_DIRECTION"
    OPPOSITE_DIRECTION = "OPPOSITE_DIRECTION"
    UNKNOWN = "UNKNOWN"


class StopType(StrEnum):
    BOARDING = "BOARDING"
    ALIGHTING = "ALIGHTING"
    PASS_THROUGH = "PASS_THROUGH"


class DispatchDecision(StrEnum):
    AUTO_DISPATCH = "AUTO_DISPATCH"
    MANUAL_REVIEW = "MANUAL_REVIEW"
    NO_FEASIBLE_PLAN = "NO_FEASIBLE_PLAN"


class RideOrderInput(ApiModel):
    order_id: UUID
    passenger_count: int = Field(ge=1)
    request_type: RequestType
    requested_departure_at: datetime
    boarding_stop_id: UUID
    alighting_stop_id: UUID


class DispatchWeights(ApiModel):
    wait: float = Field(ge=0, le=1)
    detour: float = Field(ge=0, le=1)
    stability: float = Field(ge=0, le=1)
    utilization: float = Field(ge=0, le=1)

    @model_validator(mode="after")
    def validate_total_weight(self) -> DispatchWeights:
        total = self.wait + self.detour + self.stability + self.utilization
        if total <= 0:
            raise ValueError("At least one dispatch weight must be positive")
        return self


class DispatchRuleSet(ApiModel):
    max_wait_minutes: int = Field(gt=0)
    max_detour_minutes: int = Field(ge=0)
    auto_dispatch_score_threshold: float = Field(ge=0, le=100)
    manual_review_score_threshold: float = Field(ge=0, le=100)
    weights: DispatchWeights
    insertion_policy: InsertionPolicy

    @model_validator(mode="after")
    def validate_thresholds(self) -> DispatchRuleSet:
        if self.manual_review_score_threshold > self.auto_dispatch_score_threshold:
            raise ValueError("manualReviewScoreThreshold cannot exceed autoDispatchScoreThreshold")
        return self


class PlannedStop(ApiModel):
    stop_id: UUID
    sequence: int = Field(ge=1)
    planned_arrival_at: datetime
    stop_type: StopType


class CandidateTask(ApiModel):
    task_id: UUID
    vehicle_id: UUID
    available_seats: int = Field(ge=0)
    current_stop_id: UUID
    planned_stops: list[PlannedStop] = Field(default_factory=list)
    estimated_wait_minutes: int = Field(ge=0)
    estimated_detour_minutes: int = Field(ge=0)
    direction_compatibility: DirectionCompatibility
    utilization_after_insert: float = Field(ge=0, le=1)


class DispatchEvaluateRequest(ApiModel):
    order: RideOrderInput
    rule_set: DispatchRuleSet
    candidate_tasks: list[CandidateTask] = Field(default_factory=list)


class CandidateRejection(ApiModel):
    task_id: UUID
    reason: str


class DispatchPlan(ApiModel):
    task_id: UUID
    vehicle_id: UUID
    score: float = Field(ge=0, le=100)
    estimated_wait_minutes: int = Field(ge=0)
    estimated_detour_minutes: int = Field(ge=0)
    direction_compatibility: DirectionCompatibility
    utilization_after_insert: float = Field(ge=0, le=1)


class DispatchExplanation(ApiModel):
    reason: str
    message: str
    details: dict[str, Any] = Field(default_factory=dict)


class DispatchEvaluateResponse(ApiModel):
    decision: DispatchDecision
    best_plan: DispatchPlan | None = None
    candidate_count: int = Field(ge=0)
    rejected_count: int = Field(ge=0)
    rejected_candidates: list[CandidateRejection] = Field(default_factory=list)
    explanation: DispatchExplanation
