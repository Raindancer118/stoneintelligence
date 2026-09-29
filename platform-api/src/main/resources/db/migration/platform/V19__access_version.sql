-- ADR 0013: Rechte werden je Instanz im Speicher gehalten. Jede Aenderung, die Rechte verschiebt,
-- zaehlt diese Version hoch; Instanzen vergleichen sie und laden bei Abweichung neu.
ALTER TABLE platform.vaults ADD COLUMN access_version bigint NOT NULL DEFAULT 0;

CREATE FUNCTION platform.bump_access_version(target uuid) RETURNS void LANGUAGE sql AS $$
    UPDATE platform.vaults SET access_version = access_version + 1 WHERE id = target;
$$;

CREATE FUNCTION platform.access_changed_by_vault() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM platform.bump_access_version(COALESCE(NEW.vault_id, OLD.vault_id));
    RETURN NULL;
END $$;

CREATE FUNCTION platform.access_changed_by_group() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM platform.bump_access_version(g.vault_id) FROM platform.groups g
        WHERE g.id = COALESCE(NEW.group_id, OLD.group_id);
    RETURN NULL;
END $$;

CREATE FUNCTION platform.access_changed_by_role() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM platform.bump_access_version(r.vault_id) FROM platform.roles r
        WHERE r.id = COALESCE(NEW.role_id, OLD.role_id);
    RETURN NULL;
END $$;

-- Eintrags-Freigaben haengen am Pfad ihrer Notiz: Umbenennen verschiebt sie.
CREATE FUNCTION platform.access_changed_by_note_path() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM platform.access_grants WHERE note_id = NEW.id) THEN
        PERFORM platform.bump_access_version(NEW.vault_id);
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER access_grants_version AFTER INSERT OR UPDATE OR DELETE ON platform.access_grants
    FOR EACH ROW EXECUTE FUNCTION platform.access_changed_by_vault();
CREATE TRIGGER groups_version AFTER INSERT OR UPDATE OR DELETE ON platform.groups
    FOR EACH ROW EXECUTE FUNCTION platform.access_changed_by_vault();
CREATE TRIGGER roles_version AFTER INSERT OR UPDATE OR DELETE ON platform.roles
    FOR EACH ROW EXECUTE FUNCTION platform.access_changed_by_vault();
CREATE TRIGGER group_members_version AFTER INSERT OR UPDATE OR DELETE ON platform.group_members
    FOR EACH ROW EXECUTE FUNCTION platform.access_changed_by_group();
CREATE TRIGGER group_roles_version AFTER INSERT OR UPDATE OR DELETE ON platform.group_roles
    FOR EACH ROW EXECUTE FUNCTION platform.access_changed_by_group();
CREATE TRIGGER role_permissions_version AFTER INSERT OR UPDATE OR DELETE ON platform.role_permissions
    FOR EACH ROW EXECUTE FUNCTION platform.access_changed_by_role();
CREATE TRIGGER notes_path_version AFTER UPDATE OF path ON platform.notes
    FOR EACH ROW WHEN (OLD.path IS DISTINCT FROM NEW.path) EXECUTE FUNCTION platform.access_changed_by_note_path();
