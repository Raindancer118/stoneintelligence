"""Erzeugt die animierten README-Grafiken (hell + dunkel) in den Farben aus webapp/Design.md.

    python3 docs/readme/render.py

GitHub zeigt SVGs per <img> ohne Skripte und ohne externe Schriften: alles steckt in der Datei,
Animationen sind reines CSS, das Logo liegt als data-URI bei. Ohne Bewegung (prefers-reduced-motion)
zeigt jede Grafik ihren Endzustand.
"""
import base64
import io
import math
import random
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent

THEMES = {
    "light": dict(bg="#f5f3ed", surface="#fffefa", raised="#faf9f5", line="#deded3", ink="#27292b", dim="#606366",
                  faint="#8d9094", slate="#394658", slate2="#71829a", soft="#e6e9ed", rust="#a04429",
                  strata="#394658", strata_op=0.11, on_slate="#fffefa", shadow="0.10"),
    "dark": dict(bg="#1c1e21", surface="#26292d", raised="#2c3035", line="#3b3f45", ink="#e9ecef", dim="#b5b9bf",
                 faint="#81868d", slate="#dbe2eb", slate2="#93a6bf", soft="#3e444b", rust="#e08a6e",
                 strata="#dbe2eb", strata_op=0.07, on_slate="#1c1e21", shadow="0.45"),
}

SANS = "'Public Sans','Segoe UI',Helvetica,Arial,sans-serif"
SERIF = "Georgia,'Times New Roman',serif"


def logo_uri(size=176):
    image = Image.open(ROOT / "webapp/public/logo.png").convert("RGBA")
    image.thumbnail((size, size), Image.LANCZOS)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", optimize=True)
    return "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode()


def still():
    """Ohne Bewegung: jede Animation aus, sichtbar bleibt der Grundzustand (= Endzustand)."""
    return "@media (prefers-reduced-motion: reduce) { * { animation: none !important; } }"


def strata(width, height, t, seed, layers=16, top=0):
    """Feine Gesteinsschichten - Schiefer ist geschichtet, die Linien driften unterschiedlich schnell."""
    rng = random.Random(seed)
    paths = []
    for i in range(layers):
        y0 = top + (i + 0.5) * (height - top) / layers + rng.uniform(-8, 8)
        amp = rng.uniform(4, 16)
        waves = rng.uniform(1.2, 2.6)
        phase = rng.uniform(0, math.tau)
        points = []
        for step in range(0, 49):
            x = -120 + step * (width + 240) / 48
            y = y0 + amp * math.sin(phase + waves * math.tau * step / 48) + rng.uniform(-1.2, 1.2)
            points.append((x, y))
        d = f"M{points[0][0]:.1f},{points[0][1]:.1f} " + " ".join(
            f"Q{(a[0] + b[0]) / 2:.1f},{a[1]:.1f} {b[0]:.1f},{b[1]:.1f}" for a, b in zip(points, points[1:]))
        opacity = t["strata_op"] * (1.9 if i % 5 == 2 else 1)
        width_px = 1.4 if i % 5 == 2 else 0.8
        duration = rng.uniform(26, 44)
        direction = "alternate" if i % 2 else "alternate-reverse"
        paths.append(f'<path d="{d}" fill="none" stroke="{t["strata"]}" stroke-opacity="{opacity:.3f}" '
                     f'stroke-width="{width_px}" style="animation: drift {duration:.1f}s ease-in-out infinite {direction}"/>')
    return "\n    ".join(paths)


def keyframes(name, steps):
    body = " ".join(f"{pct}% {{ {rule} }}" for pct, rule in steps)
    return f"@keyframes {name} {{ {body} }}"


# ----------------------------------------------------------------------------------------------- Hero

def hero(t, logo):
    W, H = 1280, 560
    cx, cy, cw, ch = 748, 104, 452, 352
    x0 = cx + 36
    lines = [  # (y, text, textLength, style, who, start%, end%)
        (cy + 110, "Ziele für das vierte Quartal", 338, "head", "anna", 4, 20),
        (cy + 160, "•  Versionsverlauf ausrollen", 252, "item", "tom", 21, 36),
        (cy + 196, "•  Team in den Vault einladen", 268, "item", "anna", 36, 51),
        (cy + 232, "•  Rechte für „Kunden“ prüfen", 262, "item", "tom", 52, 67),
    ]
    css = [
        keyframes("drift", [(0, "transform: translateX(-60px)"), (100, "transform: translateX(60px)")]),
        keyframes("rise", [(0, "opacity: 0; transform: translateY(10px)"), (100, "opacity: 1; transform: none")]),
        keyframes("pulse", [(0, "opacity: 1"), (50, "opacity: .25"), (100, "opacity: 1")]),
        ".rise { animation: rise .9s cubic-bezier(.2,.8,.2,1) both; }",
        ".d1 { animation-delay: .1s } .d2 { animation-delay: .25s } .d3 { animation-delay: .4s } .d4 { animation-delay: .55s } .d5 { animation-delay: .7s }",
        ".rev { transform-box: fill-box; transform-origin: 0 50%; }",
        ".pulse { animation: pulse 1.6s ease-in-out infinite; }",
    ]
    clips, texts = [], []
    for n, (y, text, length, style, who, start, end) in enumerate(lines, 1):
        css.append(keyframes(f"type{n}", [(0, "transform: scaleX(0)"), (start, "transform: scaleX(0)"),
                                         (end, "transform: scaleX(1)"), (92, "transform: scaleX(1)"),
                                         (97, "transform: scaleX(0)"), (100, "transform: scaleX(0)")]))
        css.append(f".t{n} {{ animation: type{n} 14s linear infinite; }}")
        top = y - (26 if style == "head" else 19)
        clips.append(f'<clipPath id="c{n}"><rect class="rev t{n}" x="{x0 - 2}" y="{top}" width="{length + 6}" height="{34 if style == "head" else 28}"/></clipPath>')
        if style == "head":
            texts.append(f'<text x="{x0}" y="{y}" clip-path="url(#c{n})" font-family="{SERIF}" font-size="25" fill="{t["ink"]}" textLength="{length}" lengthAdjust="spacingAndGlyphs">{text}</text>')
        else:
            texts.append(f'<text x="{x0}" y="{y}" clip-path="url(#c{n})" font-family="{SANS}" font-size="17" fill="{t["ink"]}" textLength="{length}" lengthAdjust="spacingAndGlyphs">{text}</text>')

    def cursor_steps(who):
        mine = [(y, length, style, s, e) for (y, _, length, style, w, s, e) in lines if w == who]
        steps = []
        for i, (y, length, style, s, e) in enumerate(mine):
            top = y - (24 if style == "head" else 17)
            steps.append((s, f"transform: translate({x0}px, {top}px); opacity: 1"))
            steps.append((e, f"transform: translate({x0 + length + 2}px, {top}px); opacity: 1"))
            if i + 1 < len(mine):
                nxt_y, _, nxt_style, nxt_s, _ = mine[i + 1]
                steps.append((nxt_s - 1, f"transform: translate({x0 + length + 2}px, {top}px); opacity: 1"))
        last = steps[-1][1]
        first_s = mine[0][3]
        head = [(0, steps[0][1].replace("opacity: 1", "opacity: 0")), (max(first_s - 2, 1), steps[0][1])]
        tail = [(90, last), (95, last.replace("opacity: 1", "opacity: 0")), (100, last.replace("opacity: 1", "opacity: 0"))]
        return head + steps + tail, last

    cursors = []
    for who, name, color in (("anna", "Anna", t["slate"]), ("tom", "Tom", t["slate2"])):
        steps, rest = cursor_steps(who)
        css.append(keyframes(f"cur-{who}", steps))
        rest_transform = rest.split(";")[0]
        css.append(f".cur-{who} {{ {rest_transform}; animation: cur-{who} 14s linear infinite; }}")
        label_w = 12 + 8.6 * len(name)
        cursors.append(f'''<g class="cur-{who}">
      <rect x="0" y="0" width="2" height="24" rx="1" fill="{color}"/>
      <rect x="0" y="-19" width="{label_w:.0f}" height="18" rx="3" fill="{color}"/>
      <text x="6" y="-6" font-family="{SANS}" font-size="11.5" font-weight="600" fill="{t["on_slate"]}">{name}</text>
    </g>''')
    css.append(keyframes("busy", [(0, "opacity: 1"), (70, "opacity: 1"), (73, "opacity: 0"), (97, "opacity: 0"), (100, "opacity: 1")]))
    css.append(keyframes("done", [(0, "opacity: 0"), (72, "opacity: 0"), (75, "opacity: 1"), (92, "opacity: 1"), (96, "opacity: 0"), (100, "opacity: 0")]))
    css.append(".busy { opacity: 0; animation: busy 14s linear infinite; } .done { animation: done 14s linear infinite; }")
    css.append(still())

    facts = ["Live-Cursor", "Rechte je Notiz", "Versionen zurückholen", "Offline weiter"]
    fact_x, fact_parts = 92, []
    for i, fact in enumerate(facts):
        fact_parts.append(f'<text x="{fact_x}" y="436" font-family="{SANS}" font-size="15" fill="{t["dim"]}">{fact}</text>')
        fact_x += 7.6 * len(fact) + 36
        if i + 1 < len(facts):
            fact_parts.append(f'<rect x="{fact_x - 21}" y="428" width="5" height="5" transform="rotate(45 {fact_x - 18.5} 430.5)" fill="{t["slate2"]}"/>')

    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img" aria-label="StoneIntelligence: zwei Personen schreiben gleichzeitig in dieselbe Notiz">
  <style>
    {chr(10).join("    " + rule for rule in css).strip()}
  </style>
  <defs>
    <filter id="lift" x="-10%" y="-10%" width="120%" height="130%"><feDropShadow dx="0" dy="14" stdDeviation="18" flood-color="#000" flood-opacity="{t["shadow"]}"/></filter>
    <filter id="glow"><feComponentTransfer><feFuncR type="linear" slope="1.7" intercept=".16"/><feFuncG type="linear" slope="1.7" intercept=".17"/><feFuncB type="linear" slope="1.7" intercept=".2"/></feComponentTransfer></filter>
    <linearGradient id="fade" x1="0" x2="1"><stop offset="0" stop-color="{t["bg"]}" stop-opacity=".78"/><stop offset=".48" stop-color="{t["bg"]}" stop-opacity=".7"/><stop offset=".62" stop-color="{t["bg"]}" stop-opacity="0"/></linearGradient>
    {chr(10).join("    " + c for c in clips).strip()}
  </defs>
  <rect width="{W}" height="{H}" rx="14" fill="{t["bg"]}"/>
  <g>
    {strata(W, H, t, 7)}
  </g>
  <rect width="{W}" height="{H}" rx="14" fill="url(#fade)"/>

  <g class="rise d1"><text x="92" y="170" font-family="{SANS}" font-size="12.5" font-weight="600" letter-spacing="3.4" fill="{t["dim"]}">OBSIDIAN · LIVE-SYNC · TEAM-WISSEN</text></g>
  <g class="rise d2"><image href="{logo}" x="86" y="196" width="92" height="92"{' filter="url(#glow)"' if t is THEMES["dark"] else ''}/></g>
  <g class="rise d3"><text x="194" y="262" font-family="{SERIF}" font-size="58" fill="{t["ink"]}" letter-spacing="-0.8">StoneIntelligence</text></g>
  <g class="rise d4">
    <text x="92" y="336" font-family="{SERIF}" font-size="23" fill="{t["ink"]}">Gemeinsam denken. In Obsidian schreiben.</text>
    <text x="92" y="368" font-family="{SERIF}" font-size="23" fill="{t["dim"]}">Überall weiterarbeiten.</text>
  </g>
  <g class="rise d5">
    <rect x="92" y="400" width="520" height="1" fill="{t["line"]}"/>
    {chr(10).join("    " + p for p in fact_parts).strip()}
  </g>

  <g class="rise d3">
    <rect x="{cx}" y="{cy}" width="{cw}" height="{ch}" rx="9" fill="{t["surface"]}" stroke="{t["line"]}" filter="url(#lift)"/>
    <path d="M{cx} {cy + 9} a9 9 0 0 1 9 -9 h{cw - 18} a9 9 0 0 1 9 9 v37 h-{cw} z" fill="{t["raised"]}"/>
    <rect x="{cx}" y="{cy + 46}" width="{cw}" height="1" fill="{t["line"]}"/>
    <circle cx="{cx + 22}" cy="{cy + 23}" r="4" fill="{t["line"]}"/><circle cx="{cx + 36}" cy="{cy + 23}" r="4" fill="{t["line"]}"/><circle cx="{cx + 50}" cy="{cy + 23}" r="4" fill="{t["line"]}"/>
    <text x="{cx + 72}" y="{cy + 28}" font-family="{SANS}" font-size="13.5" fill="{t["dim"]}">Team / Projektplan.md</text>
    {chr(10).join("    " + x for x in texts).strip()}
    {chr(10).join("    " + c for c in cursors).strip()}
    <rect x="{cx}" y="{cy + ch - 46}" width="{cw}" height="1" fill="{t["line"]}"/>
    <g class="busy">
      <circle class="pulse" cx="{x0 + 4}" cy="{cy + ch - 22}" r="4.5" fill="{t["slate"]}"/>
      <text x="{x0 + 18}" y="{cy + ch - 17}" font-family="{SANS}" font-size="13.5" fill="{t["dim"]}">Anna und Tom schreiben gerade</text>
    </g>
    <g class="done">
      <path d="M{x0} {cy + ch - 22} l4 4 l8 -9" fill="none" stroke="{t["slate"]}" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>
      <text x="{x0 + 18}" y="{cy + ch - 17}" font-family="{SANS}" font-size="13.5" fill="{t["dim"]}">Auf allen Geräten gleich · 2 Personen im Vault</text>
    </g>
  </g>
</svg>
'''


# ---------------------------------------------------------------------------------------- Szenen

def frame(t, W, H, title, body, css, label):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img" aria-label="{label}">
  <style>
    {keyframes("drift", [(0, "transform: translateX(-40px)"), (100, "transform: translateX(40px)")])}
    {chr(10).join("    " + rule for rule in css).strip()}
    {still()}
  </style>
  <rect width="{W}" height="{H}" rx="12" fill="{t["bg"]}"/>
  <g>{strata(W, H, t, hash(title) % 1000, layers=9)}</g>
  <rect x="28" y="28" width="{W - 56}" height="{H - 56}" rx="9" fill="{t["surface"]}" stroke="{t["line"]}"/>
  <text x="52" y="66" font-family="{SERIF}" font-size="19" fill="{t["ink"]}">{title}</text>
  <rect x="28" y="84" width="{W - 56}" height="1" fill="{t["line"]}"/>
  {body}
</svg>
'''


def versions(t):
    W, H = 640, 400
    rows = [("Anna", "heute, 09:40", True), ("Tom", "heute, 08:15", False), ("KI „Gemini“", "gestern, 22:03", False), ("Anna", "gestern, 17:30", False)]
    body = []
    for i, (who, when, current) in enumerate(rows):
        y = 104 + i * 46
        cls = ' class="pick"' if i == 2 else ""
        body.append(f'<rect{cls} x="44" y="{y}" width="228" height="40" rx="6" fill="{t["soft"]}" opacity="0"/>')
        body.append(f'<text x="58" y="{y + 18}" font-family="{SANS}" font-size="14" font-weight="600" fill="{t["ink"]}">{who}{" · aktuell" if current else ""}</text>')
        body.append(f'<text x="58" y="{y + 34}" font-family="{SANS}" font-size="12.5" fill="{t["dim"]}">{when}</text>')
    body.append(f'<rect x="288" y="100" width="1" height="264" fill="{t["line"]}"/>')
    diff = [("same", "## Ablauf"), ("removed", "Abgabe am 12. Oktober"), ("added", "Abgabe am 19. Oktober"), ("same", "Folien: Tom"),
            ("removed", "Probelauf entfällt"), ("added", "Probelauf am Donnerstag"), ("gap", "· · · 14 unveränderte Zeilen · · ·")]
    for i, (kind, text) in enumerate(diff):
        y = 108 + i * 30
        fill = {"removed": t["rust"], "added": t["slate"]}.get(kind)
        cls = f' class="row r{i}"'
        if fill:
            body.append(f'<g{cls}><rect x="304" y="{y}" width="296" height="26" rx="3" fill="{fill}" fill-opacity="0.12"/>'
                        f'<text x="314" y="{y + 18}" font-family="{SANS}" font-size="13.5" fill="{fill}">{"−" if kind == "removed" else "+"}</text>'
                        f'<text x="332" y="{y + 18}" font-family="{SANS}" font-size="13.5" fill="{t["ink"]}">{text}</text></g>')
        elif kind == "gap":
            body.append(f'<text{cls} x="452" y="{y + 18}" text-anchor="middle" font-family="{SANS}" font-size="12" font-style="italic" fill="{t["faint"]}">{text}</text>')
        else:
            body.append(f'<text{cls} x="332" y="{y + 18}" font-family="{SANS}" font-size="13.5" fill="{t["dim"]}">{text}</text>')
    body.append(f'''<g class="btn"><rect x="304" y="326" width="206" height="36" rx="6" fill="{t["slate"]}"/>
    <text x="407" y="349" text-anchor="middle" font-family="{SANS}" font-size="13.5" font-weight="600" fill="{t["on_slate"]}">Version wiederherstellen</text></g>''')
    body.append(f'''<g class="toast"><rect x="360" y="278" width="232" height="36" rx="6" fill="{t["ink"]}"/>
    <text x="476" y="301" text-anchor="middle" font-family="{SANS}" font-size="13" fill="{t["bg"]}">Version wiederhergestellt</text></g>''')
    css = [
        keyframes("pick", [(0, "opacity: 0"), (8, "opacity: 0"), (12, "opacity: 1"), (92, "opacity: 1"), (96, "opacity: 0")]),
        ".pick { opacity: 1 !important; animation: pick 11s ease infinite; }",
        keyframes("press", [(0, "transform: none"), (60, "transform: none"), (62, "transform: scale(.96)"), (65, "transform: none"), (100, "transform: none")]),
        ".btn { transform-box: fill-box; transform-origin: center; animation: press 11s ease infinite; }",
        keyframes("toast", [(0, "opacity: 0; transform: translateY(8px)"), (66, "opacity: 0; transform: translateY(8px)"),
                            (70, "opacity: 1; transform: none"), (88, "opacity: 1; transform: none"), (92, "opacity: 0; transform: none"), (100, "opacity: 0")]),
        ".toast { opacity: 0; animation: toast 11s ease infinite; }",
    ]
    for i in range(len(diff)):
        start = 14 + i * 4
        css.append(keyframes(f"row{i}", [(0, "opacity: 0; transform: translateX(-6px)"), (start, "opacity: 0; transform: translateX(-6px)"),
                                         (start + 4, "opacity: 1; transform: none"), (92, "opacity: 1; transform: none"), (96, "opacity: 0")]))
        css.append(f".r{i} {{ animation: row{i} 11s ease infinite; }}")
    return frame(t, W, H, "Versionen · Vortrag.md", "\n  ".join(body), css, "Versionsverlauf mit Vergleich und Wiederherstellen")


def lock(x, y, color):
    return (f'<rect x="{x}" y="{y + 6}" width="12" height="9" rx="2" fill="{color}"/>'
            f'<path d="M{x + 3} {y + 6} v-2.5 a3 3 0 0 1 6 0 v2.5" fill="none" stroke="{color}" stroke-width="1.8"/>')


def people(x, y, color):
    return (f'<circle cx="{x + 4}" cy="{y + 5}" r="3" fill="{color}"/><circle cx="{x + 11}" cy="{y + 5}" r="3" fill="{color}" opacity=".6"/>'
            f'<path d="M{x - 1} {y + 15} a5 5 0 0 1 10 0 z" fill="{color}"/><path d="M{x + 6} {y + 15} a5 5 0 0 1 10 0 z" fill="{color}" opacity=".6"/>')


def access(t):
    W, H = 640, 400
    tree = [(0, "Team-Vault", "folder"), (1, "Kunden", "folder"), (2, "Vertrag Müller.md", "note"), (2, "Angebot.md", "note"),
            (1, "Projekte", "folder"), (2, "Projektplan.md", "note"), (1, "Privat", "folder"), (2, "Gehälter.md", "note")]
    body = []
    for i, (depth, name, kind) in enumerate(tree):
        y = 118 + i * 32
        x = 52 + depth * 22
        if name == "Kunden":
            body.append(f'<rect class="hl" x="44" y="{y - 17}" width="226" height="28" rx="5" fill="{t["soft"]}"/>')
        if kind == "folder":
            body.append(f'<path d="M{x} {y - 10} h6 l2 2 h8 v10 h-16 z" fill="{t["slate2"]}" opacity=".85"/>')
        else:
            body.append(f'<path d="M{x + 2} {y - 11} h9 l4 4 v11 h-13 z" fill="none" stroke="{t["faint"]}" stroke-width="1.3"/>')
        weight = "600" if kind == "folder" else "400"
        body.append(f'<text x="{x + 24}" y="{y + 1}" font-family="{SANS}" font-size="14" font-weight="{weight}" fill="{t["ink"]}">{name}</text>')
        if name == "Kunden":
            body.append(f'<g class="mark m1">{people(222, y - 9, t["slate"])}</g>')
        if name == "Privat":
            body.append(f'<g class="mark m2">{lock(226, y - 12, t["slate"])}</g>')
        if name == "Gehälter.md":
            body.append(f'<g class="mark m2">{lock(226, y - 12, t["faint"])}</g>')
    pop = [("Anna", "Lesen"), ("Gruppe Vertrieb", "Bearbeiten"), ("alle anderen", "kein Zugriff")]
    panel = [f'<rect x="300" y="104" width="296" height="206" rx="8" fill="{t["raised"]}" stroke="{t["line"]}"/>',
             f'<text x="318" y="132" font-family="{SANS}" font-size="12" font-weight="600" letter-spacing="1.6" fill="{t["dim"]}">FREIGABE · KUNDEN</text>']
    for i, (who, what) in enumerate(pop):
        y = 160 + i * 44
        panel.append(f'<g class="prow p{i}"><text x="318" y="{y}" font-family="{SANS}" font-size="14" fill="{t["ink"]}">{who}</text>'
                     f'<rect x="452" y="{y - 17}" width="128" height="26" rx="5" fill="{t["surface"]}" stroke="{t["line"]}"/>'
                     f'<text x="464" y="{y}" font-family="{SANS}" font-size="13" fill="{t["slate"] if what != "kein Zugriff" else t["rust"]}">{what}</text>'
                     f'<path d="M566 {y - 7} l4 4 l4 -4" fill="none" stroke="{t["faint"]}" stroke-width="1.4"/></g>')
    panel.append(f'<text class="prow p3" x="318" y="{160 + 3 * 44 - 4}" font-family="{SANS}" font-size="12.5" fill="{t["dim"]}">Gilt für alles darin · live auf allen Geräten</text>')
    body.append(f'<g class="panel">{"".join(panel)}</g>')
    css = [
        keyframes("hl", [(0, "opacity: 0"), (8, "opacity: 0"), (12, "opacity: 1"), (90, "opacity: 1"), (95, "opacity: 0")]),
        ".hl { animation: hl 12s ease infinite; }",
        keyframes("panel", [(0, "opacity: 0; transform: translateX(-10px)"), (12, "opacity: 0; transform: translateX(-10px)"),
                            (17, "opacity: 1; transform: none"), (90, "opacity: 1; transform: none"), (95, "opacity: 0")]),
        ".panel { animation: panel 12s cubic-bezier(.2,.8,.2,1) infinite; }",
        keyframes("mark1", [(0, "opacity: 0; transform: scale(.4)"), (48, "opacity: 0; transform: scale(.4)"), (53, "opacity: 1; transform: none"), (90, "opacity: 1"), (95, "opacity: 0")]),
        keyframes("mark2", [(0, "opacity: 0; transform: scale(.4)"), (60, "opacity: 0; transform: scale(.4)"), (65, "opacity: 1; transform: none"), (90, "opacity: 1"), (95, "opacity: 0")]),
        ".mark { transform-box: fill-box; transform-origin: center; } .m1 { animation: mark1 12s cubic-bezier(.3,1.6,.5,1) infinite; } .m2 { animation: mark2 12s cubic-bezier(.3,1.6,.5,1) infinite; }",
    ]
    for i in range(4):
        start = 20 + i * 6
        css.append(keyframes(f"prow{i}", [(0, "opacity: 0"), (start, "opacity: 0"), (start + 4, "opacity: 1"), (90, "opacity: 1"), (95, "opacity: 0")]))
        css.append(f".p{i} {{ animation: prow{i} 12s ease infinite; }}")
    return frame(t, W, H, "Freigaben · Team-Vault", "\n  ".join(body), css, "Freigaben für Ordner und Notizen mit Kennzeichen im Dateibaum")


def linking(t):
    W, H = 640, 400
    nodes = {"pdf": (52, 205, "Vorlesung 4.pdf"), "a": (236, 110, "Normalformen"), "b": (236, 300, "Abhängigkeiten"),
             "c": (470, 205, "Schlüssel"), "d": (440, 300, "Denormalisierung")}
    size = {k: (8.1 * len(v[2]) + 30, 38) for k, v in nodes.items()}

    def center(k):
        x, y, _ = nodes[k]
        return x + size[k][0] / 2, y + size[k][1] / 2

    edges = [("pdf", "a", None, 0), ("pdf", "b", None, 1), ("a", "c", "benötigt", 2), ("b", "c", "definiert", 3), ("a", "d", "Gegenteil von", 4)]
    body, css = [], []
    for k, (x, y, name) in nodes.items():
        w, h = size[k]
        fill = t["soft"] if k == "pdf" else t["raised"]
        body.append(f'<g class="node n-{k}"><rect x="{x}" y="{y}" width="{w:.0f}" height="{h}" rx="6" fill="{fill}" stroke="{t["line"]}"/>'
                    f'<text x="{x + 15}" y="{y + 24}" font-family="{SANS}" font-size="13.5" fill="{t["ink"]}">{name}</text></g>')
    lines = []
    for n, (a, b, label, order) in enumerate(edges):
        (x1, y1), (x2, y2) = center(a), center(b)
        length = math.dist((x1, y1), (x2, y2))
        dashed = order == 4
        color = t["slate"] if not dashed else t["slate2"]
        lines.append(f'<path class="edge e{n}" d="M{x1:.0f} {y1:.0f} L{x2:.0f} {y2:.0f}" stroke="{color}" stroke-width="1.6" fill="none" '
                     f'stroke-dasharray="{length:.0f}" stroke-dashoffset="0"/>')
        start = 10 + order * 12
        css.append(keyframes(f"e{n}", [(0, f"stroke-dashoffset: {length:.0f}"), (start, f"stroke-dashoffset: {length:.0f}"),
                                       (start + 9, "stroke-dashoffset: 0"), (90, "stroke-dashoffset: 0"), (95, f"stroke-dashoffset: {length:.0f}"),
                                       (100, f"stroke-dashoffset: {length:.0f}")]))
        css.append(f".e{n} {{ animation: e{n} 13s ease-in-out infinite; }}")
        if label:
            mx, my = (x1 + x2) / 2, (y1 + y2) / 2
            lw = 7.2 * len(label) + 16
            lines.append(f'<g class="lab l{n}"><rect x="{mx - lw / 2:.0f}" y="{my - 11:.0f}" width="{lw:.0f}" height="20" rx="10" fill="{t["surface"]}" stroke="{color}"/>'
                         f'<text x="{mx:.0f}" y="{my + 3:.0f}" text-anchor="middle" font-family="{SANS}" font-size="11.5" fill="{color}">{label}</text></g>')
            css.append(keyframes(f"l{n}", [(0, "opacity: 0"), (start + 8, "opacity: 0"), (start + 11, "opacity: 1"), (90, "opacity: 1"), (95, "opacity: 0")]))
            css.append(f".l{n} {{ animation: l{n} 13s ease infinite; }}")
    body = lines + body
    body.append(f'<g class="judge"><rect x="{W - 262}" y="44" width="218" height="30" rx="6" fill="{t["soft"]}"/>'
                f'<circle class="dot" cx="{W - 244}" cy="59" r="4" fill="{t["slate"]}"/>'
                f'<text x="{W - 232}" y="64" font-family="{SANS}" font-size="13" fill="{t["dim"]}">KI prüft: passt der Link?</text></g>')
    css += [
        keyframes("judge", [(0, "opacity: 0"), (50, "opacity: 0"), (54, "opacity: 1"), (74, "opacity: 1"), (78, "opacity: 0"), (100, "opacity: 0")]),
        ".judge { opacity: 0; animation: judge 13s ease infinite; }",
        keyframes("dot", [(0, "opacity: 1"), (50, "opacity: .2"), (100, "opacity: 1")]),
        ".dot { animation: dot 1.2s ease-in-out infinite; }",
    ]
    return frame(t, W, H, "Verlinkung · Datenbanken", "\n  ".join(body), css, "Notizen werden automatisch und typisiert verlinkt")


def main():
    logo = logo_uri()
    for name, t in THEMES.items():
        (HERE / f"hero-{name}.svg").write_text(hero(t, logo), encoding="utf-8")
        (HERE / f"versions-{name}.svg").write_text(versions(t), encoding="utf-8")
        (HERE / f"access-{name}.svg").write_text(access(t), encoding="utf-8")
        (HERE / f"linking-{name}.svg").write_text(linking(t), encoding="utf-8")


if __name__ == "__main__":
    main()
