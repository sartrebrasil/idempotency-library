CREATE TABLE idempotency_record (
    idempotency_key VARCHAR(255)             NOT NULL,
    scope           VARCHAR(255)             NOT NULL,
    status          VARCHAR(20)              NOT NULL,
    fingerprint     VARCHAR(64),
    result_type     VARCHAR(512),
    result_payload  BYTEA,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Token of the execution holding the reservation; complete/release match it.
    owner           VARCHAR(64),
    -- The primary key IS the concurrency control: a losing INSERT fails here.
    CONSTRAINT pk_idempotency_record PRIMARY KEY (idempotency_key, scope)
);

-- Supports the purge job (idempotency.jdbc.cleanup-enabled).
CREATE INDEX ix_idempotency_record_expires_at ON idempotency_record (expires_at);
