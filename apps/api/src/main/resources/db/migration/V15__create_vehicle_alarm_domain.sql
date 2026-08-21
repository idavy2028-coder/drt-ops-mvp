CREATE TABLE vehicle_alarms (
  id UUID PRIMARY KEY,
  vehicle_id UUID NOT NULL REFERENCES vehicles(id),
  terminal_id UUID NOT NULL REFERENCES jt_terminals(id),
  location_event_id UUID REFERENCES vehicle_location_events(id),
  vehicle_task_id UUID REFERENCES vehicle_tasks(id),
  standard VARCHAR(40) NOT NULL,
  module VARCHAR(20) NOT NULL CHECK (module IN ('ADAS', 'DMS')),
  terminal_alarm_id BIGINT NOT NULL CHECK (terminal_alarm_id BETWEEN 0 AND 4294967295),
  alarm_type_code INTEGER NOT NULL CHECK (alarm_type_code BETWEEN 0 AND 255),
  alarm_type_name_snapshot VARCHAR(80) NOT NULL,
  alarm_level INTEGER NOT NULL CHECK (alarm_level BETWEEN 0 AND 255),
  terminal_alarm_identifier VARCHAR(64) NOT NULL,
  terminal_alarm_state VARCHAR(10) NOT NULL CHECK (terminal_alarm_state IN ('START', 'END')),
  occurred_at TIMESTAMPTZ NOT NULL,
  ended_at TIMESTAMPTZ,
  gateway_received_at TIMESTAMPTZ NOT NULL,
  longitude NUMERIC(10,7) NOT NULL CHECK (longitude BETWEEN -180 AND 180),
  latitude NUMERIC(10,7) NOT NULL CHECK (latitude BETWEEN -90 AND 90),
  speed_kph NUMERIC(6,2) CHECK (speed_kph >= 0),
  location_quality_status VARCHAR(20) NOT NULL
    CHECK (location_quality_status IN ('GOOD', 'WARNING', 'QUARANTINED', 'REJECTED')),
  location_quality_reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
  processing_status VARCHAR(20) NOT NULL DEFAULT 'NEW'
    CHECK (processing_status IN ('NEW', 'ACKNOWLEDGED', 'PROCESSING', 'RESOLVED', 'FALSE_POSITIVE')),
  payload_digest CHAR(64) NOT NULL CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
  deduplication_key CHAR(64) NOT NULL CHECK (deduplication_key ~ '^[0-9a-f]{64}$'),
  handled_by UUID REFERENCES user_accounts(id),
  handled_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (ended_at IS NULL OR ended_at >= occurred_at)
);

CREATE UNIQUE INDEX uq_vehicle_alarms_deduplication_key ON vehicle_alarms(deduplication_key);
CREATE UNIQUE INDEX uq_vehicle_alarms_open_source_alarm
  ON vehicle_alarms(terminal_id, vehicle_id, standard, module, alarm_type_code, terminal_alarm_id)
  WHERE ended_at IS NULL;
CREATE INDEX idx_vehicle_alarms_vehicle_occurred_at ON vehicle_alarms(vehicle_id, occurred_at DESC);
CREATE INDEX idx_vehicle_alarms_status_occurred_at ON vehicle_alarms(processing_status, occurred_at DESC);
CREATE INDEX idx_vehicle_alarms_occurred_at_brin ON vehicle_alarms USING BRIN(occurred_at);

CREATE FUNCTION prevent_vehicle_alarm_fact_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'vehicle alarm facts are immutable';
  END IF;
  IF ROW(OLD.id, OLD.vehicle_id, OLD.terminal_id, OLD.location_event_id, OLD.vehicle_task_id,
         OLD.standard, OLD.module, OLD.terminal_alarm_id, OLD.alarm_type_code, OLD.alarm_type_name_snapshot, OLD.alarm_level,
         OLD.terminal_alarm_identifier, OLD.terminal_alarm_state, OLD.occurred_at,
         OLD.gateway_received_at, OLD.longitude, OLD.latitude, OLD.speed_kph, OLD.location_quality_status,
         OLD.location_quality_reasons,
         OLD.payload_digest, OLD.deduplication_key, OLD.created_at)
     IS DISTINCT FROM
     ROW(NEW.id, NEW.vehicle_id, NEW.terminal_id, NEW.location_event_id, NEW.vehicle_task_id,
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

CREATE TRIGGER trg_vehicle_alarms_immutable_facts
BEFORE UPDATE OR DELETE ON vehicle_alarms
FOR EACH ROW EXECUTE FUNCTION prevent_vehicle_alarm_fact_mutation();

CREATE TABLE vehicle_alarm_actions (
  id UUID PRIMARY KEY,
  vehicle_alarm_id UUID NOT NULL REFERENCES vehicle_alarms(id),
  action_type VARCHAR(40) NOT NULL,
  from_status VARCHAR(20),
  to_status VARCHAR(20),
  reason VARCHAR(500),
  actor_id UUID REFERENCES user_accounts(id),
  occurred_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE FUNCTION prevent_vehicle_alarm_action_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'vehicle alarm actions are append-only';
END;
$$;

CREATE TRIGGER trg_vehicle_alarm_actions_append_only
BEFORE UPDATE OR DELETE ON vehicle_alarm_actions
FOR EACH ROW EXECUTE FUNCTION prevent_vehicle_alarm_action_mutation();

CREATE TABLE vehicle_alarm_attachments (
  id UUID PRIMARY KEY,
  vehicle_alarm_id UUID NOT NULL REFERENCES vehicle_alarms(id),
  attachment_type VARCHAR(40) NOT NULL,
  channel VARCHAR(40) NOT NULL,
  media_format VARCHAR(40) NOT NULL,
  sanitized_filename VARCHAR(255),
  size_bytes BIGINT CHECK (size_bytes >= 0),
  payload_digest CHAR(64) CHECK (payload_digest IS NULL OR payload_digest ~ '^[0-9a-f]{64}$'),
  external_media_reference VARCHAR(255),
  status VARCHAR(30) NOT NULL CHECK (status IN ('WAITING_MEDIA_SERVICE', 'REQUESTED', 'UPLOADING', 'AVAILABLE', 'FAILED', 'EXPIRED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_vehicle_alarm_attachments_status_created_at
  ON vehicle_alarm_attachments(status, created_at DESC);

CREATE TABLE vehicle_alarm_attachment_transfers (
  id UUID PRIMARY KEY,
  vehicle_alarm_attachment_id UUID NOT NULL REFERENCES vehicle_alarm_attachments(id),
  control_message_type VARCHAR(20) NOT NULL,
  platform_serial_no INTEGER,
  terminal_serial_no INTEGER,
  external_target_reference VARCHAR(255),
  retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
  status VARCHAR(30) NOT NULL CHECK (status IN ('REQUESTED', 'UPLOADING', 'AVAILABLE', 'FAILED', 'EXPIRED')),
  error_code VARCHAR(80),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_vehicle_alarm_attachment_transfers_active
  ON vehicle_alarm_attachment_transfers(vehicle_alarm_attachment_id)
  WHERE status IN ('REQUESTED', 'UPLOADING');

CREATE TABLE vehicle_alarm_outbox (
  id UUID PRIMARY KEY,
  vehicle_alarm_id UUID NOT NULL REFERENCES vehicle_alarms(id),
  event_type VARCHAR(60) NOT NULL,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
  published_at TIMESTAMPTZ,
  retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_vehicle_alarm_outbox_created_at_brin ON vehicle_alarm_outbox USING BRIN(created_at);
CREATE INDEX idx_vehicle_alarm_outbox_status_created_at ON vehicle_alarm_outbox(status, created_at);
