# Geräte und Plugins im Unternehmen

Wie ihr StoneIntelligence und Obsidian so betreibt, dass nur verwaltete Geräte und freigegebene Plugins im
Firmen-Vault landen. Hintergrund, Messungen und Grenzen: [ADR 0019](../adr/0019-plugin-steuerung.md).

Reihenfolge von hart nach weich: Zugriff beim Identitätsanbieter (A), verwaltetes Gerät und Dateirechte (B),
Netzwerk (E), Richtlinie auf dem Server (C) und im Plugin (D). Wer lokale Administratorrechte hat, überwindet
alles auf dem Gerät. Deshalb gehören A und B zusammen: A schützt den Server, B das Gerät.

## A. Bedingter Zugriff beim Identitätsanbieter

Ziel: Nur Geräte, die eure Geräteverwaltung als konform kennt, bekommen Tokens für StoneIntelligence. Das Plugin
meldet sich über den Systembrowser an und nimmt die Antwort an einer Loopback-Adresse an (OIDC mit PKCE). Der
Systembrowser ist der Punkt, an dem der Identitätsanbieter das Gerät prüft; das Plugin braucht dafür keine
Änderung. Auf Mobilgeräten läuft die Anmeldung über den Link `obsidian://`, die Regeln gelten gleich.

Der OIDC-Client des Plugins ist ein öffentlicher Client (keine Geheimnisse) mit der Weiterleitung
`http://127.0.0.1:<Port>/…` (Loopback, beliebiger Port) bzw. dem Mobil-Link. Der Server (`platform-api`) prüft
Tokens dieses Anbieters; die Regel hängt also an der **Anwendung**, nicht an StoneIntelligence.

Stand der Angaben unten: aus Herstellerdokumentation und Erfahrung, in dieser Umgebung nicht gegen einen echten
Mandanten geprüft. Als ungeprüft markierte Punkte vor dem Rollout testen.

### Microsoft Entra ID

1. **Gerätekonformität liefert Intune.** Compliance-Richtlinie für Windows, macOS (und Linux, soweit ihr es
   verwaltet) anlegen, Geräte in Intune registrieren.
2. **Anwendung registrieren** (Entra → App-Registrierungen) als öffentlicher Client: Plattform „Mobil- und
   Desktopanwendungen“, Weiterleitungs-URI `http://127.0.0.1` (Entra ignoriert beim Loopback den Port), dazu den
   Mobil-Link. API-Berechtigungen `openid`, `profile`, `email`, `offline_access`.
3. **Bedingter Zugriff** (Entra → Schutz → Bedingter Zugriff → Neue Richtlinie):
   Zuweisung: die Gruppe der Vault-Nutzenden; Zielressourcen: die oben registrierte Anwendung;
   Zugriffssteuerung → Gewähren: „Gerät muss als konform markiert sein“ (bei Bedarf zusätzlich „Hybrid-eingebundenes
   Gerät“). Erst im Nur-Bericht-Modus laufen lassen.
4. **Browser muss die Geräteidentität weiterreichen.** Windows: Edge, oder Chrome mit der Erweiterung „Windows-Konten“.
   macOS: Microsoft Enterprise SSO Plug-in (Company Portal), sonst sieht Entra ein „unbekanntes“ Gerät. Wer einen
   anderen Standardbrowser nutzt, scheitert an der Bedingung. (Ungeprüft: Verhalten von Firefox und Linux.)
5. Die Prüfung läuft bei der Anmeldung und beim Einlösen des Refresh-Tokens. Ein Gerät, das konform war und es
   nicht mehr ist, scheitert spätestens beim nächsten Token-Refresh; das Plugin zeigt dann „Anmeldung abgelaufen“
   und stoppt den Sync. Sofortiger Entzug braucht „Continuous Access Evaluation“, das für diese Anwendung nicht
   zugesagt ist (ungeprüft). Die Lebensdauer des Access-Tokens setzt die Obergrenze.

### Okta

1. **Gerätevertrauen** einrichten: Okta Verify auf den Geräten, Integration mit Intune oder Jamf, in Okta unter
   Sicherheit → Gerätesicherung („Device Assurance“) eine Richtlinie je Plattform (Betriebssystem-Mindeststand,
   Festplattenverschlüsselung, Verwaltung durch MDM).
2. **Anwendung anlegen**: OIDC, „Native Application“, Grant „Authorization Code“ mit PKCE, Refresh-Token zulassen;
   Weiterleitungen `http://127.0.0.1:<Port>/…` (Okta verlangt die Loopback-Adresse ausdrücklich, ob ein
   beliebiger Port zulässig ist, in der Konsole prüfen) und den Mobil-Link.
3. **Authentifizierungsrichtlinie** der Anwendung (Anwendung → Anmelden → Authentifizierungsrichtlinie): Regel
   „Gerät ist registriert und verwaltet“ bzw. die Geräteabsicherung aus Schritt 1 als Bedingung, alle anderen Geräte
   werden verweigert. Zuerst eine Testgruppe.
4. Okta wertet die Regel bei der Anmeldung aus; Refresh-Tokens gelten bis zu ihrem Ablauf bzw. dem Widerruf. Wer
   sofort entziehen will, widerruft die Tokens der Person (Okta → Benutzer → Sitzungen und Tokens). Ungeprüft: ob die
   Regel beim Refresh erneut greift.

### Zusammenspiel mit der Serverrichtlinie (C)

A prüft **das Gerät beim Anmelden**, C prüft **die aktiven Plugins bei jeder Verbindung**. Beide ergänzen sich:
Ein konformes Gerät mit verbotenem Plugin bleibt draußen (C), ein unverwaltetes Gerät mit sauberem Obsidian
ebenfalls (A).

## B. Verwaltetes Gerät: `ops/managed-obsidian/`

Linux und macOS: `managed-obsidian.sh`, Windows: `managed-obsidian.ps1`. Aufruf als Administrator per MDM (Intune,
Jamf, Ansible) mit einer Richtliniendatei (Vorlage `policy.example.json`), als Timer alle paar Stunden zur
Selbstheilung. Beschreibung und Rechte: `ops/managed-obsidian/README.md`.

Zwei Dinge, die ihr wissen müsst:

- Dateirechte allein halten nicht: Die Einstellung „Konfigurationsordner überschreiben“ umgeht sie (ADR 0019,
  Punkt 3). Die verlässliche Sperre ist Obsidians eigene Richtlinie (`policy.json`), die das Werkzeug mit
  ausrollt. Sie gilt nur in der Ausgabe „Obsidian Work“ (oder mit `isWork` im Profil) und ist in der normalen
  Ausgabe wirkungslos.
- Prüfsummen: Ihr tragt die SHA-256-Werte von `main.js`, `manifest.json` und `styles.css` der freigegebenen
  Release ein. So installiert niemand etwas anderes, auch wenn ein Release später ersetzt wird.

## E. Netzwerk: Plugin-Quellen sperren

Wer Obsidian nur durch einen Proxy lässt, kann die Quellen für Community-Plugins schließen. Aus dem Programmcode von
Obsidian 1.15.0 (und durch eine echte Installation bestätigt):

| Zweck | Adresse |
|---|---|
| Katalog der Plugins | `https://raw.githubusercontent.com/obsidianmd/obsidian-releases/HEAD/community-plugins.json` |
| Statistiken, Themes | `…/obsidian-releases/HEAD/community-plugin-stats.json`, `…/community-css-themes.json` |
| Plugin-Dateien | `https://github.com/<Besitzer>/<Repo>/releases/download/<Tag>/{manifest.json,main.js,styles.css}` (leitet auf `*.githubusercontent.com` um) |

Regeln (Squid, Zscaler, Palo Alto o. Ä. sinngemäß):

1. `raw.githubusercontent.com/obsidianmd/obsidian-releases/*` **sperren**. Dann zeigt die Plugin-Suche in
   Obsidian nichts mehr an.
2. `github.com/*/releases/download/*` **sperren** und nur die freigegebenen Repos zulassen, z. B.
   `github.com/liamcain/obsidian-calendar-plugin/releases/download/*`. Die Umleitungsziele
   (`objects.githubusercontent.com`, `release-assets.githubusercontent.com`) müssen für diese Pfade erreichbar
   bleiben; sie lassen sich nicht nach Repo trennen, also die Regel auf der `github.com`-Ebene setzen.
3. StoneIntelligence selbst (`platform-api`, der Identitätsanbieter) bleibt frei.
4. TLS-Interception ist nötig, wenn ihr Pfade und nicht nur Hosts filtern wollt; sonst bleibt nur
   `raw.githubusercontent.com` als Ganzes.

Grenzen: Wer Dateien von Hand in `plugins/` legt, braucht das Netzwerk nicht; das fängt B ab. Der Proxy schützt
nur Geräte hinter dem Proxy, ein Heimnetz nicht.
