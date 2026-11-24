# ADR 0014: Volltextsuche im Inhalt

Status: Angenommen (2026-11-24)

## Kontext

Die Suche fand nur Titel und Pfad. Der Server speichert Notizen als Yjs-Updates und kennt ihren Text nicht
direkt; für Inhalte gab es nur Embeddings der nächtlichen Verlinkung (ADR 0012), keine Wortsuche.

## Entscheidung

- `note_texts` (V24): Klartext je Notiz mit `tsvector` (Konfiguration `simple`, weil Vaults Sprachen mischen) und
  GIN-Index. Nur ein Index – das Yjs-Log bleibt die Quelle, die Tabelle darf geleert werden.
- Warteschlange `note_text_queue`, gefüllt von einem Trigger auf `note_snapshots`. Der `NoteTextIndexer` holt alle
  3 s bis zu 50 Notizen mit `FOR UPDATE SKIP LOCKED` (mehrere Instanzen teilen sich die Arbeit), berechnet den Text
  über die `YjsBridge` und schreibt ihn; ein älterer Stand überschreibt nie einen neueren. Scheitert es, kommt die
  Notiz zurück in die Warteschlange. Die Migration stellt den Bestand einmal hinein.
- Verschlüsselte Notizen (Level 101) werden nie indiziert, vorhandener Text fällt heraus.
- `GET …/notes/search` liefert zuerst Titel-/Pfadtreffer, dann Inhaltstreffer nach `ts_rank`; alle laufen durch
  dieselbe Rechteprüfung. Für angezeigte Inhaltstreffer kommt eine Fundstelle (`excerpts`) mit Treffern zwischen
  U+0002 und U+0003 – die Webapp setzt sie als Text, nie als HTML.
- Die Eingabe wird auf Wörter (Buchstaben, Ziffern) reduziert, jedes als Präfix, höchstens 8 (`SearchQuery`).

## Neu aufbauen

```sql
TRUNCATE platform.note_texts;
INSERT INTO platform.note_text_queue (note_id)
SELECT DISTINCT note_id FROM platform.note_snapshots ON CONFLICT DO NOTHING;
```

## Grenzen

Ein Tastendruck stellt die Notiz in die Warteschlange (ein `INSERT … ON CONFLICT DO NOTHING` je Update); der Text
ist nach einigen Sekunden durchsuchbar. Dateien (PDF-Inhalte) sind nicht enthalten. Grundlage für „Frag den Vault“.
