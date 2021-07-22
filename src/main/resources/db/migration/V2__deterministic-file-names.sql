ALTER TABLE versions ADD COLUMN file_name_secret BYTEA;

-- "Secret" for existing versions, only used when system is first upgraded or restarted. As these versions disappear
-- proper randomly secure secrets will be generated.
UPDATE versions SET file_name_secret = decode(md5(random()::TEXT || clock_timestamp()::TEXT), 'hex');

ALTER TABLE versions ALTER COLUMN file_name_secret SET NOT NULL;
