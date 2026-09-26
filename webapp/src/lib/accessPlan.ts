/**
 * Reine Logik rund um Rechte je Ordner und Datei (ADR 0011): Voreinstellungen, Beschriftungen,
 * Kennzeichen im Dateibaum. Ohne Obsidian, damit sie getestet werden kann - die Oberflaeche
 * (ShareModal, main.ts) setzt sie nur zusammen.
 */

export type Permission = "READ" | "WRITE" | "CREATE" | "DELETE" | "MANAGE";
export type ScopeType = "USER" | "GROUP" | "EVERYONE";

export interface GrantTargetInfo { kind: "folder" | "entry"; path: string; noteId: string | null; }

/** Wie `AccessController.GrantResponse`; `permissions === null` heisst "wie im Vault". */
export interface Grant {
  id: string;
  target: GrantTargetInfo;
  scopeType: ScopeType;
  subject: string | null;
  groupName: string | null;
  permissions: Permission[] | null;
  inheritsVault: boolean;
}

export interface MemberAccess { subject: string; groups: string[]; permissions: Permission[]; source: Grant | null; }

export interface AccessReport {
  target: GrantTargetInfo;
  mine: { permissions: Permission[]; source: Grant | null };
  grants: Grant[];
  inherited: Grant[];
  members: MemberAccess[];
}

export type PresetId = "none" | "read" | "edit" | "full" | "inherit";

export const PRESETS: { id: PresetId; label: string; permissions: Permission[] | null }[] = [
  { id: "read", label: "Darf lesen", permissions: ["READ"] },
  { id: "edit", label: "Darf bearbeiten", permissions: ["READ", "WRITE", "CREATE", "DELETE"] },
  { id: "full", label: "Darf alles, auch Freigaben", permissions: ["READ", "WRITE", "CREATE", "DELETE", "MANAGE"] },
  { id: "none", label: "Kein Zugriff (ausblenden)", permissions: [] },
  { id: "inherit", label: "Wie im Vault", permissions: null },
];

const ORDER: Permission[] = ["READ", "WRITE", "CREATE", "DELETE", "MANAGE"];
const WORDS: Record<Permission, string> = {
  READ: "Lesen", WRITE: "Bearbeiten", CREATE: "Anlegen", DELETE: "Löschen", MANAGE: "Verwalten",
};

export function permissionsFor(preset: PresetId): Permission[] | null {
  return PRESETS.find((candidate) => candidate.id === preset)?.permissions ?? null;
}

export function presetOf(permissions: Permission[] | null): PresetId | "custom" {
  if (permissions === null) {
    return "inherit";
  }
  const key = sorted(permissions).join(",");
  return PRESETS.find((preset) => preset.permissions !== null && sorted(preset.permissions).join(",") === key)?.id ?? "custom";
}

export function permissionsLabel(permissions: Permission[] | null): string {
  if (permissions === null) {
    return "Wie im Vault";
  }
  return permissions.length === 0 ? "Kein Zugriff" : sorted(permissions).map((permission) => WORDS[permission]).join(", ");
}

export function describeSource(source: Grant | null): string {
  if (!source) {
    return "aus der Vault-Rolle";
  }
  if (source.target.kind === "entry") {
    return "Freigabe für diese Datei";
  }
  return source.target.path === "" ? "Freigabe für den ganzen Vault" : `Freigabe für Ordner „${source.target.path}“`;
}

export function describeScope(grant: Grant): string {
  switch (grant.scopeType) {
    case "EVERYONE":
      return "Alle Mitglieder";
    case "GROUP":
      return `Gruppe „${grant.groupName ?? grant.subject}“`;
    default:
      return grant.subject ?? "";
  }
}

/** Fehlt `permissions` (Server vor 0.24), wird nichts unterstellt. */
export function isReadOnly(permissions: Permission[] | undefined | null): boolean {
  return Array.isArray(permissions) && !permissions.includes("WRITE");
}

export type Badge = "readonly" | "shared";

export function badgeFor(entry: { permissions?: Permission[] | null; shared?: boolean | null }): Badge | null {
  if (isReadOnly(entry.permissions)) {
    return "readonly";
  }
  return entry.shared ? "shared" : null;
}

export function explainAccessError(status: number): string {
  switch (status) {
    case 400:
      return "Das geht nur für Mitglieder und Gruppen dieses Vaults.";
    case 403:
      return "Dafür hast du hier keine Berechtigung – oder du würdest mehr Rechte vergeben, als du selbst hast.";
    case 404:
      return "Diesen Ordner oder diese Datei gibt es auf dem Server nicht mehr.";
    case 409:
      return "Danach könnte niemand mehr den Vault verwalten – gib zuerst jemand anderem Verwalten-Rechte.";
    default:
      return `Der Server lehnte ab (HTTP ${status}).`;
  }
}

function sorted(permissions: Permission[]): Permission[] {
  return [...new Set(permissions)].sort((a, b) => ORDER.indexOf(a) - ORDER.indexOf(b));
}
