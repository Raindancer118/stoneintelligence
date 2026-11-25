-- "Frag den Vault" (ADR 0017): Fragen an den Vault, beantwortet als KI-Job der Art ASK.
-- Fragen und Antworten sind privat (nur die fragende Person); sie werden wie die Jobs nach 90 Tagen geloescht.
ALTER TABLE platform.ai_jobs DROP CONSTRAINT ai_jobs_kind_check;
ALTER TABLE platform.ai_jobs ADD CONSTRAINT ai_jobs_kind_check CHECK (kind IN ('INGEST', 'LINKING', 'ASK'));

CREATE TABLE platform.vault_questions (
    id                  uuid PRIMARY KEY,
    vault_id            uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    asked_by            text NOT NULL,
    service             text NOT NULL,
    question            text NOT NULL,
    -- ohne Fremdschluessel: der Job darf frueher verschwinden, die Frage bleibt bis zu ihrer eigenen Frist
    job_id              uuid NOT NULL,
    asked_at            timestamptz NOT NULL,
    answered_at         timestamptz,
    answered            boolean,
    answer              text,
    -- die an den Dienst gesendeten Stellen: [{"n":1,"noteId":"...","heading":"...","excerpt":"..."}]
    sources             jsonb NOT NULL DEFAULT '[]'::jsonb,
    -- Nummern der Stellen, die die Antwort zitiert
    cited               jsonb NOT NULL DEFAULT '[]'::jsonb,
    omitted_for_consent integer NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX vault_questions_by_job ON platform.vault_questions (job_id);
CREATE INDEX vault_questions_by_asker ON platform.vault_questions (vault_id, asked_by, asked_at DESC);
CREATE INDEX vault_questions_by_age ON platform.vault_questions (asked_at);
