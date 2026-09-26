-- Embeddings fuer die Verlinkung, Stufe 2, und "Aehnliche Notizen" (ADR 0012).
--
-- Je Notiz mehrere Abschnitte (chunk), jeder mit seinem Vektor. content_hash ist der SHA-256 des
-- Notiztexts, aus dem die Abschnitte stammen - weicht er ab, rechnet der Worker neu; ebenso bei
-- anderem model. Mit der Notiz verschwinden auch ihre Vektoren (CASCADE: ohne Notiz bedeutungslos).
-- Die Dimension (384) gehoert zum gepinnten Modell multilingual-e5-small.
-- Ausdruecklich nach public: Flyway laeuft mit search_path=platform, die Anwendung mit dem Standard -
-- sonst laege der Typ vector in platform und jede Abfrage der Anwendung fande ihn nicht.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

CREATE TABLE platform.note_embeddings (
    note_id       uuid NOT NULL REFERENCES platform.notes(id) ON DELETE CASCADE,
    chunk         integer NOT NULL CHECK (chunk >= 0),
    vault_id      uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    model         text NOT NULL,
    content_hash  text NOT NULL,
    heading       text,
    embedding     public.vector(384) NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (note_id, chunk)
);

CREATE INDEX note_embeddings_by_vault ON platform.note_embeddings (vault_id);
CREATE INDEX note_embeddings_hnsw ON platform.note_embeddings USING hnsw (embedding public.vector_cosine_ops);

-- Modus: LITERAL (nur woertliche Nennungen, Stufe 1), SEMANTIC (dazu aehnliche Inhalte, lokal),
-- AI (dazu KI-Pruefung, Stufe 3 - folgt). Bisher stand nur der Standard AI drin, der noch nichts
-- bewirkte; tatsaechlich lief ueberall Stufe 1 - genau das heisst jetzt LITERAL.
ALTER TABLE platform.vault_linking DROP CONSTRAINT vault_linking_mode_check;
UPDATE platform.vault_linking SET mode = 'LITERAL' WHERE mode = 'AI';
ALTER TABLE platform.vault_linking ADD CONSTRAINT vault_linking_mode_check CHECK (mode IN ('LITERAL', 'SEMANTIC', 'AI'));
ALTER TABLE platform.vault_linking ALTER COLUMN mode SET DEFAULT 'LITERAL';

-- Einmal verlinkt, nie wieder vorgeschlagen: entfernt jemand einen Link von Hand (oder macht einen
-- Lauf rueckgaengig), setzt ihn die naechste Nacht nicht erneut. Mit einer der Notizen verschwindet das Paar.
CREATE TABLE platform.link_pairs (
    vault_id        uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    source_note_id  uuid NOT NULL REFERENCES platform.notes(id) ON DELETE CASCADE,
    target_note_id  uuid NOT NULL REFERENCES platform.notes(id) ON DELETE CASCADE,
    linked_at       timestamptz NOT NULL,

    PRIMARY KEY (source_note_id, target_note_id)
);
