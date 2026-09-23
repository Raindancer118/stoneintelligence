import type { VaultSyncState } from "../settings";
import { type FileAction, type FileMeta, type LocalFile, planFiles, type ServerFile, isSyncableFilePath } from "./filePlan";
import { FileConflictError, HttpError, type NoteApiClient, type NoteStatus } from "./NoteApiClient";
import { conflictCopyPath } from "./noteContentSync";

/** Der Obsidian-Vault aus Sicht der Datei-Synchronisation - nur Dateien, die synchronisiert werden duerfen. */
export interface FileVaultPort {
  list(): LocalFile[];
  stat(path: string): { mtime: number; size: number } | null;
  read(path: string): Promise<ArrayBuffer>;
  /** Anlegen oder ersetzen, samt fehlender Ordner - ohne dass der Event zurueckgemeldet wird. */
  write(path: string, bytes: ArrayBuffer): Promise<void>;
  rename(from: string, to: string): Promise<void>;
  trash(path: string): Promise<void>;
}

export interface FileSyncPorts {
  vaultId(): string;
  vault: FileVaultPort;
  api: Pick<NoteApiClient, "createFile" | "uploadFile" | "downloadFile"> & {
    noteStatus(vaultId: string, id: string): Promise<NoteStatus>;
  };
  state(): VaultSyncState;
  save(): void;
  report(path: string, message: string): void;
  clearProblem(path: string): void;
  log(kind: "downloaded" | "uploaded" | "renamed" | "deleted" | "conflict", path: string, detail?: string): void;
  now(): Date;
  /** Anderswo geloescht, hier aber veraendert: nachfragen (behalten → {@link FileSync.keepAfterDeletion}). */
  askDeletionDecision(path: string): void;
  contentType(path: string): string;
}

const CONTENT_TYPES: Record<string, string> = {
  pdf: "application/pdf", png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif", webp: "image/webp",
  svg: "image/svg+xml", bmp: "image/bmp", avif: "image/avif", mp3: "audio/mpeg", wav: "audio/wav", ogg: "audio/ogg",
  m4a: "audio/mp4", flac: "audio/flac", mp4: "video/mp4", webm: "video/webm", mov: "video/quicktime", txt: "text/plain",
  csv: "text/csv", json: "application/json", canvas: "application/json", zip: "application/zip",
  docx: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  xlsx: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  pptx: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
};

/** Typ nach Endung - nur zur Anzeige im Dashboard, ausgeliefert wird ohnehin immer als Anhang. */
export function contentTypeFor(path: string): string {
  const name = path.slice(path.lastIndexOf("/") + 1);
  const dot = name.lastIndexOf(".");
  return (dot > 0 && CONTENT_TYPES[name.slice(dot + 1).toLowerCase()]) || "application/octet-stream";
}

export async function sha256Hex(bytes: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

/**
 * Fuehrt den Datei-Abgleich aus (ADR 0009): laedt hoch und herunter, benennt um, loescht - und
 * sorgt dafuer, dass bei gleichzeitigen Aenderungen keine Fassung verloren geht (Konfliktkopie).
 * Alle Operationen laufen nacheinander, damit ein Datei-Event nie mit einem Abgleich kollidiert.
 */
export class FileSync {
  private queue: Promise<unknown> = Promise.resolve();

  constructor(private readonly ports: FileSyncPorts) {}

  private serialized<T>(work: () => Promise<T>): Promise<T> {
    const run = this.queue.then(work, work);
    this.queue = run.catch(() => undefined);
    return run;
  }

  private get state(): VaultSyncState {
    return this.ports.state();
  }

  pathFor(id: string): string | null {
    for (const [path, mapped] of Object.entries(this.state.fileIds)) {
      if (mapped === id) {
        return path;
      }
    }
    return null;
  }

  isFileId(id: string): boolean {
    return this.pathFor(id) !== null;
  }

  reconcile(serverFiles: ServerFile[], maxFileBytes: number | null): Promise<void> {
    return this.serialized(async () => {
      const state = this.state;
      const plan = planFiles({
        serverFiles,
        localFiles: this.ports.vault.list().filter((file) => isSyncableFilePath(file.path)),
        fileIds: state.fileIds,
        meta: state.fileMeta,
        pendingIds: new Set(state.pendingOps.map((op) => ("noteId" in op ? op.noteId : ""))),
        blockedPaths: state.blockedPaths,
        maxFileBytes,
      });
      const server = new Map(serverFiles.map((file) => [file.id, file]));
      for (const action of plan) {
        await this.execute(action, server, maxFileBytes);
      }
      this.ports.save();
    });
  }

  /** Lokal angelegt oder geaendert (Obsidian-Event). */
  localChanged(path: string, maxFileBytes: number | null): Promise<void> {
    return this.serialized(async () => {
      if (!isSyncableFilePath(path) || this.state.blockedPaths[path] !== undefined) {
        return;
      }
      const id = this.state.fileIds[path];
      const stat = this.ports.vault.stat(path);
      if (!stat) {
        return;
      }
      if (maxFileBytes !== null && stat.size > maxFileBytes) {
        this.reportTooLarge(path, maxFileBytes);
        return;
      }
      await this.guarded(path, () => (id
        ? this.upload(id, path, this.state.fileMeta[id]?.revision ?? 0)
        : this.create(path)));
      this.ports.save();
    });
  }

  /** Lokal geloescht: Zuordnung loesen; liefert die Id fuer die Loeschung auf dem Server. */
  localDeleted(path: string): string | null {
    const id = this.state.fileIds[path];
    if (!id) {
      return null;
    }
    this.unmap(id);
    return id;
  }

  /** Lokal umbenannt/verschoben: Zuordnung mitnehmen; liefert die Id fuer den Server. */
  localRenamed(from: string, to: string): string | null {
    const id = this.state.fileIds[from];
    if (!id) {
      return null;
    }
    delete this.state.fileIds[from];
    this.state.fileIds[to] = id;
    return id;
  }

  /** Anderswo geloescht: in den Papierkorb, ausser die Datei wurde hier seitdem veraendert. */
  remoteDeleted(id: string): Promise<void> {
    return this.serialized(async () => {
      const path = this.pathFor(id);
      if (!path) {
        return;
      }
      const meta = this.state.fileMeta[id];
      this.unmap(id);
      this.ports.save();
      if (!this.ports.vault.stat(path)) {
        return;
      }
      if (await this.changedSince(path, meta)) {
        this.ports.askDeletionDecision(path);
        return;
      }
      await this.ports.vault.trash(path);
      this.ports.log("deleted", path, "Auf einem anderen Gerät gelöscht, im Papierkorb");
    });
  }

  /** Nach einer Loeschentscheidung "Behalten": als neue Datei hochladen. */
  keepAfterDeletion(path: string): Promise<void> {
    return this.serialized(async () => {
      const id = this.state.fileIds[path];
      if (id) {
        this.unmap(id);
      }
      await this.guarded(path, () => this.create(path));
      this.ports.save();
    });
  }

  private async execute(action: FileAction, server: Map<string, ServerFile>, maxFileBytes: number | null): Promise<void> {
    const path = action.kind === "renameLocal" ? action.from : action.path;
    await this.guarded(path, async () => {
      switch (action.kind) {
        case "download":
          return this.download(action.id, action.path);
        case "upload":
          return this.upload(action.id, action.path, action.baseRevision);
        case "create":
          return this.create(action.path);
        case "adopt":
          return this.adopt(action.id, action.path, server.get(action.id));
        case "conflict":
          return this.conflict(action.id, action.path, server.get(action.id));
        case "renameLocal":
          return this.renameLocal(action.id, action.from, action.to);
        case "checkMissing":
          return this.checkMissing(action.id, action.path);
        case "tooLarge":
          return this.reportTooLarge(action.path, maxFileBytes ?? action.size);
      }
    });
  }

  /** Fachliche Ablehnungen melden; Netzfehler still lassen - der naechste Abgleich versucht es erneut. */
  private async guarded(path: string, work: () => Promise<void>): Promise<void> {
    try {
      await work();
    } catch (error) {
      if (error instanceof HttpError && (error.status === 401 || error.status === 403)) {
        this.ports.report(path, "Keine Berechtigung für diese Datei.");
      } else if (error instanceof HttpError && (error.status === 413 || error.status === 507)) {
        this.ports.report(path, error.message);
      } else if (error instanceof HttpError && error.status >= 400 && error.status < 500 && error.status !== 409) {
        this.ports.report(path, `Server lehnte ab (HTTP ${error.status}).`);
      } else {
        console.debug(`StoneIntelligence: Datei "${path}" nicht abgeglichen`, error);
      }
    }
  }

  private async download(id: string, path: string): Promise<void> {
    const file = await this.ports.api.downloadFile(this.ports.vaultId(), id);
    await this.ports.vault.write(path, file.bytes);
    this.map(path, id);
    this.remember(id, path, file.revision, file.sha256);
    this.ports.clearProblem(path);
    this.ports.log("downloaded", path);
  }

  private async upload(id: string, path: string, baseRevision: number): Promise<void> {
    const bytes = await this.ports.vault.read(path);
    const sha256 = await sha256Hex(bytes);
    const meta = this.state.fileMeta[id];
    if (meta && meta.sha256 === sha256) {
      // Nur der Zeitstempel hat sich geaendert - nichts zu uebertragen.
      this.remember(id, path, meta.revision, sha256);
      return;
    }
    try {
      const uploaded = await this.ports.api.uploadFile(this.ports.vaultId(), id, baseRevision, bytes, this.ports.contentType(path));
      this.remember(id, path, uploaded.revision, uploaded.sha256);
      this.ports.clearProblem(path);
      this.ports.log("uploaded", path);
    } catch (error) {
      if (!(error instanceof FileConflictError)) {
        throw error;
      }
      await this.keepCopyAndTakeServer(id, path, bytes);
    }
  }

  private async create(path: string): Promise<void> {
    const id = await this.ports.api.createFile(this.ports.vaultId(), path);
    // Zuordnung VOR dem Hochladen: bricht es ab, laedt der naechste Abgleich auf Basis 0 hoch.
    this.map(path, id);
    this.ports.save();
    await this.upload(id, path, 0);
  }

  private async adopt(id: string, path: string, server: ServerFile | undefined): Promise<void> {
    const bytes = await this.ports.vault.read(path);
    const sha256 = await sha256Hex(bytes);
    this.map(path, id);
    if (!server || server.revision === 0) {
      await this.upload(id, path, 0);
    } else if (server.sha256 === sha256) {
      this.remember(id, path, server.revision, sha256);
    } else {
      await this.keepCopyAndTakeServer(id, path, bytes);
    }
  }

  private async conflict(id: string, path: string, server: ServerFile | undefined): Promise<void> {
    const bytes = await this.ports.vault.read(path);
    const sha256 = await sha256Hex(bytes);
    const meta = this.state.fileMeta[id];
    if (meta && meta.sha256 === sha256) {
      return this.download(id, path);
    }
    if (server && server.sha256 === sha256) {
      this.remember(id, path, server.revision, sha256);
      return;
    }
    await this.keepCopyAndTakeServer(id, path, bytes);
  }

  /** Beide Seiten geaendert: die eigene Fassung als Kopie behalten (und hochladen), die des Servers uebernehmen. */
  private async keepCopyAndTakeServer(id: string, path: string, localBytes: ArrayBuffer): Promise<void> {
    const copyPath = conflictCopyPath(path, this.ports.now(), (candidate) => this.ports.vault.stat(candidate) !== null);
    await this.ports.vault.write(copyPath, localBytes);
    this.ports.log("conflict", path, `Eigene Fassung als „${copyPath.split("/").pop()}“ behalten`);
    await this.download(id, path);
    await this.create(copyPath);
  }

  private async renameLocal(id: string, from: string, to: string): Promise<void> {
    await this.ports.vault.rename(from, to);
    delete this.state.fileIds[from];
    this.state.fileIds[to] = id;
    const stat = this.ports.vault.stat(to);
    const meta = this.state.fileMeta[id];
    if (stat && meta) {
      this.state.fileMeta[id] = { ...meta, mtime: stat.mtime, size: stat.size };
    }
    this.ports.log("renamed", to, `Umbenannt von „${from.split("/").pop()}“`);
  }

  private async checkMissing(id: string, path: string): Promise<void> {
    const status = await this.ports.api.noteStatus(this.ports.vaultId(), id);
    if (status === "deleted") {
      // Direkt, nicht ueber die Warteschlange - wir laufen bereits in ihr.
      const meta = this.state.fileMeta[id];
      this.unmap(id);
      if (await this.changedSince(path, meta)) {
        this.ports.askDeletionDecision(path);
      } else {
        await this.ports.vault.trash(path);
        this.ports.log("deleted", path, "Auf einem anderen Gerät gelöscht, im Papierkorb");
      }
    } else if (status === "forbidden") {
      this.unmap(id);
      this.state.blockedPaths[path] = "Kein Zugriff mehr auf diese Datei.";
      this.ports.report(path, "Kein Zugriff mehr auf diese Datei. Die lokale Datei bleibt unverändert.");
    }
  }

  /** Hier seit dem letzten Abgleich veraendert? Am Inhalt entschieden; ohne Stand gewinnt die Loeschung. */
  private async changedSince(path: string, meta: FileMeta | undefined): Promise<boolean> {
    const stat = this.ports.vault.stat(path);
    if (!meta || !stat || (stat.mtime === meta.mtime && stat.size === meta.size)) {
      return false;
    }
    return (await sha256Hex(await this.ports.vault.read(path))) !== meta.sha256;
  }

  private reportTooLarge(path: string, maxFileBytes: number): void {
    this.ports.report(path, `Datei ist größer als ${Math.round(maxFileBytes / (1024 * 1024))} MB – sie bleibt nur auf diesem Gerät.`);
  }

  private map(path: string, id: string): void {
    for (const [mapped, mappedId] of Object.entries(this.state.fileIds)) {
      if (mappedId === id && mapped !== path) {
        delete this.state.fileIds[mapped];
      }
    }
    this.state.fileIds[path] = id;
  }

  private unmap(id: string): void {
    for (const [path, mapped] of Object.entries(this.state.fileIds)) {
      if (mapped === id) {
        delete this.state.fileIds[path];
      }
    }
    delete this.state.fileMeta[id];
  }

  private remember(id: string, path: string, revision: number, sha256: string): void {
    const stat = this.ports.vault.stat(path);
    if (stat) {
      this.state.fileMeta[id] = { revision, sha256, mtime: stat.mtime, size: stat.size };
    }
  }
}
