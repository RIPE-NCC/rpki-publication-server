BEGIN;

-- Some utility functions first

-- Auxiliary functions for client_id lock
CREATE OR REPLACE FUNCTION acquire_client_id_lock(client_id_ TEXT) RETURNS VOID AS
$$
    SELECT pg_advisory_xact_lock(hashed)
    FROM (
        -- Here md5 used just as an instance of reasonable string -> int conversion.
        -- We only need it to return preferably different integers for different strings
        -- and do not use any extensions such as pgcrypto.
         SELECT ('x' || substr(md5($1), 1, 16)) :: BIT(64) :: BIGINT AS hashed
    ) AS z
$$ LANGUAGE SQL;

-- Just pick up some arbitrary number to lock on when 'versions'
-- table is modified
CREATE OR REPLACE FUNCTION lock_versions() RETURNS VOID AS
$$
SELECT pg_advisory_xact_lock(999999);
$$ LANGUAGE SQL;


-- Reusable part of both create and replace an object in the 'objects' table.
-- 
CREATE OR REPLACE FUNCTION merge_object(bytes_ BYTEA,
                                        hash_ CHAR(64),
                                        url_ TEXT,
                                        client_id_ TEXT) RETURNS SETOF objects AS
$$
    WITH
        existing AS (
            SELECT * FROM objects WHERE hash = hash_
        ),
        inserted AS (
            INSERT INTO objects (hash, content, url, client_id)
            SELECT hash_, bytes_, url_, client_id_
            WHERE NOT EXISTS (SELECT * FROM existing)
            RETURNING *
        )
        SELECT * FROM existing
        UNION ALL
        SELECT * FROM inserted
$$ LANGUAGE SQL;


-- Add an object, corresponds to <publish> without hash to replace.
--
-- Returns JSON string that can be simply converted into
-- BaseError object in the Scala code.
--
-- Error codes are defined by the RFC (https://tools.ietf.org/html/rfc8181#page-9)
CREATE OR REPLACE FUNCTION create_object(bytes_ BYTEA,
                                         url_ TEXT,
                                         client_id_ TEXT) RETURNS JSON AS
$body$
DECLARE
    hash_ CHAR(64);
BEGIN

    IF EXISTS (SELECT * FROM live_objects WHERE url = url_) THEN
        RETURN json_build_object('code', 'object_already_present', 'message',
                                 format('An object is already present at this URI [%s].', url_));
    END IF;

    SELECT LOWER(encode(sha256(bytes_), 'hex')) INTO hash_;

    IF EXISTS (SELECT * FROM live_objects WHERE hash = hash_) THEN
        RETURN json_build_object('code', 'object_already_present', 'message',
                                 format('An object with the same hash is already present with different URI, [%s].', url_));
    END IF;

    WITH merged_object AS (
        SELECT *
        FROM merge_object(bytes_, hash_, url_, client_id_)
    )
    INSERT INTO object_log (operation, new_object_id)
    SELECT 'INS', z.id
    FROM merged_object AS z;

    RETURN NULL;
END
$body$
    LANGUAGE plpgsql;


--
-- Replace an object, corresponds to <publish hash="...">
--
-- Returns JSON string that can be simply converted into
-- BaseError object in the Scala code.
--
-- Error codes are defined by the RFC (https://tools.ietf.org/html/rfc8181#page-9)
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

    SELECT hash, id, client_id
    INTO existing_hash, existing_object_id, existing_client_id
    FROM live_objects
    WHERE url = url_;

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
                                 format('Not allowed to update an object of another client: [%s].', url_));
    END IF;

    SELECT LOWER(encode(sha256(bytes_), 'hex')) INTO hash_;

    WITH deleted_object AS (
        UPDATE objects SET is_deleted = TRUE
        WHERE hash = LOWER(hash_to_replace)
        RETURNING id
    ),
     merged_object AS (
        SELECT *
        FROM merge_object(bytes_, hash_, url_, client_id_)
    )
    INSERT INTO object_log (operation, new_object_id, old_object_id)
    SELECT 'UPD', n.id, d.id
    FROM merged_object AS n, deleted_object AS d;

    RETURN NULL;

END
$body$
    LANGUAGE plpgsql;


-- Delete an object, corresponds to <withdraw hash="...">.
-- Returns JSON string that can be simply converted into
-- BaseError object in the Scala code.
--
-- Error codes are defined by the RFC (https://tools.ietf.org/html/rfc8181#page-9)
CREATE OR REPLACE FUNCTION delete_object(url_ TEXT,
                                         hash_to_delete CHAR(64),
                                         client_id_ TEXT) RETURNS JSON AS
$body$
DECLARE
    existing_object_id BIGINT;
    existing_client_id TEXT;
    existing_hash      CHAR(64);
BEGIN

    SELECT hash, id, client_id
    INTO existing_hash, existing_object_id, existing_client_id
    FROM live_objects
    WHERE url = url_;

    IF NOT FOUND THEN
        RETURN json_build_object('code', 'no_object_present', 'message',
                                 format('There is no object present at this URI [%s].', url_));
    END IF;

    IF existing_hash <> hash_to_delete THEN
        RETURN json_build_object('code', 'no_object_matching_hash', 'message',
                                 format('Cannot withdraw the object [%s], hash does not match, ' ||
                                 'passed %s, but existing one is %s.', url_, hash_to_delete, existing_hash));
    END IF;

    IF existing_client_id <> client_id_ THEN
        RETURN json_build_object('code', 'permission_failure', 'message',
                                 format('Not allowed to delete an object of another client: [%s].', url_));
    END IF;

    WITH deleted_object AS (
        UPDATE objects SET is_deleted = TRUE
        WHERE hash = LOWER(hash_to_delete)
        RETURNING id
    )
    INSERT INTO object_log(operation, old_object_id)
    SELECT 'DEL', id
    FROM deleted_object;

    RETURN NULL;
END
$body$
    LANGUAGE plpgsql;

--
CREATE OR REPLACE VIEW live_objects AS
SELECT * FROM objects WHERE NOT is_deleted;

-- All the objects currently stored
CREATE OR REPLACE VIEW current_state AS
SELECT hash, url, client_id, content
FROM objects o
WHERE NOT o.is_deleted
ORDER BY url;


-- Currently existing log of object changes
CREATE OR REPLACE VIEW current_log AS
SELECT
    ol.id,
    operation,
    CASE operation
        WHEN 'DEL' THEN old_o.url
        WHEN 'INS' THEN new_o.url
        WHEN 'UPD' THEN new_o.url
    END AS url,
    CASE operation
        WHEN 'DEL' THEN old_o.hash
        WHEN 'INS' THEN NULL
        WHEN 'UPD' THEN old_o.hash
    END AS old_hash,
    CASE operation
        WHEN 'DEL' THEN NULL
        WHEN 'INS' THEN new_o.content
        WHEN 'UPD' THEN new_o.content
    END AS content
FROM object_log ol
LEFT JOIN objects old_o ON old_o.id = ol.old_object_id
LEFT JOIN objects new_o ON new_o.id = ol.new_object_id
ORDER BY id ASC;


CREATE OR REPLACE FUNCTION read_delta(session_id_ TEXT, serial_ BIGINT)
    RETURNS SETOF current_log AS
$body$
DECLARE
    last_log_entry_id_     BIGINT;
    previous_log_entry_id_ BIGINT;
BEGIN
    -- get the last log entry pointer for the current serial
    SELECT last_log_entry_id
    INTO last_log_entry_id_
    FROM versions
    WHERE session_id = session_id_
      AND serial = serial_;

    -- get the last log entry pointer for the previous serial
    SELECT last_log_entry_id
    INTO previous_log_entry_id_
    FROM versions
    WHERE session_id = session_id_
      AND serial < serial_
    ORDER BY serial DESC
    LIMIT 1;

    IF NOT FOUND THEN
        previous_log_entry_id_ = 0;
    END IF;

    -- objects related to the current delta serial are the ones
    -- with id between last_log_entry_id of this serial and
    -- last_log_entry_id of the previous serial, i.e. previous_log_entry_id_
    RETURN QUERY SELECT *
                 FROM current_log
                 WHERE id <= last_log_entry_id_
                   AND id > previous_log_entry_id_
                 ORDER BY url ASC;
END
$body$
    LANGUAGE plpgsql;


-- Return deltas that reasonable to put into the notification.xml file.
-- These has to be
-- 1) deltas from the latest session
-- 2) of total size not larger than the latest snapshot
-- 3) having a definite hash (latest delta can have undefined hash until its file is generated and written)
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
WHERE z.session_id = latest_version.session_id
AND total_delta_size <= latest_version.snapshot_size
AND z.delta_hash IS NOT NULL
ORDER BY z.id DESC;


-- Create another entry in the versions table, either the
-- next version (and the next serial) in the same session
-- or generate a new session id if the table is empty.
CREATE OR REPLACE FUNCTION freeze_version() RETURNS SETOF versions AS
$body$
BEGIN
    -- NOTE This one is safe from race conditions only if
    -- lock_versions is called before in the same transaction.
    PERFORM lock_versions();

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
    ELSEIF EXISTS(SELECT * FROM object_log) THEN
        -- create a new version entry from object_log
        -- (if there's anything new in the object_log)
        INSERT INTO versions (session_id, serial, last_log_entry_id)
        SELECT uuid_in(md5(random()::TEXT || clock_timestamp()::TEXT)::CSTRING), 1, id
        FROM object_log
        ORDER BY id DESC
        LIMIT 1;
    ELSE
        -- this can happen on a completely empty database, no object_log, no versions
        -- just initialise with something reasonable.
        INSERT INTO versions (session_id, serial, last_log_entry_id)
        VALUES (uuid_in(md5(random()::TEXT || clock_timestamp()::TEXT)::CSTRING), 1, 0);

    END IF;

    RETURN QUERY
        SELECT *
        FROM versions
        ORDER BY id DESC
        LIMIT 1;
END
$body$
    LANGUAGE plpgsql;



-- Returns if there's any entry in the object_log table since
-- the last frozen version.
CREATE OR REPLACE FUNCTION changes_exist() RETURNS BOOLEAN AS
$body$
BEGIN
    IF (EXISTS(SELECT * FROM versions)) THEN
        RETURN (
            -- force index backwards scan by using MAX instead of EXISTS
            SELECT MAX(id) IS NOT NULL
            FROM object_log
            WHERE id > (
                SELECT last_log_entry_id
                FROM versions
                ORDER BY id DESC
                LIMIT 1)
        );
    ELSE
        RETURN EXISTS(SELECT * FROM object_log);
    END IF;
END
$body$
    LANGUAGE plpgsql;


-- Delete versions that
-- a) have different session id than the latest version
-- b) are not reasonable to keep, i.e. total size of deltas in the session
-- becomes bigger than the snapshot size
-- c) not the last version
CREATE OR REPLACE FUNCTION delete_old_versions()
    RETURNS SETOF versions AS
$$
WITH latest_version AS (
    SELECT *
    FROM versions
    ORDER BY id DESC
    LIMIT 1
),
     deleted_versions AS (
         DELETE FROM versions
             WHERE id NOT IN (SELECT id FROM reasonable_deltas)
                 AND id NOT IN (SELECT id FROM latest_version)
             RETURNING *
     ),
     deleted_log AS (
         DELETE FROM object_log ol
             WHERE EXISTS(
                     SELECT last_log_entry_id
                     FROM deleted_versions
                     WHERE ol.id <= last_log_entry_id
                 )
             RETURNING id
     ),
     deleted_objects AS (
         DELETE FROM objects o
             WHERE o.id IN (
                 SELECT id
                 FROM objects o
                 WHERE o.is_deleted
                   AND NOT EXISTS(
                         SELECT *
                         FROM object_log
                         WHERE new_object_id = o.id
                     )
                   AND NOT EXISTS(
                         SELECT *
                         FROM object_log
                         WHERE old_object_id = o.id
                     )
             )
             RETURNING id
     )
SELECT *
FROM deleted_versions;

$$ LANGUAGE SQL;


COMMIT;