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

## Bewegung

Alles in `app.css` (Abschnitt „Bewegung"), Bereichs-/Notizwechsel zusätzlich über View Transitions
aus `src/lib/motion.ts` (Typen `area` und `note`). Keine Animationsbibliothek.

- Tokens: `--ease-out`, `--ease-in-out`, `--ease-spring` (Federkurve als `linear()`), Dauern
  140/260/520ms.
- Bereichswechsel: Inhalt gleitet von unten ein, die Markierung des aktiven Bereichs wandert
  (`view-transition-name: active-area`). Notizwechsel: nur das Dokument gleitet seitlich.
- Navigation einklappen: Spaltenbreite gleitet, Beschriftungen blenden (`allow-discrete`).
- Ordnerbaum: Pfeil dreht, Einträge blenden über `@starting-style` ein.
- Schnellsprung: Hintergrund mit Unschärfe, Dialog federt, Treffer gestaffelt.
- Kopfzeile sticky, Kante/Unschärfe per Scroll-Timeline. Knöpfe heben sich, geben beim Drücken nach.
- Anmeldeseite: Überschrift steigt auf, Blätter schweben herein und treiben langsam, Linien
  zeichnen sich. Laden: Logo atmet.
- `prefers-reduced-motion`: alle Animationen, Übergänge und View Transitions aus; `motion.ts`
  startet dann gar keine Transition.

## Technik

- **Ein Stylesheet:** alle Styles in `src/app.css`, keine `<style>`-Blöcke in Komponenten.
  Einzige Ausnahme sind datengetriebene Custom Properties im Markup (z. B. `--depth` für die
  Einrückung im Baum).
- Sichtbarer Fokus, 48px-Touchziele, `prefers-reduced-motion` schaltet Übergänge ab.

## Frühere Richtungen

„Funktionale Klarheit" (Creme/Waldgrün, Tabs oben, Verwaltung als eine lange Seite) bis
27.09.2026; eine eigene Variante „Werkstatt" (Graphit/Bernstein) wurde zugunsten von A verworfen.
