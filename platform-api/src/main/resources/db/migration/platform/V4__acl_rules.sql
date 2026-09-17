-- Ordner- und Themen-ACLs (Plan.md Abschnitt 3 + 4.4a). Praezedenz wird ausschliesslich in
-- Anwendungscode berechnet (PathRules/TopicRules, reine Funktionen) - diese Tabellen sind nur
-- der Regel-Speicher.

CREATE TABLE platform.path_rules (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vault_id     uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    path_prefix  text NOT NULL,
    scope_type   text NOT NULL CHECK (scope_type IN ('EVERYONE', 'USER')),
    scope_subject text,
    effect       text NOT NULL CHECK (effect IN ('ALLOW', 'DENY')),

    CHECK ((scope_type = 'USER') = (scope_subject IS NOT NULL))
);

CREATE INDEX idx_path_rules_vault_id ON platform.path_rules (vault_id);

CREATE TABLE platform.topic_rules (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vault_id     uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    topic        text NOT NULL,
    scope_type   text NOT NULL CHECK (scope_type IN ('EVERYONE', 'USER')),
    scope_subject text,
    effect       text NOT NULL CHECK (effect IN ('ALLOW', 'DENY')),

    CHECK ((scope_type = 'USER') = (scope_subject IS NOT NULL))
);

CREATE INDEX idx_topic_rules_vault_id ON platform.topic_rules (vault_id);
