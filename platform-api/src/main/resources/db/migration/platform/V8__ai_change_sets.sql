-- KI-Aenderungen als Change-Sets (ADR 0008): je Verarbeitung die exakten Operationen je Notiz mit
-- Text davor/danach, damit sich alles konfliktgeprueft rueckgaengig machen laesst. Bewusst KEIN
-- Fremdschluessel auf platform.notes: Rueckgaengig loescht angelegte Notizen, der Nachweis bleibt.
-- Datensparsamkeit: die Textkopien loescht der AiChangeSetPurger nach Ablauf des Rueckgaengig-
-- Zeitraums (DSGVO Art. 5 Abs. 1 lit. e).
CREATE TABLE platform.ai_change_sets (
    id            uuid PRIMARY KEY,
    vault_id      uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    service       text NOT NULL,
    agent         text NOT NULL,
    requested_by  text NOT NULL,
    label         text NOT NULL,
    created_at    timestamptz NOT NULL,
    reverted_at   timestamptz
);

CREATE INDEX ai_change_sets_by_vault ON platform.ai_change_sets (vault_id, created_at DESC);
CREATE INDEX ai_change_sets_by_age ON platform.ai_change_sets (created_at);

CREATE TABLE platform.ai_changes (
    id             uuid PRIMARY KEY,
    change_set_id  uuid NOT NULL REFERENCES platform.ai_change_sets(id) ON DELETE CASCADE,
    sequence       bigserial NOT NULL,
    note_id        uuid NOT NULL,
    path           text NOT NULL,
    kind           text NOT NULL CHECK (kind IN ('CREATED', 'UPDATED')),
    text_before    text NOT NULL,
    text_after     text NOT NULL,
    at             timestamptz NOT NULL
);

CREATE INDEX ai_changes_by_change_set ON platform.ai_changes (change_set_id, sequence);
