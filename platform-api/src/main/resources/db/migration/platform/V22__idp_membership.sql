-- ADR 0013 Schritt 5: Mitglieder kommen aus dem Identitaetsanbieter der Firma.
--
-- Zwei Wege fuehren in eine verknuepfte Gruppe:
--   TOKEN - der Gruppen-Claim beim Anmelden. Gilt 7 Tage nach dem letzten Sehen, weil WebSocket und
--           naechtliche Laeufe kein Token haben.
--   SCIM  - der IdP meldet Konten und Gruppen selbst. Fuer ein per SCIM bekanntes Konto gilt allein
--           SCIM: sonst hielte ein altes Token jemanden noch Tage in einer Gruppe, aus der der IdP
--           ihn laengst genommen hat.
-- Ein deaktiviertes oder geloeschtes SCIM-Konto hat keinen Zugriff mehr, auch nicht als direktes Mitglied.

ALTER TABLE platform.groups ADD COLUMN idp_group text CHECK (idp_group IS NULL OR length(idp_group) BETWEEN 1 AND 256);
CREATE UNIQUE INDEX idx_groups_vault_idp ON platform.groups (vault_id, idp_group) WHERE idp_group IS NOT NULL;
CREATE INDEX idx_groups_idp ON platform.groups (idp_group) WHERE idp_group IS NOT NULL;

-- Nur verknuepfte IdP-Gruppen werden gemerkt, alles andere aus dem Token interessiert hier nicht.
CREATE TABLE platform.subject_idp_groups (
    subject   text NOT NULL,
    idp_group text NOT NULL,
    seen_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (subject, idp_group)
);
CREATE INDEX idx_subject_idp_groups_group ON platform.subject_idp_groups (idp_group);
CREATE INDEX idx_subject_idp_groups_seen ON platform.subject_idp_groups (seen_at);

-- SCIM 2.0 (RFC 7643/7644). subject: der Wert, den der Principal-Claim im Token traegt - je nach
-- Einstellung userName oder externalId, beim Schreiben berechnet.
CREATE TABLE platform.directory_users (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject      text NOT NULL UNIQUE,
    user_name    text NOT NULL,
    external_id  text,
    display_name text,
    active       boolean NOT NULL DEFAULT true,
    -- Geloeschte bleiben gesperrt, bis der IdP das Konto neu anlegt (POST belebt die Zeile wieder).
    deleted_at   timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CHECK (deleted_at IS NULL OR NOT active)
);
-- userName ist laut RFC 7643 nicht caseExact.
CREATE UNIQUE INDEX idx_directory_users_user_name ON platform.directory_users (lower(user_name));

-- idp_key: unter diesem Namen verknuepfen Vaults die Gruppe - displayName oder externalId, je nachdem,
-- was der IdP im Gruppen-Claim schickt (Authentik: Namen, Entra ID: Objekt-IDs).
CREATE TABLE platform.directory_groups (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    idp_key      text NOT NULL UNIQUE,
    display_name text NOT NULL,
    external_id  text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE platform.directory_group_members (
    group_id uuid NOT NULL REFERENCES platform.directory_groups(id) ON DELETE CASCADE,
    user_id  uuid NOT NULL REFERENCES platform.directory_users(id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, user_id)
);
CREATE INDEX idx_directory_group_members_user ON platform.directory_group_members (user_id);

-- Anzeigenamen fuer Subjects, die selbst nichts sagen (sub/oid). Aus dem Token der Person; faellt nach
-- 13 Monaten ohne Anmeldung weg (IdpMembershipMaintenance).
CREATE TABLE platform.people (
    subject      text PRIMARY KEY,
    display_name text NOT NULL,
    last_seen_at timestamptz NOT NULL DEFAULT now()
);

CREATE VIEW platform.blocked_subjects AS
SELECT subject FROM platform.directory_users WHERE NOT active;

CREATE VIEW platform.effective_group_members AS
SELECT m.group_id, m.subject FROM (
    SELECT gm.group_id, gm.subject FROM platform.group_members gm
    UNION
    SELECT g.id, s.subject FROM platform.groups g
      JOIN platform.subject_idp_groups s ON s.idp_group = g.idp_group
     WHERE s.seen_at > now() - interval '7 days'
       AND NOT EXISTS (SELECT 1 FROM platform.directory_users d WHERE d.subject = s.subject)
    UNION
    SELECT g.id, du.subject FROM platform.groups g
      JOIN platform.directory_groups dg ON dg.idp_key = g.idp_group
      JOIN platform.directory_group_members dm ON dm.group_id = dg.id
      JOIN platform.directory_users du ON du.id = dm.user_id
) m
WHERE NOT EXISTS (SELECT 1 FROM platform.blocked_subjects b WHERE b.subject = m.subject);

-- Rechte-Cache (V19) und Feed (V20) erfahren von jeder Aenderung: fuer jeden Vault, der die IdP-Gruppe
-- verknuepft hat, eine neue Zugriffs-Version und ein Ereignis fuer die betroffene Person.
CREATE FUNCTION platform.idp_member_changed(idp_group text, changed_subject text) RETURNS void LANGUAGE sql AS $$
    SELECT platform.bump_access_version(g.vault_id) FROM platform.groups g WHERE g.idp_group = idp_member_changed.idp_group;
    INSERT INTO platform.access_events (vault_id, scope_type, scope_subject, folder_path)
    SELECT DISTINCT g.vault_id, 'USER', changed_subject, '' FROM platform.groups g
     WHERE g.idp_group = idp_member_changed.idp_group;
$$;

CREATE FUNCTION platform.token_groups_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Auffrischen aendert nichts - ausser die Zeile war schon abgelaufen: dann ist die Person wieder da.
    IF TG_OP = 'UPDATE' AND OLD.seen_at > now() - interval '7 days' THEN
        RETURN NULL;
    END IF;
    PERFORM platform.idp_member_changed(COALESCE(NEW.idp_group, OLD.idp_group), COALESCE(NEW.subject, OLD.subject));
    RETURN NULL;
END $$;
CREATE TRIGGER subject_idp_groups_changed AFTER INSERT OR UPDATE OR DELETE ON platform.subject_idp_groups
    FOR EACH ROW EXECUTE FUNCTION platform.token_groups_changed();

CREATE FUNCTION platform.directory_member_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM platform.idp_member_changed(dg.idp_key, du.subject)
       FROM platform.directory_groups dg, platform.directory_users du
      WHERE dg.id = COALESCE(NEW.group_id, OLD.group_id) AND du.id = COALESCE(NEW.user_id, OLD.user_id);
    RETURN NULL;
END $$;
CREATE TRIGGER directory_group_members_changed AFTER INSERT OR DELETE ON platform.directory_group_members
    FOR EACH ROW EXECUTE FUNCTION platform.directory_member_changed();

-- Eine umbenannte (bzw. neu geschluesselte) SCIM-Gruppe trifft andere verknuepfte Vaults.
CREATE FUNCTION platform.directory_group_key_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM platform.bump_access_version(g.vault_id) FROM platform.groups g
      WHERE g.idp_group IN (OLD.idp_key, NEW.idp_key);
    INSERT INTO platform.access_events (vault_id, scope_type, scope_subject, folder_path)
    SELECT g.vault_id, 'GROUP', g.id::text, '' FROM platform.groups g WHERE g.idp_group IN (OLD.idp_key, NEW.idp_key);
    RETURN NULL;
END $$;
CREATE TRIGGER directory_groups_key AFTER UPDATE OF idp_key ON platform.directory_groups
    FOR EACH ROW WHEN (OLD.idp_key IS DISTINCT FROM NEW.idp_key) EXECUTE FUNCTION platform.directory_group_key_changed();

-- Sperren, Entsperren oder ein neuer Subject-Wert betreffen jeden Vault der Person.
CREATE FUNCTION platform.directory_user_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    affected text[];
    vaults uuid[];
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.active = NEW.active AND OLD.subject = NEW.subject THEN
        RETURN NULL;
    END IF;
    affected := array_remove(ARRAY[OLD.subject, NEW.subject], NULL);
    vaults := ARRAY(
        SELECT g.vault_id FROM platform.group_members gm JOIN platform.groups g ON g.id = gm.group_id
         WHERE gm.subject = ANY (affected)
        UNION
        SELECT g.vault_id FROM platform.subject_idp_groups s JOIN platform.groups g ON g.idp_group = s.idp_group
         WHERE s.subject = ANY (affected)
        UNION
        SELECT g.vault_id FROM platform.directory_group_members dm
          JOIN platform.directory_groups dg ON dg.id = dm.group_id
          JOIN platform.groups g ON g.idp_group = dg.idp_key
         WHERE dm.user_id = COALESCE(NEW.id, OLD.id));
    PERFORM platform.bump_access_version(v) FROM unnest(vaults) v;
    INSERT INTO platform.access_events (vault_id, scope_type, scope_subject, folder_path)
    SELECT v, 'USER', s, '' FROM unnest(vaults) v, unnest(affected) s;
    RETURN NULL;
END $$;
CREATE TRIGGER directory_users_changed AFTER INSERT OR UPDATE OR DELETE ON platform.directory_users
    FOR EACH ROW EXECUTE FUNCTION platform.directory_user_changed();

-- Verknuepfung gesetzt, geaendert oder geloest: die Gruppe als Ganzes und jede Person, die nur ueber die
-- alte IdP-Gruppe drin war.
CREATE FUNCTION platform.group_link_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO platform.access_events (vault_id, scope_type, scope_subject, folder_path)
    VALUES (NEW.vault_id, 'GROUP', NEW.id::text, '');
    INSERT INTO platform.access_events (vault_id, scope_type, scope_subject, folder_path)
    SELECT DISTINCT NEW.vault_id, 'USER', x.subject, '' FROM (
        SELECT s.subject FROM platform.subject_idp_groups s WHERE s.idp_group = OLD.idp_group
        UNION
        SELECT du.subject FROM platform.directory_groups dg
          JOIN platform.directory_group_members dm ON dm.group_id = dg.id
          JOIN platform.directory_users du ON du.id = dm.user_id
         WHERE dg.idp_key = OLD.idp_group) x;
    RETURN NULL;
END $$;
CREATE TRIGGER groups_idp_link AFTER UPDATE OF idp_group ON platform.groups
    FOR EACH ROW WHEN (OLD.idp_group IS DISTINCT FROM NEW.idp_group) EXECUTE FUNCTION platform.group_link_changed();
