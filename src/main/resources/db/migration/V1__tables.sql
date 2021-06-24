DROP TABLE IF EXISTS objects CASCADE;
DROP TABLE IF EXISTS object_log CASCADE;
DROP TABLE IF EXISTS versions CASCADE;

CREATE TABLE objects
(
    id         BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hash       BYTEA   NOT NULL CHECK (LENGTH(hash) = 32),
    url        TEXT    NOT NULL,
    client_id  TEXT    NOT NULL,
    content    BYTEA   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX idx_objects_url ON objects (url) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX idx_objects_hash ON objects (hash);
CREATE INDEX idx_objects_client_id ON objects (client_id);


CREATE TABLE versions
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id         TEXT                     NOT NULL,
    serial             BIGINT                   NOT NULL,
    snapshot_file_name TEXT,
    delta_file_name    TEXT,
    snapshot_hash      BYTEA  CHECK (LENGTH(snapshot_hash) = 32),
    delta_hash         BYTEA  CHECK (LENGTH(delta_hash) = 32),
    snapshot_size      BIGINT,
    delta_size         BIGINT,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT delta_field_in_sync CHECK (
                delta_size IS NULL
                AND delta_hash IS NULL
                AND delta_file_name IS NULL
            OR
                delta_size IS NOT NULL
                    AND delta_hash IS NOT NULL
                    AND delta_file_name IS NOT NULL
        ),
    CONSTRAINT snapshot_field_in_sync CHECK (
                snapshot_size IS NULL
                AND snapshot_hash IS NULL
                AND snapshot_file_name IS NULL
            OR
                snapshot_size IS NOT NULL
                    AND snapshot_hash IS NOT NULL
                    AND snapshot_file_name IS NOT NULL
        )
);

CREATE UNIQUE INDEX idx_versions_session_id_serial ON versions (session_id ASC, serial DESC);


CREATE TABLE object_log
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation     TEXT   NOT NULL,
    new_object_id BIGINT,
    old_object_id BIGINT,
    version_id    BIGINT,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CHECK (CASE operation
           WHEN 'INS' THEN old_object_id IS NULL AND new_object_id IS NOT NULL
           WHEN 'UPD' THEN new_object_id IS NOT NULL AND new_object_id IS NOT NULL
           WHEN 'DEL' THEN old_object_id IS NOT NULL AND new_object_id IS NULL
           ELSE FALSE
           END
        ),
    FOREIGN KEY (new_object_id) REFERENCES objects (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    FOREIGN KEY (old_object_id) REFERENCES objects (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    FOREIGN KEY (version_id) REFERENCES versions (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE INDEX idx_object_log_new_object_id ON object_log (new_object_id) WHERE new_object_id IS NOT NULL;
CREATE INDEX idx_object_log_old_object_id ON object_log (old_object_id) WHERE old_object_id IS NOT NULL;
CREATE INDEX idx_object_log_version_id ON object_log (version_id);
