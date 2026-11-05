-- Schema for idempotency.store=jdbc (MySQL / MariaDB).
CREATE TABLE IF NOT EXISTS idempotency_record (
    idempotency_key VARCHAR(255) NOT NULL,
    scope           VARCHAR(255) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    fingerprint     VARCHAR(64),
    result_type     VARCHAR(512),
    result_payload  LONGBLOB,
    created_at      DATETIME(6)  NOT NULL,
    expires_at      DATETIME(6)  NOT NULL,
    -- Token of the execution holding the reservation; complete/release match it.
    -- Upgrading: ALTER TABLE idempotency_record ADD COLUMN owner VARCHAR(64);
    owner           VARCHAR(64),
    PRIMARY KEY (idempotency_key, scope),
    INDEX ix_idempotency_record_expires_at (expires_at)
);
