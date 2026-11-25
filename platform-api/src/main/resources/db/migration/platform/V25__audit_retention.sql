-- Aufbewahrungsfrist des Protokolls je Vault, in Tagen. NULL = unbegrenzt (Standard). Mindestens ein
-- Jahr; Eintraege von KI-Agenten loescht die Frist nie (Anforderungen.md), das regelt der Loeschjob.
ALTER TABLE platform.vaults
    ADD COLUMN audit_retention_days integer CHECK (audit_retention_days IS NULL OR audit_retention_days >= 365);
