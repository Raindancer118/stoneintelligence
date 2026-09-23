/**
 * Reiner Abgleichsplan fuer Dateien (PDFs, Bilder, Anhaenge - ADR 0009), analog zu
 * {@link planReconciliation} fuer Notizen. Statt eines Yjs-Zustands gibt es Fassungen: der
 * Server nummeriert sie, das Plugin merkt sich je Datei Fassung, Hash und Datei-Stat vom letzten
 * Abgleich. Der Plan entscheidet nur anhand von Stat und Fassung; ob sich der Inhalt wirklich
 * geaendert hat, prueft der Ausfuehrende am Hash, bevor er etwas hoch- oder herunterlaedt.
 */

export interface ServerFile {
  id: string;
  path: string;
  /** 0 = angelegt, Inhalt noch nicht hochgeladen. */
  revision: number;
  sha256: string | null;
}

export interface LocalFile {
  path: string;
  mtime: number;
  size: number;
}

/** Stand von Datei und Server beim letzten erfolgreichen Abgleich. */
export interface FileMeta {
  revision: number;
  sha256: string;
  mtime: number;
  size: number;
}

export interface FilePlanInput {
  serverFiles: ServerFile[];
  localFiles: LocalFile[];
  /** Pfad -> Datei-Id. */
  fileIds: Record<string, string>;
  /** Datei-Id -> Stand beim letzten Abgleich. */
  meta: Record<string, FileMeta>;
  /** Dateien mit noch nicht uebertragener Loeschung/Umbenennung. */
  pendingIds: Set<string>;
  blockedPaths: Record<string, string>;
  /** Grenze des Servers; `null` = unbekannt. */
  maxFileBytes: number | null;
}

export type FileAction =
  | { kind: "download"; id: string; path: string }
  | { kind: "upload"; id: string; path: string; baseRevision: number }
  | { kind: "create"; path: string }
  /** Gleicher Pfad hier und auf dem Server, noch nicht verknuepft - der Ausfuehrende vergleicht die Hashes. */
  | { kind: "adopt"; id: string; path: string }
  /** Hier UND auf dem Server geaendert - der Ausfuehrende prueft am Hash, ob es wirklich einer ist. */
  | { kind: "conflict"; id: string; path: string }
  | { kind: "renameLocal"; id: string; from: string; to: string }
  | { kind: "checkMissing"; id: string; path: string }
  | { kind: "tooLarge"; path: string; size: number };

const FORBIDDEN_CHARACTERS = /[\\:*?"<>|\u0000-\u001f\u007f]/;

/** Spiegelt `FilePaths.isValid` des Servers: keine Notiz, nichts Verstecktes, keine Sonderzeichen. */
export function isSyncableFilePath(path: string): boolean {
  if (!path || path.length > 1024 || path !== path.trim() || path.toLowerCase().endsWith(".md") || FORBIDDEN_CHARACTERS.test(path)) {
    return false;
  }
  return path.split("/").every((segment) => segment.trim().length > 0 && !segment.startsWith("."));
}

function statChanged(file: LocalFile, meta: FileMeta): boolean {
  return meta.mtime !== file.mtime || meta.size !== file.size;
}

export function planFiles(input: FilePlanInput): FileAction[] {
  const actions: FileAction[] = [];
  const serverById = new Map(input.serverFiles.map((file) => [file.id, file]));
  const serverByPath = new Map(input.serverFiles.map((file) => [file.path, file]));
  const localByPath = new Map(input.localFiles.map((file) => [file.path, file]));
  const mappedIds = new Set(Object.values(input.fileIds));
  const mappedPaths = new Set(Object.keys(input.fileIds));
  const claimed = new Set<string>();

  for (const [path, id] of Object.entries(input.fileIds)) {
    if (input.pendingIds.has(id) || input.blockedPaths[path] !== undefined) {
      continue;
    }
    const server = serverById.get(id);
    const local = localByPath.get(path);
    const meta = input.meta[id];
    if (!server) {
      if (local) {
        actions.push({ kind: "checkMissing", id, path });
      }
      continue;
    }
    if (server.path !== path) {
      if (local && !localByPath.has(server.path) && !mappedPaths.has(server.path)) {
        actions.push({ kind: "renameLocal", id, from: path, to: server.path });
      } else if (!local && localByPath.has(server.path) && !mappedPaths.has(server.path)) {
        // Schon am neuen Ort (unterbrochener Abgleich) - neu zuordnen statt ewig zu haengen.
        claimed.add(server.path);
        actions.push({ kind: "adopt", id, path: server.path });
      }
      continue;
    }
    if (!local) {
      if (server.revision > 0) {
        actions.push({ kind: "download", id, path });
      }
      continue;
    }
    if (!meta) {
      actions.push(server.revision > 0 ? { kind: "adopt", id, path } : { kind: "upload", id, path, baseRevision: 0 });
      continue;
    }
    const localChanged = statChanged(local, meta);
    const serverChanged = server.revision !== meta.revision;
    if (localChanged && serverChanged) {
      actions.push({ kind: "conflict", id, path });
    } else if (localChanged) {
      actions.push({ kind: "upload", id, path, baseRevision: meta.revision });
    } else if (serverChanged) {
      actions.push({ kind: "download", id, path });
    }
  }

  for (const server of input.serverFiles) {
    if (mappedIds.has(server.id) || input.pendingIds.has(server.id) || mappedPaths.has(server.path)) {
      continue;
    }
    if (localByPath.has(server.path)) {
      claimed.add(server.path);
      actions.push({ kind: "adopt", id: server.id, path: server.path });
    } else if (server.revision > 0) {
      actions.push({ kind: "download", id: server.id, path: server.path });
    }
  }

  for (const local of input.localFiles) {
    if (mappedPaths.has(local.path) || claimed.has(local.path) || serverByPath.has(local.path)) {
      continue;
    }
    if (!isSyncableFilePath(local.path) || input.blockedPaths[local.path] !== undefined) {
      continue;
    }
    if (input.maxFileBytes !== null && local.size > input.maxFileBytes) {
      actions.push({ kind: "tooLarge", path: local.path, size: local.size });
    } else {
      actions.push({ kind: "create", path: local.path });
    }
  }
  return actions;
}
