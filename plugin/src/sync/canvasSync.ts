import { debugLog } from "../debugLog";
import * as Y from "yjs";
import {
  applyCanvasChange, canvasEquals, canvasOfDoc, parseCanvas, serializeCanvas, type CanvasData, type CanvasEntry,
} from "./canvasDoc";
import type { ContentSyncPorts, ContentSyncResult } from "./noteContentSync";

const isEmpty = (canvas: CanvasData) => canvas.nodes.length === 0 && canvas.edges.length === 0;

/** Canvas eines gespeicherten Yjs-Zustands (`null`, wenn es keinen gibt) - Gegenstueck zu `textOfState`. */
export function canvasOfState(state: Uint8Array | null): CanvasData | null {
  if (!state) {
    return null;
  }
  const doc = new Y.Doc();
  try {
    Y.applyUpdate(doc, state);
    return canvasOfDoc(doc);
  } finally {
    doc.destroy();
  }
}

/**
 * Nur lesen duerfen: wurde das Canvas trotzdem veraendert, landet die Aenderung als Kopie daneben
 * und die Datei geht auf den zuletzt abgeglichenen Stand zurueck (wie `revertReadOnlyEdit`).
 */
export async function revertReadOnlyCanvasEdit(
  ports: Pick<ContentSyncPorts, "loadState" | "readFile" | "writeFile" | "writeConflictCopy">,
  noteId: string,
  path: string,
): Promise<string | null> {
  const lastSynced = canvasOfState(await ports.loadState(noteId));
  const raw = await ports.readFile(path);
  const current = parseCanvas(raw);
  if (!lastSynced || !current || canvasEquals(lastSynced, current)) {
    return null;
  }
  const copy = await ports.writeConflictCopy(path, raw);
  await ports.writeFile(path, serializeCanvas(lastSynced));
  return copy;
}

/**
 * Einmaliger Abgleich eines (nicht geoeffneten) Canvas, gleicher Ablauf wie `syncNoteContent`:
 * lokale Aenderungen seit dem letzten Abgleich werden als Differenz ins Dokument uebernommen,
 * dann der Server-Stand eingeholt und das Ergebnis zurueck in die Datei geschrieben. Beim
 * Erstkontakt wird vereinigt statt eine Seite zu verwerfen - Karten sind unabhaengige Eintraege.
 */
export async function syncCanvasContent(ports: ContentSyncPorts, noteId: string, path: string): Promise<ContentSyncResult> {
  const raw = await ports.readFile(path);
  const local = parseCanvas(raw);
  if (!local) {
    return { outcome: "invalid" };
  }
  const doc = new Y.Doc();
  try {
    const state = await ports.loadState(noteId);
    if (state) {
      Y.applyUpdate(doc, state);
    }
    const base = state ? canvasOfDoc(doc) : null;
    const localChanged = base !== null && !canvasEquals(base, local);
    if (localChanged) {
      applyCanvasChange(doc, base, local);
    }
    const connection = await ports.connect(noteId, doc);
    if (!connection) {
      if (localChanged) {
        await ports.saveState(noteId, Y.encodeStateAsUpdate(doc));
      }
      return { outcome: "offline", pendingLocalChanges: localChanged };
    }
    try {
      let result: ContentSyncResult;
      if (!base) {
        const server = canvasOfDoc(doc);
        applyCanvasChange(doc, null, local);
        const merged = canvasOfDoc(doc);
        if (canvasEquals(merged, local)) {
          result = { outcome: isEmpty(server) && !isEmpty(local) ? "pushed" : "unchanged" };
        } else {
          await ports.writeFile(path, serializeCanvas(merged));
          result = { outcome: isEmpty(local) ? "pulled" : "merged" };
        }
      } else {
        const merged = canvasOfDoc(doc);
        if (!canvasEquals(merged, local)) {
          await ports.writeFile(path, serializeCanvas(merged));
          result = { outcome: localChanged ? "merged" : "pulled" };
        } else {
          result = { outcome: localChanged ? "pushed" : "unchanged" };
        }
      }
      await ports.saveState(noteId, Y.encodeStateAsUpdate(doc));
      return result;
    } finally {
      connection.disconnect();
    }
  } finally {
    doc.destroy();
  }
}

export interface CanvasLivePorts {
  /** Aktueller Inhalt - bei geoeffneter Ansicht samt noch nicht gespeicherter Aenderungen. */
  readLocal(): Promise<string | null>;
  /** Schreibt den zusammengefuehrten Stand in die Datei (Obsidian laedt die Ansicht daraus neu). */
  writeLocal(content: string): Promise<void>;
  saveState(state: Uint8Array): Promise<void>;
}

export interface CanvasLiveOptions {
  remoteDebounceMs?: number;
  saveDebounceMs?: number;
}

const LOCAL = Symbol("canvas-local");

/** Wie viele Karten und Kanten sich zwischen zwei Staenden unterscheiden. */
function distance(a: CanvasData, b: CanvasData): number {
  const count = (left: CanvasEntry[], right: CanvasEntry[]) => {
    const other = new Map(right.map((entry) => [entry.id, JSON.stringify(entry)]));
    const changed = left.filter((entry) => other.get(entry.id) !== JSON.stringify(entry)).length;
    return changed + right.filter((entry) => !left.some((candidate) => candidate.id === entry.id)).length;
  };
  return count(a.nodes, b.nodes) + count(a.edges, b.edges);
}

/**
 * Haelt ein geoeffnetes Canvas mit seinem Yjs-Dokument im Gleichlauf (ADR 0018): Aenderungen der
 * Ansicht/Datei gehen als Differenz zum letzten bekannten Stand ins Dokument, Aenderungen anderer
 * kommen gebuendelt als zusammengefuehrte Datei zurueck. Noch nicht gespeicherte Ansichts-Aenderungen
 * werden vor dem Schreiben eingelesen, damit sie nicht ueberschrieben werden.
 */
export class CanvasLiveBinding {
  /** Stand, auf dem die letzte Aenderung der Ansicht aufbaut. */
  private known: CanvasData = { nodes: [], edges: [] };
  /**
   * Zuletzt zurueckgeschriebener Stand. Zur Basis wird er erst, wenn die Ansicht ihn auch zeigt -
   * laedt sie nicht neu, darf ihr veralteter Inhalt nicht als Loeschung fremder Aenderungen gelten.
   */
  private pendingWrite: CanvasData | null = null;
  private pendingRaw: string | null = null;
  private lastRaw: string | null = null;
  private queue: Promise<unknown> = Promise.resolve();
  private remoteTimer: ReturnType<typeof setTimeout> | null = null;
  private saveTimer: ReturnType<typeof setTimeout> | null = null;
  private disposed = false;
  private readonly onUpdate = (_update: Uint8Array, origin: unknown): void => {
    this.scheduleSave();
    if (origin !== LOCAL) {
      this.scheduleRemote();
    }
  };

  constructor(
    private readonly doc: Y.Doc,
    private readonly ports: CanvasLivePorts,
    private readonly options: CanvasLiveOptions = {},
  ) {}

  /** Vor dem Verbinden: gespeicherten Stand laden und lokale Aenderungen seit dem letzten Abgleich uebernehmen. */
  async attach(savedState: Uint8Array | null): Promise<void> {
    if (savedState) {
      Y.applyUpdate(this.doc, savedState, LOCAL);
    }
    const raw = await this.ports.readLocal();
    const local = raw === null ? null : parseCanvas(raw);
    if (local) {
      applyCanvasChange(this.doc, savedState ? canvasOfDoc(this.doc) : null, local, LOCAL);
      this.known = local;
      this.lastRaw = raw;
    }
    this.doc.on("update", this.onUpdate);
  }

  localChanged(): Promise<void> {
    return this.serialized(() => this.foldLocal());
  }

  async dispose(): Promise<void> {
    this.disposed = true;
    this.doc.off("update", this.onUpdate);
    for (const timer of [this.remoteTimer, this.saveTimer]) {
      if (timer !== null) {
        clearTimeout(timer);
      }
    }
    await this.queue.catch(() => undefined);
    await this.ports.saveState(Y.encodeStateAsUpdate(this.doc));
  }

  private serialized<T>(work: () => Promise<T>): Promise<T> {
    const run = this.queue.then(work, work);
    this.queue = run.catch(() => undefined);
    return run;
  }

  private async foldLocal(): Promise<void> {
    const raw = await this.ports.readLocal();
    if (raw === null) {
      return;
    }
    if (raw === this.lastRaw) {
      if (this.pendingWrite && raw === this.pendingRaw) {
        this.known = this.pendingWrite;
        this.pendingWrite = null;
      }
      return;
    }
    this.lastRaw = raw;
    const local = parseCanvas(raw);
    if (!local) {
      return;
    }
    if (this.pendingWrite && canvasEquals(local, this.pendingWrite)) {
      this.known = local;
      this.pendingWrite = null;
      return;
    }
    // Hat die Ansicht den zurueckgeschriebenen Stand schon geladen und die Person danach etwas geaendert,
    // baut die Aenderung auf ihm auf - sonst auf dem alten Stand, den die Ansicht noch zeigt.
    const reloaded = this.pendingWrite !== null && distance(local, this.pendingWrite) < distance(local, this.known);
    const base = reloaded ? this.pendingWrite! : this.known;
    if (canvasEquals(local, base)) {
      return;
    }
    applyCanvasChange(this.doc, base, local, LOCAL);
    this.known = local;
    if (reloaded) {
      this.pendingWrite = null;
    }
  }

  private scheduleRemote(): void {
    if (this.remoteTimer !== null || this.disposed) {
      return;
    }
    this.remoteTimer = setTimeout(() => {
      this.remoteTimer = null;
      this.serialized(() => this.flushRemote()).catch((error) => debugLog("StoneIntelligence: Canvas nicht geschrieben", error));
    }, this.options.remoteDebounceMs ?? 300);
  }

  private async flushRemote(): Promise<void> {
    if (this.disposed) {
      return;
    }
    await this.foldLocal();
    const merged = canvasOfDoc(this.doc);
    if (canvasEquals(merged, this.pendingWrite ?? this.known)) {
      return;
    }
    const content = serializeCanvas(merged);
    this.pendingWrite = merged;
    this.pendingRaw = content;
    this.lastRaw = content;
    await this.ports.writeLocal(content);
  }

  private scheduleSave(): void {
    if (this.saveTimer !== null || this.disposed) {
      return;
    }
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null;
      this.ports.saveState(Y.encodeStateAsUpdate(this.doc)).catch(() => undefined);
    }, this.options.saveDebounceMs ?? 1500);
  }
}
