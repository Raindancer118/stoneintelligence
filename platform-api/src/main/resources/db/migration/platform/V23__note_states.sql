-- ADR 0013, Schritt 6: zusammengefuehrter Yjs-Zustand je Notiz bis einschliesslich `revision`.
-- Beitreten sendet diesen Zustand plus die Updates danach statt der ganzen Historie.
-- note_snapshots bleibt vollstaendig und die Quelle fuer Versionen; diese Tabelle ist nur ein
-- Cache und darf jederzeit geleert werden.
CREATE TABLE platform.note_states (
    note_id    uuid PRIMARY KEY REFERENCES platform.notes(id) ON DELETE CASCADE,
    revision   bigint NOT NULL CHECK (revision > 0),
    state      bytea NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
