-- Frei definierbare Rollen und Gruppen (Plan.md Abschnitt 4.4a) - kein festes Vierer-Set wie im
-- Vorgaenger stonesync. Nutzer->Gruppe->Rolle ist die einzige Zuordnungskette (keine direkten
-- Nutzer-Rollen-Zuweisungen).
--
-- Alle Tabellen haengen letztlich am Vault: ON DELETE CASCADE ist hier korrekt, weil eine
-- Vault-Loeschung eine explizite, harte Batch-Operation ist (wie bereits fuer notes begruendet,
-- s. V1__initial_schema.sql und ADR 0004) - Rollen/Gruppen sind ohne ihren Vault bedeutungslos.

CREATE TABLE platform.roles (
    id       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vault_id uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    name     text NOT NULL,

    UNIQUE (vault_id, name)
);

CREATE TABLE platform.role_permissions (
    role_id    uuid NOT NULL REFERENCES platform.roles(id) ON DELETE CASCADE,
    permission text NOT NULL,

    PRIMARY KEY (role_id, permission)
);

CREATE TABLE platform.groups (
    id       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vault_id uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    name     text NOT NULL,

    UNIQUE (vault_id, name)
);

-- subject = das Subject-Claim aus dem OIDC-Token (Phase 3) - bewusst kein FK auf eine eigene
-- Nutzertabelle, die es noch nicht gibt (Identity-Provider bleibt Authentik, kein lokaler
-- Nutzerspeicher als zweite Quelle der Wahrheit).
CREATE TABLE platform.group_members (
    group_id uuid NOT NULL REFERENCES platform.groups(id) ON DELETE CASCADE,
    subject  text NOT NULL,

    PRIMARY KEY (group_id, subject)
);

CREATE TABLE platform.group_roles (
    group_id uuid NOT NULL REFERENCES platform.groups(id) ON DELETE CASCADE,
    role_id  uuid NOT NULL REFERENCES platform.roles(id) ON DELETE CASCADE,

    PRIMARY KEY (group_id, role_id)
);

CREATE INDEX idx_group_members_subject ON platform.group_members (subject);
