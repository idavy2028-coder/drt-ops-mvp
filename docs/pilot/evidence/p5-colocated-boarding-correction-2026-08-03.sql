\set ON_ERROR_STOP on

BEGIN;

SELECT id
FROM vehicle_tasks
WHERE id = '1626d98d-d792-4a7d-91eb-40f6ccbba5be'::uuid
  AND status = 'IN_PROGRESS'
FOR UPDATE;

DO $assertions$
DECLARE
    target_task CONSTANT uuid := '1626d98d-d792-4a7d-91eb-40f6ccbba5be';
    first_order CONSTANT uuid := 'a9c1c313-c4f5-49aa-8ed5-403c843027f1';
    second_order CONSTANT uuid := '0440ec69-afb1-468d-b69e-8c2372fc4ae4';
    first_boarding CONSTANT uuid := 'fb044aac-d48a-4e36-a8f8-26660f528bda';
    first_alighting CONSTANT uuid := '8f4ffcc7-48e2-492f-89fc-844580445913';
    second_boarding CONSTANT uuid := '9ffda872-4d30-4a06-89fb-8dae41fe995c';
    second_alighting CONSTANT uuid := '4b7f1206-4fb9-480f-84fd-d0a3eb9cf6c1';
    shared_stop CONSTANT uuid := 'f4d512d5-0eac-44ef-9b9e-d8d94c33282b';
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM vehicle_tasks vt
        JOIN vehicles v ON v.id = vt.vehicle_id
        WHERE vt.id = target_task AND vt.status = 'IN_PROGRESS' AND v.plate_number = '甘J00856D'
    ) THEN
        RAISE EXCEPTION '目标任务、状态或车辆不符合纠正前置条件';
    END IF;

    IF (SELECT count(*) FROM task_stops WHERE vehicle_task_id = target_task) <> 4 THEN
        RAISE EXCEPTION '目标任务节点数不等于 4';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM task_stops
        WHERE id = first_boarding AND vehicle_task_id = target_task
          AND ride_order_id = first_order AND sequence_number = 1
          AND stop_type = 'BOARDING' AND virtual_stop_id = shared_stop
          AND status = 'BOARDED' AND actual_arrival_at IS NOT NULL
    ) OR NOT EXISTS (
        SELECT 1 FROM task_stops
        WHERE id = first_alighting AND vehicle_task_id = target_task
          AND ride_order_id = first_order AND sequence_number = 2
          AND stop_type = 'ALIGHTING' AND status = 'PLANNED' AND actual_arrival_at IS NULL
    ) OR NOT EXISTS (
        SELECT 1 FROM task_stops
        WHERE id = second_boarding AND vehicle_task_id = target_task
          AND ride_order_id = second_order AND sequence_number = 3
          AND stop_type = 'BOARDING' AND virtual_stop_id = shared_stop
          AND status = 'PLANNED' AND actual_arrival_at IS NULL
    ) OR NOT EXISTS (
        SELECT 1 FROM task_stops
        WHERE id = second_alighting AND vehicle_task_id = target_task
          AND ride_order_id = second_order AND sequence_number = 4
          AND stop_type = 'ALIGHTING' AND status = 'PLANNED' AND actual_arrival_at IS NULL
    ) THEN
        RAISE EXCEPTION '目标任务节点顺序或状态不符合纠正前置条件';
    END IF;

    IF (SELECT count(*) FROM ride_orders
        WHERE id IN (first_order, second_order) AND status = 'IN_PROGRESS') <> 2 THEN
        RAISE EXCEPTION '两笔目标订单并非均为 IN_PROGRESS';
    END IF;

    IF (SELECT count(*) FROM user_accounts
        WHERE id = '35e8db28-9cfb-4c3b-a107-4a7de7083f04'
          AND username = 'dispatcher02' AND enabled) <> 1 THEN
        RAISE EXCEPTION 'dispatcher02 操作者不唯一、未启用或 ID 已变化';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM vehicle_location_events
        WHERE id = '011fdfde-1a23-4769-ac15-075afe61726e'
          AND vehicle_task_id = target_task AND task_stop_id = first_boarding
          AND event_type = 'PICKUP_ARRIVED'
    ) OR NOT EXISTS (
        SELECT 1 FROM vehicle_location_events
        WHERE id = '6fb5b2c5-7dc9-497c-bd8a-c2f4a5fb1701'
          AND vehicle_task_id = target_task AND task_stop_id = first_boarding
          AND event_type = 'PASSENGER_BOARDED'
    ) THEN
        RAISE EXCEPTION '原始到站或第一笔上车位置事件不符合前置条件';
    END IF;

    IF EXISTS (SELECT 1 FROM vehicle_location_events WHERE id = 'b995ae6c-de46-40fa-8a68-0c0f48a8e5bc')
       OR EXISTS (SELECT 1 FROM audit_logs WHERE id IN (
            'fabe29aa-c5e5-42aa-b6e8-57d0c07427c7',
            'fe010501-f2c1-4ef3-b5cd-de167a21a17f')) THEN
        RAISE EXCEPTION '纠正记录固定 ID 已存在，拒绝重复执行';
    END IF;
END
$assertions$;

UPDATE task_stops
SET sequence_number = 20
WHERE id = '8f4ffcc7-48e2-492f-89fc-844580445913';

UPDATE task_stops
SET sequence_number = 2,
    actual_arrival_at = (
        SELECT actual_arrival_at
        FROM task_stops
        WHERE id = 'fb044aac-d48a-4e36-a8f8-26660f528bda'
    ),
    status = 'BOARDED'
WHERE id = '9ffda872-4d30-4a06-89fb-8dae41fe995c';

UPDATE task_stops
SET sequence_number = 3
WHERE id = '8f4ffcc7-48e2-492f-89fc-844580445913';

INSERT INTO vehicle_location_events (
    id, vehicle_id, vehicle_task_id, task_stop_id, virtual_stop_id,
    event_type, source, location, longitude, latitude, coordinate_system,
    standardized_address, driver_reported_at, recorded_at, recorded_by,
    note, correction_reason, corrects_event_id, idempotency_key,
    request_fingerprint, snapshot_applied, outside_service_area
)
SELECT
    'b995ae6c-de46-40fa-8a68-0c0f48a8e5bc'::uuid,
    vehicle_id,
    vehicle_task_id,
    '9ffda872-4d30-4a06-89fb-8dae41fe995c'::uuid,
    virtual_stop_id,
    'PASSENGER_BOARDED',
    source,
    location,
    longitude,
    latitude,
    coordinate_system,
    standardized_address,
    driver_reported_at,
    clock_timestamp(),
    '35e8db28-9cfb-4c3b-a107-4a7de7083f04'::uuid,
    'COLOCATED_BOARDING_INSERTION_ORDER_CORRECTION',
    NULL,
    NULL,
    'e069d511-c9ff-476d-bdf9-423f1e73b467'::uuid,
    repeat('0', 64),
    false,
    outside_service_area
FROM vehicle_location_events
WHERE id = '6fb5b2c5-7dc9-497c-bd8a-c2f4a5fb1701';

INSERT INTO audit_logs (
    id, entity_type, entity_id, action, actor_type, actor_id, reason, metadata_json, created_at
) VALUES (
    'fabe29aa-c5e5-42aa-b6e8-57d0c07427c7'::uuid,
    'VEHICLE_TASK',
    '1626d98d-d792-4a7d-91eb-40f6ccbba5be'::uuid,
    'PASSENGER_BOARDED',
    'USER',
    '35e8db28-9cfb-4c3b-a107-4a7de7083f04',
    '9ffda872-4d30-4a06-89fb-8dae41fe995c',
    '{"locationEventId":"b995ae6c-de46-40fa-8a68-0c0f48a8e5bc"}'::jsonb,
    clock_timestamp()
), (
    'fe010501-f2c1-4ef3-b5cd-de167a21a17f'::uuid,
    'VEHICLE_TASK',
    '1626d98d-d792-4a7d-91eb-40f6ccbba5be'::uuid,
    'TASK_COLOCATED_BOARDING_CORRECTED',
    'USER',
    '35e8db28-9cfb-4c3b-a107-4a7de7083f04',
    'COLOCATED_BOARDING_INSERTION_ORDER_CORRECTION',
    '{"firstOrderId":"a9c1c313-c4f5-49aa-8ed5-403c843027f1","secondOrderId":"0440ec69-afb1-468d-b69e-8c2372fc4ae4","firstBoardingStopId":"fb044aac-d48a-4e36-a8f8-26660f528bda","secondBoardingStopId":"9ffda872-4d30-4a06-89fb-8dae41fe995c","sharedArrivalEventId":"011fdfde-1a23-4769-ac15-075afe61726e","locationEventId":"b995ae6c-de46-40fa-8a68-0c0f48a8e5bc"}'::jsonb,
    clock_timestamp()
);

DO $postconditions$
BEGIN
    IF (SELECT string_agg(stop_type || ':' || status, ',' ORDER BY sequence_number)
        FROM task_stops
        WHERE vehicle_task_id = '1626d98d-d792-4a7d-91eb-40f6ccbba5be')
       <> 'BOARDING:BOARDED,BOARDING:BOARDED,ALIGHTING:PLANNED,ALIGHTING:PLANNED' THEN
        RAISE EXCEPTION '纠正后节点顺序或状态断言失败';
    END IF;

    IF (SELECT count(*) FROM vehicle_location_events
        WHERE id = 'b995ae6c-de46-40fa-8a68-0c0f48a8e5bc'
          AND task_stop_id = '9ffda872-4d30-4a06-89fb-8dae41fe995c'
          AND event_type = 'PASSENGER_BOARDED') <> 1 THEN
        RAISE EXCEPTION '纠正型上车位置事件断言失败';
    END IF;

    IF (SELECT count(*) FROM audit_logs
        WHERE id IN ('fabe29aa-c5e5-42aa-b6e8-57d0c07427c7', 'fe010501-f2c1-4ef3-b5cd-de167a21a17f')
          AND metadata_json ->> 'locationEventId' = 'b995ae6c-de46-40fa-8a68-0c0f48a8e5bc') <> 2 THEN
        RAISE EXCEPTION '纠正审计断言失败';
    END IF;
END
$postconditions$;

COMMIT;
