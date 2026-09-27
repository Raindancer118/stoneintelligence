/**
 * `obsidian://stoneintelligence-connect?stoneVault=<uuid>&name=<name>[&server=&ws=&issuer=&client=]` - der letzte Schritt der
 * Einrichtungsseite im Web-Dashboard: verbindet diesen Obsidian-Vault mit einem gemeinsamen Vault,
 * ohne dass jemand eine ID abtippt. Der Parameter heisst bewusst NICHT `vault`: den wertet Obsidian
 * selbst als Namen des zu oeffnenden Obsidian-Vaults aus und verwirft den Link sonst.
 *
 * <p>Server- und Anmeldeadressen nennt die Einrichtungsseite, damit auch selbst gehostete Instanzen
 * funktionieren. Ein praeparierter Link koennte damit auf einen fremden Server zeigen - deshalb
 * uebernimmt das Plugin einen anderen Server nie still, sondern nennt ihn im Verbinden-Dialog
 * (`serverSwitchWarning`) und verwirft beim Wechsel die alte Anmeldung (`applyServer`).
 * Der Name dient nur der Anzeige - massgeblich ist, was der Server zur Id liefert.
 */
export const CONNECT_ACTION = "stoneintelligence-connect";

import type { ServerSettings } from "../settings";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MAX_NAME_LENGTH = 120;

export interface ConnectLink {
  vaultId: string;
  vaultName: string | null;
  /** Server der Einrichtungsseite; null/fehlend (aeltere Links) = der eingestellte Server. */
  server?: ServerSettings | null;
}

const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);
const CLIENT_ID = /^[\x21-\x7e]{1,200}$/;

/** Nur https/wss - unverschluesselt allein auf diesem Rechner (Entwicklung). Keine Zugangsdaten in der URL. */
function safeUrl(value: string, secure: string, plain: string): boolean {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return false;
  }
  if (url.username || url.password) {
    return false;
  }
  return url.protocol === secure || (url.protocol === plain && LOCAL_HOSTS.has(url.hostname));
}

/** undefined = der Link nennt keinen Server; null = er nennt einen, aber unvollstaendig oder ungueltig. */
function parseServer(params: Record<string, string | undefined>): ServerSettings | null | undefined {
  const server = (params.server ?? "").trim();
  const ws = (params.ws ?? "").trim();
  const issuer = (params.issuer ?? "").trim();
  const client = (params.client ?? "").trim();
  if (!server && !ws && !issuer && !client) {
    return undefined;
  }
  const valid = safeUrl(server, "https:", "http:")
    && safeUrl(issuer, "https:", "http:")
    && (!ws || safeUrl(ws, "wss:", "ws:"))
    && CLIENT_ID.test(client);
  return valid ? { platformApiUrl: server, platformWsUrl: ws, oidcIssuerUrl: issuer, oidcClientId: client } : null;
}

export function parseConnectLink(params: Record<string, string | undefined>): ConnectLink | null {
  const vaultId = (params.stoneVault ?? "").trim().toLowerCase();
  if (!UUID.test(vaultId)) {
    return null;
  }
  const server = parseServer(params);
  if (server === null) {
    return null;
  }
  const name = (params.name ?? "").trim();
  return { vaultId, vaultName: name ? name.slice(0, MAX_NAME_LENGTH) : null, server: server ?? null };
}
