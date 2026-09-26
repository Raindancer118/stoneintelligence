# ADR 0012: Nächtliche Verlinkung (Embeddings + KI-Prüfung)

Status: Angenommen und umgesetzt (2026-09-26, Releases 0.28.0 bis 0.30.0)

## Kontext

Anforderungen.md: Begriffe sollen vorwärts und rückwärts automatisch verknüpft werden, auf
semantischer Basis statt nur bei gleichem Wortlaut. Ein stärkeres Modell entscheidet, ob eine
Verknüpfung im Kontext Sinn ergibt. Beziehungen sollen typisiert sein, das ganze Vault über
Embeddings indiziert.

Tom (26.09.2026): Jede Nacht um **02:00** sucht ein Lauf alle Stellen und Notizen, die verlinkt
werden könnten. Die Kandidaten gehen mit etwas Kontext an die KI, die „sinnvoll" oder „Müll"
entscheidet. Weitere Entscheidungen:

- Sinnvolle Links schreibt die KI **direkt** als normales KI-Change-Set. Sie lassen sich über
  „KI-Änderungen" rückgängig machen.
- Links kommen **in den Text, wo es passt** (`[[Ziel|Wort]]`). Gibt es keine passende Stelle,
  landen sie in einer Liste `## Verwandt` am Ende der Notiz.

Warum nicht nur semantische Suche: Ähnlichkeit findet Kandidaten gut, entscheidet aber schlecht.
Fast gleiche Notizen (Controlling I/II, Protokolle desselben Projekts, Übersichtsseiten) sehen sich
immer ähnlich, ein fester Schwellwert lässt entweder Müll durch oder verliert gute Links.
Beziehungstyp und Textstelle liefert sie gar nicht.

## Entscheidungen

### 1. Drei Stufen, die KI sieht nur die Grauzone

1. **Regeln ohne KI:** Kommt Titel oder Alias einer Notiz wörtlich (Wortgrenzen, ohne Code,
   Frontmatter, Überschriften und vorhandene Links) in einer anderen vor, wird verlinkt. Das
   geschieht nur bei der ersten Erwähnung je Notiz. Das ist sicher und kostet nichts.
2. **Embeddings:** Kandidatenpaare kommen über Abschnitts-Ähnlichkeit (pgvector) zustande.
   Beinahe-Duplikate und klar Unverwandtes fallen automatisch weg. Schwellwerte sind
   konfigurierbar und werden vor dem Einschalten mit `TrialRun` an einem echten Vault eingestellt.
3. **KI (SMART-Stufe):** Paare aus der Grauzone gehen gebündelt an die KI, je Paar mit den beiden
   passenden Abschnitten. Antwort je Richtung: nützlich ja/nein, Beziehungstyp (`uses`,
   `requires`, `part_of`, `related_to`, `described_by`, …) und die Ankerstelle als **wörtliches**
   Zitat aus dem Text. Steht das Zitat nicht exakt so in der Notiz, kommt der Link unter
   `## Verwandt`. Die KI erfindet nichts dazu.

Vorwärts und rückwärts werden getrennt bewertet: A → B kann sinnvoll sein, B → A nicht. Die
Rückrichtung zeigt Obsidian ohnehin als Backlink.

### 2. Schreiben ohne Inhalt zu verlieren

Ein Inline-Link sind zwei **Einfügungen** um das vorhandene Wort (`[[Ziel|` davor, `]]` danach),
als Yjs-Update über `AiWriteService`. Es wird nie Text ersetzt oder gelöscht, gleichzeitiges
Tippen bleibt unberührt. Rückgängig entfernt genau diese Einfügungen, mit Konfliktprüfung wie
bisher.

- **Keine Obergrenze als Standard.** Eine Höchstzahl neuer Links je Notiz und Nacht lässt sich je
  Vault einschalten und einstellen (Standard aus).
- Die typisierte Beziehung wird zusätzlich in `platform.note_relations` gespeichert, als
  Grundlage für den späteren Knowledge Graph.
- Abgelehnte Paare merkt sich `platform.link_decisions` (mit Inhalts-Hash beider Abschnitte). Die
  KI wird erst wieder gefragt, wenn sich einer der beiden Abschnitte ändert.

**Notizen von Menschen:** Links werden **standardmäßig auch in Notizen von Menschen**
geschrieben, das will Tom ausdrücklich. Die Vault-Einstellung „KI darf Menschen-Notizen
verlinken" (Standard an) schaltet das ab, dann wird nur in KI-Notizen geschrieben und nur noch
*auf* Menschen-Notizen verlinkt. `AiWriteService` bekommt dafür eine eng gefasste Ausnahme: In
Menschen-Notizen sind nur reine Link-Einfügungen aus einem Verlinkungs-Job erlaubt, nie andere
Änderungen. Das deckt auch die Anforderung ab, dass sich einstellen lässt, ob die KI Dateien von
Menschen bearbeiten darf.

### 2a. Zwei Modi: KI-geprüft oder nur semantisch

Je Vault einstellbar:

- **KI-geprüft** (Standard): alle drei Stufen wie oben.
- **Nur semantisch**: ausschließlich Stufen 1 und 2, **kein externer KI-Anbieter**. Damit dabei
  auch die Embeddings nicht nach außen gehen, rechnet der Worker sie mit einem **lokalen
  Embedding-Modell** (ONNX Runtime, mehrsprachiges Modell wie `multilingual-e5-small`, im
  Worker-Image). Die Ankerstelle ist dann der Abschnitt, der Titel oder Alias des Ziels am
  nächsten kommt; ohne passende Stelle `## Verwandt`. Der Beziehungstyp ist immer `related_to`.
  Die Schwellwerte sind in diesem Modus strenger, weil keine KI mehr aussiebt.

Die Embedding-Quelle ist unabhängig davon wählbar: lokal (Standard, kostet kein Kontingent) oder
ein Anbieter über ai-gateway.

### 3. Ablauf und Betrieb

- **Zeitplan:** platform-api legt täglich um 02:00 Europe/Berlin je Vault mit eingeschalteter
  Verlinkung einen Job `kind = LINKING` in die bestehende `ai_jobs`-Queue. Fortschritt, Warten
  auf Kontingent, Abbrechen und Rückgängig laufen damit wie beim Einlesen. Läuft der Job der
  Vornacht noch, entsteht kein zweiter.
- **Inkrementell:** Nachts werden nur Notizen verglichen, die seit dem letzten Lauf neu sind
  oder sich geändert haben, dann aber gegen das ganze Vault. Sonntags läuft ein voller Abgleich.
- **Embeddings:** Der Worker (weiterhin ohne DB-Zugriff, ADR 0008) holt geänderte Notizen über
  `/internal/ai`, teilt sie in Abschnitte und schickt die Vektoren an platform-api. Tabelle
  `platform.note_embeddings` (Note, Abschnitt, Inhalts-Hash, Modell, `vector`). Das Postgres-Image
  auf dorn ist bereits `pgvector/pgvector:pg17`, V15 legt die Extension und die Tabelle an.
  Ein Modellwechsel indiziert neu.
- **Lokale Embeddings** im Worker (ONNX Runtime + Tokenizer, Modell im Image, kein Netz). Das ist
  der Standard und Pflicht für den Modus „nur semantisch".
- **ai-gateway 0.5.0:** bekommt Embeddings (`AiGateway.embed`), für OpenAI-kompatible Anbieter
  und Gemini, mit derselben Kontingent-Logik wie Chat, als wählbare Alternative. Veröffentlicht
  auf packages.tstieh.de.
- **Rechte und Levels:** Der Lauf handelt mit den Rechten der Person, die ihn eingeschaltet hat
  (wie `requestedBy`), je Eintrag nach ADR 0011. Er liest nur Notizen in den Levels, die der
  KI-Dienst darf, nie 100/101.

### 4. Datenschutz

Im Modus „KI-geprüft" gehen nachts Notizinhalte an externe KI-Anbieter. Im Modus „nur semantisch"
mit lokalen Embeddings verlässt nichts den Server. Trotzdem:

- Die Verlinkung ist **je Vault ausdrücklich einzuschalten** (Standard aus).
- Die Datenschutzerklärung (`PrivacyPolicy.svelte`) nennt diesen Zweck und die Anbieter,
  bevor der Lauf live geht.

### 5. Steuerung aus Obsidian (passt zu ADR 0011)

- Vault-Verwaltung → Reiter *KI*: Verlinkung ein/aus, Modus (KI-geprüft / nur semantisch),
  Embedding-Quelle, „KI darf Menschen-Notizen verlinken", optionale Höchstzahl je Notiz,
  letzter Lauf mit Ergebnis, „Jetzt verlinken".
- Morgens eine Obsidian-Notice „Heute Nacht 12 Links gesetzt". Sie führt ins KI-Protokoll, dort
  lässt sich einzeln oder alles rückgängig machen (vorhandenes `AiChangesModal`).
- Kontextmenü: „Links für diese Notiz jetzt suchen".
- **Semantische Suche** fällt mit dem Index ab: Befehl „Ähnliche Notizen" und Suche nach
  Bedeutung, nur über Notizen, die man lesen darf.
- Die Webapp bekommt dieselben Schalter.

## Umsetzung in Schritten (test-first)

| Version | Inhalt |
|---|---|
| ai-gateway 0.5.0 | Embeddings-API + Kontingent (Alternative zu lokal), Tests gegen aufgezeichnete Antworten |
| 0.28.0 | V15 pgvector, lokale Embeddings im Worker, Embedding-Index inkrementell, Stufe 1 (wörtliche Erwähnungen), Change-Set mit Inline-Einfügungen + Rückgängig |
| 0.29.0 | Stufen 2 + 3, `link_decisions`, `note_relations`, Modi, Einstellungen (Menschen-Notizen, Höchstzahl), TrialRun-Kalibrierung an einem echten Vault |
| 0.30.0 | 02:00-Zeitplan, Schalter und Notice in Obsidian und Webapp, „Ähnliche Notizen", Datenschutzerklärung, Rollout |

Prüfungen: Einfügungen verlieren bei gleichzeitigem Tippen nichts (Yjs-Test mit zwei Clients).
Rückgängig nach Weiterbearbeiten lässt die Bearbeitung stehen. Kein Link in Code, Frontmatter
oder vorhandene Links. Level-100/101-Notizen tauchen in keinem Embedding auf. Ein abgelehntes
Paar wird ohne Änderung nicht erneut gefragt.

## Nachtrag: Reihenfolge der Umsetzung (27.09.2026)

Als erster, vollständig nutzbarer Schritt kam **0.28.0**. Es nutzt nur Stufe 1 (wörtliche Titel und
Aliase), das braucht weder Embeddings noch eine externe KI. Enthalten sind der sichere Schreib- und
Rückgängig-Weg, die Einstellungen je Vault, „Jetzt verlinken", der 02:00-Lauf und die Oberflächen in
Obsidian und im Web.

- Links setzt der Server auf dem Text, der beim Schreiben gilt (`AiWriteService.linkNote`). Bei einem
  Schreibkonflikt wird neu gerechnet, sodass nichts überschrieben wird. Gespeichert wird nur das
  eingefügte Markup (`ai_changes.details`, Art `LINKED`), nicht der Notiztext.
- Rückgängig nimmt genau dieses Markup heraus, auch wenn inzwischen weitergeschrieben wurde.
- Die Regeln, wo ein Link stehen darf, stehen in `domain-core` (`LinkText`), damit Worker und Server
  dieselben Regeln nutzen. Nie verlinkt wird in Frontmatter, Code, Überschriften, vorhandenen Links,
  Formeln, Kommentaren, URLs und Tags.
- Der Worker findet Nennungen mit `MentionFinder`, einmal Wort für Wort durch jeden Text statt
  quadratisch. Namen unter 3 Zeichen oder ohne Buchstaben zählen nicht.
- Modus (KI-geprüft oder nur semantisch) und Embedding-Quelle stehen schon in `vault_linking`. Sie
  wirken erst mit den Stufen 2 und 3 und sind bis dahin in keiner Oberfläche zu sehen.

Es folgen: Embeddings (lokal, pgvector) mit Stufe 2 und „Ähnliche Notizen", danach Stufe 3 (KI-Prüfung,
Beziehungstypen, `link_decisions`) samt Datenschutzhinweis.

## Nachtrag: Stufe 2 und „Ähnliche Notizen" (27.09.2026, 0.29.0)

- **Lokale Embeddings:** Modell `multilingual-e5-small` (Xenova-ONNX, quantisiert, gepinnt auf `761b726`,
  per SHA-256 geprüft) im Worker-Image. Die Texte werden mit ONNX Runtime und den HF-Tokenizern (DJL)
  verarbeitet und verlassen den Server nicht. Jeder Abschnitt einer Notiz trägt ihren Titel vorn
  (`NoteChunker`).
- **Speicher:** `platform.note_embeddings` (pgvector, HNSW, Kosinus) mit Modell und Quelltext-Hash. Neu
  berechnet wird nur Geändertes. Die ITs laufen jetzt auf `pgvector/pgvector:pg17`, wie im Betrieb.
- **Modi:** `LITERAL` (neuer Standard, nur Stufe 1) und `SEMANTIC` (Stufe 1 und 2). `AI` wird
  abgelehnt, bis Stufe 3 existiert.
- **Schwelle für Stufe 2:** 0,86, Beinahe-Duplikate ab 0,97 werden nicht verlinkt. Gemessen an
  Beispielnotizen lag Ähnliches bei 0,83 bis 0,90 und Fremdes bei 0,75 bis 0,80.
  `LinkingCalibrationTest` hält die Schwelle am echten Modell fest.
- **Einmal verlinkt, nie wieder** (`platform.link_pairs`): Entfernt jemand einen Link von Hand oder
  macht einen Lauf rückgängig, setzt die nächste Nacht ihn nicht erneut. Deshalb kann jeder Lauf alle
  Notizen prüfen; einen gesonderten vollen Abgleich am Sonntag braucht es nicht.
- **„Ähnliche Notizen"** (`GET …/notes/{id}/similar`): in Obsidian über das Datei-Kontextmenü und einen
  Befehl, im Web in der Notizansicht. Es zeigt nur Notizen, die man lesen darf.

## Nachtrag: Stufe 3 und Einwilligung (27.09.2026, 0.30.0)

- **Modus `AI`:** Kandidaten ab 0,82 (`STONEAI_LINK_AI_SIMILARITY`) bis unter die Duplikatschwelle prüft
  `LinkJudge`, ein SMART-Aufruf je Quellnotiz mit höchstens 8 Kandidaten. Die Antwort wird streng
  geprüft: Ein Anker zählt nur, wenn er wörtlich in der Quelle steht, sonst kommt der Link unter
  `## Verwandt`. Unbekannte Beziehungen werden zu `related_to`. Ist die Antwort unlesbar, gilt das als
  keine Entscheidung. Ist kein Kontingent frei, wartet der Lauf mit dem bestehenden Mechanismus, bereits
  getroffene Entscheidungen bleiben erhalten.
- **Gemerkt wird** in `platform.link_rejections` (Ablehnung mit den Text-Hashes beider Notizen; neu gefragt
  wird erst nach einer Änderung) und in `platform.note_relations` (Beziehungstyp je gesetztem Link).
  Bereits verlinkte Paare kommen gar nicht erst als Kandidaten.
- **Abweichung vom ursprünglichen Plan: Einwilligung je Mitglied.** Die Übermittlung an Anbieter in den USA
  beruht auf Einwilligung (Art. 49 Abs. 1 lit. a DSGVO), und Verwaltende können sie nicht für andere
  geben. Deshalb gehen im Modus `AI` nur Notizen, deren Verfasser eingewilligt hat, und Notizen der KI
  selbst an den Anbieter, als Quelle wie als Ziel. Gespeichert wird die Einwilligung in
  `platform.linking_ai_consents`; sie ist jederzeit widerrufbar und wirkt nur, solange die Person
  Mitglied ist. Notizen ohne Einwilligung werden weiterhin lokal verlinkt (Stufen 1 und 2).
- **Datenschutzerklärung angepasst.** Das umfasst die Verlinkung auch in Notizen von Menschen, die lokale
  Berechnung, die Übermittlung nur mit Einwilligung und die Speicherdauern. Dabei wurde auch der seit
  0.28.0 falsche Satz „Die KI verändert keine von Menschen angelegten Notizen" korrigiert.
- **Betriebsproben:** `EmbedderCheck` (lokales Modell) und `LinkJudgeCheck <dienst>` (echtes Sprachmodell,
  erfundenes Beispiel ohne Vault-Inhalte).
