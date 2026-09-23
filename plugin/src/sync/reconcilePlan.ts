/**
 * Reiner, Obsidian-freier Abgleichsplan zwischen Server-Bestand und lokalem Vault. Der Plan
 * beschreibt nur, WAS zu tun ist - ausgefuehrt wird er in main.ts. So ist die eigentliche
 * Entscheidungslogik (die frueher ueber mehrere Event-Handler verstreut und nur teilweise
 * vorhanden war) vollstaendig testbar.
 *
 * Grundlage ist IMMER eine vollstaendige Server-Liste (`complete === true`, Fehlerklasse 2) -
 * nur dann darf "fehlt auf dem Server" ueberhaupt als Signal gelten.
 */

export interface ServerNote {
  id: string;
  path: string;
  /** Hoechste Update-Sequenz auf dem Server; fehlt bei aelteren Servern -> immer abgleichen. */
  revision?: number;
}

export interface LocalFileInfo {
  path: string;
  mtime: number;
  size: number;
}

/** Stand der Datei und des Servers beim letzten erfolgreichen Abgleich dieser Notiz. */
export interface NoteMeta {
  revision: number;
  mtime: number;
  size: number;
}

export interface ReconcileInput {
  serverNotes: ServerNote[];
  localFiles: LocalFileInfo[];
  /** Pfad -> NoteId (lokale Zuordnung). */
  noteIds: Record<string, string>;
  /** NoteId -> letzter Abgleichsstand. */
  meta: Record<string, NoteMeta>;
  /** Notizen mit noch nicht zum Server uebertragener Loeschung/Umbenennung. */
  pendingNoteIds: Set<string>;
  /** Gerade in einem Editor geoeffnete Pfade - deren Inhalt synchronisiert die Live-Bindung. */
  livePaths: Set<string>;
  /** Pfade, die der Server abgelehnt hat (z. B. fehlende Rechte), bis zur naechsten lokalen Aenderung. */
  blockedPaths: Record<string, string>;
}

export type ReconcileAction =
  | { kind: "download"; noteId: string; path: string }
  | { kind: "adopt"; noteId: string; path: string }
  | { kind: "upload"; path: string }
  | { kind: "sync"; noteId: string; path: string; serverRevision: number | null }
  | { kind: "renameLocal"; noteId: string; from: string; to: string }
  | { kind: "checkMissing"; noteId: string; path: string; locallyChanged: boolean };

const FORBIDDEN_CHARACTERS = /[\\:*?"<>|\u0000-\u001f\u007f]/;

/** Spiegelt `NoteController.validatePath` - was der Server ablehnen wuerde, wird gar nicht erst versucht. */
export function isSyncablePath(path: string): boolean {
  if (!path.toLowerCase().endsWith(".md") || path.length > 1024 || path !== path.trim()) {
    return false;
  }
  if (FORBIDDEN_CHARACTERS.test(path)) {
    return false;
  }
  return path.split("/").every((segment) => segment.trim().length > 0 && !segment.startsWith("."));
}

function locallyChanged(file: LocalFileInfo, meta: NoteMeta | undefined): boolean {
  return !meta || meta.mtime !== file.mtime || meta.size !== file.size;
}

export function planReconciliation(input: ReconcileInput): ReconcileAction[] {
  const actions: ReconcileAction[] = [];
  const serverById = new Map(input.serverNotes.map((note) => [note.id, note]));
  const localByPath = new Map(input.localFiles.map((file) => [file.path, file]));
  const mappedIds = new Set(Object.values(input.noteIds));
  const mappedPaths = new Set(Object.keys(input.noteIds));
  const claimedLocalPaths = new Set<string>();

  for (const [path, noteId] of Object.entries(input.noteIds)) {
    if (input.pendingNoteIds.has(noteId)) {
      continue;
    }
    const server = serverById.get(noteId);
    const local = localByPath.get(path);
    const meta = input.meta[noteId];

    if (!server) {
      if (local) {
        actions.push({ kind: "checkMissing", noteId, path, locallyChanged: locallyChanged(local, meta) });
      }
      continue;
    }

    if (!local) {
      if (!localByPath.has(server.path)) {
        actions.push({ kind: "download", noteId, path: server.path });
      }
      continue;
    }

    if (server.path !== path) {
      if (!localByPath.has(server.path) && !mappedPaths.has(server.path)) {
        actions.push({ kind: "renameLocal", noteId, from: path, to: server.path });
      }
      continue;
    }

    if (input.livePaths.has(path)) {
      continue;
    }
    const revision = server.revision ?? null;
    if (revision === null || !meta || meta.revision !== revision || locallyChanged(local, meta)) {
      actions.push({ kind: "sync", noteId, path, serverRevision: revision });
    }
  }

  for (const server of input.serverNotes) {
    if (mappedIds.has(server.id) || input.pendingNoteIds.has(server.id)) {
      continue;
    }
    if (mappedPaths.has(server.path)) {
      // Eine ANDERE, bereits zugeordnete Notiz belegt diesen Pfad lokal - nicht ueberschreiben.
      continue;
    }
    if (localByPath.has(server.path)) {
      claimedLocalPaths.add(server.path);
      actions.push({ kind: "adopt", noteId: server.id, path: server.path });
    } else {
      actions.push({ kind: "download", noteId: server.id, path: server.path });
    }
  }

  for (const local of input.localFiles) {
    if (mappedPaths.has(local.path) || claimedLocalPaths.has(local.path)) {
      continue;
    }
    if (!isSyncablePath(local.path) || input.blockedPaths[local.path] !== undefined) {
      continue;
    }
    actions.push({ kind: "upload", path: local.path });
  }

  return actions;
}
