# StoneIntelligence webapp — Design

Stand 27.09.2026. Entstanden aus einer von drei Varianten (Codex-Lauf „A", Feature-Brief ohne
Design-Vorgaben); Tom hat Look und Layout von A gewählt und den Akzent auf Schiefer (Slate)
umstellen lassen, passend zum Logo (facettierter Schieferstein).

## Farbe

Warme Papierflächen, dunkle Schiefer-Navigation, ein Akzent: Schiefer-Blaugrau. Kupfer nur für
Fehler/Warnungen.

| Token | Wert | Verwendung |
|---|---|---|
| `--bg` | `#f5f3ed` | Seitenhintergrund |
| `--surface` / `--surface-raised` | `#faf9f5` / `#fffefa` | Panels, Dokument, Eingaben |
| `--line` | `#deded3` | Trennlinien |
| `--ink` / `--ink-dim` | `#27292b` / `#606366` | Text / Sekundärtext |
| `--slate` | `#394658` | Akzent: Hauptknöpfe, aktive Einträge, Links |
| `--slate-soft` | `#e6e9ed` | Hintergrund aktiver Einträge |
| `--rust` / `--accent` | `#a04429` | Fehler, Löschen |
| Navigation | `#26292d` | dunkle Leiste links (eigene Token-Überschreibung im Block `.main-navigation`) |

## Typografie

Public Sans (selbst gehostet, `@fontsource`) für UI und Fließtext, Georgia für redaktionelle
Überschriften (Vault-Name, Notiztitel, Abschnittsköpfe). Kleine Versal-Labels mit Tracking als
Überzeilen.

## Aufbau

- **Navigation links, einklappbar** (Zustand in `localStorage`, Schlüssel
  `stone.navigation.collapsed`): Marke, Vault-Auswahl + „Neuer Vault", Bereiche Notizen,
  In Obsidian, KI-Wissen, Mitglieder & Rechte (nur mit MANAGE). Unten „Obsidian einrichten",
  Konto, Abmelden. Auf dem Handy öffnet ein Menüknopf die Leiste.
- **Kopfzeile:** Brotkrumen und Schnellsprung (Strg/⌘+K).
- **Notizen:** links Suche, Ordner-/Sortierfilter und aufklappbarer Ordnerbaum, rechts das
  Dokument (Lesen/Bearbeiten, Speichern, Export). Ähnliche Notizen und Freigabe über der Notiz.
- **Mitglieder & Rechte:** Unterreiter Mitglieder (mit Einladen), Rollen, Gruppen, Protokoll,
  Vault-Einstellungen.
- **Rechtslinks** (Datenschutz, Impressum) in der Fußzeile jeder Ansicht.

## Daten und Suche

Die Übersicht lädt beim Öffnen alle Seiten (nur Pfade/Metadaten, 500 pro Anfrage) und zeigt sie
schon während des Ladens. Die Suche (Notizbereich und Schnellsprung) läuft über
`GET /api/v1/vaults/{id}/notes/search` auf dem Server, verzögert und nur mit der jeweils letzten
Anfrage. Inhalte werden nie für eine Suche geladen.

## Technik

- **Ein Stylesheet:** alle Styles in `src/app.css`, keine `<style>`-Blöcke in Komponenten.
  Einzige Ausnahme sind datengetriebene Custom Properties im Markup (z. B. `--depth` für die
  Einrückung im Baum).
- Sichtbarer Fokus, 48px-Touchziele, `prefers-reduced-motion` schaltet Übergänge ab.

## Frühere Richtungen

„Funktionale Klarheit" (Creme/Waldgrün, Tabs oben, Verwaltung als eine lange Seite) bis
27.09.2026; eine eigene Variante „Werkstatt" (Graphit/Bernstein) wurde zugunsten von A verworfen.
