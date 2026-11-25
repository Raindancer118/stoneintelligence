# ADR 0016: Kommentare und Erwähnungen

Status: Angenommen (2026-11-25)

## Kontext

Kommentare mit @Erwähnung sind der häufigste Grund, warum Teams Notion statt Obsidian wählen, und die größte
Lücke gegenüber Relay (Marktrecherche vom 25.11.). Der Server kennt den Notiztext nicht (Yjs), die Rechte
stehen im `VaultAccessGuard` (ADR 0011).

## Entscheidungen

### Modell

- **Thread je Notiz**, darin Kommentare in Reihenfolge. Ein Thread ist entweder an eine Textstelle gebunden
  oder gehört zur ganzen Notiz. Antworten, eigene Kommentare bearbeiten und löschen, Thread lösen und wieder
  öffnen. Löschen wir den letzten Kommentar, verschwindet der Thread.
- **Textstelle als Zitat, nicht als Position:** gespeichert werden das markierte Zitat (höchstens 1000 Zeichen)
  und je bis zu 200 Zeichen Umgebung davor und danach. Der Client sucht das Zitat im aktuellen Text
  (`commentAnchor.ts`, in Plugin und Webapp identisch): erst Zitat samt Umgebung, dann das Zitat allein, bei
  mehreren Treffern der mit der besten Umgebung. Ohne Treffer zeigt die Oberfläche „Stelle geändert", der
  Kommentar bleibt erhalten. Es gibt keine Zeichenoffsets, die beim Tippen davor verrutschen. Der Server
  sucht nicht selbst, er speichert nur.
- **Nur Text.** Kommentare sind Klartext, höchstens 4000 Zeichen; die Oberflächen setzen sie nie als HTML.
- Tabellen `comment_threads`, `comments`, `comment_notifications` (**V26**; V25 gehört einer anderen
  Änderung). Alles hängt per `ON DELETE CASCADE` an Notiz und Vault. Kommentare auf Dateien gibt es nicht,
  ebenso nicht auf verschlüsselten Notizen (Level 101): das gespeicherte Zitat wäre Klartext auf dem Server.
  Wird eine Notiz erst nach Kommentaren verschlüsselt, bleiben die alten Zitate stehen (Grenze, bewusst offen).

### Rechte

- **Kommentare sehen darf, wer die Notiz lesen darf (`READ` an ihrem Pfad), und kommentieren darf ebenfalls
  jede Person mit `READ`.** Lesende können also kommentieren: Nachfragen und Anmerkungen brauchen kein
  Schreibrecht, und gerade Gäste und Prüfer sollen sich äußern können, ohne den Text ändern zu dürfen.
- Bearbeiten darf nur die Autorin oder der Autor. Löschen darf sie oder er sowie, wer an der Notiz `MANAGE`
  hat (Moderation). Lösen und Wiederöffnen darf jede Person mit `READ`.
- Jede Operation prüft zuerst Mitgliedschaft und `READ` auf die Notiz (auch bei Zugriffen über Kommentar- oder
  Thread-Id; Ids anderer Vaults sind „nicht gefunden").
- **Kein Leck:** Kommentare stehen in eigenen Tabellen und tauchen weder in der Volltextsuche (`note_texts`),
  dem Änderungs-Feed noch in Listen auf. Das Protokoll enthält nur Aktion und Ids (`threadId`, `commentId`),
  nie den Text, und läuft durch dieselbe Sichtbarkeitsprüfung wie die übrigen Notiz-Ereignisse. Hinweise
  speichern nur Verweise; Text, Titel und Pfad löst der Server beim Lesen auf und lässt Hinweise auf Notizen
  weg, die die Person nicht mehr lesen darf (Freigabe entzogen, Mitgliedschaft beendet).
- Namen kommen aus `PeopleDirectory`; fehlt einer (Konto vergessen, DSGVO-Frist), steht „Unbekannt". Der
  Kommentar bleibt, wie auch Protokoll-Einträge.

### Erwähnungen

- Im Text steht `@[Name](subject)`. Der Server leitet die Erwähnten aus dem Text ab, statt einer zweiten
  Liste zu glauben. Die Oberflächen zeigen sie als gestaltetes Element (nie `{@html}`).
- Vorschlagsliste aus `GET /vaults/{v}/members` (jedes Mitglied darf sie lesen; Anzeigenamen). Erwähnen lässt
  sich nur, wer Mitglied ist **und** die Notiz lesen darf; sonst lehnt der Server den ganzen Kommentar mit 422 und
  einer verständlichen Meldung ab („Ben darf diese Notiz nicht lesen und lässt sich deshalb nicht erwähnen."),
  gespeichert wird nichts. Beim Bearbeiten zählen nur neu hinzugekommene Erwähnungen.
- Wer erwähnt wird, bekommt einen **Hinweis in der App** (ungelesen-Liste, `GET /api/v1/notifications`, über alle
  Vaults der Person, höchstens 100) und, **nur wenn SMTP konfiguriert ist** (`Mailer.isConfigured()`), eine
  Mail mit Auszug. Ohne SMTP entfällt die Mail ganz (kein Protokoll-Rauschen mit Mailtext), der Hinweis bleibt.
  Die Adresse kommt über `UserDirectory.findByUsername(subject)` aus Authentik, wie bei Einladungen; ohne
  Verzeichnis oder Adresse gibt es keine Mail. Mails gehen erst nach dem Commit raus, ein Fehlschlag wird nur
  geloggt und macht den Kommentar nicht ungültig. Sich selbst erwähnen löst nichts aus; jede Person bekommt
  je Kommentar einen Hinweis. Wird ein Kommentar gelöscht, fallen seine Hinweise mit weg.

### Live

Neue Kommentare erscheinen bei anderen mit offener Notiz **per Abfrage alle 20 Sekunden** (und sofort beim
Öffnen sowie nach jeder eigenen Aktion), nicht über die Sync-Verbindung. Gründe: Die Webapp hat keine
Sync-Verbindung, Vault-Ankündigungen zielen auf Bestand und Pfad (Frame-Typen, Cluster-Nachricht, Opt-in je
Plugin-Version) und eine Ankündigung müsste je Empfänger auf `READ` gefiltert werden. Für einen Kommentar-Takt
ist das mehr Umbau als Nutzen; eine Abfrage kostet eine kleine, indizierte Anfrage je offener Notiz und
pausiert bei verdecktem Tab. Der Hinweis-Zähler in der Kopfzeile fragt im selben Takt (60 s). Bei Bedarf
lässt sich später ein Frame „Kommentare geändert" ergänzen, ohne die API zu ändern.

### Protokoll

`comment.created`, `comment.edited`, `comment.deleted`, `comment.resolved`, `comment.reopened` mit
`noteId`, `threadId`, `commentId`. Der Verlauf je Notiz zeigt sie als Sätze (`historyText.ts`).

### Oberflächen (ADR 0011, Parität)

- **Webapp:** Schaltfläche „Kommentare" mit Zahl offener Threads in der Notizleiste, Panel mit Threads,
  Antworten, Bearbeiten, Löschen, Lösen, Erwähnen mit Vorschlägen; im Lesemodus Text markieren, dann
  „Kommentieren". Glocke mit Zähler in der Kopfzeile.
- **Plugin:** Ansicht „Kommentare" (Seitenleiste) für die aktive Notiz, Befehl „Kommentar zur Auswahl"
  (Editor-Auswahl), Hinweise als Obsidian-Notice bei neuen Erwähnungen (Abfrage alle 60 s), Befehl „Hinweise
  anzeigen". `/api/v1/notifications` und alle Kommentar-Endpunkte werden im Plugin genutzt, ApiParityTest
  bleibt ohne Ausnahme.

## Nicht Teil dieser Entscheidung

Emoji-Reaktionen, Datei-Anhänge in Kommentaren, Erwähnen von Gruppen (`@team`), Markierungen im Editor-Text
selbst (Hervorhebung der Stelle), Zusammenfassungs-Mails statt Einzelmails, Abmeldung von Hinweismails je Person.
