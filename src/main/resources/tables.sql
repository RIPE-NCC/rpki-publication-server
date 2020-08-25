BEGIN;

CREATE TABLE IF NOT EXISTS objects (
    id BIGSERIAL PRIMARY KEY,    
    hash CHAR(64) NOT NULL,
    content BYTEA NOT NULL    
);

CREATE TABLE IF NOT EXISTS object_urls (
    url TEXT PRIMARY KEY,
    object_id BIGINT NOT NULL,
    client_id TEXT NOT NULL,
    CONSTRAINT fk_objects_urls_to_object
      FOREIGN KEY(object_id) 
	  REFERENCES objects(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_objects_hash ON objects(hash);
CREATE INDEX idx_object_urls_client_id ON object_urls(client_id);

COMMIT;