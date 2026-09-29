-- ADR 0013: Geraete laden nur ihre Arbeitsbereiche, je Bereich ein Bereichsscan ueber den Pfad.
CREATE INDEX idx_notes_vault_path_c ON platform.notes (vault_id, path COLLATE "C");

-- Grosse Vaults: Verwaltende legen fest, dass Geraete nur gewaehlte Bereiche synchronisieren.
ALTER TABLE platform.vaults ADD COLUMN selective_sync boolean NOT NULL DEFAULT false;
