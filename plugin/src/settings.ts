import type { StoredTokens } from "./sync/AuthentikAuthClient";
import type { ConnectLink } from "./sync/connectLink";
import type { FileMeta } from "./sync/filePlan";
import type { NoteMeta } from "./sync/reconcilePlan";

/** Eine lokal ausgefuehrte Loeschung/Umbenennung, die der Server noch nicht bestaetigt hat. */
export type PendingOp = NoteOp | FolderOp;
export type NoteOp =
  | { kind: "delete"; noteId: string; path: string; operationId: string }
  | { kind: "rename"; noteId: string; path: string };
/** Ordner sind eigene Objekte auf dem Server - auch leere und geloeschte muessen ueberall ankommen. */
export type FolderOp =
  | { kind: "folderCreate"; path: string }
  | { kind: "folderDelete"; path: string }
  | { kind: "folderRename"; path: string; to: string };

export function isNoteOp(op: PendingOp): op is NoteOp {
  return op.kind === "delete" || op.kind === "rename";
}

/** Alles, was zu EINEM Vault gehoert - ein Vault-Wechsel darf keine fremden NoteIds weiterverwenden. */
export interface VaultSyncState {
  /** Pfad -> NoteId. */
  noteIds: Record<string, string>;
  /** NoteId -> Stand beim letzten erfolgreichen Abgleich. */
  noteMeta: Record<string, NoteMeta>;
  pendingOps: PendingOp[];
  /** Pfad -> Grund, warum der Server ihn abgelehnt hat (bis zur naechsten lokalen Aenderung). */
  blockedPaths: Record<string, string>;
  /** Server-Ordner beim letzten Ordnerabgleich; `null` = noch nie abgeglichen. */
  knownFolders: string[] | null;
  /** Pfad -> Datei-Id (PDFs, Bilder, Anhaenge - ADR 0009); getrennt von den Notizen. */
  fileIds: Record<string, string>;
  /** Datei-Id -> Stand beim letzten Abgleich (Fassung, Hash, Stat). */
  fileMeta: Record<string, FileMeta>;
}

export interface StoneIntelligenceSettings {
  platformApiUrl: string;
  /** Leer = aus der API-URL abgeleitet (https -> wss). */
  platformWsUrl: string;
  vaultId: string;
  /** Nur zur Anzeige zwischengespeichert. */
  vaultName: string;
  oidcIssuerUrl: string;
  oidcClientId: string;
  tokens: StoredTokens | null;
  /**
   * Zwischengespeicherter Anzeigename aus Authentiks `userinfo` - Label des eigenen Cursors bei
   * allen anderen. Bewusst persistiert: er wird einmal nach dem Login aufgeloest, damit das
   * Anlegen einer Sync-Session keinen Netzwerkaufruf braucht.
   */
  displayName: string | null;
  paused: boolean;
  /** Ordner, die nie synchronisiert werden (Praefix-Vergleich auf ganze Ordnernamen). */
  excludedFolders: string[];
  vaults: Record<string, VaultSyncState>;
  /** Von einem anderen Vault angelegt: beim ersten Start mit diesem gemeinsamen Vault verbinden. */
  pendingConnect: ConnectLink | null;
  /** Eigenschaften einmalig ausgeblendet (s. propertiesDisplay) - danach entscheidet die Person. */
  propertiesDefaultApplied: boolean;
}

/**
 * Die gehostete Instanz als Voreinstellung: Wer das Plugin installiert, soll sich direkt anmelden
 * koennen, statt fuenf Felder aus der Webapp abzuschreiben. Alles bleibt unter "Erweitert"
 * ueberschreibbar. Die Client-ID ist ein oeffentlicher PKCE-Client (kein Secret).
 */
export const DEFAULT_SETTINGS: StoneIntelligenceSettings = {
  platformApiUrl: "https://stoneintelligence.tstieh.de",
  platformWsUrl: "",
  vaultId: "",
  vaultName: "",
  oidcIssuerUrl: "https://portal.tstieh.de/application/o/stoneintelligence/",
  oidcClientId: "Tij1T4pH0BYGw55MU55R9AzybAAoMPlSs7n43nOo",
  tokens: null,
  displayName: null,
  paused: false,
  excludedFolders: [],
  vaults: {},
  pendingConnect: null,
  propertiesDefaultApplied: false,
};

export function emptyVaultState(): VaultSyncState {
  return { noteIds: {}, noteMeta: {}, pendingOps: [], blockedPaths: {}, knownFolders: [], fileIds: {}, fileMeta: {} };
}

/** Laedt gespeicherte Daten beliebigen (auch alten) Formats in die aktuelle Struktur. */
export function migrateSettings(raw: unknown): StoneIntelligenceSettings {
  const stored = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>;
  const { noteIds: legacyNoteIds, ...rest } = stored;
  const settings: StoneIntelligenceSettings = {
    ...DEFAULT_SETTINGS,
    ...(rest as Partial<StoneIntelligenceSettings>),
    vaults: { ...((rest.vaults as Record<string, VaultSyncState> | undefined) ?? {}) },
    excludedFolders: [...((rest.excludedFolders as string[] | undefined) ?? [])],
  };

  // Frueher waren localhost-Werte der Default und wurden beim ersten Speichern mitgeschrieben -
  // ohne konfigurierten Vault wurden sie nie benutzt.
  if (!settings.vaultId && settings.platformApiUrl === "http://localhost:8080") {
    settings.platformApiUrl = DEFAULT_SETTINGS.platformApiUrl;
    settings.platformWsUrl = "";
    settings.oidcIssuerUrl ||= DEFAULT_SETTINGS.oidcIssuerUrl;
    settings.oidcClientId ||= DEFAULT_SETTINGS.oidcClientId;
  }

  if (legacyNoteIds && typeof legacyNoteIds === "object" && settings.vaultId) {
    const state = settings.vaults[settings.vaultId] ?? emptyVaultState();
    state.noteIds = { ...(legacyNoteIds as Record<string, string>), ...state.noteIds };
    settings.vaults[settings.vaultId] = state;
  }
  for (const [vaultId, state] of Object.entries(settings.vaults)) {
    // Vor der Ordner-Synchronisation gespeichert: noch nie abgeglichen (`null`, s. planFolders).
    settings.vaults[vaultId] = { ...emptyVaultState(), knownFolders: null, ...(state as Partial<VaultSyncState>) };
  }
  return settings;
}

export function wsUrlFor(settings: Pick<StoneIntelligenceSettings, "platformApiUrl" | "platformWsUrl">): string {
  if (settings.platformWsUrl.trim()) {
    return settings.platformWsUrl.trim().replace(/\/+$/, "");
  }
  return settings.platformApiUrl.trim().replace(/\/+$/, "").replace(/^http(s?):/i, "ws$1:");
}

export function isExcluded(path: string, folders: string[]): boolean {
  return folders.some((folder) => {
    const prefix = folder.trim().replace(/^\/+|\/+$/g, "");
    return prefix.length > 0 && path.startsWith(`${prefix}/`);
  });
}

/** Wie {@link isExcluded}, aber fuer einen Ordner: der ausgeschlossene Ordner selbst zaehlt mit. */
export function isExcludedFolder(path: string, folders: string[]): boolean {
  return isExcluded(`${path}/`, folders);
}

const insideFolder = (path: string, folder: string): boolean => path === folder || path.startsWith(`${folder}/`);

/** Merkt eine lokale Ordneroperation vor und zieht den bekannten Ordnerstand gleich mit. */
export function queueFolderOp(state: VaultSyncState, op: FolderOp): void {
  state.pendingOps.push(op);
  if (state.knownFolders === null || op.kind === "folderCreate") {
    return;
  }
  const moved = state.knownFolders.filter((path) => insideFolder(path, op.path));
  const rest = state.knownFolders.filter((path) => !insideFolder(path, op.path));
  state.knownFolders = op.kind === "folderRename"
    ? [...rest, ...moved.map((path) => op.to + path.slice(op.path.length))].sort()
    : rest;
}

export function queueRename(state: VaultSyncState, noteId: string, path: string): void {
  if (state.pendingOps.some((op) => op.kind === "delete" && op.noteId === noteId)) {
    return;
  }
  state.pendingOps = state.pendingOps.filter((op) => !(op.kind === "rename" && op.noteId === noteId));
  state.pendingOps.push({ kind: "rename", noteId, path });
}

export function queueDelete(state: VaultSyncState, noteId: string, path: string, operationId: string): void {
  state.pendingOps = state.pendingOps.filter((op) => !isNoteOp(op) || op.noteId !== noteId);
  state.pendingOps.push({ kind: "delete", noteId, path, operationId });
}
