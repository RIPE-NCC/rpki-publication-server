BEGIN;

CREATE INDEX idx_object_client_id ON objects(client_id);

COMMIT;