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
    target_vehicle CONSTANT uuid := '12efe967-0be5-485e-a8dc-836fd64a2516';
    operator_id CONSTANT uuid := '35e8db28-9cfb-4c3b-a107-4a7de7083f04';
    shared_stop CONSTANT uuid := 'f4d512d5-0eac-44ef-9b9e-d8d94c33282b';
    first_boarding CONSTANT uuid := 'fb044aac-d48a-4e36-a8f8-26660f528bda';
    second_boarding CONSTANT uuid := '9ffda872-4d30-4a06-89fb-8dae41fe995c';
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM vehicle_tasks vt
        JOIN vehicles v ON v.id = vt.vehicle_id
        WHERE vt.id = target_task AND vt.status = 'IN_PROGRESS'
          AND vt.vehicle_id = target_vehicle AND v.plate_number = '甘J00856D'
          AND v.current_status = 'IN_SERVICE'
    ) THEN
        RAISE EXCEPTION '任务或车辆不符合补充审计前置条件';
    END IF;

    IF (SELECT string_agg(
            sequence_number || ':' || stop_type || ':' || left(ride_order_id::text, 8) || ':' || status,
            ',' ORDER BY sequence_number)
        FROM task_stops WHERE vehicle_task_id = target_task)
       <> '1:BOARDING:a9c1c313:BOARDED,2:BOARDING:0440ec69:BOARDED,3:ALIGHTING:a9c1c313:PLANNED,4:ALIGHTING:0440ec69:PLANNED' THEN
        RAISE EXCEPTION '当前节点顺序或状态不符合已提交纠正结果';
    END IF;

    IF (SELECT count(*) FROM ride_orders
        WHERE id IN (
            'a9c1c313-c4f5-49aa-8ed5-403c843027f1',
            '0440ec69-afb1-468d-b69e-8c2372fc4ae4')
          AND status = 'IN_PROGRESS') <> 2 THEN
        RAISE EXCEPTION '目标订单状态已变化';
    END IF;

    IF (SELECT count(*) FROM user_accounts
        WHERE id = operator_id AND username = 'dispatcher02' AND enabled) <> 1 THEN
        RAISE EXCEPTION 'dispatcher02 操作者不符合前置条件';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM vehicle_location_events
        WHERE id = '011fdfde-1a23-4769-ac15-075afe61726e'
          AND vehicle_id = target_vehicle AND vehicle_task_id = target_task
          AND task_stop_id = first_boarding AND virtual_stop_id = shared_stop
          AND event_type = 'PICKUP_ARRIVED' AND source = 'MANUAL_DISPATCHER'
          AND longitude = 105.2582240 AND latitude = 35.1976360
          AND coordinate_system = 'GCJ02' AND standardized_address = '高铁站'
          AND driver_reported_at = '2026-08-03 03:56:00+00'::timestamptz
          AND recorded_at = '2026-08-03 03:57:05.593407+00'::timestamptz
          AND recorded_by = operator_id
    ) THEN
        RAISE EXCEPTION '共享高铁站到站事实不符合精确证据';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM vehicle_location_events
        WHERE id = '6fb5b2c5-7dc9-497c-bd8a-c2f4a5fb1701'
          AND vehicle_id = target_vehicle AND vehicle_task_id = target_task
          AND task_stop_id = first_boarding AND virtual_stop_id = shared_stop
          AND event_type = 'PASSENGER_BOARDED' AND source = 'MANUAL_DISPATCHER'
          AND longitude = 105.2582240 AND latitude = 35.1976360
          AND coordinate_system = 'GCJ02' AND standardized_address = '高铁站'
          AND driver_reported_at = '2026-08-03 03:57:00+00'::timestamptz
          AND recorded_at = '2026-08-03 03:59:44.247194+00'::timestamptz
          AND recorded_by = operator_id
    ) THEN
        RAISE EXCEPTION '第一笔上车位置事实不符合精确证据';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM task_stops first_stop
        JOIN task_stops second_stop ON second_stop.id = second_boarding
        WHERE first_stop.id = first_boarding
          AND first_stop.actual_arrival_at = '2026-08-03 03:57:05.599807+00'::timestamptz
          AND second_stop.actual_arrival_at = first_stop.actual_arrival_at
    ) THEN
        RAISE EXCEPTION '两个上车节点未共享历史实际到站时间';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM vehicle_location_events correction
        JOIN vehicle_location_events source_event
          ON source_event.id = '6fb5b2c5-7dc9-497c-bd8a-c2f4a5fb1701'
        WHERE correction.id = 'b995ae6c-de46-40fa-8a68-0c0f48a8e5bc'
          AND correction.vehicle_id = target_vehicle
          AND correction.vehicle_task_id = target_task
          AND correction.task_stop_id = second_boarding
          AND correction.virtual_stop_id = shared_stop
          AND correction.event_type = 'PASSENGER_BOARDED'
          AND correction.source = source_event.source
          AND ST_Equals(correction.location::geometry, source_event.location::geometry)
          AND correction.longitude = source_event.longitude
          AND correction.latitude = source_event.latitude
          AND correction.coordinate_system = source_event.coordinate_system
          AND correction.standardized_address = source_event.standardized_address
          AND correction.driver_reported_at = source_event.driver_reported_at
          AND correction.recorded_by = operator_id
          AND correction.note = 'COLOCATED_BOARDING_INSERTION_ORDER_CORRECTION'
          AND NOT correction.snapshot_applied
          AND correction.outside_service_area = source_event.outside_service_area
    ) THEN
        RAISE EXCEPTION '已提交纠正事件未精确复制预期位置事实';
    END IF;

    IF (SELECT count(*) FROM audit_logs
        WHERE id IN (
            'fabe29aa-c5e5-42aa-b6e8-57d0c07427c7',
            'fe010501-f2c1-4ef3-b5cd-de167a21a17f')
          AND metadata_json ->> 'locationEventId' = 'b995ae6c-de46-40fa-8a68-0c0f48a8e5bc') <> 2 THEN
        RAISE EXCEPTION '现有纠正审计链不完整';
    END IF;

    IF EXISTS (SELECT 1 FROM audit_logs WHERE id = '1078b27e-5700-49c4-bbf5-34c7070e383b') THEN
        RAISE EXCEPTION '补充审计固定 ID 已存在，拒绝重复执行';
    END IF;
END
$assertions$;

INSERT INTO audit_logs (
    id, entity_type, entity_id, action, actor_type, actor_id, reason, metadata_json, created_at
) VALUES (
    '1078b27e-5700-49c4-bbf5-34c7070e383b'::uuid,
    'VEHICLE_TASK',
    '1626d98d-d792-4a7d-91eb-40f6ccbba5be'::uuid,
    'TASK_COLOCATED_BOARDING_CORRECTED',
    'USER',
    '35e8db28-9cfb-4c3b-a107-4a7de7083f04',
    'COLOCATED_BOARDING_CORRECTION_CONTEXT_SUPPLEMENT',
    '{
      "basisCode":"DISPATCHER_CONFIRMED_COLOCATED_BOARDING",
      "basisText":"现场确认两名乘客在高铁站同时上车",
      "correctionExecutedAt":"2026-08-03T05:03:20.836592Z",
      "firstOrderId":"a9c1c313-c4f5-49aa-8ed5-403c843027f1",
      "secondOrderId":"0440ec69-afb1-468d-b69e-8c2372fc4ae4",
      "sharedArrivalEventId":"011fdfde-1a23-4769-ac15-075afe61726e",
      "sourceBoardingEventId":"6fb5b2c5-7dc9-497c-bd8a-c2f4a5fb1701",
      "locationEventId":"b995ae6c-de46-40fa-8a68-0c0f48a8e5bc",
      "oldSequence":[
        {"sequence":1,"stopId":"fb044aac-d48a-4e36-a8f8-26660f528bda","type":"BOARDING","orderId":"a9c1c313-c4f5-49aa-8ed5-403c843027f1"},
        {"sequence":2,"stopId":"8f4ffcc7-48e2-492f-89fc-844580445913","type":"ALIGHTING","orderId":"a9c1c313-c4f5-49aa-8ed5-403c843027f1"},
        {"sequence":3,"stopId":"9ffda872-4d30-4a06-89fb-8dae41fe995c","type":"BOARDING","orderId":"0440ec69-afb1-468d-b69e-8c2372fc4ae4"},
        {"sequence":4,"stopId":"4b7f1206-4fb9-480f-84fd-d0a3eb9cf6c1","type":"ALIGHTING","orderId":"0440ec69-afb1-468d-b69e-8c2372fc4ae4"}
      ],
      "newSequence":[
        {"sequence":1,"stopId":"fb044aac-d48a-4e36-a8f8-26660f528bda","type":"BOARDING","orderId":"a9c1c313-c4f5-49aa-8ed5-403c843027f1"},
        {"sequence":2,"stopId":"9ffda872-4d30-4a06-89fb-8dae41fe995c","type":"BOARDING","orderId":"0440ec69-afb1-468d-b69e-8c2372fc4ae4"},
        {"sequence":3,"stopId":"8f4ffcc7-48e2-492f-89fc-844580445913","type":"ALIGHTING","orderId":"a9c1c313-c4f5-49aa-8ed5-403c843027f1"},
        {"sequence":4,"stopId":"4b7f1206-4fb9-480f-84fd-d0a3eb9cf6c1","type":"ALIGHTING","orderId":"0440ec69-afb1-468d-b69e-8c2372fc4ae4"}
      ]
    }'::jsonb,
    clock_timestamp()
);

DO $postconditions$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM audit_logs
        WHERE id = '1078b27e-5700-49c4-bbf5-34c7070e383b'
          AND action = 'TASK_COLOCATED_BOARDING_CORRECTED'
          AND metadata_json ->> 'basisCode' = 'DISPATCHER_CONFIRMED_COLOCATED_BOARDING'
          AND metadata_json ->> 'basisText' = '现场确认两名乘客在高铁站同时上车'
          AND jsonb_array_length(metadata_json -> 'oldSequence') = 4
          AND jsonb_array_length(metadata_json -> 'newSequence') = 4
          AND metadata_json ->> 'locationEventId' = 'b995ae6c-de46-40fa-8a68-0c0f48a8e5bc'
    ) THEN
        RAISE EXCEPTION '补充纠正审计后置断言失败';
    END IF;
END
$postconditions$;

COMMIT;
