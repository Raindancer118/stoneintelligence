# ADR 0018: Canvas gemeinsam bearbeiten

Status: Angenommen (2026-11-25)

## Kontext

Ein Obsidian-Canvas (`.canvas`, JSON Canvas: `{nodes, edges}`) wurde bisher wie jede andere Datei synchronisiert
(ADR 0009): als Version mit `If-Match`. Bearbeiteten zwei Personen gleichzeitig, blieb eine Fassung als
Konfliktkopie liegen, nichts wurde live gezeigt. Relay liefert ein gemeinsames Canvas aus (siehe
`docs/recherche/wettbewerb-2026-11.md`). Ziel: Karten verschieben, bearbeiten, anlegen und verbinden
erscheint live bei allen, gleichzeitige Änderungen verschmelzen, Offline-Änderungen ebenso, und die Datei bleibt
gültiges JSON Canvas für Leute ohne Plugin.

## Entscheidungen

1. **Yjs-Dokument je Canvas** (ADR 0002 gilt weiter: das Dokument ist die Wahrheit, die Datei eine Serialisierung).
   `nodes` und `edges` sind Maps nach Id, jeder Eintrag eine Map seiner Felder. Der Text einer Textkarte ist ein
   `Y.Text` (zeichenweise zusammengeführt), alle anderen Felder Einzelwerte (Letzter gewinnt je Feld). Unbekannte
   Schlüssel der obersten Ebene stehen in `meta`. Die Reihenfolge (= Ebene im Canvas) steckt je Eintrag in `_o`;
   neue Karten bekommen einen Wert zwischen ihren Nachbarn, erst eine echte Umordnung schreibt alle neu.
2. **Änderungen als Differenz, nicht als Ersatz.** Aus Datei oder Ansicht wird nur `vorher → nachher` ins Dokument
   übernommen (`applyCanvasChange`). So bleiben gleichzeitige Änderungen anderer an anderen Karten oder Feldern
   erhalten. Verschieben (`x`/`y`) und Textänderung derselben Karte verschmelzen. Löschen gewinnt gegen eine
   gleichzeitige Änderung derselben Karte. Kanten zu gelöschten Karten werden nicht ausgegeben (bleiben im Dokument).
   Erstkontakt ohne gemeinsame Basis vereinigt: nur fehlende Karten kommen hinzu, nichts wird überschrieben oder gelöscht.
3. **Eigene Art `CANVAS` in `platform.notes.kind`** (V28) statt Erkennung nur an der Endung. Ein Canvas läuft über
   denselben Raum, dieselben Updates und Snapshots wie eine Notiz, aber:
   - Listen, Änderungsfeed und Ankündigungen liefern ihn nur, wenn `kinds=…canvas` angefragt bzw. Datei-Ankündigungen
     abonniert sind. Ein älteres Plugin sähe sonst einen Eintrag `x.canvas` als Notiz und überschriebe das JSON mit Markdown.
   - Anlegen: `POST …/notes` mit `kind: "canvas"` (Pfad muss auf `.canvas` enden, Umbenennen bleibt `.canvas`).
   - Inhalts- und Versionsendpunkte lehnen Canvas mit 409 ab; die Suche indiziert sie nicht (V28 ersetzt den Trigger
     `queue_note_text`). Kartentexte zu indizieren wäre möglich, lohnt aber erst, wenn „Frag den Vault“ sie braucht.
   - `GET /api/v1/files/limits` meldet `canvas: true`. Das Plugin schaltet nur dann um; gegen einen älteren Server
     bleibt alles wie bisher (Canvas als Datei).
   - Die Webapp listet Canvas und verweist auf Obsidian, einen Editor gibt es dort nicht.
4. **Plugin: geöffnetes Canvas.** `CanvasLiveManager` bindet jedes geöffnete Canvas an sein Dokument (`CanvasLiveBinding`).
   Die Ansicht wird alle 700 ms über `view.getViewData()` gelesen (TextFileView, öffentliche API; enthält Ungespeichertes,
   Obsidian selbst speichert erst nach einigen Sekunden). Unterschiede gehen als Differenz zum letzten Stand ins Dokument.
   Fremde Änderungen werden nach 300 ms gebündelt als zusammengeführte JSON-Datei per `vault.modify` geschrieben; Obsidian lädt
   eine geänderte, offene Datei neu. Vorher wird die Ansicht eingelesen, damit Ungespeichertes nicht überschrieben wird.
   Eine Ansicht, die noch nicht geladen ist (`view.data` leer), zählt nie als „alles gelöscht“.
5. **Kein Eingriff in Obsidians Canvas-Interna** (`canvas.setData`, `nodes`-Map u. Ä. sind undokumentiert und ändern sich
   zwischen Versionen). Die Live-Wirkung läuft ausschließlich über Datei und öffentliche TextFileView-API. Zeigt eine
   Ansicht den zurückgeschriebenen Stand nicht, gilt ihr alter Inhalt nicht als Löschung der fremden Änderungen
   (Basis wird über den geringeren Abstand gewählt); es geht nichts verloren, der Stand erscheint beim nächsten Öffnen.
6. **Nicht geöffnete Canvas** gleicht der Hintergrundabgleich aus (`syncCanvasContent`, gleicher Ablauf wie
   `syncNoteContent`): lokaler Stand gegen gespeicherten Yjs-Zustand → Differenz → Server-Catchup → Ergebnis in die Datei.
   Offline-Änderungen beider Seiten verschmelzen beim Verbinden.
7. **Umstellung bestehender Canvas.** Ein von einem älteren Plugin als Datei hochgeladenes Canvas wandelt das erste neue Plugin
   um: lokal sichern, den Dateieintrag auf dem Server entfernen (ADR 0009 räumt die Bytes nach 24 h), unter demselben Pfad als
   Canvas anlegen und den lokalen Inhalt als Erstkontakt einspielen. Andere Geräte behalten ihre Datei und gleichen sie
   gegen den neuen Canvas ab (Vereinigung). Dateiversionen älterer Fassungen gehen dabei verloren (ADR 0009 Punkt 6 hielt sie ohnehin nicht).
8. **Ohne Plugin** liegt die Datei als gültiges JSON Canvas im Vault; das Plugin schreibt sie im Format, das Obsidian selbst
   verwendet. Zugriffsrechte, Audit, Löschen, Umbenennen und Freigaben laufen wie bei Notizen. Nur-Lesen-Canvas bleiben im
   Hintergrundabgleich (Änderungen landen als Kopie daneben), sie werden nicht live gebunden.

## Grenzen

- Die Live-Anzeige hängt daran, dass Obsidian eine per `vault.modify` geänderte, geöffnete `.canvas`-Datei neu lädt. Das ist
  nicht gegen eine laufende Obsidian-Version geprüft (nur gegen einen Nachbau von Server und Verbindung); fehlt das Neuladen,
  erscheinen fremde Änderungen erst beim erneuten Öffnen. Ein Neuladen ersetzt die Ansicht, ein gerade laufendes Ziehen
  einer Karte kann dabei abbrechen.
- Zwischen Einlesen der Ansicht und Neuladen liegen Millisekunden, in denen eine Eingabe verloren gehen kann.
- Löschen gewinnt gegen gleichzeitiges Bearbeiten derselben Karte; zwei gleichzeitige Änderungen desselben Feldes (z. B. beide
  verschieben dieselbe Karte) ergeben eine davon.
- Keine Cursor oder Auswahl anderer im Canvas, keine Versionen, keine Suche in Kartentexten, keine Anzeige im Browser.
