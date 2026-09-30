-- ADR 0013: Geraete laden nur ihre Arbeitsbereiche, je Bereich ein Bereichsscan ueber den Pfad.
CREATE INDEX idx_notes_vault_path_c ON platform.notes (vault_id, path COLLATE "C");

-- Grosse Vaults: Verwaltende legen fest, dass Geraete nur gewaehlte Bereiche synchronisieren.
ALTER TABLE platform.vaults ADD COLUMN selective_sync boolean NOT NULL DEFAULT false;

-- Baeume laden Ebene fuer Ebene (Webapp, Ordnerauswahl): Eintraege DIREKT in einem Ordner, ohne den
-- ganzen Teilbaum darunter zu lesen.
ALTER TABLE platform.notes ADD COLUMN parent text GENERATED ALWAYS AS (
    CASE WHEN strpos(path, '/') = 0 THEN '' ELSE regexp_replace(path, '/[^/]*$', '') END) STORED;
CREATE INDEX idx_notes_vault_parent ON platform.notes (vault_id, parent, path COLLATE "C");
