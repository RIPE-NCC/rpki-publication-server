BEGIN;

DROP TABLE IF EXISTS object_urls;
DROP TABLE IF EXISTS objects;
DROP TABLE IF EXISTS object_log;
DROP TABLE IF EXISTS versions;

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

CREATE TABLE versions
(
    version    BIGINT PRIMARY KEY,
    session_id TEXT NOT NULL,
    state      TEXT NOT NULL DEFAULT 'pending',
    serial     BIGINT,
    CHECK (state IN ('pending', 'generated'))
);

CREATE TABLE object_log
(
    id        BIGSERIAL PRIMARY KEY,
    version   BIGINT,
    object_id BIGINT,
    operation CHAR(3)  NOT NULL,
    hash      CHAR(64) NOT NULL,
    CHECK (operation IN ('INS', 'UPD', 'DEL')),
    CONSTRAINT fk_object_log_to_version
        FOREIGN KEY (version)
            REFERENCES versions (version) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_objects_hash ON objects (hash);
CREATE INDEX idx_object_urls_client_id ON object_urls (client_id);

-- Only one version in the pending state is allowed (it must be the last one)
CREATE UNIQUE INDEX idx_uniq_versions_version ON versions (version);
CREATE UNIQUE INDEX idx_uniq_versions_only_one_pending ON versions (state) WHERE state = 'pending';
CREATE UNIQUE INDEX idx_uniq_versions_generates ON versions (session_id, serial);

COMMIT;