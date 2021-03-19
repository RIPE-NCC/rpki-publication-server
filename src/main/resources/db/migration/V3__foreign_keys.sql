BEGIN;

ALTER TABLE object_log
ADD CONSTRAINT object_log_fk_new_object_id
FOREIGN KEY (new_object_id) REFERENCES objects (id)
ON DELETE RESTRICT
ON UPDATE CASCADE;

ALTER TABLE object_log
ADD CONSTRAINT object_log_fk_old_object_id
FOREIGN KEY (old_object_id) REFERENCES objects (id)
ON DELETE RESTRICT
ON UPDATE CASCADE;

COMMIT;