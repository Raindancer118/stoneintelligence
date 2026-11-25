-- Kommentare je Notiz (ADR 0016). Die Textstelle steht als Zitat mit Umgebung da, nicht als Position:
-- der Client sucht sie im aktuellen Text. Alles hängt per ON DELETE CASCADE an Notiz und Vault, ein
-- gelöschter Eintrag nimmt seine Kommentare mit. clock_timestamp() statt now(), damit die Reihenfolge
-- auch innerhalb einer Transaktion stimmt.
CREATE TABLE platform.comment_threads (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vault_id      uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    note_id       uuid NOT NULL REFERENCES platform.notes(id) ON DELETE CASCADE,
    quote         text,
    quote_prefix  text,
    quote_suffix  text,
    created_by    text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
    resolved_by   text,
    resolved_at   timestamptz
);
CREATE INDEX comment_threads_by_note ON platform.comment_threads (note_id, created_at);

CREATE TABLE platform.comments (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id   uuid NOT NULL REFERENCES platform.comment_threads(id) ON DELETE CASCADE,
    author      text NOT NULL,
    body        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    edited_at   timestamptz
);
CREATE INDEX comments_by_thread ON platform.comments (thread_id, created_at);

-- Nur Verweise: Text und Titel werden beim Lesen aufgelöst und gegen das Leserecht geprüft.
CREATE TABLE platform.comment_notifications (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient   text NOT NULL,
    vault_id    uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    note_id     uuid NOT NULL REFERENCES platform.notes(id) ON DELETE CASCADE,
    thread_id   uuid NOT NULL REFERENCES platform.comment_threads(id) ON DELETE CASCADE,
    comment_id  uuid NOT NULL REFERENCES platform.comments(id) ON DELETE CASCADE,
    actor       text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    read_at     timestamptz
);
CREATE INDEX comment_notifications_by_recipient ON platform.comment_notifications (recipient, created_at DESC);
