# ADR 0017: Frag den Vault

Status: Angenommen (2026-11-25)

## Kontext

Eine Frage in natürlicher Sprache soll eine Antwort liefern, die **nur** aus Notizen entsteht, die die fragende Person
lesen darf, mit nummerierten Quellenangaben, die die Notiz (und die Stelle) öffnen. Grundlagen: Volltextindex
`note_texts` (ADR 0014), Abschnitts-Embeddings `note_embeddings` (ADR 0012), KI-Jobs und Worker (ADR 0008),
Einwilligung je Verfasser (`linking_ai_consents`, ADR 0012 Stufe 3), Parität Obsidian und Web (ADR 0011).

## Entscheidungen

### 1. Ablauf als KI-Job `ASK`

1. `POST /api/v1/vaults/{id}/ask` legt eine Frage (`vault_questions`, V27) und einen Job `kind = ASK` in die
   bestehende Queue. Fortschritt, Warten auf Kontingent, Wiederholung und Abbrechen laufen wie bei jedem KI-Job.
   Ein `ASK`-Job hat weder Dokument noch Change-Set (er schreibt nichts in den Vault).
2. Der Worker holt den Job, **embedded die Frage selbst** (er besitzt das Modell, `Embedder.Kind.QUERY`) und fragt
   platform-api nach den Textstellen (`POST /internal/ai/jobs/{id}/passages`, Body: Vektor oder leer).
   Ohne Embedding-Modell sucht platform-api nur über den Volltext.
3. Der Worker baut den Prompt aus den nummerierten Stellen, ruft das Modell über ai-gateway, prüft die Antwort und
   schickt sie an platform-api (`POST /internal/ai/jobs/{id}/answer`), das sie mit den Zitaten speichert.

Warum die Aufteilung: platform-api hat keinen LLM-Zugriff und kein Embedding-Modell, der Worker keinen
Datenbankzugriff. Die Suche samt Rechteprüfung gehört auf den Server (dort liegen Rechte, Index, Vektoren); der
Vektor der Frage ist die einzige Eingabe, die nur der Worker liefern kann. Er wird nicht gespeichert.

### 2. Suche in platform-api (`AskRetrieval`)

- **Volltext:** die Frage wird zu einer tsquery mit ODER über ihre inhaltstragenden Wörter (`SearchQuery.anyOf`,
  Stoppwörter und Wörter unter 3 Zeichen fallen weg, höchstens 12). `SearchQuery.tsquery` (UND) taugt für Fragen nicht.
- **Semantik:** `NoteEmbeddingRepository.nearest(vault, vektor, n)` liefert die nächsten Abschnitte (Kosinus).
- **Zusammenführen:** Reciprocal Rank Fusion über beide Ranglisten je Notiz. Die Stelle ist der Abschnitt des
  semantischen Treffers, sonst der Abschnitt der Notiz mit den meisten Frage-Wörtern. Abschnitte schneidet
  dieselbe `NoteChunker`-Logik wie beim Indizieren (nach `domain-core` verschoben, vorher nur im Worker).
- **Rechte vor allem anderen:** Kandidaten laufen durch `VaultAccessGuard` (READ der fragenden Person), dann durch die
  Level-Schranke des Dienstes (`AiWriteService.mayProcess`, schließt 100/101 und KI-sperrende Level aus). Erst danach
  zählt Einwilligung, Rang und Budget. Nichts davon geschieht im Worker.
- **Budget:** höchstens 8 Stellen, höchstens 2 je Notiz, zusammen höchstens 3 500 Token (geschätzt: Zeichen / 3).
- Stellen sind nummeriert 1..n; die Nummern speichert der Server mit der Frage (`sources`), der Worker kennt sie nur
  aus der Antwort auf `/passages`.

### 3. Einwilligung

Der Dienst bekommt in `STONEINTELLIGENCE_AI_SERVICES` optional ein viertes Feld `local` (`lokal|Ollama|1,2|local`).
- **Externer Dienst:** nur Stellen aus Notizen, deren Verfasser eingewilligt hat (`linking_ai_consents`, wirkt nur,
  solange die Person Mitglied ist) oder die eine KI geschrieben hat; dieselbe Regel wie in Stufe 3 der Verlinkung.
- **Lokaler Dienst:** alle lesbaren Stellen der erlaubten Levels, denn nichts verlässt den eigenen Server.
- Wurden gute Treffer wegen fehlender Einwilligung ausgelassen, speichert der Server die Zahl
  (`omitted_for_consent`), und die Oberfläche sagt es der fragenden Person. Es sind nur Notizen, die sie ohnehin
  lesen darf, deshalb verrät die Zahl nichts.
- Dieselbe Einwilligung deckt jetzt Verlinkung und „Frag den Vault“; Beschriftung in Webapp und Plugin sowie die
  Datenschutzerklärung nennen beides, und der Schalter steht auch direkt im Frage-Dialog. Sie bleibt je Mitglied und
  jederzeit widerrufbar. Ob ein Dienst extern oder lokal ist, legt der Betrieb fest: viertes Feld `local` in
  `STONEINTELLIGENCE_AI_SERVICES`; ohne die Angabe gilt ein Dienst als extern (sicherer Standard).

### 4. Antwort

- Der Prompt erlaubt nur die Stellen als Wissen, verbietet Wissen von außen und verlangt Nummern in eckigen Klammern
  hinter jeder Aussage. Antwort als JSON `{"beantwortbar": bool, "antwort": "…"}`.
- Der Worker prüft: nur Nummern, die es gab; mindestens ein Zitat; der Server prüft es noch einmal. Eine Antwort ohne
  gültiges Zitat ist unbrauchbar und wird nie gespeichert: Der Job versucht es erneut (3 Versuche), danach schlägt er mit
  einer Meldung fehl. Ohne Stellen wird das Modell gar nicht gefragt. Läuft keine Einbettung (kein Modell oder Fehler),
  sucht platform-api nur im Volltext.
- Reichen die Stellen nicht, lautet das Ergebnis „Die Notizen, die du lesen darfst, beantworten das nicht“ (kein Text
  des Modells). `answered = false`.
- Der Server speichert die Antwort und die **tatsächlich zitierten** Quellen (Notiz, Pfad, Überschrift, kurzer Auszug).
- Beim Lesen prüft der Server erneut, ob die fragende Person die zitierten Notizen noch lesen darf. Wenn nicht, bleibt
  die Antwort verborgen (`restricted`), damit Verlauf kein Weg an entzogene Inhalte ist.
- Die Webapp rendert Antworten über den bestehenden gesäuberten Markdown-Renderer; Zitate `[n]` werden zu Links.

### 5. Weitere Festlegungen

- Frage und Job: Der Job heißt neutral „Frage an den Vault“ und steht nicht in der gemeinsamen Jobliste (`GET …/ai/jobs`),
  die alle Mitglieder sehen; die Frage selbst steht nur in `vault_questions`. Andere können sie auch nicht abbrechen.
- Semantische Untergrenze: Kosinus 0,80 (`stoneintelligence.ask.min-similarity`), weil Fremdes mit e5-small bei 0,75 bis
  0,80 liegt (ADR 0012). Bei einem Modellwechsel mit den Verlinkungsschwellen neu messen.
- Standarddienst, wenn die Person keinen wählt: ein lokaler, sonst der erste eingerichtete.

### 6. Audit, Datenschutz, Grenzen

- Beim Abruf der Stellen schreibt der Server ein Audit-Ereignis `ai.ask`: wer gefragt hat, welcher Dienst, ob lokal,
  welche Notizen (Ids) gesendet wurden und wie viele wegen fehlender Einwilligung nicht. Kein Text, auch nicht die Frage.
- Fragen und Antworten sind privat (nur die fragende Person sieht sie), 90 Tage aufbewahrt (wie Jobs), vorher einzeln
  löschbar (`DELETE …/ask/{id}`).
- Grenzen: höchstens 3 offene Fragen je Person, Frage 5 bis 1 000 Zeichen. Dateien (PDF-Inhalte) sind nicht Teil der
  Suche (ADR 0014). Die Qualität hängt am Modell; belegt ist nur, was in den gesendeten Stellen steht.

### 7. Oberflächen (ADR 0011)

Webapp: eigener Eintrag „Frag den Vault“ in der Vault-Navigation mit Fragefeld, Antwort samt Quellenliste und Verlauf.
Plugin: Befehl „Frag den Vault“ öffnet eine Seitenansicht mit denselben Funktionen; ein Quellenklick öffnet die Notiz
im Vault. Beide nutzen dieselben Endpunkte (`ApiParityTest` bleibt ohne Ausnahme).
