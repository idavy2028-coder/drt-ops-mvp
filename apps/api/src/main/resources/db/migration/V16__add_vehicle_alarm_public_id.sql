ALTER TABLE vehicle_alarms ADD COLUMN public_id UUID;

UPDATE vehicle_alarms SET public_id = gen_random_uuid();

ALTER TABLE vehicle_alarms ALTER COLUMN public_id SET NOT NULL;
ALTER TABLE vehicle_alarms ADD CONSTRAINT uq_vehicle_alarms_public_id UNIQUE (public_id);

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
