# P5 同站点多人上车纠正前证据（2026-08-03）

## 范围与结论

- 目标环境：本地试点 PostgreSQL `drt_ops_pilot_bootstrap`。
- 目标任务：`1626d98d-d792-4a7d-91eb-40f6ccbba5be`。
- 目标车辆：`12efe967-0be5-485e-a8dc-836fd64a2516`，车牌 `甘J00856D`。
- 采集方式：只读 SQL 查询；未修改业务数据。
- 隐私处理：不记录乘客姓名、电话、密码或无关订单信息。

## 纠正前任务节点

| 序号 | 节点 ID | 类型 | 订单 ID | 虚拟站点 ID | 站点 | 状态 | 实际到站时间 |
|---:|---|---|---|---|---|---|---|
| 1 | `fb044aac-d48a-4e36-a8f8-26660f528bda` | BOARDING | `a9c1c313-c4f5-49aa-8ed5-403c843027f1` | `f4d512d5-0eac-44ef-9b9e-d8d94c33282b` | 高铁站 | BOARDED | `2026-08-03 03:57:05.599807+00` |
| 2 | `8f4ffcc7-48e2-492f-89fc-844580445913` | ALIGHTING | `a9c1c313-c4f5-49aa-8ed5-403c843027f1` | `25a238fb-e941-42bc-95a2-3a425cd73ef6` | 安川 | PLANNED | — |
| 3 | `9ffda872-4d30-4a06-89fb-8dae41fe995c` | BOARDING | `0440ec69-afb1-468d-b69e-8c2372fc4ae4` | `f4d512d5-0eac-44ef-9b9e-d8d94c33282b` | 高铁站 | PLANNED | — |
| 4 | `4b7f1206-4fb9-480f-84fd-d0a3eb9cf6c1` | ALIGHTING | `0440ec69-afb1-468d-b69e-8c2372fc4ae4` | `cd62cad5-6ae5-42bf-9089-7eab5b5c02ed` | 陇阳镇 | PLANNED | — |

任务状态为 `IN_PROGRESS`，两笔订单状态均为 `IN_PROGRESS`。两个上车节点共享高铁站 ID `f4d512d5-0eac-44ef-9b9e-d8d94c33282b`，但被第一笔下车节点隔开。

## 任务与订单最小恢复快照

### 车辆任务

| 字段 | 值 |
|---|---|
| id | `1626d98d-d792-4a7d-91eb-40f6ccbba5be` |
| vehicle_id | `12efe967-0be5-485e-a8dc-836fd64a2516` |
| driver_id | `bece927e-a853-4556-8065-7d3c14c3d749` |
| status | `IN_PROGRESS` |
| planned_start_at | `2026-08-03 03:57:00+00` |
| planned_end_at | — |
| current_stop_id | `f4d512d5-0eac-44ef-9b9e-d8d94c33282b` |
| source_type | `ALGORITHM` |
| created_at | `2026-08-03 03:50:42.875195+00` |
| updated_at | `2026-08-03 03:57:05.599825+00` |

### 订单（不含姓名与电话）

| 订单 ID | 状态 | 人数 | 类型 | 起点坐标 | 终点坐标 | 上车站 ID | 下车站 ID | 预计上车 | 预计到达 |
|---|---|---:|---|---|---|---|---|---|---|
| `a9c1c313-c4f5-49aa-8ed5-403c843027f1` | IN_PROGRESS | 1 | IMMEDIATE | `105.2633470,35.1982550` | `105.2733980,35.2157760` | `f4d512d5-0eac-44ef-9b9e-d8d94c33282b` | `25a238fb-e941-42bc-95a2-3a425cd73ef6` | `2026-08-03 03:57:00+00` | `2026-08-03 04:01:22+00` |
| `0440ec69-afb1-468d-b69e-8c2372fc4ae4` | IN_PROGRESS | 1 | IMMEDIATE | `105.2632570,35.1962070` | `105.3306050,35.2824340` | `f4d512d5-0eac-44ef-9b9e-d8d94c33282b` | `cd62cad5-6ae5-42bf-9089-7eab5b5c02ed` | `2026-08-03 03:57:00+00` | `2026-08-03 04:15:00+00` |

两笔订单均为 `GCJ02`、地址来源 `DISPATCHER_ENTRY`，`failure_reason` 为空；纠正事务不修改任何订单字段。

## 派单决策

| 决策 ID | 订单 ID | 结果 | 目标任务 | 目标车辆 | 分数 | 创建时间 |
|---|---|---|---|---|---:|---|
| `8d77a060-6fa2-40ea-acf4-c39d35d7ef83` | `a9c1c313-c4f5-49aa-8ed5-403c843027f1` | AUTO_DISPATCH | `1626d98d-d792-4a7d-91eb-40f6ccbba5be` | `12efe967-0be5-485e-a8dc-836fd64a2516` | 85.59 | `2026-08-03 03:50:42.942851+00` |
| `306dbd78-dd98-44e0-9690-edb1616e7961` | `0440ec69-afb1-468d-b69e-8c2372fc4ae4` | MANUAL_REVIEW | `1626d98d-d792-4a7d-91eb-40f6ccbba5be` | `12efe967-0be5-485e-a8dc-836fd64a2516` | 79.15 | `2026-08-03 03:51:13.608547+00` |

## 原始位置事件

| 事件 ID | 类型 | 节点 ID | 驾驶员上报时间 | 记录时间 | 操作者 ID |
|---|---|---|---|---|---|
| `412bb403-54ad-4cb2-816d-84e538b2f955` | TASK_STARTED | — | `2026-08-03 03:56:00+00` | `2026-08-03 03:56:53.363230+00` | `35e8db28-9cfb-4c3b-a107-4a7de7083f04` |
| `011fdfde-1a23-4769-ac15-075afe61726e` | PICKUP_ARRIVED | `fb044aac-d48a-4e36-a8f8-26660f528bda` | `2026-08-03 03:56:00+00` | `2026-08-03 03:57:05.593407+00` | `35e8db28-9cfb-4c3b-a107-4a7de7083f04` |
| `6fb5b2c5-7dc9-497c-bd8a-c2f4a5fb1701` | PASSENGER_BOARDED | `fb044aac-d48a-4e36-a8f8-26660f528bda` | `2026-08-03 03:57:00+00` | `2026-08-03 03:59:44.247194+00` | `35e8db28-9cfb-4c3b-a107-4a7de7083f04` |

原始高铁站到站事件为 `011fdfde-1a23-4769-ac15-075afe61726e`；第一笔上车事件为 `6fb5b2c5-7dc9-497c-bd8a-c2f4a5fb1701`。

## 原始审计

| 审计 ID | 动作 | 原因/节点 ID | 引用位置事件 ID |
|---|---|---|---|
| `27333417-215e-44a4-9e34-058a707ce425` | TASK_STARTED | — | `412bb403-54ad-4cb2-816d-84e538b2f955` |
| `38b1c3d7-e1bc-4947-93c0-895e48f8c7c1` | TASK_STOP_ARRIVED | `fb044aac-d48a-4e36-a8f8-26660f528bda` | `011fdfde-1a23-4769-ac15-075afe61726e` |
| `48a842d3-c80c-42ea-a1b7-804fb731b8bc` | PASSENGER_BOARDED | `fb044aac-d48a-4e36-a8f8-26660f528bda` | `6fb5b2c5-7dc9-497c-bd8a-c2f4a5fb1701` |

操作者账号 `dispatcher02` 唯一且启用，用户 ID 为 `35e8db28-9cfb-4c3b-a107-4a7de7083f04`。

### 订单级审计

| 订单 ID | 动作 | 操作者 | 原因 | 创建时间 |
|---|---|---|---|---|
| `a9c1c313-c4f5-49aa-8ed5-403c843027f1` | ORDER_AUTO_DISPATCHED | SYSTEM / dispatch-orchestrator | AUTO_DISPATCH_THRESHOLD_REACHED | `2026-08-03 03:50:42.951840+00` |
| `0440ec69-afb1-468d-b69e-8c2372fc4ae4` | ORDER_PENDING_MANUAL_REVIEW | SYSTEM / dispatch-orchestrator | MANUAL_REVIEW_THRESHOLD_REACHED | `2026-08-03 03:51:13.624981+00` |
| `0440ec69-afb1-468d-b69e-8c2372fc4ae4` | MANUAL_REVIEW_APPROVED | USER / `35e8db28-9cfb-4c3b-a107-4a7de7083f04` | — | `2026-08-03 03:54:26.669776+00` |

纠正事务不修改或删除订单级审计。

## 恢复基线

如纠正事务需要人工回退，应恢复本文件“纠正前任务节点”的原序号、状态和实际到站时间，并删除且仅删除纠正事务固定生成的以下追加记录：

- 位置事件：`b995ae6c-de46-40fa-8a68-0c0f48a8e5bc`
- 上车审计：`fabe29aa-c5e5-42aa-b6e8-57d0c07427c7`
- 纠正审计：`fe010501-f2c1-4ef3-b5cd-de167a21a17f`
- 上下文补充审计：`1078b27e-5700-49c4-bbf5-34c7070e383b`

执行恢复前仍必须锁定目标任务并复核当前状态，禁止不带前置条件直接删除记录。
