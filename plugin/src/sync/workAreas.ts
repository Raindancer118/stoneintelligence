/**
 * Arbeitsbereiche eines Geraets in grossen Vaults (ADR 0013): gewaehlte Ordner plus angeheftete
 * Notizen ausserhalb davon. `null` = der ganze Vault (Bereiche aus). Rein und getestet.
 */

export interface AreaState {
  /** Geraete-Entscheidung; ohne sie gilt die Vorgabe des Vaults. */
  areasMode?: boolean;
  areas?: string[];
  /** Einzelne Notizen ausserhalb der Bereiche, die dieses Geraet trotzdem aktuell haelt. */
  pinned?: string[];
}

/** Mehr schickt das Plugin nicht mit (Server-Grenze 200). */
export const MAX_SCOPE = 150;
/** Passt auf keinen echten Pfad (Punkt-Pfade synchronisiert das Plugin nicht): "Bereiche an, aber keiner gewaehlt". */
export const NOTHING = ".stoneintelligence-keine-bereiche";

export function isAreasMode(state: AreaState, vaultSelectiveSync: boolean): boolean {
  return state.areasMode ?? vaultSelectiveSync;
}

export function effectiveScope(state: AreaState, vaultSelectiveSync: boolean): string[] | null {
  if (!isAreasMode(state, vaultSelectiveSync)) {
    return null;
  }
  const areas = outermost((state.areas ?? []).map(normalize).filter(Boolean));
  let pinned = outermost((state.pinned ?? []).map(normalize).filter((path) => path && !inAny(path, areas)));
  while (areas.length + pinned.length > MAX_SCOPE && pinned.some((path) => path.includes("/"))) {
    pinned = outermost(pinned.map((path) => (path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : path)))
      .filter((path) => !inAny(path, areas));
  }
  const scope = outermost([...areas, ...pinned]).slice(0, MAX_SCOPE);
  return scope.length === 0 ? [NOTHING] : scope;
}

export function inScope(path: string, scope: string[] | null): boolean {
  return scope === null || inAny(path, scope);
}

export function outOfScope(paths: string[], scope: string[] | null): string[] {
  return paths.filter((path) => !inScope(path, scope));
}

export function scopeKey(scope: string[] | null): string {
  return scope === null ? "*" : [...scope].sort().join("\n");
}

function inAny(path: string, areas: string[]): boolean {
  return areas.some((area) => path === area || path.startsWith(`${area}/`));
}

function outermost(paths: string[]): string[] {
  const sorted = [...new Set(paths)].sort();
  const result: string[] = [];
  for (const path of sorted) {
    if (!inAny(path, result)) {
      result.push(path);
    }
  }
  return result;
}

function normalize(path: string): string {
  return path.split("/").filter(Boolean).join("/");
}
