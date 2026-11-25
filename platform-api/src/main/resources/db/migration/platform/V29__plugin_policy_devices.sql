-- Plugin-Steuerung je Vault (ADR 0019): Richtlinie, Geraeteregister und die Geraetekennung am Sync-Ticket.
CREATE TABLE platform.plugin_policies (
    vault_id    uuid PRIMARY KEY REFERENCES platform.vaults(id) ON DELETE CASCADE,
    mode        text NOT NULL CHECK (mode IN ('OFF', 'ENFORCE')),
    -- [{"id":"calendar","version":"1.5.10"}], version optional (null = jede)
    allowed     jsonb NOT NULL DEFAULT '[]'::jsonb,
    updated_by  text NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- Ein Geraet ist eine Plugin-Instanz (Kennung steht in den Plugin-Daten). Der Stand ist die letzte Meldung
-- des Plugins; er belegt nichts, was ein manipuliertes Plugin verschweigt (ADR 0019).
CREATE TABLE platform.devices (
    vault_id           uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    device_id          text NOT NULL CHECK (char_length(device_id) BETWEEN 8 AND 64),
    subject            text NOT NULL,
    name               text NOT NULL,
    platform           text NOT NULL,
    obsidian_version   text NOT NULL,
    plugin_version     text NOT NULL,
    restricted_mode    boolean NOT NULL,
    -- [{"id":"calendar","version":"1.5.10"}] - nur aktivierte Community-Plugins
    plugins            jsonb NOT NULL DEFAULT '[]'::jsonb,
    first_seen         timestamptz NOT NULL,
    last_seen          timestamptz NOT NULL,
    revoked_at         timestamptz,
    revoked_by         text,
    wipe_requested_at  timestamptz,
    wiped_at           timestamptz,
    PRIMARY KEY (vault_id, device_id)
);
CREATE INDEX devices_by_last_seen ON platform.devices (vault_id, last_seen DESC);

ALTER TABLE platform.sync_tickets ADD COLUMN device_id text;
