-- Dateien (PDFs, Bilder, Anhaenge) synchronisieren (ADR 0009): Eintraege derselben Tabelle wie
-- Notizen, damit Ids, Pfade, Umbenennen, Tombstones, Ordner und Pfadregeln fuer beide gelten.
ALTER TABLE platform.notes ADD COLUMN kind text NOT NULL DEFAULT 'NOTE' CHECK (kind IN ('NOTE', 'FILE'));

-- Inhalt einer Datei als fortlaufende Versionen; die Bytes liegen inhaltsadressiert im
-- Dateispeicher (gehostet: Storage Box). Loeschen der Datei loescht ihre Versionen, die Bytes
-- entfernt die Aufraeumroutine nach einer Karenzzeit (DSGVO Art. 5 Abs. 1 lit. e).
CREATE TABLE platform.file_versions (
    note_id       uuid NOT NULL REFERENCES platform.notes(id) ON DELETE CASCADE,
    revision      bigint NOT NULL CHECK (revision >= 1),
    sha256        text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size          bigint NOT NULL CHECK (size >= 0),
    content_type  text NOT NULL,
    created_by    text NOT NULL,
    created_at    timestamptz NOT NULL,
    PRIMARY KEY (note_id, revision)
);

CREATE INDEX file_versions_by_sha ON platform.file_versions (sha256);
