# 调度算法服务接口契约

本文档定义 Java 调度编排层调用 Python/FastAPI 算法服务的第一版契约。算法服务只返回调度建议和解释，不直接修改订单、车辆任务、任务节点或审计数据。

## 接口

- 方法：`POST`
- 路径：`/dispatch/evaluate`
- 用途：基于待调度订单、调度规则组和候选车辆任务，评估是否可自动派发、需要人工确认，或暂无可行方案。

## 请求结构

```json
{
  "order": {
    "orderId": "00000000-0000-0000-0000-000000000001",
    "passengerCount": 1,
    "requestType": "IMMEDIATE",
    "requestedDepartureAt": "2026-07-08T02:30:00Z",
    "boardingStopId": "11111111-1111-1111-1111-111111111111",
    "alightingStopId": "22222222-2222-2222-2222-222222222222"
  },
  "ruleSet": {
    "maxWaitMinutes": 12,
    "maxDetourMinutes": 8,
    "autoDispatchScoreThreshold": 80,
    "manualReviewScoreThreshold": 60,
    "weights": {
      "wait": 0.35,
      "detour": 0.25,
      "stability": 0.3,
      "utilization": 0.1
    },
    "insertionPolicy": "SAME_DIRECTION_ONLY"
  },
  "candidateTasks": [
    {
      "taskId": "33333333-3333-3333-3333-333333333333",
      "vehicleId": "44444444-4444-4444-4444-444444444444",
      "availableSeats": 8,
      "currentStopId": "11111111-1111-1111-1111-111111111111",
      "plannedStops": [
        {
          "stopId": "11111111-1111-1111-1111-111111111111",
          "sequence": 1,
          "plannedArrivalAt": "2026-07-08T02:36:00Z",
          "stopType": "BOARDING"
        }
      ],
      "estimatedWaitMinutes": 6,
      "estimatedDetourMinutes": 3,
      "directionCompatibility": "SAME_DIRECTION",
      "utilizationAfterInsert": 0.67
    }
  ]
}
```

## 响应结构

```json
{
  "decision": "AUTO_DISPATCH",
  "bestPlan": {
    "taskId": "33333333-3333-3333-3333-333333333333",
    "vehicleId": "44444444-4444-4444-4444-444444444444",
    "score": 88.67,
    "estimatedWaitMinutes": 6,
    "estimatedDetourMinutes": 3,
    "directionCompatibility": "SAME_DIRECTION",
    "utilizationAfterInsert": 0.67
  },
  "candidateCount": 1,
  "rejectedCount": 0,
  "rejectedCandidates": [],
  "explanation": {
    "reason": "AUTO_DISPATCH_THRESHOLD_REACHED",
    "message": "Best plan reached the auto-dispatch score threshold.",
    "details": {
      "score": 88.67,
      "threshold": 80
    }
  }
}
```

## 决策值

- `AUTO_DISPATCH`：最高分达到自动派发阈值，可由调度编排层继续执行派发落库。
- `MANUAL_REVIEW`：最高分达到人工确认阈值但未达到自动派发阈值，应进入调度工作台。
- `NO_FEASIBLE_PLAN`：无候选、候选全部违反硬约束，或最高分低于人工确认阈值。

`candidateCount` 表示本次请求传入的候选任务总数，`rejectedCount` 表示被硬约束剔除的候选任务数。

## 第一版硬约束

- 候选任务为空时直接返回 `NO_FEASIBLE_PLAN`，解释原因为 `NO_CANDIDATE_TASK`。
- 候选车辆剩余座位数必须大于或等于订单乘客数。
- 预计等待时间不能超过 `maxWaitMinutes`。
- 预计绕行时间不能超过 `maxDetourMinutes`。
- 当 `insertionPolicy` 为 `SAME_DIRECTION_ONLY` 时，候选任务必须为 `SAME_DIRECTION`。

## 第一版评分维度

- `wait`：等待时间越接近上限，得分越低。
- `detour`：绕行时间越接近上限，得分越低。
- `stability`：同方向且绕行较小的任务稳定性更高。
- `utilization`：插单后座位利用率越接近合理目标，得分越高。

评分结果只作为算法建议。订单状态、任务状态、审计日志和最终派发结果由 Java 调度编排层负责。
