INSERT INTO dispatch_rule_sets (
  id,
  name,
  max_wait_minutes,
  max_detour_minutes,
  booking_window_minutes,
  auto_dispatch_score_threshold,
  manual_review_score_threshold,
  wait_weight,
  detour_weight,
  stability_weight,
  utilization_weight,
  insertion_policy,
  enabled
) VALUES (
  '11111111-1111-1111-1111-111111111111',
  'Default dynamic insertion rules',
  10,
  8,
  60,
  82.00,
  62.00,
  0.35,
  0.20,
  0.30,
  0.15,
  'DYNAMIC_INSERTION',
  TRUE
);

INSERT INTO service_areas (
  id,
  name,
  boundary,
  service_start,
  service_end,
  rule_set_id,
  enabled
) VALUES (
  '22222222-2222-2222-2222-222222222222',
  'Demo Service Area',
  ST_GeogFromText('SRID=4326;POLYGON((116.3000000 39.9000000,116.3600000 39.9000000,116.3600000 39.9500000,116.3000000 39.9500000,116.3000000 39.9000000))'),
  '06:30:00',
  '22:30:00',
  '11111111-1111-1111-1111-111111111111',
  TRUE
);

INSERT INTO virtual_stops (
  id,
  service_area_id,
  name,
  location,
  service_radius_meters,
  boarding_enabled,
  alighting_enabled,
  safety_note,
  enabled
) VALUES
  (
    '55555555-5555-5555-5555-555555555551',
    '22222222-2222-2222-2222-222222222222',
    'North Gate',
    ST_GeogFromText('SRID=4326;POINT(116.3120000 39.9400000)'),
    250,
    TRUE,
    TRUE,
    'Use marked curbside waiting area.',
    TRUE
  ),
  (
    '55555555-5555-5555-5555-555555555552',
    '22222222-2222-2222-2222-222222222222',
    'Community Center',
    ST_GeogFromText('SRID=4326;POINT(116.3250000 39.9360000)'),
    250,
    TRUE,
    TRUE,
    'Board near the main entrance.',
    TRUE
  ),
  (
    '55555555-5555-5555-5555-555555555553',
    '22222222-2222-2222-2222-222222222222',
    'Metro Link',
    ST_GeogFromText('SRID=4326;POINT(116.3440000 39.9330000)'),
    300,
    TRUE,
    TRUE,
    'Wait at the bus bay.',
    TRUE
  ),
  (
    '55555555-5555-5555-5555-555555555554',
    '22222222-2222-2222-2222-222222222222',
    'Office Park',
    ST_GeogFromText('SRID=4326;POINT(116.3510000 39.9210000)'),
    300,
    TRUE,
    TRUE,
    'Use the south-side pickup area.',
    TRUE
  ),
  (
    '55555555-5555-5555-5555-555555555555',
    '22222222-2222-2222-2222-222222222222',
    'Hospital East',
    ST_GeogFromText('SRID=4326;POINT(116.3330000 39.9140000)'),
    250,
    TRUE,
    TRUE,
    'Avoid emergency vehicle lane.',
    TRUE
  ),
  (
    '55555555-5555-5555-5555-555555555556',
    '22222222-2222-2222-2222-222222222222',
    'South Market',
    ST_GeogFromText('SRID=4326;POINT(116.3090000 39.9120000)'),
    250,
    TRUE,
    TRUE,
    'Wait near the signed loading zone.',
    TRUE
  );

INSERT INTO vehicles (
  id,
  plate_number,
  vehicle_type,
  capacity,
  current_status,
  current_location,
  fleet_name,
  dispatchable
) VALUES
  (
    '33333333-3333-3333-3333-333333333331',
    'DRT-001',
    'Microbus',
    12,
    'IDLE',
    ST_GeogFromText('SRID=4326;POINT(116.3180000 39.9290000)'),
    'Demo Fleet',
    TRUE
  ),
  (
    '33333333-3333-3333-3333-333333333332',
    'DRT-002',
    'Microbus',
    12,
    'IDLE',
    ST_GeogFromText('SRID=4326;POINT(116.3460000 39.9250000)'),
    'Demo Fleet',
    TRUE
  );

INSERT INTO drivers (
  id,
  name,
  phone,
  qualification_status,
  shift_start,
  shift_end,
  current_status,
  fleet_name
) VALUES
  (
    '44444444-4444-4444-4444-444444444441',
    'Demo Driver A',
    '13900000001',
    'QUALIFIED',
    '2026-07-08T06:30:00+08:00',
    '2026-07-08T14:30:00+08:00',
    'AVAILABLE',
    'Demo Fleet'
  ),
  (
    '44444444-4444-4444-4444-444444444442',
    'Demo Driver B',
    '13900000002',
    'QUALIFIED',
    '2026-07-08T14:30:00+08:00',
    '2026-07-08T22:30:00+08:00',
    'AVAILABLE',
    'Demo Fleet'
  );
