# StoneIntelligence webapp — Design

Interner, login-gated Admin-Dashboard (Vault-/Berechtigungsverwaltung), ein Nutzer. Kein
Marketing-Auftritt. Richtung: **Funktionale Klarheit** (Google-M3-artig) statt Dark-SaaS-Dashboard.

## Warum dieser Neustart

Erster Entwurf (dunkler Hintergrund, Gitterlinien-Background, Amber-Akzent, Pill-Badges,
Monospace als Leitmotiv) traf exakt mehrere dokumentierte KI-Slop-Muster: Dark-Mode-Dashboard-
Cliche als sicherer Default, Pill-Badges für jede Kleinigkeit, generisches Karten-Raster. Neu
aufgesetzt nach `anti-ai-slop.md`.

## Farbe

Warme Neutraltöne statt kaltem Blaugrau (Anthropic/Mennour-Prinzip), ein einziger ruhiger Akzent
statt Amber/Lila.

| Token | Wert | Verwendung |
|---|---|---|
| `--bg` | `#eeece7` | Seitenhintergrund, warmes Hellgrau |
| `--surface` | `#faf9f5` | Panels/Karten |
| `--surface-raised` | `#ffffff` | Eingabefelder, Hover-Zustände |
| `--line` | `#dcd8cd` | Trennlinien |
| `--ink` | `#211f1a` | Haupttext |
| `--ink-dim` | `#6b6459` | Sekundärtext |
| `--forest` | `#2f4d3a` | Akzent (Buttons, aktive Zustände, Links) |
| `--forest-hover` | `#3c6049` | Akzent-Hover |
| `--rust` | `#a3402c` | Fehler/DENY |

Kein Gradient. Kein Lila/Blau. Dark Mode ist hier bewusst NICHT der Default - das Tool wird tagsüber
am Schreibtisch benutzt, warmes Hell passt besser zur funktionalen-Klarheit-Richtung als das
generische Dark-Dashboard.

## Typografie

Eine Schriftfamilie, **Public Sans** (humanistische Grotesk, offen lizenziert, kein Inter-Default) -
bewusst kein Serif-Pairing, das würde hier eher editorial als funktional wirken.

- Überschriften: Public Sans 700, `letter-spacing: -0.01em` bei großen Graden.
- Fließtext/UI: Public Sans 400/500.
- Echte Bezeichner (Vault-/Rollen-/Gruppen-IDs): `ui-monospace`-Stack, **nur dort**, nicht als
  durchgängiges Leitmotiv wie im ersten Entwurf.

## Layout & Komponenten

- Kein Karten-Grid mit gleichförmigen Schatten. Panels über echte, weiche Elevation
  (`box-shadow`, nicht Rahmen+Farbklecks), Listen über schlichte Trennlinien statt Karten.
- **Keine Pill-Badges.** Permissions/Rollen als kompakte, in Großbuchstaben gesetzte Labels mit
  Tracking statt runder Pillen; DENY/ALLOW über Farbe + Gewicht, nicht über eine weitere Pille.
  Gruppenmitglieder als einfache Liste mit dezentem „×"-Entfernen-Link, keine Chip-Optik.
  Rollen-Zuweisung pro Gruppe als Checkbox-Liste, kein Pill-Toggle.
- Layout asymmetrisch: schmale linke Sidebar (Vault-Auswahl) + breiterer Inhaltsbereich, nicht
  zentrierte Einzelspalte.
- Buttons: rechteckig, minimal gerundet (`--radius: 4px`), kein durchgängiges Pill-Rounding.
- Touch-Targets ≥ 48px für alle Buttons/Links.

## Bewusst NICHT umgesetzt

Kein WebGL/3D/Scroll-Storytelling (Cookbook-Techniken A-F) - falsche Kategorie für ein
Utility-Dashboard mit einem Nutzer, würde nur Ladezeit/Komplexität kosten ohne Aussage zu tragen
(vgl. Skill-Referenz Abschnitt E: „nicht jede Seite braucht WebGL"). `prefers-reduced-motion`
ist trotzdem respektiert, da die wenigen Übergänge (Hover, Panel-Wechsel) rein CSS-transition-
basiert sind, keine JS-Animation-Library.

## Kunden-Dashboard (19.09.2026)

Das Dashboard wird vom internen Verwaltungswerkzeug zum täglichen Notiz-Arbeitsplatz.
Die von Tom freigegebene selbstständige Erweiterung baut auf der bestehenden Richtung auf.
Geprüfte Varianten: reine Tabellenverwaltung (zu wenig Platz fürs Schreiben), kartenbasierte
Übersicht (zu wenig Informationsdichte), Notiz-Arbeitsplatz mit Liste und Dokument (gewählt).

- Bestehende Public-Sans-Typografie, Waldgrün und warme Oberflächen bleiben erhalten.
- Vault-Auswahl links; im Vault die Bereiche „Notizen“ und „Verwaltung“.
- Notizen: kompakte durchsuchbare Pfadliste mit Ordnerfilter, daneben ein großes Dokument.
  Auf schmalen Displays wechselt man zwischen Liste und Dokument. Linksbündige Formulare,
  beschriftete Eingaben, sichtbarer Fokus, mindestens 48px hohe Hauptaktionen.
- Dokument: Titel/Pfad, Lesen/Bearbeiten, ausdrücklicher Speicherknopf, Status und
  verständliche Konfliktauflösung. Aufgeräumte Markdown-Vorschau, exportierbare Entwürfe.
- Keine dekorativen Statistikkarten oder erfundenen Inhalte. Leerzustände erklären den
  nächsten Schritt. Berechtigungen bestimmen sichtbare Aktionen; der Server prüft erneut.
- Editor/Vorschau werden bei Bedarf geladen, Listen seitenweise. Kein Download sämtlicher
  Notizinhalte für eine Suche: die Suche bezieht sich ausdrücklich auf Titel und Pfad.
