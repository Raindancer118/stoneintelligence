# StoneIntelligence webapp — Design

Stand 24.11.2026. Toms Hausstil: ruhig wie Banking, monochrom, Tinte als Aktion, Haarlinien statt
Schatten. Löst die Fassung vom 27.09. (Cremepapier, Schiefer-Leiste, Georgia, Federn) ab.

## Farbe

Tokens in `src/app.css` (`:root` hell, `:root[data-theme="dark"]` dunkel). Neutrale ohne Farbstich.

| Token | Hell | Dunkel | Wofür |
|---|---|---|---|
| `--canvas` / `--surface` / `--surface-2` | `#fafafa` / `#fff` / `#f0f0f0` | `#161616` / `#202020` / `#2a2a2a` | Seite, Karten, Flächen und Skeletons |
| `--line` / `--line-strong` / `--field-line` | `#e1e1e1` / `#c5c5c5` / `#8c8c8c` | `#393939` / `#545454` / `#6e6e6e` | Haarlinien, Buttons, Feldrahmen (3:1) |
| `--text-1/2/3` | `#161616` / `#4d4d4d` / `#6c6c6c` | `#f2f2f2` / `#b7b7b7` / `#898989` | Text |
| `--tinte` | `#181b1f` | `#c3c7ce` | Primäraktion, Auswahlbalken, Fokus |
| `--live` | `#2e9e52` | `#4cb86a` | Nur Lebendiges (laufende KI-Jobs), nie Textfarbe |
| `--success/--warning/--danger` | | | Bedeutung, mit `-soft`-Hintergrund |

Alte Namen (`--ink`, `--slate`, `--bg` …) sind Aliase. Hell/Dunkel: Schalter in der Kopfzeile
(`ThemeToggle`, gespeichert unter `stone.theme`), sonst Systemeinstellung.

## Typo, Form, Tiefe

- Plus Jakarta Sans 400–600, JetBrains Mono für Pfade, Code, Tastenkürzel. Satzschreibung, keine Versalien.
- Notizpfade stehen überall in Mono – das eine wiederkehrende Detail.
- Radien 4–8, Buttons 40 hoch. Karten: Haarlinie, kein Schatten. Schatten nur auf Menüs und Dialogen.
- Linien 0,5 px auf 2x-Schirmen (`--hairline`), sonst 1 px.

## Aufbau

- **Leiste (252 px):** Vaults, darunter die Bereiche **Notizen · Dokumente & KI · Mitglieder & Rechte ·
  Einstellungen · Obsidian verbinden**. Verwaltung nur mit MANAGE; die App fragt die Rechte selbst ab.
  Aktiver Eintrag = Balken links, keine Füllung. Einklappen über das Icon neben dem Logo.
- **Kopfzeile:** „Vault / Bereich“, Schnellsprung (Strg/⌘+K), Hell/Dunkel.
- **Notizen:** keine Seitenüberschrift. Liste links (Suche + Neue Notiz oben, Sortierung und
  Aktualisieren klein darunter, Freigabe des Ordners am Ende). Rechts **eine** Leiste je Notiz: Pfad und
  Titel, Lesen/Bearbeiten, Speichern (nur beim Bearbeiten), Freigeben, „Mehr“ (ähnliche Notizen,
  Versionen, Umbenennen, Herunterladen, Neu laden, Löschen).
- **Ohne geöffnete Notiz:** Checkliste „Vault einrichten“ (erste Notiz, Leute einladen, Obsidian
  verbinden), bis alles erledigt oder ausgeblendet ist (`setupSteps.ts`). Den Obsidian-Schritt zählt der
  geöffnete Verbinden-Link – ob ein Gerät verbunden ist, sieht der Server nicht.
- **Unter einem Bereich oder Reiter keine zweite Überschrift mit demselben Namen.**

## Laden

Skeletons in der Form des Inhalts (`Skeleton.svelte`: tree, document, list, panel, notes), Fläche 2,
Pulsieren 1,6 s. Erst nach 300 ms; wo ein Ladezustand Inhalt ersetzt, hält `LoadingGate` sie mindestens
400 ms (`gated`). Screenreader hören den Ladetext, die Formen sind `aria-hidden`. Keine Spinner für Inhalte.

## Bewegung

Nur ease-out (`--ease-out`), Dauern als Tokens (`--dur-press` 120 … `--dur-drawer` 400). Drücken
`scale(.97)`. Keine Federn, kein Glanz, kein Atmen, keine Parallaxe; Dauerbewegung nur bei Skeletons.
View Transitions aus `src/lib/motion.ts` bleiben (Bereich, Notiz, Reiter). `prefers-reduced-motion`
schaltet alles ab.

## Technik

- **Ein Stylesheet:** alles in `src/app.css`, keine `<style>`-Blöcke. Ausnahme: datengetriebene
  Custom Properties im Markup (`--depth` im Baum).
- `accessPlan.ts`, `historyText.ts`, `versionText.ts` sind exakte Kopien aus `plugin/src/sync/`
  (Test prüft das) – Änderungen immer im Plugin machen.
- Sichtbarer Fokus (2 px Tinte), Feldrahmen mit 3:1 Kontrast.
