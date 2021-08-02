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


CREATE OR REPLACE FUNCTION verify_object_is_absent(url_ TEXT) RETURNS JSON AS
$body$
BEGIN
    IF EXISTS (SELECT * FROM live_objects WHERE url = url_) THEN
        RETURN json_build_object('code', 'object_already_present', 'message',
                                 format('An object is already present at this URI [%s].', url_));
    ELSE
        RETURN NULL;
    END IF;
END;
$body$
  LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION verify_object_is_present(url_ TEXT, hash_to_replace BYTEA, client_id_ TEXT) RETURNS JSON AS
$body$
DECLARE
    existing_client_id TEXT;
    existing_hash      BYTEA;
BEGIN
    SELECT hash, client_id
    INTO existing_hash, existing_client_id
    FROM live_objects
    WHERE url = url_;

    IF NOT FOUND THEN
        RETURN json_build_object('code', 'no_object_present', 'message',
                                 format('There is no object present at this URI [%s].', url_));
    END IF;

    IF existing_hash <> hash_to_replace THEN
        RETURN json_build_object('code', 'no_object_matching_hash', 'message',
                                 format('Cannot replace or withdraw the object [%s], hash does not match, passed %s, but existing one is %s.',
                                    url_, ENCODE(hash_to_replace, 'hex'), ENCODE(existing_hash, 'hex')));
    END IF;

    IF existing_client_id <> client_id_ THEN
        RETURN json_build_object('code', 'permission_failure', 'message',
                                 format('Not allowed to replace or withdraw an object of another client: [%s].', url_));
    END IF;

    RETURN NULL;
END;
$body$
  LANGUAGE plpgsql;

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
    error_ JSON;
    new_object_id_ BIGINT;
BEGIN
    SELECT verify_object_is_absent(url_) INTO error_;
    IF error_ IS NOT NULL THEN
        RETURN error_;
    END IF;

    INSERT INTO objects (url, hash, content, client_id)
    VALUES (url_, sha256(bytes_), bytes_, client_id_)
    RETURNING id INTO STRICT new_object_id_;

    INSERT INTO object_log (operation, new_object_id)
    VALUES ('INS', new_object_id_);

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
                                          hash_to_replace BYTEA,
                                          url_ TEXT,
                                          client_id_ TEXT) RETURNS JSON AS
$body$
DECLARE
    error_ JSON;
    old_object_id_ BIGINT;
    new_object_id_ BIGINT;
BEGIN
    SELECT verify_object_is_present(url_, hash_to_replace, client_id_) INTO error_;
    IF error_ IS NOT NULL THEN
        RETURN error_;
    END IF;

    UPDATE objects SET deleted_at = NOW()
    WHERE url = url_ AND hash = hash_to_replace AND client_id = client_id_ AND deleted_at IS NULL
    RETURNING id INTO STRICT old_object_id_;

    INSERT INTO objects (url, hash, content, client_id)
    VALUES (url_, sha256(bytes_), bytes_, client_id_)
    RETURNING id INTO STRICT new_object_id_;

    INSERT INTO object_log (operation, new_object_id, old_object_id)
    VALUES ('UPD', new_object_id_, old_object_id_);

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
                                         hash_to_delete BYTEA,
                                         client_id_ TEXT) RETURNS JSON AS
$body$
DECLARE
    error_ JSON;
    old_object_id_ BIGINT;
BEGIN
    SELECT verify_object_is_present(url_, hash_to_delete, client_id_) INTO error_;
    IF error_ IS NOT NULL THEN
        RETURN error_;
    END IF;

    UPDATE objects SET deleted_at = NOW()
    WHERE url = url_ AND hash = hash_to_delete AND client_id = client_id_ AND deleted_at IS NULL
    RETURNING id INTO STRICT old_object_id_;

    INSERT INTO object_log(operation, old_object_id)
    VALUES ('DEL', old_object_id_);

    RETURN NULL;
END
$body$
    LANGUAGE plpgsql;

--
CREATE OR REPLACE VIEW live_objects AS
SELECT * FROM objects WHERE deleted_at IS NULL;

-- All the objects currently stored
CREATE OR REPLACE VIEW current_state AS
SELECT hash, url, client_id, content
FROM objects o
WHERE o.deleted_at IS NULL
ORDER BY url;


-- Currently existing log of object changes
CREATE OR REPLACE VIEW current_log AS
    WITH combined_operations AS (
        SELECT ol.version_id,
               o.url,
               min(ol.id) AS oldest_id,
               max(ol.id) AS newest_id
          FROM object_log ol
              JOIN objects o ON o.id = COALESCE(ol.new_object_id, ol.old_object_id)
         GROUP BY ol.version_id, o.url
    )
    SELECT op.version_id, op.url, old.hash AS old_hash, new.content
      FROM combined_operations op
          JOIN object_log oldest ON op.oldest_id = oldest.id
          JOIN object_log newest ON op.newest_id = newest.id
          LEFT JOIN objects old ON old.id = oldest.old_object_id
          LEFT JOIN objects new ON new.id = newest.new_object_id
     WHERE COALESCE(old.hash, '\x') <> COALESCE(new.hash, '\x')
     ORDER BY op.version_id ASC, op.url ASC;


CREATE OR REPLACE FUNCTION read_delta(session_id_ TEXT, serial_ BIGINT)
    RETURNS SETOF current_log AS
$body$
DECLARE
    version_id_ BIGINT;
BEGIN
    -- Select the `version_id` first to avoid having to join to `current_log`, since joining is much slower in this case.
    SELECT id
      INTO version_id_
      FROM versions
     WHERE session_id = session_id_ AND serial = serial_;

    RETURN QUERY SELECT current_log.*
                   FROM current_log
                  WHERE version_id = version_id_
                  ORDER BY url ASC;
END
$body$
    LANGUAGE plpgsql;

DROP VIEW IF EXISTS latest_version CASCADE;
CREATE VIEW latest_version AS
  SELECT *
    FROM versions
    ORDER BY id DESC
    LIMIT 1;

-- Return deltas that reasonable to put into the notification.xml file.
-- These has to be
-- 1) deltas from the latest session
-- 2) of total size not larger than the latest snapshot
-- 3) having a definite hash (latest delta can have undefined hash until its file is generated and written)
DROP VIEW IF EXISTS reasonable_deltas CASCADE;
CREATE VIEW reasonable_deltas AS
SELECT z.*
FROM (SELECT v.*,
             CAST(SUM(delta_size) OVER (PARTITION BY session_id ORDER BY serial DESC) AS BIGINT) AS total_delta_size
      FROM versions v
     ) AS z,
     latest_version
WHERE z.session_id = latest_version.session_id
AND total_delta_size <= latest_version.snapshot_size
AND z.delta_hash IS NOT NULL
ORDER BY z.serial DESC;


-- Create another entry in the versions table, either the
-- next version (and the next serial) in the same session
-- or generate a new session id if the table is empty.
CREATE OR REPLACE FUNCTION freeze_version(file_name_secret_ BYTEA) RETURNS SETOF versions AS
$body$
DECLARE
    current_session_id_ TEXT;
    current_serial_     BIGINT;
    new_version_id_     BIGINT;
BEGIN
    PERFORM lock_versions();

    SELECT session_id, serial
      INTO current_session_id_, current_serial_
      FROM latest_version;

    IF NOT FOUND THEN
        INSERT INTO versions (session_id, serial, file_name_secret)
        VALUES (uuid_in(md5(random()::TEXT || clock_timestamp()::TEXT)::CSTRING), 1, file_name_secret_)
        RETURNING id INTO STRICT new_version_id_;
    ELSEIF changes_exist() THEN
        INSERT INTO versions (session_id, serial, file_name_secret)
        VALUES (current_session_id_, current_serial_ + 1, file_name_secret_)
        RETURNING id INTO STRICT new_version_id_;
    END IF;

    IF new_version_id_ IS NOT NULL THEN
        UPDATE object_log
           SET version_id = new_version_id_
         WHERE version_id IS NULL;
    END IF;

    RETURN QUERY SELECT * FROM latest_version;
END
$body$
    LANGUAGE plpgsql;



-- Returns if there's any entry in the object_log table since
-- the last frozen version.
CREATE OR REPLACE FUNCTION changes_exist() RETURNS BOOLEAN AS
$body$
BEGIN
    RETURN EXISTS(SELECT * FROM current_log WHERE version_id IS NULL);
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
WITH deleted_versions AS (
         DELETE FROM versions
             WHERE NOT EXISTS (SELECT * FROM reasonable_deltas d WHERE d.id = versions.id)
               AND NOT EXISTS (SELECT * FROM latest_version lv WHERE lv.id = versions.id)
             RETURNING *
     ),
     deleted_objects AS (
         DELETE FROM objects o
             WHERE o.deleted_at IS NOT NULL
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
             RETURNING id
     )
SELECT *
FROM deleted_versions;

$$ LANGUAGE SQL;
