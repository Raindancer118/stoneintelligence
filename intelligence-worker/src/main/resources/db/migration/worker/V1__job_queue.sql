-- Persistierte Job-Queue (Outbox) für den intelligence-worker (Plan.md Abschnitt 2 + 3):
-- Backpressure und Zuverlässigkeit laufen ueber diese Tabelle, nie ueber einen
-- In-Memory-Executor. idempotency_key verhindert doppelte Verarbeitung bei Client-Retries.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS worker;

CREATE TYPE worker.job_status AS ENUM ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'QUARANTINED');

CREATE TABLE worker.jobs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type        text NOT NULL,
    idempotency_key text NOT NULL,
    payload         jsonb NOT NULL,
    status          worker.job_status NOT NULL DEFAULT 'PENDING',
    attempts        integer NOT NULL DEFAULT 0,
    max_attempts    integer NOT NULL DEFAULT 5,
    available_at    timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    UNIQUE (job_type, idempotency_key)
);

CREATE INDEX idx_jobs_status_available_at ON worker.jobs (status, available_at);
