from fastapi.testclient import TestClient

from drt_algorithm.main import app


client = TestClient(app)


BOARDING_STOP_ID = "11111111-1111-1111-1111-111111111111"
ALIGHTING_STOP_ID = "22222222-2222-2222-2222-222222222222"


def sample_request(candidate_tasks: list[dict]) -> dict:
    return {
        "order": {
            "orderId": "00000000-0000-0000-0000-000000000001",
            "passengerCount": 1,
            "requestType": "IMMEDIATE",
            "requestedDepartureAt": "2026-07-08T02:30:00Z",
            "boardingStopId": BOARDING_STOP_ID,
            "alightingStopId": ALIGHTING_STOP_ID,
        },
        "ruleSet": {
            "maxWaitMinutes": 12,
            "maxDetourMinutes": 8,
            "autoDispatchScoreThreshold": 80,
            "manualReviewScoreThreshold": 60,
            "weights": {
                "wait": 0.35,
                "detour": 0.25,
                "stability": 0.30,
                "utilization": 0.10,
            },
            "insertionPolicy": "SAME_DIRECTION_ONLY",
        },
        "candidateTasks": candidate_tasks,
    }


def same_direction_task() -> dict:
    return {
        "taskId": "33333333-3333-3333-3333-333333333333",
        "vehicleId": "44444444-4444-4444-4444-444444444444",
        "availableSeats": 8,
        "currentStopId": BOARDING_STOP_ID,
        "plannedStops": [
            {
                "stopId": BOARDING_STOP_ID,
                "sequence": 1,
                "plannedArrivalAt": "2026-07-08T02:36:00Z",
                "stopType": "BOARDING",
            },
            {
                "stopId": ALIGHTING_STOP_ID,
                "sequence": 2,
                "plannedArrivalAt": "2026-07-08T02:50:00Z",
                "stopType": "ALIGHTING",
            },
        ],
        "estimatedWaitMinutes": 6,
        "estimatedDetourMinutes": 3,
        "directionCompatibility": "SAME_DIRECTION",
        "utilizationAfterInsert": 0.67,
        "candidateType": "EXISTING_TASK",
        "activationCost": 0,
        "precheckRejectionReason": None,
        "taskDisruptionScore": 65,
    }


def opposite_direction_task() -> dict:
    task = same_direction_task()
    task["taskId"] = "55555555-5555-5555-5555-555555555555"
    task["directionCompatibility"] = "OPPOSITE_DIRECTION"
    return task


def new_vehicle_task() -> dict:
    task = same_direction_task()
    task["taskId"] = "66666666-6666-6666-6666-666666666666"
    task["vehicleId"] = "77777777-7777-7777-7777-777777777777"
    task["estimatedWaitMinutes"] = 1
    task["estimatedDetourMinutes"] = 0
    task["utilizationAfterInsert"] = 0.25
    task["candidateType"] = "NEW_TASK"
    task["activationCost"] = 1
    task["taskDisruptionScore"] = 100
    return task


def test_no_vehicle_returns_no_feasible_plan() -> None:
    response = client.post("/dispatch/evaluate", json=sample_request(candidate_tasks=[]))

    assert response.status_code == 200
    body = response.json()
    assert body["decision"] == "NO_FEASIBLE_PLAN"
    assert body["candidateCount"] == 0
    assert body["explanation"]["reason"] == "NO_CANDIDATE_TASK"


def test_same_direction_low_detour_returns_auto_dispatch() -> None:
    response = client.post(
        "/dispatch/evaluate", json=sample_request(candidate_tasks=[same_direction_task()])
    )

    assert response.status_code == 200
    body = response.json()
    assert body["decision"] == "AUTO_DISPATCH"
    assert body["bestPlan"]["score"] >= 80
    assert body["bestPlan"]["estimatedWaitMinutes"] <= 12


def test_same_direction_policy_rejects_opposite_direction_task() -> None:
    response = client.post(
        "/dispatch/evaluate",
        json=sample_request(candidate_tasks=[opposite_direction_task()]),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["decision"] == "NO_FEASIBLE_PLAN"
    assert body["candidateCount"] == 1
    assert body["rejectedCount"] == 1
    assert body["rejectedCandidates"][0]["reason"] == "DIRECTION_MISMATCH"


def test_feasible_existing_task_wins_over_higher_scored_new_vehicle() -> None:
    existing = same_direction_task()
    existing["estimatedWaitMinutes"] = 5
    existing["estimatedDetourMinutes"] = 8
    new_vehicle = new_vehicle_task()

    response = client.post(
        "/dispatch/evaluate",
        json=sample_request(candidate_tasks=[existing, new_vehicle]),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["bestPlan"]["taskId"] == existing["taskId"]
    assert body["bestPlan"]["activationCost"] == 0
    assert body["bestPlan"]["selectionReason"] == "EXISTING_TASK_PREFERRED"


def test_highest_score_wins_within_existing_task_tier() -> None:
    lower_scored = same_direction_task()
    lower_scored["estimatedWaitMinutes"] = 10
    lower_scored["estimatedDetourMinutes"] = 7
    higher_scored = same_direction_task()
    higher_scored["taskId"] = "88888888-8888-8888-8888-888888888888"
    higher_scored["vehicleId"] = "99999999-9999-9999-9999-999999999999"
    higher_scored["estimatedWaitMinutes"] = 2
    higher_scored["estimatedDetourMinutes"] = 1

    body = client.post(
        "/dispatch/evaluate",
        json=sample_request(candidate_tasks=[lower_scored, higher_scored]),
    ).json()

    assert body["bestPlan"]["taskId"] == higher_scored["taskId"]
    assert body["bestPlan"]["activationCost"] == 0


def test_task_disruption_score_breaks_tie_within_existing_task_tier() -> None:
    disrupted = same_direction_task()
    disrupted["taskDisruptionScore"] = 20
    stable = same_direction_task()
    stable["taskId"] = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    stable["vehicleId"] = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    stable["taskDisruptionScore"] = 90

    body = client.post(
        "/dispatch/evaluate",
        json=sample_request(candidate_tasks=[disrupted, stable]),
    ).json()

    assert body["bestPlan"]["taskId"] == stable["taskId"]


def test_new_vehicle_is_selected_when_all_existing_tasks_are_infeasible() -> None:
    existing = same_direction_task()
    existing["estimatedWaitMinutes"] = 13
    new_vehicle = new_vehicle_task()

    body = client.post(
        "/dispatch/evaluate",
        json=sample_request(candidate_tasks=[existing, new_vehicle]),
    ).json()

    assert body["bestPlan"]["taskId"] == new_vehicle["taskId"]
    assert body["bestPlan"]["activationCost"] == 1
    assert body["bestPlan"]["selectionReason"] == "NEW_VEHICLE_REQUIRED"
    assert body["rejectedCandidates"] == [
        {"taskId": existing["taskId"], "reason": "WAIT_TIME_EXCEEDED"}
    ]


def test_precheck_rejection_reason_takes_precedence_over_score_constraints() -> None:
    existing = same_direction_task()
    existing["precheckRejectionReason"] = "ROUTE_INSERTION_UNAVAILABLE"
    new_vehicle = new_vehicle_task()

    body = client.post(
        "/dispatch/evaluate",
        json=sample_request(candidate_tasks=[existing, new_vehicle]),
    ).json()

    assert body["bestPlan"]["taskId"] == new_vehicle["taskId"]
    assert body["rejectedCandidates"] == [
        {"taskId": existing["taskId"], "reason": "ROUTE_INSERTION_UNAVAILABLE"}
    ]


def test_selection_explanation_contains_candidate_cost_and_reason() -> None:
    existing = same_direction_task()

    body = client.post(
        "/dispatch/evaluate",
        json=sample_request(candidate_tasks=[existing]),
    ).json()

    assert body["explanation"]["details"]["candidateType"] == "EXISTING_TASK"
    assert body["explanation"]["details"]["activationCost"] == 0
    assert (
        body["explanation"]["details"]["selectionReason"]
        == "EXISTING_TASK_PREFERRED"
    )


def test_openapi_reports_dispatch_contract_version_0_2_0() -> None:
    body = client.get("/openapi.json").json()

    assert body["info"]["version"] == "0.2.0"
