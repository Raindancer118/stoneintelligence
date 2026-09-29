-- Versionsverlauf: wer ein Update geschrieben hat. Bestehende Updates bleiben ohne Autor (NULL).
ALTER TABLE platform.note_snapshots ADD COLUMN actor text;
