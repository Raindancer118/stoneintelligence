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

## Nachtrag: lange Dokumente und Foliensätze (2026-09-23)

Anlass: ein 186-seitiger Controlling-Foliensatz und ein 260-seitiger Beamer-Satz (Diskrete
Mathematik). PDFs wurden bisher **je Seite** gelesen – 200 Folien = 200 Aufrufe ohne Kontext; das
Token-Budget reichte für etwa 80, der Rest fiel still weg.

1. **Aufräumen vor dem Lesen** (`PageCleaner`): Seitenzahlen am Seitenrand, Animationsschritte
   (eine Seite, deren Zeilen alle auf der nächsten wiederkehren) und Kopf-/Fußzeilen, die auf
   ≥ 60 % der Seiten **und** im ersten wie im letzten Viertel stehen (Ziffern ignoriert – die
   Textextraktion klebt die Seitenzahl an die Fußzeile). Kapitelüberschriften über einen langen
   Abschnitt bleiben: sie sind Struktur.
2. **Seiten bündeln**: aufeinanderfolgende Seiten teilen sich einen Abschnitt bis 10.000 Zeichen,
   mit `[S. n]`-Markern; Quellenangaben als Seitenbereich („S. 12–18“). Aus 183 Folien werden 9
   Abschnitte.
3. **Gliederung für den Planer**: bei langen Dokumenten sieht er die Folientitel des ganzen Satzes
   (bis 12.000 Zeichen, sonst gleichmäßig ausgedünnt) plus Auszüge. Für Vorlesungen: erst ein
   Überblick über die Veranstaltung, dann je zentralem Konzept eine Notiz (etwa eine je 5–10
   Folien), nichts Organisatorisches.
4. **Zusammenführen in Stufen**: große Themen werden in Portionen ≤ 12.000 Zeichen gemergt, dann
   die Ergebnisse; ein Merge unter 25 % der Eingabe gilt als abgeschnitten, die Teile bleiben.
5. **Nichts fällt still weg**: nicht gelesene Abschnitte (Budget), unbrauchbare Antworten und nicht
   lesbare Bildseiten stehen in der Quellnotiz und in der Job-Meldung. Eine nicht lesbare Bildseite
   bricht den Lauf nicht mehr ab.
6. **Warten statt Aufgeben**: sind alle Anbieter nur vorübergehend weg (503/429/Netz), wird bis zu
   `llm.retryAttempts` (4) mal gewartet (20 s, 60 s, 180 s). `ingest.maxPages` 200 → 500.
7. **Betrieb**: Groq (Free Tier) erlaubt 8.000 Token/Minute je Schlüssel inklusive Antwortlänge –
   für große Abschnitte ungeeignet, nur Rückfallebene. Hauptlast trägt Gemini mit breiten Ketten
   (3.6-flash, 3.8-flash, flash-latest, 3.5-flash, flash-lite-latest; Bilder zuletzt über Qwen auf
   Groq).

## Nachtrag: Tabellen, Quellenlinks, schlanke Eigenschaften (2026-09-25, Issue #1)

1. **Nur aus den Quellen**: Planer, Extraktion und Zusammenführen verwenden ausschließlich den
   Dokumenttext und die Titel vorhandener Notizen – kein Wissen von außen, keine Beispiele oder
   Zahlen, die nicht im Text stehen, keine Vermutungen.
2. **Tabellen bleiben Tabellen**: Extraktion, Bild-Lesen und Zusammenführen geben Tabellen als
   Markdown-Tabellen wieder (fehlende Zellen bleiben leer). `[[Ziel|Text]]` in einer Tabellenzeile
   wird zu `[[Ziel\|Text]]`, sonst beendet das `|` die Zelle.
3. **Quellenangabe als Link**: `*Quelle: …*` verlinkt jede Stelle – bei gespeichertem PDF auf die
   Seite (`[[Anhänge/X.pdf#page=12|X, S. 12–18]]`, Obsidian öffnet das PDF dort), sonst auf die
   Quellnotiz. Dafür wird das Original vor den Notizen gespeichert.
4. **Eigenschaften**: KI-Notizen tragen nur noch `aliases` (falls vorhanden), `tags`, `created`,
   `updated`; `title` nur, wenn der Dateiname abweichen musste. `type`, `source`, `source_page`,
   `entities`, `related`, `confidence` entfallen – Herkunft steht verlinkt im Text, Verwandtes
   unter „Siehe auch“. Beim nächsten Schreiben verlieren ältere KI-Notizen (`type: concept`) diese
   Felder; Notizen, die ein Mensch angelegt hat, behalten alle Eigenschaften. Quellnotiz und Index
   ebenso (Seitenzahl steht jetzt im Text der Quellnotiz).
