-- ADR 0013: Aenderungs-Feed. Jede Zeile, die fuer den Abgleich zaehlt, traegt die Transaktion, die sie
-- geschrieben hat (xid8). Ein Leser nimmt nur Transaktionen unter pg_snapshot_xmin(pg_current_snapshot())
-- - die sind alle abgeschlossen, spaeter committende mit kleinerer Nummer gibt es darunter nicht.
-- Bestehende Zeilen bleiben NULL (kein Umschreiben grosser Tabellen): wer noch keinen Cursor hat, holt
-- ohnehin einmal die volle Liste.

ALTER TABLE platform.notes ADD COLUMN change_tx xid8;
ALTER TABLE platform.notes ALTER COLUMN change_tx SET DEFAULT pg_current_xact_id();
CREATE INDEX idx_notes_vault_change ON platform.notes (vault_id, change_tx);

-- Nur was die Liste zeigt: Oeffnen/Bearbeiten-Zeitstempel sind kein Abgleichsgrund.
CREATE FUNCTION platform.touch_change_tx() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.change_tx := pg_current_xact_id();
    RETURN NEW;
END $$;
CREATE TRIGGER notes_change_tx BEFORE UPDATE OF path, note_level, kind ON platform.notes
    FOR EACH ROW WHEN (OLD.path IS DISTINCT FROM NEW.path OR OLD.note_level IS DISTINCT FROM NEW.note_level
                       OR OLD.kind IS DISTINCT FROM NEW.kind)
    EXECUTE FUNCTION platform.touch_change_tx();

ALTER TABLE platform.note_snapshots ADD COLUMN change_tx xid8;
ALTER TABLE platform.note_snapshots ALTER COLUMN change_tx SET DEFAULT pg_current_xact_id();
CREATE INDEX idx_note_snapshots_change ON platform.note_snapshots (change_tx);

ALTER TABLE platform.file_versions ADD COLUMN change_tx xid8;
ALTER TABLE platform.file_versions ALTER COLUMN change_tx SET DEFAULT pg_current_xact_id();
CREATE INDEX idx_file_versions_change ON platform.file_versions (change_tx);

ALTER TABLE platform.note_tombstones ADD COLUMN change_tx xid8;
ALTER TABLE platform.note_tombstones ALTER COLUMN change_tx SET DEFAULT pg_current_xact_id();
CREATE INDEX idx_note_tombstones_change ON platform.note_tombstones (vault_id, change_tx);

ALTER TABLE platform.folders ADD COLUMN change_tx xid8;
ALTER TABLE platform.folders ALTER COLUMN change_tx SET DEFAULT pg_current_xact_id();
CREATE INDEX idx_folders_change ON platform.folders (vault_id, change_tx);

-- Ordner werden beim Verschieben geloescht und neu angelegt: jeder verschwundene Pfad wird vermerkt.
CREATE TABLE platform.folder_tombstones (
    vault_id   uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    path       text NOT NULL,
    change_tx  xid8 NOT NULL DEFAULT pg_current_xact_id(),
    deleted_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_folder_tombstones_change ON platform.folder_tombstones (vault_id, change_tx);

CREATE FUNCTION platform.folder_removed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO platform.folder_tombstones (vault_id, path) VALUES (OLD.vault_id, OLD.path);
    RETURN NULL;
END $$;
CREATE TRIGGER folders_tombstone AFTER DELETE ON platform.folders
    FOR EACH ROW EXECUTE FUNCTION platform.folder_removed();

-- Wessen Sicht sich wo geaendert hat. Der Feed liefert einer Person nur Ereignisse, die sie betreffen
-- (sie selbst, eine ihrer Gruppen, alle), statt bei jeder Freigabe im Vault alle Geraete neu laden zu lassen.
-- folder_path '' = der ganze Vault.
CREATE TABLE platform.access_events (
    vault_id      uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    scope_type    text NOT NULL CHECK (scope_type IN ('USER', 'GROUP', 'EVERYONE')),
    scope_subject text,
    folder_path   text,
    note_id       uuid,
    change_tx     xid8 NOT NULL DEFAULT pg_current_xact_id(),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CHECK ((folder_path IS NULL) <> (note_id IS NULL))
);
CREATE INDEX idx_access_events_change ON platform.access_events (vault_id, change_tx);

CREATE FUNCTION platform.grant_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        INSERT INTO platform.access_events (vault_id, scope_type, scope_subject, folder_path, note_id)
        VALUES (OLD.vault_id, OLD.scope_type, OLD.scope_subject, OLD.folder_path, OLD.note_id);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        INSERT INTO platform.access_events (vault_id, scope_type, scope_subject, folder_path, note_id)
        VALUES (NEW.vault_id, NEW.scope_type, NEW.scope_subject, NEW.folder_path, NEW.note_id);
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER access_grants_event AFTER INSERT OR UPDATE OR DELETE ON platform.access_grants
    FOR EACH ROW EXECUTE FUNCTION platform.grant_event();

CREATE FUNCTION platform.membership_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO platform.access_events (vault_id, scope_type, scope_subject, folder_path)
    SELECT g.vault_id, 'USER', COALESCE(NEW.subject, OLD.subject), '' FROM platform.groups g
    WHERE g.id = COALESCE(NEW.group_id, OLD.group_id);
    RETURN NULL;
END $$;
CREATE TRIGGER group_members_event AFTER INSERT OR UPDATE OR DELETE ON platform.group_members
    FOR EACH ROW EXECUTE FUNCTION platform.membership_event();

CREATE FUNCTION platform.group_roles_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO platform.access_events (vault_id, scope_type, scope_subject, folder_path)
    SELECT g.vault_id, 'GROUP', g.id::text, '' FROM platform.groups g
    WHERE g.id = COALESCE(NEW.group_id, OLD.group_id);
    RETURN NULL;
END $$;
CREATE TRIGGER group_roles_event AFTER INSERT OR UPDATE OR DELETE ON platform.group_roles
    FOR EACH ROW EXECUTE FUNCTION platform.group_roles_event();

CREATE FUNCTION platform.role_permissions_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO platform.access_events (vault_id, scope_type, scope_subject, folder_path)
    SELECT g.vault_id, 'GROUP', g.id::text, '' FROM platform.group_roles gr
    JOIN platform.groups g ON g.id = gr.group_id
    WHERE gr.role_id = COALESCE(NEW.role_id, OLD.role_id);
    RETURN NULL;
END $$;
CREATE TRIGGER role_permissions_event AFTER INSERT OR UPDATE OR DELETE ON platform.role_permissions
    FOR EACH ROW EXECUTE FUNCTION platform.role_permissions_event();
