-- Ordner als eigene Objekte: leere Ordner werden synchronisiert, und ein auf einem Geraet
-- geloeschter Ordner verschwindet auf den anderen, statt leer liegen zu bleiben.
CREATE TABLE platform.folders (
    vault_id    uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    path        text NOT NULL,
    created_by  text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (vault_id, path)
);

-- Bestehende Vaults kannten bisher nur Notizen: alle Ordner oberhalb vorhandener Notizen anlegen.
INSERT INTO platform.folders (vault_id, path, created_by)
SELECT DISTINCT n.vault_id,
       array_to_string((string_to_array(n.path, '/'))[1:depth], '/'),
       'migration'
FROM platform.notes n,
     generate_series(1, cardinality(string_to_array(n.path, '/')) - 1) AS depth
ON CONFLICT DO NOTHING;
