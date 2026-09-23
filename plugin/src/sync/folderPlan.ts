/**
 * Reiner Abgleichsplan fuer Ordner (Server-Liste gegen lokale Ordner). Ordner sind eigene Objekte
 * auf dem Server: leere Ordner kommen ueberall an, und ein anderswo geloeschter Ordner verschwindet
 * hier - aber erst, wenn er leer ist. Was darin nicht synchronisiert wird (Anhaenge, behaltene
 * Notizen), wird nie mit geloescht.
 */

export interface LocalFolder {
  path: string;
  /** Liegt irgendwo darin (auch in Unterordnern) noch eine Datei? */
  hasFiles: boolean;
}

export interface FolderPlanInput {
  serverFolders: string[];
  /** Server-Stand beim letzten Ordnerabgleich; `null` = noch nie abgeglichen (erstes Mal nach dem Update). */
  knownFolders: string[] | null;
  localFolders: LocalFolder[];
  /** Ordner, zu denen noch eine lokale Operation aussteht - bis dahin nicht anfassen. */
  busyPaths: string[];
}

export interface FolderPlan {
  /** Auf dem Server, hier nicht: anlegen (Eltern zuerst). */
  createLocal: string[];
  /** Hier neu: auf dem Server anlegen. */
  upload: string[];
  /** Anderswo geloescht und hier leer: entfernen (tiefste zuerst). */
  removeLocal: string[];
  /** Neuer bekannter Stand fuer den naechsten Abgleich. */
  known: string[];
}

const FORBIDDEN_CHARACTERS = /[\\:*?"<>|\u0000-\u001f\u007f]/;

/** Spiegelt `FolderPaths.isValid` des Servers. */
export function isSyncableFolderPath(path: string): boolean {
  if (!path || path.length > 1024 || path !== path.trim() || FORBIDDEN_CHARACTERS.test(path)) {
    return false;
  }
  return path.split("/").every((segment) => segment.trim().length > 0 && !segment.startsWith("."));
}

export function isInsideFolder(path: string, folder: string): boolean {
  return path === folder || path.startsWith(`${folder}/`);
}

const depth = (path: string): number => path.split("/").length;
const shallowFirst = (a: string, b: string): number => depth(a) - depth(b) || a.localeCompare(b);

export function planFolders(input: FolderPlanInput): FolderPlan {
  const server = new Set(input.serverFolders);
  const known = input.knownFolders === null ? null : new Set(input.knownFolders);
  const local = new Map(input.localFolders.map((folder) => [folder.path, folder]));
  const busy = (path: string): boolean => input.busyPaths.some((op) => isInsideFolder(path, op));

  const createLocal = input.serverFolders.filter((path) => !local.has(path) && !busy(path)).sort(shallowFirst);
  const upload: string[] = [];
  const removeLocal: string[] = [];
  const waiting: string[] = [];

  for (const folder of input.localFolders) {
    if (server.has(folder.path) || busy(folder.path)) {
      continue;
    }
    const deletedElsewhere = known === null ? !folder.hasFiles : known.has(folder.path);
    if (!deletedElsewhere) {
      upload.push(folder.path);
      continue;
    }
    const subfolderStillShared = input.serverFolders.some((path) => isInsideFolder(path, folder.path));
    if (folder.hasFiles || subfolderStillShared) {
      waiting.push(folder.path);
    } else {
      removeLocal.push(folder.path);
    }
  }

  return {
    createLocal,
    upload: upload.sort(shallowFirst),
    removeLocal: removeLocal.sort((a, b) => shallowFirst(b, a)),
    known: [...new Set([...input.serverFolders, ...upload, ...(known === null ? [] : waiting)])].sort(),
  };
}
