BEGIN;

DROP TABLE IF EXISTS objects CASCADE;
DROP TABLE IF EXISTS object_log CASCADE;
DROP TABLE IF EXISTS versions CASCADE;

CREATE TABLE objects
(
    id         BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hash       TEXT    NOT NULL,
    url        TEXT    NOT NULL,
    client_id  TEXT    NOT NULL,
    content    BYTEA   NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
);

CREATE TABLE object_log
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation     TEXT   NOT NULL,
    new_object_id BIGINT,
    old_object_id BIGINT,
    CHECK (operation IN ('INS', 'UPD', 'DEL')),
    CHECK (
            operation = 'INS' AND old_object_id IS NULL AND new_object_id IS NOT NULL OR
            operation = 'UPD' AND new_object_id IS NOT NULL AND new_object_id IS NOT NULL OR
            operation = 'DEL' AND old_object_id IS NOT NULL AND new_object_id IS NULL
        )
);

CREATE TABLE versions
(
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id        TEXT   NOT NULL,
    serial            BIGINT NOT NULL,
    last_log_entry_id BIGINT NOT NULL,
    snapshot_hash     TEXT,
    delta_hash        TEXT,
    snapshot_size     BIGINT,
    delta_size        BIGINT,
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT delta_field_in_sync CHECK (
            delta_size IS NULL AND delta_hash IS NULL OR
            delta_size IS NOT NULL AND delta_hash IS NOT NULL
        ),
    CONSTRAINT snapshot_field_in_sync CHECK (
            snapshot_hash IS NULL AND snapshot_size IS NULL OR
            snapshot_hash IS NOT NULL AND snapshot_size IS NOT NULL
        )
);

CREATE UNIQUE INDEX idx_objects_hash ON objects (hash);
CREATE UNIQUE INDEX idx_objects_url ON objects (url) WHERE NOT is_deleted;

CREATE UNIQUE INDEX idx_uniq_versions_generates ON versions (session_id, serial);

COMMIT;