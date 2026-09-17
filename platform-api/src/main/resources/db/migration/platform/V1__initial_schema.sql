-- StoneIntelligence platform schema, V1: Fundament (Plan.md Abschnitt 6, Phase 1).
--
-- Cascade-Policy ist bewusst pro Fremdschlüssel einzeln begründet (Plan.md Abschnitt 3,
-- Fehlerklasse 4) statt pauschal für alle Kind-Tabellen von vaults/notes vergeben.

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS platform;

-- Ein Vault ist die oberste Isolationsgrenze (Mandant). Cross-Vault-Betrieb ist laut Plan.md
-- Abschnitt 8.2 verworfen, die Fremdschlüsselgrenze bleibt trotzdem strikt.
CREATE TABLE platform.vaults (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- Eine Notiz/Datei, adressiert über ihre stabile ID (nie über den Pfad, Fehlerklasse 5).
-- ON DELETE CASCADE auf vault_id: das Löschen eines gesamten Vaults ist eine explizite,
-- harte Batch-Operation (Plan.md Abschnitt 3.4) - kein versehentlicher Nebeneffekt.
CREATE TABLE platform.notes (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vault_id        uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    path            text NOT NULL,
    note_level      integer NOT NULL CHECK (note_level BETWEEN 1 AND 101),
    -- Monoton wachsende, serverseitig vergebene Sequenznummer je Zeile (bigserial, NICHT die
    -- UUID) - Grundlage fuer die Keyset-Pagination der Reconciliation (Fehlerklasse 2). Eine
    -- UUID hat KEINE Beziehung zur Einfuegereihenfolge; ein "id > letzte-gesehene-id"-Cursor
    -- kann eine waehrend der Pagination neu eingefuegte Zeile mit "kleinerer" UUID dauerhaft
    -- uebergehen, obwohl die letzte Seite faelschlich complete=true meldet.
    sequence        bigserial NOT NULL,

    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_edited_by  text,
    last_edited_at  timestamptz,
    last_opened_by  text,
    last_opened_at  timestamptz,

    UNIQUE (vault_id, path)
);

CREATE INDEX idx_notes_vault_id ON platform.notes (vault_id);
CREATE INDEX idx_notes_vault_sequence ON platform.notes (vault_id, sequence);
CREATE INDEX idx_notes_path_trgm ON platform.notes USING gin (path gin_trgm_ops);

-- Snapshots des Yjs-CRDT-Zustands (der laut Plan.md Abschnitt 8.4 die Source of Truth ist).
-- Rohe Update-Logs vor dem letzten konsistenten Snapshot sind technisch abhängig und fachlich
-- wertlos, sobald der Snapshot existiert - CASCADE ist hier korrekt (Fehlerklasse 4).
CREATE TABLE platform.note_snapshots (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    note_id         uuid NOT NULL REFERENCES platform.notes(id) ON DELETE CASCADE,
    server_sequence bigint NOT NULL,
    state           bytea NOT NULL,
    is_ciphertext   boolean NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),

    UNIQUE (note_id, server_sequence)
);

CREATE INDEX idx_note_snapshots_note_id ON platform.note_snapshots (note_id);

-- Tombstones sind laut Plan.md Abschnitt 3, Fehlerklasse 3 ein dauerhafter, expliziter Zustand
-- (keine Zeitfenster) mit serverseitiger Sequenznummer und idempotenter Operations-ID. Sie
-- referenzieren note_id bewusst NICHT als Fremdschlüssel - die Notiz-Zeile existiert nach der
-- Löschung nicht mehr, der Tombstone ist gerade der Beleg dafür. GC läuft über eine explizite
-- Regel (Retention-Frist / Geräte-Checkpoint / erzwungener Baseline-Snapshot), nie über TTL.
CREATE TABLE platform.note_tombstones (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vault_id        uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    note_id         uuid NOT NULL,
    operation_id    text NOT NULL,
    server_sequence bigserial NOT NULL,
    deleted_by      text NOT NULL,
    deleted_at      timestamptz NOT NULL DEFAULT now(),
    garbage_collected_at timestamptz,

    -- Idempotenz ist bewusst an (vault_id, note_id, operation_id) gebunden, nicht nur
    -- (vault_id, operation_id): sonst koennte ein wiederverwendeter/erratener operation_id-Wert
    -- fuer eine ANDERE Note im selben Vault denselben Tombstone zurueckliefern und dadurch deren
    -- Sync-Session schliessen sowie einen falschen Audit-Eintrag fuer sie erzeugen.
    UNIQUE (vault_id, note_id, operation_id)
);

CREATE INDEX idx_note_tombstones_vault_sequence ON platform.note_tombstones (vault_id, server_sequence);

-- Audit-Events sind eigenständig aufbewahrungspflichtig (Anforderungen.md: "Alle Änderungen von
-- Agenten sollen dauerhaft gespeichert werden") und müssen das Löschen des zugehörigen Vaults
-- oder Notiz überleben (Plan.md Abschnitt 3, Fehlerklasse 4) - deshalb bewusst KEINE
-- Fremdschlüssel-Constraint auf vaults/notes, nur die IDs als Referenz-Werte. Ein Löschen von
-- personenbezogenen Nutzdaten wirkt hier über Payload-Redaktion, nie über Zeilenlöschung.
CREATE TABLE platform.audit_events (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vault_id    uuid NOT NULL,
    note_id     uuid,
    actor       text NOT NULL,
    action      text NOT NULL,
    payload     jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_events_vault_occurred ON platform.audit_events (vault_id, occurred_at);
