BEGIN;

DROP TABLE IF EXISTS object_urls CASCADE;
DROP TABLE IF EXISTS objects CASCADE;
DROP TABLE IF EXISTS object_log CASCADE;
DROP TABLE IF EXISTS versions CASCADE;

CREATE TABLE objects
(
    id      BIGSERIAL PRIMARY KEY,
    hash    CHAR(64) NOT NULL,
    content BYTEA    NOT NULL
);

CREATE TABLE object_urls
(
    url       TEXT PRIMARY KEY,
    object_id BIGINT NOT NULL,
    client_id TEXT   NOT NULL,
    CONSTRAINT fk_objects_urls_to_object
        FOREIGN KEY (object_id)
            REFERENCES objects (id) ON DELETE CASCADE
);

CREATE TABLE object_log
(
    id        BIGSERIAL PRIMARY KEY,
    operation CHAR(3) NOT NULL,
    url       TEXT    NOT NULL,
    old_hash  CHAR(64),
    content   BYTEA,
    CHECK (operation IN ('INS', 'UPD', 'DEL')),
    CHECK (
            operation = 'INS' AND old_hash IS NULL AND content IS NOT NULL OR
            operation = 'UPD' AND old_hash IS NOT NULL AND content IS NOT NULL OR
            operation = 'DEL' AND old_hash IS NOT NULL AND content IS NULL
        )
);

CREATE TABLE versions
(
    id                BIGSERIAL PRIMARY KEY,
    session_id        TEXT   NOT NULL,
    serial            BIGINT,
    last_log_entry_id BIGINT NOT NULL,
    snapshot_hash     CHAR(64),
    delta_hash        CHAR(64),
    snapshot_size     BIGINT,
    delta_size        BIGINT,
    CHECK (
            delta_size IS NULL AND delta_hash IS NULL OR
            delta_size IS NOT NULL AND delta_size IS NOT NULL
        ),
    CHECK (
            snapshot_hash IS NULL AND snapshot_size IS NULL AND
            snapshot_hash IS NOT NULL AND snapshot_size IS NOT NULL
        )
);

CREATE UNIQUE INDEX idx_objects_hash ON objects (hash);
CREATE INDEX idx_object_urls_client_id ON object_urls (client_id);

-- Only one version in the pending state is allowed (it must be the last one)
CREATE UNIQUE INDEX idx_uniq_versions_generates ON versions (session_id, serial);

COMMIT;