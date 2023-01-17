-- We want to reset RRDP session and for that we delete all versions and
-- by cascading, delete all referring rows in object_log.
DELETE FROM versions;
