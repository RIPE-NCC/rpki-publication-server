BEGIN;

CREATE OR REPLACE FUNCTION last_pending_version() RETURNS BIGINT AS
$body$
DECLARE
    the_version    BIGINT;
    the_serial     BIGINT;
    the_session_id TEXT;
BEGIN
    PERFORM pg_advisory_lock(9999);

    SELECT version
    INTO the_version
    FROM versions
    WHERE state = 'pending';

    IF FOUND THEN
        RETURN the_version;
    ELSE
        SELECT version + 1, session_id, serial
        INTO the_version, the_session_id, the_serial
        FROM versions
        ORDER BY version DESC
        LIMIT 1;

        INSERT INTO versions(version, session_id, state, serial)
        VALUES (the_version, the_session_id, 'pending', the_serial + 1);
    END IF;

    PERFORM pg_advisory_unlock(9999);

    RETURN the_version;
END
$body$
    LANGUAGE plpgsql;


-- TODO Generate session id internally
CREATE OR REPLACE FUNCTION init_versions(session_id_ TEXT) RETURNS BIGINT AS
$body$
BEGIN
    PERFORM pg_advisory_lock(9999);

    IF (SELECT COUNT(*) FROM versions) = 0 THEN
        INSERT INTO versions(version, session_id, state, serial)
        VALUES (1, session_id_, 'pending', 1);
    END IF;

    PERFORM pg_advisory_unlock(9999);

    RETURN 1;
END
$body$
    LANGUAGE plpgsql;


-- TODO Do something with object_log entries as well
CREATE OR REPLACE FUNCTION mark_pending_as_generated() RETURNS VOID AS
$body$
BEGIN
    PERFORM pg_advisory_lock(9999);

    WITH generated_version AS (
        UPDATE versions SET state = 'generated'
            WHERE state = 'pending'
            RETURNING version
    )
    DELETE
    FROM object_log
    WHERE version IN (SELECT version FROM generated_version);

    PERFORM pg_advisory_unlock(9999);
END
$body$
    LANGUAGE plpgsql;


-- Add an object, corresponds to
-- <publish> without hash to replace.
CREATE OR REPLACE FUNCTION create_object(version_ BIGINT,
                                         bytes_ BYTEA,
                                         url_ TEXT,
                                         client_id_ TEXT) RETURNS CHAR(64) AS
$body$
DECLARE
    existing_object_id BIGINT;
    existing_client_id TEXT;
    hash_              CHAR(64);
BEGIN
    SELECT encode(sha256(bytes_), 'hex') INTO hash_;

    SELECT o.id, ou.client_id
    INTO existing_object_id, existing_client_id
    FROM objects o
             INNER JOIN object_urls ou ON ou.object_id = o.id
    WHERE o.hash = hash_;

    IF existing_object_id IS NOT NULL THEN
        RAISE EXCEPTION 'Tried to insert existing object % ', url_ ;
    ELSE
        IF existing_client_id IS NOT NULL AND existing_client_id <> client_id_ THEN
            RAISE EXCEPTION 'Tried to use object of a different client % ', url_ ;
        ELSE
            WITH z AS (
                INSERT INTO objects (hash, content) VALUES (hash_, bytes_)
                    RETURNING id
            )
            SELECT id
            INTO existing_object_id
            FROM z;
        END IF;
    END IF;

    INSERT INTO object_urls(url, object_id, client_id)
    VALUES (url_, existing_object_id, client_id_);

    INSERT INTO object_log(version, object_id, operation)
    VALUES (version_, existing_object_id, 'INS');

    RETURN hash_;
END
$body$
    LANGUAGE plpgsql;


--
-- Replace an object, corresponds to <publish hash="...">
--
CREATE OR REPLACE FUNCTION replace_object(version_ BIGINT,
                                         bytes_ BYTEA,
                                         hash_to_replace CHAR(64),
                                         url_ TEXT,
                                         client_id_ TEXT) RETURNS CHAR(64) AS
$body$
DECLARE
    existing_object_id BIGINT;
    existing_client_id TEXT;
    hash_              CHAR(64);
BEGIN
    SELECT encode(sha256(bytes_), 'hex') INTO hash_;

    SELECT o.id, ou.client_id
    INTO existing_object_id, existing_client_id
    FROM objects o
    INNER JOIN object_urls ou ON ou.object_id = o.id
    WHERE o.hash = hash_to_replace;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Tried to replace object that doesn''t exist % ', url_ ;
    ELSE
        -- NOTE: reference in object_urls will be removed by the cascading constraint
        DELETE FROM objects WHERE hash = hash_to_replace;
    END IF;

    IF existing_client_id IS NOT NULL AND existing_client_id <> client_id_ THEN
        RAISE EXCEPTION 'Tried to replace object of a different client % ', url_ ;
    ELSE
        WITH z AS (
            INSERT INTO objects (hash, content) VALUES (hash_, bytes_)
                RETURNING id
        )
        SELECT id
        INTO existing_object_id
        FROM z;
    END IF;

    INSERT INTO object_urls(url, object_id, client_id)
    VALUES (url_, existing_object_id, client_id_);

    INSERT INTO object_log(version, object_id, operation, old_hash)
    VALUES (version_, existing_object_id, 'UPD', hash_to_replace);

    RETURN hash_;
END
$body$
    LANGUAGE plpgsql;


-- Replace an object, corresponds to
-- <publish> without hash to replace.
CREATE OR REPLACE FUNCTION delete_object(version_ BIGINT,
                                         hash_to_delete CHAR(64),
                                         client_id_ TEXT) RETURNS VOID AS
$body$
DECLARE
    existing_object_id BIGINT;
    existing_client_id TEXT;
    existing_url TEXT;
BEGIN
    SELECT o.id, ou.client_id, ou.url
    INTO existing_object_id, existing_client_id, existing_url
    FROM objects o
    INNER JOIN object_urls ou ON ou.object_id = o.id
    WHERE o.hash = hash_to_delete;

    IF existing_client_id IS NOT NULL AND existing_client_id <> client_id_ THEN
        RAISE EXCEPTION 'Tried to delete object of a different client % ', existing_url ;
    ELSE
        -- NOTE: reference in object_urls will be removed by the cascading constraint
        DELETE FROM objects WHERE hash = hash_to_delete;
    END IF;

    INSERT INTO object_log(version, operation, old_hash)
    VALUES (version_, 'DEL', hash_to_delete);
END
$body$
    LANGUAGE plpgsql;


-- Auxiliary functions for client_id lock
CREATE OR REPLACE FUNCTION acquire_client_id_lock(client_id_ TEXT) RETURNS VOID AS
$$
SELECT pg_advisory_lock(1234567, CAST(hashed % 1234567 AS INT))
FROM (SELECT hash_bigint(client_id_) AS hashed) AS z
$$ LANGUAGE SQL;

CREATE OR REPLACE FUNCTION hash_bigint(TEXT) RETURNS BIGINT AS
$$
SELECT ('x' || substr(md5($1), 1, 16)) :: BIT(64) :: BIGINT;
$$ LANGUAGE SQL;

COMMIT;