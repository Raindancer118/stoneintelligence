import * as Y from "yjs";
import { CanvasLiveBinding } from "./canvasSync";
import type { SyncClient } from "./SyncClient";

/** Ein in Obsidian geoeffnetes Canvas; `readView` liefert den Inhalt der Ansicht samt Ungespeichertem (`null`: noch nicht geladen). */
export interface OpenCanvasView {
  path: string;
  readView(): string | null;
}

export interface CanvasLiveHost {
  openCanvases(): OpenCanvasView[];
  noteIdOf(path: string): string | undefined;
  /** Legt das Canvas auf dem Server an, falls es noch keine Id hat. */
  ensureNoteId(path: string): Promise<string | null>;
  isTrackable(path: string): boolean;
  readFile(path: string): Promise<string | null>;
  writeFile(path: string, content: string): Promise<void>;
  loadState(noteId: string): Promise<Uint8Array | null>;
  saveState(noteId: string, state: Uint8Array): Promise<void>;
  createClient(noteId: string, doc: Y.Doc): SyncClient;
  onProblem?(path: string, error: unknown): void;
  /** Nach dem Loesen: der Hintergrundabgleich soll das Canvas beim naechsten Durchlauf pruefen. */
  onDetached?(path: string, noteId: string): void;
}

interface Session {
  path: string;
  noteId: string;
  doc: Y.Doc;
  client: SyncClient;
  binding: CanvasLiveBinding;
  view: OpenCanvasView;
}

/**
 * Verbindet jedes geoeffnete Canvas mit seinem Yjs-Dokument (ADR 0018) und beobachtet die Ansicht
 * regelmaessig auf Aenderungen - Obsidian speichert ein Canvas erst nach einigen Sekunden, die
 * Ansicht kennt den Stand sofort. Geschlossene Canvas fallen zurueck in den Hintergrundabgleich.
 */
export class CanvasLiveManager {
  private readonly sessions = new Map<string, Session>();
  private readonly starting = new Set<string>();
  private pollTimer: ReturnType<typeof setInterval> | null = null;

  constructor(private readonly host: CanvasLiveHost, private readonly options: { pollMs?: number; remoteDebounceMs?: number } = {}) {}

  has(path: string): boolean {
    return this.sessions.has(path);
  }

  busy(path: string): boolean {
    return this.sessions.has(path) || this.starting.has(path);
  }

  paths(): string[] {
    return [...this.sessions.keys(), ...this.starting];
  }

  startPolling(): void {
    if (this.pollTimer === null) {
      this.pollTimer = setInterval(() => this.poll(), this.options.pollMs ?? 700);
    }
  }

  /** Bindet neu geoeffnete Canvas und loest geschlossene. */
  async refresh(): Promise<void> {
    const open = this.host.openCanvases().filter((view) => this.host.isTrackable(view.path));
    for (const path of [...this.sessions.keys()]) {
      const session = this.sessions.get(path)!;
      if (!open.some((view) => view.path === path)) {
        await this.detach(path);
      } else {
        session.view = open.find((view) => view.path === path)!;
      }
    }
    for (const view of open) {
      if (!this.busy(view.path)) {
        await this.start(view);
      }
    }
  }

  localChanged(path: string): Promise<void> {
    return this.sessions.get(path)?.binding.localChanged() ?? Promise.resolve();
  }

  /** Datei wurde umbenannt: die Sitzung folgt, die Id bleibt. */
  rename(from: string, to: string): void {
    const session = this.sessions.get(from);
    if (session) {
      this.sessions.delete(from);
      session.path = to;
      session.view = { ...session.view, path: to };
      this.sessions.set(to, session);
    }
  }

  async detach(path: string): Promise<void> {
    const session = this.sessions.get(path);
    if (!session) {
      return;
    }
    this.sessions.delete(path);
    session.client.disconnect();
    try {
      await session.binding.dispose();
    } finally {
      session.doc.destroy();
      this.host.onDetached?.(path, session.noteId);
    }
  }

  async detachAll(): Promise<void> {
    if (this.pollTimer !== null) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
    for (const path of [...this.sessions.keys()]) {
      await this.detach(path);
    }
  }

  private poll(): void {
    for (const [path, session] of this.sessions) {
      session.binding.localChanged().catch((error) => this.host.onProblem?.(path, error));
    }
  }

  private async start(view: OpenCanvasView): Promise<void> {
    const { path } = view;
    this.starting.add(path);
    try {
      const noteId = this.host.noteIdOf(path) ?? (await this.host.ensureNoteId(path));
      if (!noteId) {
        return;
      }
      const doc = new Y.Doc();
      const session: Session = { path, noteId, doc, view } as Session;
      const binding = new CanvasLiveBinding(doc, {
        readLocal: async () => session.view.readView() ?? this.host.readFile(session.path),
        writeLocal: (content) => this.host.writeFile(session.path, content),
        saveState: (state) => this.host.saveState(noteId, state),
      }, { remoteDebounceMs: this.options.remoteDebounceMs });
      session.binding = binding;
      await binding.attach(await this.host.loadState(noteId));
      const client = this.host.createClient(noteId, doc);
      session.client = client;
      client.awareness.setLocalState(null);
      client.onNoteDeleted = () => void this.detach(session.path);
      const stillOpen = this.host.openCanvases().some((candidate) => candidate.path === path)
        && this.host.noteIdOf(path) === noteId;
      if (!stillOpen || this.sessions.has(path)) {
        await binding.dispose();
        doc.destroy();
        return;
      }
      client.connect();
      this.sessions.set(path, session);
    } catch (error) {
      this.host.onProblem?.(path, error);
    } finally {
      this.starting.delete(path);
    }
  }
}
