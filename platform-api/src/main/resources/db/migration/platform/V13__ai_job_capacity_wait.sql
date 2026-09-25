-- Ein Job, dessen KI-Dienst kein Kontingent mehr hatte, wartet auf dessen Rueckkehr, statt einen
-- Versuch zu verbrauchen (Anbieter-Limits laufen oft erst nach Stunden ab). Das Dashboard zeigt
-- ihn so an - "wartet auf Kapazitaet" ist etwas anderes als "neuer Versuch nach einem Fehler".
ALTER TABLE platform.ai_jobs ADD COLUMN waiting_for_capacity boolean NOT NULL DEFAULT false;
