-- Einladungen in einen Vault (per E-Mail an Personen ohne Konto). Gespeichert wird nur der
-- SHA-256-Hash des Tokens aus dem Mail-Link. Datensparsamkeit: abgeschlossene Einladungen loescht
-- der InvitationService 30 Tage nach Annahme/Widerruf/Ablauf (DSGVO Art. 5 Abs. 1 lit. e).
CREATE TABLE platform.vault_invitations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vault_id        uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    email           text NOT NULL,
    access          text NOT NULL CHECK (access IN ('EDIT', 'READ')),
    token_hash      text NOT NULL UNIQUE,
    invited_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    external_id     text,
    enrollment_url  text,
    accepted_by     text,
    accepted_at     timestamptz,
    revoked_at      timestamptz
);

CREATE INDEX vault_invitations_pending_by_vault ON platform.vault_invitations (vault_id)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
CREATE INDEX vault_invitations_pending_by_email ON platform.vault_invitations (lower(email))
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
