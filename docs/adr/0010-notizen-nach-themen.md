# ADR 0010: KI-Notizen nach Themen statt nach Begriffen

Status: Angenommen (2026-09-23)

## Kontext

Der erste echte Lauf (Brief an die Versicherung, Unfallschilderung, Hintergrund einer DnD-Figur)
ergab 43 Notizen wie „Privattelefonnummer“, „Abbildung 1“, „Buch“ oder „Riss“ – je Begriff eine
Notiz, Dubletten („HWS‑Distorsion“, „Schleudertrauma“, „Schleudertrauma (HWS-Distorsion)“) und
Links ins Leere. Ursache: Das Extraktions-Prompt verlangte atomare Zettelkasten-Notizen („ein
Konzept = eine Notiz“), und jeder Abschnitt wurde für sich gelesen, ohne Blick aufs Ganze.

## Entscheidungen

1. **Erst planen, dann schreiben.** Ein Aufruf (SMART) liest das ganze Dokument (bei langen
   Dokumenten Auszüge jedes Abschnitts, bis 24.000 Zeichen) und legt die Themen fest: das
   Ereignis/den Vorgang, Beteiligte mit eigener Rolle, bei Geschichten Figuren und Orte, bei
   Lehrtexten zentrale Begriffe. Einzelheiten (Nummern, Adressen, Daten, Abbildungen) werden nie
   eigene Themen. Der Planer sieht die Titel der vorhandenen Notizen und übernimmt sie, wenn ein
   Dokument dasselbe betrifft – ein zweiter Brief zum selben Unfall ergänzt dieselben Notizen.
2. **Die Extraktion schreibt nur geplante Themen.** Hat der Planer alles gelesen, wird eine Notiz
   außerhalb des Plans in das passende oder das Hauptthema eingefaltet (mit Zwischenüberschrift);
   nur bei Auszügen darf ein Abschnitt ein Thema ergänzen. Scheitert der Plan, gibt es eine Notiz
   über das Dokument; ist kein Anbieter erreichbar, wird der Job wiederholt.
3. **Kein Link ins Leere.** Links werden über die Datei aufgelöst, in der die Notiz liegt
   (Obsidian löst nach Dateinamen auf): `[[Datei|Titel]]`, bei Namensgleichheit mit Pfad. Verweise
   des Modells im Text auf nicht vorhandene Notizen werden zu Text. Dateinamen behalten Leerzeichen;
   typografische Bindestriche/Leerzeichen werden vereinheitlicht. Ähnliche Titel gelten nur als
   dieselbe Notiz, wenn Zahlen übereinstimmen und sie sich höchstens um Tippfehler unterscheiden.
4. **Das Original liegt im Vault.** Ein hochgeladenes PDF wird als synchronisierte Datei unter
   `Anhänge/` abgelegt und in der Quellnotiz eingebettet. Es gehört zum Change-Set
   (`FILE_CREATED`, SHA-256): Rückgängig entfernt es, solange niemand es ersetzt hat.
5. **Rückgängig aus Obsidian.** Befehl „KI-Änderungen anzeigen und rückgängig machen“ und ein
   Link `obsidian://stoneintelligence-ai-changes` in jeder Quellnotiz; Läufe, die die geöffnete
   Notiz geschrieben haben, stehen oben.

## Folgen

- Ein Lauf kostet einen zusätzlichen SMART-Aufruf; dafür entstehen im Testfall 9 statt 43 Notizen.
- `llm.maxOutputTokens` (Standard 16.384): reichhaltigere Notizen und Reasoning-Modelle sprengten
  die Standardlänge der Anbieter, Antworten brachen mitten im JSON ab.
- Probeläufe ohne Vault: `TrialRun` im Worker-Image (siehe Klassenkommentar).
