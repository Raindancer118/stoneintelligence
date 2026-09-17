-- Sync-Tickets sind seit der Multiplexing-Umstellung vault-, nicht mehr notenskopiert - eine
-- einzelne WebSocket-Verbindung joint/verlaesst beliebig viele Notiz-Raeume ueber denselben
-- Handshake (s. SyncWebSocketHandler). Die Notiz-spezifische Berechtigungspruefung passiert
-- seitdem bei JOIN, nicht mehr bei der Ticket-Ausstellung.

ALTER TABLE platform.sync_tickets DROP COLUMN note_id;
