BEGIN;

CREATE INDEX IF NOT EXISTS idx_object_client_id ON objects(client_id);

COMMIT;