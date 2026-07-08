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
    }


def opposite_direction_task() -> dict:
    task = same_direction_task()
    task["taskId"] = "55555555-5555-5555-5555-555555555555"
    task["directionCompatibility"] = "OPPOSITE_DIRECTION"
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
