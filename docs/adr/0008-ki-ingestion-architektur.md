# ADR 0008: KI-Ingestion (Meilenstein 1) — Architektur

Status: Angenommen (2026-09-23)

## Kontext

Erster Meilenstein des Intelligence-Service (Anforderungen.md, Plan.md 4.2/Phase 5): Dokumente in
der Webapp hochladen (auch mehrere), eine KI legt daraus atomare, verlinkte Wissensnotizen mit
Frontmatter und Quellenangabe im gemeinsamen Vault an, jede KI-Änderung lässt sich mit wenigen
Klicks rückgängig machen. Entscheidungen von Tom (23.09.2026): beliebiges Modell anschließbar,
Standard ist `ai-gateway` (Gemini + Groq); KI bearbeitet standardmäßig keine von Menschen
erstellten Notizen (pro Vault umschaltbar).

## Entscheidungen

1. **Pipeline aus `stoneai-core` wiederverwenden**, nicht neu schreiben: Schutzprüfung, Laden
   (PDF/Text, OCR für Scans), Chunking, Extraktion, Konsolidierung, Managed Blocks, Frontmatter.
   `stoneai-core` wird dafür so erweitert, dass das Schreiben von Notizen über eine
   Text-Schnittstelle („bestehender Text rein → neuer Text raus") statt nur über lokale Dateien
   geht, und als Bibliothek auf packages.tstieh.de veröffentlicht. Die CLI bleibt unverändert.
2. **Modell austauschbar über einen Port** (`LlmClient` aus `stoneai-core`): Standard-Adapter
   `ai-gateway`; zusätzlich ein OpenAI-kompatibler Adapter (Ollama, vLLM, OpenRouter …). Welcher
   Anbieter/welches Modell, konfiguriert der Betrieb; Schlüssel nie im Klartext im Repo.
3. **Yjs bleibt Source of Truth (ADR 0002).** Weil es für Java keine ausgereifte Yjs-Bibliothek
   gibt, läuft die echte Yjs-Bibliothek eingebettet über **GraalJS** in `platform-api`
   (`YjsBridge`). Das Bundle wird aus TypeScript gebaut, das dieselbe Diff-Logik wie das Plugin
   importiert (`plugin/src/sync/textDiff.ts`) — keine zweite CRDT- oder Diff-Implementierung.
   Spike 23.09.: Java-erzeugte Updates dekodieren in Node-Yjs identisch; einmalig ~2 s Laden,
   Operationen im Millisekundenbereich.
4. **Schreiben nur über `platform-api`** (kein direkter DB-Zugriff des Workers auf fremde
   Schemas): interne Endpunkte unter `/internal/**`, geschützt durch ein Service-Token
   (konstantzeitiger Vergleich), nur für den Worker. Dieselben Wege wie Menschen: Notiz anlegen,
   Text als Yjs-Update anhängen, Live-Broadcast an offene Editoren, Vault-Ankündigungen, Audit.
   Der Akteur ist eine **KI-Identität** (`ki:<name>`), in Audit und Frontmatter sichtbar.
5. **Uploads** über `platform-api` (Berechtigung CREATE, Typ- und Größenlimits), gespeichert in
   Postgres, verarbeitet über die vorhandene persistierte Job-Queue (`worker.jobs`,
   `FOR UPDATE SKIP LOCKED`). Fortschritt/Status per Job abrufbar.
6. **Change-Sets statt Git-Revert (Plan 4.2):** Jede Verarbeitung schreibt ein Change-Set mit
   den exakten Operationen je Notiz (angelegt, Text vorher/nachher). Rückgängig machen ist eine
   konfliktgeprüfte Kompensation: nur wo der aktuelle Text noch dem KI-Stand entspricht, wird
   zurückgesetzt bzw. die angelegte Notiz gelöscht; wo inzwischen ein Mensch weitergeschrieben
   hat, wird nichts überschrieben und das im Ergebnis gemeldet.
7. **Menschliche Notizen** (nicht von einer KI-Identität angelegt) werden standardmäßig nie
   verändert; die KI verlinkt nur darauf.
8. **Quelldokumente im Vault:** Obsidian-Sync überträgt bisher nur Markdown. Meilenstein 1 legt
   je Dokument eine Markdown-Quellnotiz an (Metadaten, Verweis auf die erzeugten Notizen,
   Download über das Dashboard); Binäranhänge im Vault sind ein eigener späterer Schritt.

## Nicht Teil von Meilenstein 1

Embeddings/semantische Verlinkung, Wissensgraph mit typisierten Kanten, SELMA, MCP, Internet-
Quellen (standardmäßig aus) — Meilensteine 2 und 3.
