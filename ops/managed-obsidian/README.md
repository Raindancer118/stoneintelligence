# managed-obsidian

Stellt Obsidian für Firmen so bereit, dass im Firmen-Vault nur freigegebene Plugins liegen. Hintergrund und
Grenzen: [ADR 0019](../../docs/adr/0019-plugin-steuerung.md), Betrieb: [docs/enterprise/geraete-und-plugins.md](../../docs/enterprise/geraete-und-plugins.md).

- `managed-obsidian.sh` (Linux, macOS; braucht `jq`, `curl`, root) und `managed-obsidian.ps1` (Windows, Administrator).
- `policy.example.json`: die Richtlinie (Vault-Pfad je System, Server, Plugins mit Version und SHA-256).
- Aufruf: `sudo ./managed-obsidian.sh --policy policy.json` (`--dry-run` zeigt nur an, `--keep-unlisted` lässt
  fremde Plugin-Ordner stehen). Wiederholen ist harmlos; als Timer alle paar Stunden stellt es den Sollzustand her.

## Was ein Lauf tut

1. Legt den Vault und `.obsidian/plugins` an.
2. Installiert genau die Plugins der Richtlinie aus den GitHub-Releases, prüft jede Datei gegen die SHA-256 und
   bricht bei Abweichung ab, ohne etwas zu ändern. Plugin-Ordner außerhalb der Liste werden entfernt.
3. Schreibt `community-plugins.json` und belegt im StoneIntelligence-Plugin Server, Anmeldedienst und Client vor
   (`data.json`, nie Tokens; vorhandene Einstellungen bleiben).
4. Schreibt Obsidians eigene Richtlinie (`/etc/obsidian/policy.json`, macOS `/Library/Application Support/Obsidian/`,
   Windows `C:\Program Files\Obsidian\`). Sie wirkt nur in „Obsidian Work“.
5. Setzt Rechte (Linux/macOS): `.obsidian` gehört root mit Sticky-Bit (1777), `plugins/` und
   `community-plugins.json` root 755/644, `data.json` der Person. Die Person arbeitet im Vault normal und kann
   die Plugins weder ändern noch ersetzen. Mit `"immutable": true` setzt macOS zusätzlich `chflags uchg`.
   Windows: Administratoren Besitzer, Benutzer nur Lesen und Ausführen, `data.json` änderbar.

Optional `serverPolicy`: Der Lauf fragt `GET /api/v1/vaults/<id>/plugin-policy` mit dem Token aus `tokenFile` und
installiert nur, was die Vault-Richtlinie erlaubt. Das Konto muss Mitglied des Vaults sein; ein Dienstkonto beim
Identitätsanbieter ist ungeprüft. Ohne Antwort bleibt der Bestand unverändert.

## Tests

```
bash ops/managed-obsidian/test/managed-obsidian.test.sh   # ohne root und Netz (chown als Attrappe, file://-Releases)
bash ops/managed-obsidian/test/userns.test.sh             # echte Rechte in einem User-Namespace, ohne root
bash ops/managed-obsidian/test/parse-powershell.sh        # nur Syntax, braucht pwsh (sonst übersprungen)
```

Das PowerShell-Skript ist nicht ausgeführt worden; vor dem Rollout auf einem Testgerät laufen lassen.
