-- Gemeinsame Canvas (ADR 0018): Eintraege der Art CANVAS sind Yjs-Dokumente wie Notizen, werden aber
-- nur an Plugins ausgeliefert, die die Art ausdruecklich anfordern.
ALTER TABLE platform.notes DROP CONSTRAINT IF EXISTS notes_kind_check;
ALTER TABLE platform.notes ADD CONSTRAINT notes_kind_check CHECK (kind IN ('NOTE', 'FILE', 'CANVAS'));

-- Ein Canvas hat keinen Text: er kommt nicht in die Warteschlange des Suchindex (ADR 0014).
CREATE OR REPLACE FUNCTION platform.queue_note_text() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO platform.note_text_queue (note_id)
    SELECT NEW.note_id WHERE EXISTS (SELECT 1 FROM platform.notes n WHERE n.id = NEW.note_id AND n.kind = 'NOTE')
    ON CONFLICT DO NOTHING;
    RETURN NULL;
END;
$$;
