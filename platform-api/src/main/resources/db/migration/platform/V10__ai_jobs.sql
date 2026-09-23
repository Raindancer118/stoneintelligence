-- KI-Verarbeitung hochgeladener Dokumente als persistierte Job-Queue (ADR 0008). Der Worker holt
-- Jobs ueber /internal (FOR UPDATE SKIP LOCKED, Lease fuer den Wiederanlauf nach Absturz) und
-- braucht selbst keinen Datenbankzugriff. Datensparsamkeit: `content` wird geleert, sobald der Job
-- endet; die Metadaten loescht die Aufraeumroutine nach 90 Tagen (DSGVO Art. 5 Abs. 1 lit. e).
CREATE TABLE platform.ai_jobs (
    id             uuid PRIMARY KEY,
    vault_id       uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    service        text NOT NULL,
    requested_by   text NOT NULL,
    file_name      text NOT NULL,
    content_type   text NOT NULL,
    size           bigint NOT NULL,
    level          integer NOT NULL CHECK (level BETWEEN 1 AND 99),
    content        bytea,
    status         text NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    attempts       integer NOT NULL DEFAULT 0,
    max_attempts   integer NOT NULL,
    available_at   timestamptz NOT NULL,
    lease_until    timestamptz,
    progress       text,
    percent        integer,
    error          text,
    change_set_id  uuid,
    created_at     timestamptz NOT NULL,
    finished_at    timestamptz
);

CREATE INDEX ai_jobs_claimable ON platform.ai_jobs (created_at) WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX ai_jobs_by_vault ON platform.ai_jobs (vault_id, created_at DESC);
CREATE INDEX ai_jobs_by_age ON platform.ai_jobs (created_at);
