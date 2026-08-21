CREATE TABLE gateway_outbox (
    idempotency_key UUID PRIMARY KEY,
    kind VARCHAR(32) NOT NULL,
    schema_version INTEGER NOT NULL,
    payload_json CHARACTER LARGE OBJECT,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    delivered_at TIMESTAMP(9) WITH TIME ZONE,
    last_error_code VARCHAR(96),
    CONSTRAINT chk_gateway_outbox_kind
        CHECK (kind IN ('LOCATION', 'ALARM', 'ATTACHMENT_CONTROL')),
    CONSTRAINT chk_gateway_outbox_status
        CHECK (status IN ('PENDING', 'DELIVERING', 'DELIVERED', 'DEAD_LETTER')),
    CONSTRAINT chk_gateway_outbox_schema_version CHECK (schema_version > 0),
    CONSTRAINT chk_gateway_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_gateway_outbox_delivery
    ON gateway_outbox (status, next_attempt_at, kind, created_at);

CREATE TABLE gateway_dispatch_state (
    lane VARCHAR(32) PRIMARY KEY,
    next_batch_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_gateway_dispatch_lane CHECK (lane IN ('LOCATION'))
);

INSERT INTO gateway_dispatch_state (lane, next_batch_at)
VALUES ('LOCATION', TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00:00');
