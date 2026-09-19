# Dashboard/API-Prüfung vom 19.09.2026

## Korrigiert

- Gruppenliste: Rollen-Zuordnungen werden pro Vault gesammelt geladen. Im PostgreSQL-Test
  mit 32 Gruppen sinkt die Anzahl der SQL-Abfragen von 35 auf 4, einschließlich
  Berechtigungsprüfung. Gruppen ohne Rollen bleiben enthalten, fremde Vaults ausgeschlossen.
- Dashboard: Nach Rollen-, Gruppen-, Mitgliedschafts- und Pfadregeländerungen wird nur die
  betroffene Liste nachgeladen. Damit entfallen zwei der bisher drei GET-Anfragen je Aktion.
  Der initiale Abruf bleibt parallel.
- Leere erfolgreiche HTTP-Antworten ohne `Content-Length` verursachen keinen JSON-Parserfehler
  mehr. Fehlerstatus und tatsächlich fehlerhaftes JSON bleiben Fehler.
- Abgelaufene gespeicherte Anmeldungen führen zurück zur Login-Ansicht statt in ein
  funktionsloses Dashboard mit fehlgeschlagenem Vault-Abruf.
- Mitglieder- und Rollenänderungen prüfen zusätzlich, ob die adressierte Gruppe tatsächlich
  zum Vault aus der URL gehört. Vorher konnte ein Verwalter seines eigenen Vaults eine
  fremde Gruppen-ID verwenden. Die Prüfung nutzt eine gezielte, indizierte ID-Abfrage.

## Verifikation

- Regressionstests zuerst rot: 35 statt höchstens 4 SQL-Abfragen; unnötige Listenabrufe;
  leere HTTP-200-Antwort; abgelaufene Anmeldung; vier fremde Gruppenmutationen.
- `./mvnw -B -ntp verify`: alle Module, einschließlich PostgreSQL-Testcontainers,
  Anwendungsstart, HTTP/WebSocket-End-to-End und Vault-Sync-Simulation.
- Webapp: 14 Tests einschließlich Svelte-Komponentenstart und Formularinteraktion,
  `npm run check`, `npm run build`.
- Plugin unverändert: 129 Tests und `npm run build`.

## Grenzen

Die SQL- und HTTP-Anfragezahlen sind lokal verifiziert; daraus wird keine konkrete
Verbesserung der produktiven Ladezeit in Millisekunden abgeleitet. Kein Produktionsdeployment
und kein vollständiges Sicherheits- oder Performance-Audit. Das Webapp-JavaScript bleibt
bei rund 36 KB gzip; hier war keine große Bundle-Optimierung erforderlich.

Der Plugin-Start wartet weiterhin auf den sequenziellen Download fehlender Servernotizen,
bevor `syncOpenEditorBindings` aufgerufen wird. Ebenso lädt die Vault-Liste serverseitig
Vaults noch einzeln. Diese Pfade sind mögliche weitere Optimierungskandidaten, in diesem
Durchlauf aber weder unter realer Vault-Last vermessen noch verändert.
