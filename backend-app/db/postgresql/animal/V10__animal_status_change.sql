-- Latest status change of each animal, for favorite notifications. Only the last change is kept, not a history.
-- The trigger catches every writer (APMS JPQL bulk update, shelter JPA update) in the source transaction.
-- UNKNOWN is the unmatched APMS string fallback, so a move to it is not a change.
-- No backfill: rows start with NULL, so notifications begin with changes after this migration.
ALTER TABLE pawbridge_animal.animals ADD COLUMN previous_status varchar(32);
ALTER TABLE pawbridge_animal.animals ADD COLUMN status_changed_at timestamp with time zone;

CREATE FUNCTION pawbridge_animal.mark_animal_status_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status AND NEW.status <> 'UNKNOWN' THEN
        NEW.previous_status := OLD.status;
        NEW.status_changed_at := now();
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER animal_status_changed BEFORE UPDATE OF status ON pawbridge_animal.animals
FOR EACH ROW EXECUTE FUNCTION pawbridge_animal.mark_animal_status_changed();
