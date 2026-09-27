# StoneIntelligence – Wissensarbeitsplatz

Die Oberfläche verwendet eine dunkle, einklappbare Navigationsleiste, warme Papierflächen und einen kupferfarbenen Akzent. Public Sans bleibt lokal eingebunden; redaktionelle Überschriften in Georgia geben langen Notizen Raum. Keine zusätzlichen Laufzeitabhängigkeiten.

Die Hauptnavigation enthält Notizen, In Obsidian, KI-Wissen und (mit MANAGE) Mitglieder & Rechte. Verwaltung ist in Mitglieder, Rollen, Gruppen, Protokoll und Vault-Einstellungen gegliedert. Auf schmalen Geräten öffnet die Navigation über einen eigenen Schalter; im Notizbereich wechselt die Ansicht zwischen Ordnerbaum und Dokument.

Strg/⌘+K öffnet den Schnellsprung. Dieser verwendet ausschließlich bereits geladene Metadaten. Ordner sind verschachtelt und einzeln einklappbar. Weitere Notizseiten werden ausdrücklich nachgeladen. Bestehende Yjs-, OIDC-, Freigabe- und History-Module werden wiederverwendet.

Rechtliche Links bleiben außerhalb der wechselnden Ansichten erreichbar. Dialoge halten den Tastaturfokus und geben ihn beim Schließen zurück. Bedienelemente besitzen sichtbare Fokusmarkierungen und mindestens 48 Pixel große Touchflächen. Animationen werden bei reduzierter Bewegung abgeschaltet.

Validierung: Vitest für Logik und Komponenten; Playwright für Browserabläufe, mobile Ansichten, Tastaturbedienung und reproduzierbare Screenshots mit API-Mocks. Screenshots liegen in review-screenshots/.
