import type { ConnectLink } from "./connectLink";

/**
 * Verbinden-Link auf einem Geraet, auf dem es fuer den gemeinsamen Vault noch keinen Obsidian-Vault
 * gibt: statt den gerade offenen (oft privaten) Vault anzubinden und dessen Notizen hochzuladen,
 * wird ein neuer Obsidian-Vault angelegt - mit installiertem Plugin und dem Auftrag, sich beim
 * ersten Start mit dem gemeinsamen Vault zu verbinden. Hier nur die reine Planung; das Anlegen
 * selbst (Dateisystem, Obsidians Vault-Liste) steckt in `desktopVaults.ts`.
 */

export interface LocalVault {
  id: string;
  path: string;
  open: boolean;
}

const DEFAULT_FOLDER = "StoneIntelligence";

/**
 * Der lokale Obsidian-Vault, dessen Plugin bereits mit `stoneVaultId` verbunden ist - ausser dem
 * gerade offenen. `readPluginData` liefert den Inhalt von data.json oder null; Vaults, deren Daten
 * sich nicht lesen lassen, zaehlen als nicht verbunden.
 */
export function findLinkedVault(
  vaults: LocalVault[],
  currentPath: string,
  stoneVaultId: string,
  readPluginData: (vaultPath: string) => unknown,
): LocalVault | null {
  for (const vault of vaults) {
    if (vault.path === currentPath) {
      continue;
    }
    let data: unknown;
    try {
      data = readPluginData(vault.path);
    } catch {
      continue;
    }
    if (data && typeof data === "object" && (data as { vaultId?: unknown }).vaultId === stoneVaultId) {
      return vault;
    }
  }
  return null;
}

/** Ordnername aus dem Vault-Namen: ohne Zeichen, die ein Dateisystem ablehnt, nie versteckt, nie `..`. */
export function vaultFolderName(name: string | null): string {
  const cleaned = (name ?? "")
    // eslint-disable-next-line no-control-regex
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, " ")
    .replace(/\s+/g, " ")
    .replace(/^[.\s]+|[.\s]+$/g, "");
  return cleaned || DEFAULT_FOLDER;
}

/** Neuer Vault neben dem aktuellen, mit Nummer, falls der Ordner schon existiert. */
export function suggestVaultPath(currentPath: string, name: string | null, exists: (path: string) => boolean): string {
  const trimmed = currentPath.replace(/[\\/]+$/, "");
  const cut = Math.max(trimmed.lastIndexOf("/"), trimmed.lastIndexOf("\\"));
  return uniqueChildPath(cut > 0 ? trimmed.slice(0, cut) : trimmed, separatorOf(currentPath), name, exists);
}

/**
 * Ordner aus dem Ordner-Dialog: ein leerer Ordner wird selbst zum Vault (so legt man ihn im Dialog
 * typischerweise an), in einem Ordner mit Inhalt entsteht der Vault als Unterordner.
 */
export function vaultPathForPickedFolder(
  picked: string,
  pickedIsEmpty: boolean,
  name: string | null,
  exists: (path: string) => boolean,
): string {
  const trimmed = picked.length > 1 ? picked.replace(/[\\/]+$/, "") : picked;
  return pickedIsEmpty ? trimmed : uniqueChildPath(trimmed, separatorOf(picked), name, exists);
}

function separatorOf(path: string): string {
  return path.includes("\\") && !path.includes("/") ? "\\" : "/";
}

function uniqueChildPath(parent: string, separator: string, name: string | null, exists: (path: string) => boolean): string {
  const base = `${parent.endsWith(separator) ? parent : parent + separator}${vaultFolderName(name)}`;
  if (!exists(base)) {
    return base;
  }
  for (let n = 2; ; n++) {
    if (!exists(`${base} ${n}`)) {
      return `${base} ${n}`;
    }
  }
}

export interface NewVaultInput {
  pluginId: string;
  /** Dateiname -> Inhalt, aus dem Plugin-Ordner des aktuellen Vaults. */
  pluginFiles: Record<string, string>;
  settings: { platformApiUrl: string; platformWsUrl: string; oidcIssuerUrl: string; oidcClientId: string };
  link: ConnectLink;
}

/**
 * Dateien (relativ zum neuen Vault) fuer ein aktiviertes Plugin mit den Server-Einstellungen dieses
 * Geraets. Bewusst OHNE Tokens: Authentik rotiert Refresh-Tokens, zwei Vaults mit demselben Token
 * wuerden sich gegenseitig abmelden - der neue Vault meldet sich selbst an.
 */
export function newVaultFiles(input: NewVaultInput): Array<{ path: string; content: string }> {
  const pluginDir = `.obsidian/plugins/${input.pluginId}`;
  const { platformApiUrl, platformWsUrl, oidcIssuerUrl, oidcClientId } = input.settings;
  return [
    ...Object.entries(input.pluginFiles).map(([name, content]) => ({ path: `${pluginDir}/${name}`, content })),
    { path: ".obsidian/community-plugins.json", content: JSON.stringify([input.pluginId]) },
    {
      path: `${pluginDir}/data.json`,
      content: JSON.stringify({ platformApiUrl, platformWsUrl, oidcIssuerUrl, oidcClientId, pendingConnect: input.link }, null, 2),
    },
  ];
}
