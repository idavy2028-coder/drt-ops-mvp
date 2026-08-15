ALTER TABLE vehicle_alarms ADD COLUMN public_id UUID;

UPDATE vehicle_alarms SET public_id = gen_random_uuid();

ALTER TABLE vehicle_alarms ALTER COLUMN public_id SET NOT NULL;
ALTER TABLE vehicle_alarms ADD CONSTRAINT uq_vehicle_alarms_public_id UNIQUE (public_id);
ALTER TABLE vehicle_alarms ADD CONSTRAINT ck_vehicle_alarms_public_id_distinct CHECK (public_id <> id);

CREATE INDEX idx_vehicle_alarm_actions_alarm_created_at
  ON vehicle_alarm_actions (vehicle_alarm_id, created_at DESC);

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM vehicle_alarm_outbox AS outbox
    WHERE outbox.payload = '{}'::jsonb
      AND outbox.event_type NOT IN ('ALARM_CREATED', 'ALARM_STATUS_CHANGED', 'ALARM_ENDED')
  ) THEN
    RAISE EXCEPTION 'V16 cannot backfill an unsupported legacy vehicle alarm outbox event type';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM vehicle_alarm_outbox AS outbox
    LEFT JOIN LATERAL (
      SELECT action.created_at, count(*) AS candidates, min(action.to_status) AS to_status
      FROM vehicle_alarm_actions AS action
      WHERE action.vehicle_alarm_id = outbox.vehicle_alarm_id
        AND action.created_at <= outbox.created_at
      GROUP BY action.created_at
      ORDER BY action.created_at DESC
      LIMIT 1
    ) AS latest ON TRUE
    WHERE outbox.payload = '{}'::jsonb
      AND outbox.event_type = 'ALARM_STATUS_CHANGED'
      AND (latest.created_at IS NULL
        OR latest.candidates <> 1
        OR latest.to_status IS NULL
        OR latest.to_status NOT IN ('NEW', 'ACKNOWLEDGED', 'PROCESSING', 'RESOLVED', 'FALSE_POSITIVE'))
  ) THEN
    RAISE EXCEPTION 'V16 cannot unambiguously backfill a legacy alarm status change';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM vehicle_alarm_outbox AS outbox
    LEFT JOIN LATERAL (
      SELECT action.created_at, count(*) AS candidates, min(action.to_status) AS to_status
      FROM vehicle_alarm_actions AS action
      WHERE action.vehicle_alarm_id = outbox.vehicle_alarm_id
        AND action.created_at <= outbox.created_at
      GROUP BY action.created_at
      ORDER BY action.created_at DESC
      LIMIT 1
    ) AS latest ON TRUE
    WHERE outbox.payload = '{}'::jsonb
      AND outbox.event_type = 'ALARM_ENDED'
      AND latest.created_at IS NOT NULL
      AND (latest.candidates <> 1
        OR latest.to_status IS NULL
        OR latest.to_status NOT IN ('NEW', 'ACKNOWLEDGED', 'PROCESSING', 'RESOLVED', 'FALSE_POSITIVE'))
  ) THEN
    RAISE EXCEPTION 'V16 cannot unambiguously backfill a legacy alarm ended event';
  END IF;
END;
$$;

UPDATE vehicle_alarm_outbox AS outbox
SET payload = jsonb_build_object(
  'publicId', alarm.public_id,
  'eventType', outbox.event_type,
  'status', CASE
    WHEN outbox.event_type = 'ALARM_CREATED' THEN 'NEW'
    WHEN outbox.event_type = 'ALARM_STATUS_CHANGED' THEN (
      SELECT action.to_status
      FROM vehicle_alarm_actions AS action
      WHERE action.vehicle_alarm_id = outbox.vehicle_alarm_id
        AND action.created_at <= outbox.created_at
      ORDER BY action.created_at DESC, action.id DESC
      LIMIT 1
    )
    WHEN outbox.event_type = 'ALARM_ENDED' THEN COALESCE((
      SELECT action.to_status
      FROM vehicle_alarm_actions AS action
      WHERE action.vehicle_alarm_id = outbox.vehicle_alarm_id
        AND action.created_at <= outbox.created_at
      ORDER BY action.created_at DESC, action.id DESC
      LIMIT 1
    ), 'NEW')
  END,
  'level', alarm.alarm_level,
  'module', alarm.module,
  'occurredAt', alarm.occurred_at
)
FROM vehicle_alarms AS alarm
WHERE outbox.vehicle_alarm_id = alarm.id
  AND outbox.payload = '{}'::jsonb;

CREATE OR REPLACE FUNCTION prevent_vehicle_alarm_fact_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'vehicle alarm facts are immutable';
  END IF;
  IF ROW(OLD.id, OLD.public_id, OLD.vehicle_id, OLD.terminal_id, OLD.location_event_id, OLD.vehicle_task_id,
         OLD.standard, OLD.module, OLD.terminal_alarm_id, OLD.alarm_type_code, OLD.alarm_type_name_snapshot, OLD.alarm_level,
         OLD.terminal_alarm_identifier, OLD.terminal_alarm_state, OLD.occurred_at,
         OLD.gateway_received_at, OLD.longitude, OLD.latitude, OLD.speed_kph, OLD.location_quality_status,
         OLD.location_quality_reasons,
         OLD.payload_digest, OLD.deduplication_key, OLD.created_at)
     IS DISTINCT FROM
     ROW(NEW.id, NEW.public_id, NEW.vehicle_id, NEW.terminal_id, NEW.location_event_id, NEW.vehicle_task_id,
         NEW.standard, NEW.module, NEW.terminal_alarm_id, NEW.alarm_type_code, NEW.alarm_type_name_snapshot, NEW.alarm_level,
         NEW.terminal_alarm_identifier, NEW.terminal_alarm_state, NEW.occurred_at,
         NEW.gateway_received_at, NEW.longitude, NEW.latitude, NEW.speed_kph, NEW.location_quality_status,
         NEW.location_quality_reasons,
         NEW.payload_digest, NEW.deduplication_key, NEW.created_at) THEN
    RAISE EXCEPTION 'vehicle alarm facts are immutable';
  END IF;
  RETURN NEW;
END;
$$;
