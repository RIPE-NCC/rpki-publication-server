BEGIN;

-- Some utility functions first
--
CREATE OR REPLACE FUNCTION hash_bigint(TEXT) RETURNS BIGINT AS
$$
SELECT ('x' || substr(md5($1), 1, 16)) :: BIT(64) :: BIGINT;
$$ LANGUAGE SQL;

-- Auxiliary functions for client_id lock
CREATE OR REPLACE FUNCTION acquire_client_id_lock(client_id_ TEXT) RETURNS VOID AS
$$
SELECT pg_advisory_lock(1234567, CAST(hashed % 1234567 AS INT))
FROM (SELECT hash_bigint(client_id_) AS hashed) AS z
$$ LANGUAGE SQL;

-- Just pick up some arbitrary number to lock on when 'versions'
-- table is modified
CREATE OR REPLACE FUNCTION lock_versions() RETURNS VOID AS
$$
SELECT pg_advisory_xact_lock(999999);
$$ LANGUAGE SQL;


-- Reusable part of both create and replace
-- 
CREATE OR REPLACE FUNCTION merge_object(bytes_ BYTEA,
                                        hash_ CHAR(64),
                                        url_ TEXT,
                                        client_id_ TEXT) RETURNS VOID AS
$body$
BEGIN
    WITH
        existing AS (
            SELECT id FROM objects WHERE hash = hash_
        ),
        inserted AS (
            INSERT INTO objects (hash, content)
            SELECT hash_, bytes_
            WHERE NOT EXISTS (SELECT * FROM existing)
            RETURNING id
        )
        INSERT INTO object_urls (url, object_id, client_id)
        SELECT url_, id, client_id_
        FROM (
            SELECT id FROM existing
            UNION ALL
            SELECT id FROM inserted
        ) AS z;
END
$body$
LANGUAGE plpgsql;


-- Add an object, corresponds to
-- <publish> without hash to replace.
CREATE OR REPLACE FUNCTION create_object(bytes_ BYTEA,
                                         url_ TEXT,
                                         client_id_ TEXT) RETURNS JSON AS
$body$
DECLARE
    hash_ CHAR(64);
BEGIN

    IF EXISTS (SELECT * FROM object_urls WHERE url = url_) THEN
        RETURN json_build_object('code', 'object_already_present', 'message',
                                 format('An object is already present at this URI [%s].', url_));
    END IF;

    SELECT LOWER(encode(sha256(bytes_), 'hex')) INTO hash_;

    PERFORM merge_object(bytes_, hash_, url_, client_id_);

    INSERT INTO object_log (operation, url, content)
    VALUES ('INS', url_, bytes_);

    RETURN NULL;
END
$body$
    LANGUAGE plpgsql;


--
-- Replace an object, corresponds to <publish hash="...">
--
CREATE OR REPLACE FUNCTION replace_object(bytes_ BYTEA,
                                          hash_to_replace CHAR(64),
                                          url_ TEXT,
                                          client_id_ TEXT) RETURNS JSON AS
$body$
DECLARE
    existing_object_id BIGINT;
    existing_client_id TEXT;
    hash_              CHAR(64);
    existing_hash      CHAR(64);
BEGIN

    SELECT o.hash, o.id, ou.client_id
    INTO existing_hash, existing_object_id, existing_client_id
    FROM objects o
    INNER JOIN object_urls ou ON ou.object_id = o.id
    WHERE ou.url = url_;

    IF NOT FOUND THEN
        RETURN json_build_object('code', 'no_object_present', 'message',
                                 format('There is no object present at this URI [%s].', url_));
    END IF;

    IF existing_hash <> hash_to_replace THEN
        RETURN json_build_object('code', 'no_object_matching_hash', 'message',
                                 format('Cannot republish the object [%s], hash doesn''t match, passed %s, but existing one is %s',
                                    url_, hash_to_replace, existing_hash));
    END IF;

    IF existing_client_id <> client_id_ THEN
        RETURN json_build_object('code', 'permission_failure', 'message',
                                 format('Not allowed to update an object of another client [%s].', url_));
    END IF;

    -- NOTE: reference in object_urls will be removed by the cascading constraint
    DELETE FROM objects WHERE hash = LOWER(hash_to_replace);

    SELECT LOWER(encode(sha256(bytes_), 'hex')) INTO hash_;
    PERFORM merge_object(bytes_, hash_, url_, client_id_);

    INSERT INTO object_log(operation, url, old_hash, content)
    VALUES ('UPD', url_, hash_to_replace, bytes_);

    RETURN NULL;

END
$body$
    LANGUAGE plpgsql;


-- Delete an object, corresponds to <withdraw hash="...">.
CREATE OR REPLACE FUNCTION delete_object(url_ TEXT,
                                         hash_to_delete CHAR(64),
                                         client_id_ TEXT) RETURNS JSON AS
$body$
DECLARE
    existing_object_id BIGINT;
    existing_client_id TEXT;
    existing_hash      CHAR(64);
BEGIN

    SELECT o.hash, o.id, ou.client_id
    INTO existing_hash, existing_object_id, existing_client_id
    FROM objects o
    INNER JOIN object_urls ou ON ou.object_id = o.id
    WHERE ou.url = url_;

    IF NOT FOUND THEN
        RETURN json_build_object('code', 'no_object_present', 'message',
                                 format('There is no object present at this URI [%s].', url_));
    END IF;

    IF existing_hash <> hash_to_delete THEN
        RETURN json_build_object('code', 'no_object_matching_hash', 'message',
                                 format('Cannot withdraw the object [%s], hash doesn''t match, ' ||
                                 'passed %s, but existing one is %s', url_, hash_to_delete, existing_hash));
    END IF;

    IF existing_client_id <> client_id_ THEN
        RETURN json_build_object('code', 'permission_failure', 'message',
                                 format('Not allowed to delete an object of another client [%s].', url_));
    END IF;

    -- NOTE: reference in object_urls will be removed by the cascading constraint
    DELETE FROM objects WHERE hash = LOWER(hash_to_delete);

    INSERT INTO object_log(operation, url, old_hash)
    VALUES ('DEL', url_, hash_to_delete);

    RETURN NULL;
END
$body$
    LANGUAGE plpgsql;



CREATE OR REPLACE VIEW current_state AS
SELECT LOWER(hash) as hash, url, client_id, content
FROM objects o
INNER JOIN object_urls ou ON ou.object_id = o.id
ORDER BY url;


CREATE OR REPLACE VIEW latest_delta AS
WITH log AS (
    SELECT id, operation, url, old_hash, content
    FROM object_log o
)
SELECT *
FROM log
WHERE NOT EXISTS(
        SELECT * FROM versions
    )
   OR id > (
    SELECT last_log_entry_id
    FROM versions
    ORDER BY id DESC
    LIMIT 1)
ORDER BY id ASC;


-- Return del
CREATE OR REPLACE VIEW reasonable_deltas AS
WITH latest_version AS (
    SELECT *
    FROM versions
    ORDER BY id DESC
    LIMIT 1
)
SELECT z.*
FROM (SELECT v.*,
             SUM(delta_size) OVER (ORDER BY id DESC) AS total_delta_size
      FROM versions v
     ) AS z,
     latest_version
WHERE total_delta_size <= latest_version.snapshot_size
ORDER BY z.id DESC;


-- Create another entry in the versions table, either the
-- next version (and the next serial) in the same session
-- or generate a new session id if the table is empty.
CREATE OR REPLACE FUNCTION freeze_version() RETURNS VOID AS
$body$
BEGIN
    -- NOTE This one is safe from race conditions only if
    -- lock_versions is called before in the same transaction.
    IF EXISTS(SELECT * FROM versions) THEN
        -- create a new session-id+serial entry only in case there are
        -- new entries in the log since the last session-id+serial.
        -- (if there's anything new in the object_log)
        WITH previous_version AS (
            SELECT id, session_id, serial, last_log_entry_id
            FROM versions v
            ORDER BY id DESC
            LIMIT 1
        )
        INSERT
        INTO versions (session_id, serial, last_log_entry_id)
        SELECT v.session_id, v.serial + 1, ol.id
        FROM object_log ol,
             previous_version v
        WHERE ol.id > v.last_log_entry_id
        ORDER BY ol.id DESC
        LIMIT 1;
    ELSE
        -- create a completely new version entry from object_log
        -- (if there's anything new in the object_log)
        INSERT INTO versions (session_id, serial, last_log_entry_id)
        SELECT uuid_in(md5(random()::TEXT || clock_timestamp()::TEXT)::CSTRING), 1, id
        FROM object_log
        ORDER BY id DESC
        LIMIT 1;
    END IF;

END
$body$
    LANGUAGE plpgsql;

COMMIT;