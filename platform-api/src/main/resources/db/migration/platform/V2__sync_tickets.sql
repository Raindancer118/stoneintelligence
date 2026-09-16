-- Geteilter Ticket-Store fuer Mehr-Instanz-Betrieb (mehrere platform-api-Prozesse hinter einem
-- Load Balancer teilen sich denselben Store statt eines prozesslokalen In-Memory-Maps).
-- Tickets leben Sekunden - kein FK auf vaults/notes noetig, keine Aufbewahrungspflicht.

CREATE TABLE platform.sync_tickets (
    token       text PRIMARY KEY,
    vault_id    uuid NOT NULL,
    note_id     uuid NOT NULL,
    actor       text NOT NULL,
    issued_at   timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL
);

CREATE INDEX idx_sync_tickets_expires_at ON platform.sync_tickets (expires_at);
