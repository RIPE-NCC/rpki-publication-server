BEGIN;

CREATE TABLE IF NOT EXISTS objects (
    id BIGSERIAL PRIMARY KEY,    
    hash CHAR(32) NOT NULL,
    content BYTEA NOT NULL    
);


CREATE TABLE IF NOT EXISTS objects_urls (
    url TEXT PRIMARY KEY,
    object_id BIGINT NOT NULL,
    CONSTRAINT fk_objects_urls_to_object
      FOREIGN KEY(object_id) 
	  REFERENCES objects(id)
);

CREATE UNIQUE INDEX idx_objects_hash ON objects(hash);

COMMIT;