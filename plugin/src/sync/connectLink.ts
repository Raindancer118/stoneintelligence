/**
 * `obsidian://stoneintelligence-connect?stoneVault=<uuid>&name=<name>` - der letzte Schritt der
 * Einrichtungsseite im Web-Dashboard: verbindet diesen Obsidian-Vault mit einem gemeinsamen Vault,
 * ohne dass jemand eine ID abtippt. Der Parameter heisst bewusst NICHT `vault`: den wertet Obsidian
 * selbst als Namen des zu oeffnenden Obsidian-Vaults aus und verwirft den Link sonst.
 *
 * <p>Bewusst NUR Vault-Id und Anzeigename: Server- oder Anmeldeadressen aus einem Link zu
 * uebernehmen hiesse, dass ein praeparierter Link das Plugin auf einen fremden Server umleiten
 * koennte. Der Name dient nur der Anzeige - massgeblich ist, was der Server zur Id liefert.
 */
export const CONNECT_ACTION = "stoneintelligence-connect";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MAX_NAME_LENGTH = 120;

export interface ConnectLink {
  vaultId: string;
  vaultName: string | null;
}

export function parseConnectLink(params: Record<string, string | undefined>): ConnectLink | null {
  const vaultId = (params.stoneVault ?? "").trim().toLowerCase();
  if (!UUID.test(vaultId)) {
    return null;
  }
  const name = (params.name ?? "").trim();
  return { vaultId, vaultName: name ? name.slice(0, MAX_NAME_LENGTH) : null };
}
