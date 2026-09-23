-- KI-Laeufe legen das gelesene Original als Datei im Vault ab (ADR 0008/0009); das Rueckgaengig-
-- machen entfernt sie wieder - geprueft am SHA-256 in text_after.
ALTER TABLE platform.ai_changes DROP CONSTRAINT ai_changes_kind_check;
ALTER TABLE platform.ai_changes ADD CONSTRAINT ai_changes_kind_check
    CHECK (kind IN ('CREATED', 'UPDATED', 'FILE_CREATED'));
