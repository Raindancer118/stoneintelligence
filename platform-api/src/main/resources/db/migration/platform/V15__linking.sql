-- Naechtliche Verlinkung (ADR 0012), Stufe 1.
--
-- 1. KI-Jobs koennen jetzt auch Verlinkungslaeufe sein (ohne Dokument).
ALTER TABLE platform.ai_jobs ADD COLUMN kind text NOT NULL DEFAULT 'INGEST' CHECK (kind IN ('INGEST', 'LINKING'));

-- 2. Eine gesetzte Verlinkung ist eine eigene Art Aenderung: gespeichert wird nur das eingefuegte
--    Markup (details), nicht der Notiztext - Rueckgaengig nimmt genau dieses Markup wieder heraus,
--    auch wenn inzwischen weitergeschrieben wurde. text_before/text_after bleiben dabei leer, damit
--    von Menschen geschriebene Notizen nicht ein zweites Mal gespeichert werden.
ALTER TABLE platform.ai_changes DROP CONSTRAINT ai_changes_kind_check;
ALTER TABLE platform.ai_changes ADD CONSTRAINT ai_changes_kind_check
    CHECK (kind IN ('CREATED', 'UPDATED', 'FILE_CREATED', 'LINKED'));
ALTER TABLE platform.ai_changes ADD COLUMN details jsonb;

-- 3. Einstellungen je Vault. Standard: aus. Links auch in Notizen von Menschen: an (Toms Wunsch).
--    requested_by: in wessen Namen (mit wessen Rechten) der naechtliche Lauf handelt.
CREATE TABLE platform.vault_linking (
    vault_id            uuid PRIMARY KEY REFERENCES platform.vaults(id) ON DELETE CASCADE,
    enabled             boolean NOT NULL DEFAULT false,
    mode                text NOT NULL DEFAULT 'AI' CHECK (mode IN ('AI', 'SEMANTIC')),
    link_human_notes    boolean NOT NULL DEFAULT true,
    max_links_per_note  integer CHECK (max_links_per_note > 0),
    service             text,
    requested_by        text NOT NULL,
    last_run_at         timestamptz,
    updated_at          timestamptz NOT NULL
);
