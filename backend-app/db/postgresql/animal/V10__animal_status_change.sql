-- Latest status change of each animal, for favorite notifications. Only the last change is kept, not a history.
-- The application writes both columns when the status really changes (shelter edits and the APMS sync).
-- No backfill: rows start with NULL, so notifications begin with changes after this migration.
ALTER TABLE pawbridge_animal.animals ADD COLUMN previous_status varchar(32);
ALTER TABLE pawbridge_animal.animals ADD COLUMN status_changed_at timestamp with time zone;
