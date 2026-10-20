# Firmen-Login: Gruppen und SCIM

StoneIntelligence übernimmt Mitglieder aus dem Identitätsanbieter (ADR 0013, Schritt 5):

- **Gruppen verknüpfen.** Unter *Mitglieder & Rechte → Gruppen* (Webapp) bzw. *Verwaltung → Gruppen*
  (Obsidian) lässt sich jede Gruppe mit einer Gruppe des Logins verknüpfen. Wer dort Mitglied ist, ist es
  hier auch, ohne Einladung. Der Server merkt sich die Gruppen bei jeder Anmeldung und vergisst sie nach
  7 Tagen ohne Anmeldung.
- **SCIM 2.0** unter `/scim/v2` meldet Konten und Gruppen sofort. Für ein per SCIM bekanntes Konto gilt
  allein SCIM. Deaktivieren oder Löschen beendet jeden Zugriff sofort: API-Anfragen bekommen 401, offene
  Sync-Verbindungen schließen mit Code 4401.

Direkt eingetragene Mitglieder bleiben daneben bestehen. Mindestens eine verwaltende Person muss direkt
eingetragen sein; das stellt sicher, dass kein Vault durch eine Änderung im Login ohne Verwaltung dasteht.

## Einstellungen (`platform-api`)

| Variable | Standard | Bedeutung |
| :--- | :--- | :--- |
| `STONEINTELLIGENCE_OIDC_PRINCIPAL_CLAIM` | `preferred_username` | Claim, der die Person bezeichnet. Für Firmen ein unveränderlicher: `oid` (Entra ID) oder `sub`. |
| `STONEINTELLIGENCE_OIDC_GROUPS_CLAIM` | `groups` | Claim mit den Gruppen, auch als Pfad (`realm_access.roles`). |
| `STONEINTELLIGENCE_OIDC_NAME_CLAIM` | `name` | Anzeigename; fehlt er, gilt `preferred_username`. |
| `STONEINTELLIGENCE_SCIM_TOKEN` | leer (aus) | Bearer-Token für SCIM, mind. 32 Zeichen. Mehrere durch Komma, um ohne Ausfall zu wechseln. |
| `STONEINTELLIGENCE_SCIM_SUBJECT_ATTRIBUTE` | `userName` | SCIM-Attribut, das dem Principal-Claim entspricht: `userName` oder `externalId`. |
| `STONEINTELLIGENCE_SCIM_GROUP_KEY` | `displayName` | Was im Gruppen-Claim steht: `displayName` oder `externalId`. |

Token erzeugen: `openssl rand -base64 48`.

## Anbieter

**Microsoft Entra ID.** Der Gruppen-Claim enthält Objekt-IDs. Einstellungen: Principal `oid`, SCIM-Subject
`externalId`, Gruppen-Schlüssel `externalId`. In der Enterprise-App unter *Provisioning* die Mandanten-URL
`https://<api>/scim/v2` und das Token eintragen; in den Attributzuordnungen `objectId` → `externalId` für
Benutzer und Gruppen. Verknüpft wird mit der Objekt-ID der Gruppe.

**Okta.** Gruppen-Claim `groups` (Filter in der Autorisierungsserver-Regel), SCIM-App mit
*Push Groups*. Standardwerte passen, wenn der Principal `preferred_username` (= Okta-Benutzername) bleibt.

**Authentik.** Im Scope-Mapping ist `groups` mit den Gruppennamen enthalten. SCIM-Provider mit URL
`https://<api>/scim/v2` und dem Token anlegen. Standardwerte passen.

**Keycloak.** Gruppen-Mapper mit Claim `groups` (ohne vollen Pfad) oder Realm-Rollen über
`STONEINTELLIGENCE_OIDC_GROUPS_CLAIM=realm_access.roles`. Keycloak hat kein eingebautes SCIM; ohne SCIM
gilt nur der Gruppen-Claim.

## Was unterstützt wird

Users und Groups: anlegen, lesen, ersetzen (PUT), ändern (PATCH), löschen, Filter (`eq`, `ne`, `co`,
`sw`, `ew`, `gt`/`ge`/`lt`/`le`, `pr`, `and`/`or`/`not`, `members[value eq "…"]`), Seiten bis 1000,
`attributes`/`excludedAttributes`. Dazu `ServiceProviderConfig`, `ResourceTypes`, `Schemas`. Nicht
unterstützt: Bulk, Sortieren, ETags, `/Me`. Attribute ohne Bedeutung hier (E-Mails, Titel, Erweiterungen)
werden angenommen und übergangen.

Gelöschte Konten bleiben 30 Tage gesperrt und verschwinden dann; Name und externalId werden sofort
entfernt. Legt der IdP das Konto in dieser Zeit neu an, behält es seine ID.
