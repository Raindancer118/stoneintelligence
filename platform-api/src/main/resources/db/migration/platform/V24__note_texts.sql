-- Volltextsuche im Inhalt. Der Server liest Notizen sonst nicht (Yjs-Updates); der Klartext hier
-- entsteht im Hintergrund aus dem Yjs-Stand und ist nur ein Suchindex - jederzeit neu aufbaubar.
-- 'simple' statt einer Sprache: Vaults mischen Deutsch und Englisch, gesucht wird mit Praefixen.
CREATE TABLE platform.note_texts (
    note_id   uuid PRIMARY KEY REFERENCES platform.notes(id) ON DELETE CASCADE,
    vault_id  uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    revision  bigint NOT NULL,
    body      text NOT NULL,
    search    tsvector GENERATED ALWAYS AS (to_tsvector('simple', body)) STORED
);
CREATE INDEX note_texts_search ON platform.note_texts USING gin (search);
CREATE INDEX note_texts_by_vault ON platform.note_texts (vault_id);

-- Was neu zu indizieren ist. Ein Trigger statt eines Merkers im Speicher: uebersteht Neustarts und
-- funktioniert mit mehreren Instanzen (abgeholt wird mit SKIP LOCKED).
CREATE TABLE platform.note_text_queue (
    note_id    uuid PRIMARY KEY REFERENCES platform.notes(id) ON DELETE CASCADE,
    queued_at  timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION platform.queue_note_text() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO platform.note_text_queue (note_id) VALUES (NEW.note_id) ON CONFLICT DO NOTHING;
    RETURN NULL;
END;
$$;

CREATE TRIGGER note_snapshots_queue_text
    AFTER INSERT ON platform.note_snapshots
    FOR EACH ROW EXECUTE FUNCTION platform.queue_note_text();

-- Bestand: jede Notiz mit Inhalt einmal.
INSERT INTO platform.note_text_queue (note_id)
SELECT DISTINCT s.note_id FROM platform.note_snapshots s
JOIN platform.notes n ON n.id = s.note_id
WHERE n.kind = 'NOTE'
ON CONFLICT DO NOTHING;
