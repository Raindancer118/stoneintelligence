-- Rechte je Ordner und je Eintrag (ADR 0011). Loest die Pfadregeln (V4) ab: die spezifischste
-- Freigabe ersetzt die Vault-Rechte, kann also Rechte geben und nehmen. Praezedenz wird
-- ausschliesslich in AccessResolver berechnet - diese Tabelle ist nur der Speicher.
--
-- Ziel ist entweder ein Ordnerpfad (ohne fuehrende/abschliessende Schraegstriche, '' = der ganze
-- Vault) oder eine NoteId. Eintrags-Freigaben haengen an der Id, damit sie Umbenennen/Verschieben
-- ueberstehen, und verschwinden mit dem Eintrag (CASCADE: ohne ihren Eintrag sind sie bedeutungslos). Ordner-
-- Freigaben verschiebt FolderRegistry beim Umbenennen des Ordners mit.
--
-- permissions: NULL = "wie im Vault" (hebt eine Einschraenkung weiter oben auf), leeres Array =
-- nichts erlaubt (der Eintrag ist fuer diese Person unsichtbar).
CREATE TABLE platform.access_grants (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vault_id      uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    folder_path   text,
    note_id       uuid REFERENCES platform.notes(id) ON DELETE CASCADE,
    scope_type    text NOT NULL CHECK (scope_type IN ('EVERYONE', 'USER', 'GROUP')),
    scope_subject text,
    permissions   text[],
    created_by    text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),

    CHECK ((folder_path IS NULL) <> (note_id IS NULL)),
    CHECK (folder_path IS NULL OR (folder_path NOT LIKE '/%' AND folder_path NOT LIKE '%/')),
    CHECK ((scope_type = 'EVERYONE') = (scope_subject IS NULL))
);

-- Je Ziel und Wer hoechstens eine Freigabe - Setzen ist ein Upsert.
CREATE UNIQUE INDEX uq_access_grants_folder
    ON platform.access_grants (vault_id, folder_path, scope_type, coalesce(scope_subject, ''))
    WHERE folder_path IS NOT NULL;
CREATE UNIQUE INDEX uq_access_grants_note
    ON platform.access_grants (note_id, scope_type, coalesce(scope_subject, ''))
    WHERE note_id IS NOT NULL;
CREATE INDEX idx_access_grants_vault_id ON platform.access_grants (vault_id);

-- Bestehende Pfadregeln uebernehmen. Ein Praefix, das genau einen Eintrag trifft, wird zur
-- Eintrags-Freigabe, alles andere zur Ordner-Freigabe. DENY -> nichts erlaubt, ALLOW -> wie im
-- Vault (genau das bedeutete ALLOW bisher: eine Sperre weiter oben aufheben). Bei doppelten
-- Regeln auf demselben Ziel gewinnt wie bisher DENY. path_rules selbst bleibt als Rueckfall
-- unangetastet, wird aber nicht mehr gelesen.
WITH rules AS (
    SELECT r.vault_id,
           trim(both '/' from r.path_prefix) AS path,
           r.scope_type,
           r.scope_subject,
           bool_or(r.effect = 'DENY') AS denied
    FROM platform.path_rules r
    GROUP BY 1, 2, 3, 4
)
INSERT INTO platform.access_grants (vault_id, folder_path, note_id, scope_type, scope_subject, permissions, created_by)
SELECT rules.vault_id,
       CASE WHEN n.id IS NULL THEN rules.path END,
       n.id,
       rules.scope_type,
       rules.scope_subject,
       CASE WHEN rules.denied THEN ARRAY[]::text[] END,
       'migration:V14'
FROM rules
LEFT JOIN platform.notes n ON n.vault_id = rules.vault_id AND n.path = rules.path;

COMMENT ON TABLE platform.path_rules IS 'Abgeloest durch access_grants (V14, ADR 0011) - wird nicht mehr gelesen.';
