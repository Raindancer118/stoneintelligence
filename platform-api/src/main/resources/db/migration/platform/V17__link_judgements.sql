-- Stufe 3 der Verlinkung (ADR 0012): eine KI prueft Kandidaten.
--
-- link_rejections: die KI fand den Link nicht sinnvoll. Gefragt wird erst wieder, wenn sich eine der
-- beiden Notizen geaendert hat (Hashes der Texte zum Zeitpunkt der Entscheidung).
CREATE TABLE platform.link_rejections (
    vault_id        uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    source_note_id  uuid NOT NULL REFERENCES platform.notes(id) ON DELETE CASCADE,
    target_note_id  uuid NOT NULL REFERENCES platform.notes(id) ON DELETE CASCADE,
    source_hash     text NOT NULL,
    target_hash     text NOT NULL,
    decided_at      timestamptz NOT NULL,

    PRIMARY KEY (source_note_id, target_note_id)
);

-- note_relations: welche Art Beziehung ein gesetzter Link ausdrueckt - Grundlage fuer den spaeteren
-- Wissensgraphen. Mit einer der Notizen verschwindet die Beziehung.
CREATE TABLE platform.note_relations (
    vault_id        uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    source_note_id  uuid NOT NULL REFERENCES platform.notes(id) ON DELETE CASCADE,
    target_note_id  uuid NOT NULL REFERENCES platform.notes(id) ON DELETE CASCADE,
    relation        text NOT NULL CHECK (relation IN ('uses', 'requires', 'part_of', 'related_to', 'described_by',
                                                      'example_of', 'contrasts_with')),
    change_set_id   uuid,
    created_at      timestamptz NOT NULL,

    PRIMARY KEY (source_note_id, target_note_id)
);

-- Einwilligung je Mitglied (Art. 49 Abs. 1 lit. a DSGVO): nur Notizen von Personen, die zugestimmt
-- haben (und Notizen der KI selbst), gehen im Modus "KI-geprueft" als Auszug an den KI-Anbieter.
-- Eine verwaltende Person kann das nicht fuer andere erklaeren. Widerruf = Zeile weg.
CREATE TABLE platform.linking_ai_consents (
    vault_id      uuid NOT NULL REFERENCES platform.vaults(id) ON DELETE CASCADE,
    subject       text NOT NULL,
    consented_at  timestamptz NOT NULL,

    PRIMARY KEY (vault_id, subject)
);
