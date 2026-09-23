import type { TransportState } from "./multiplexedTransport";

/**
 * Was das Plugin gerade tut, in EINER Groesse zusammengefasst - Grundlage fuer Statusleiste,
 * Seitenleiste und Einstellungen. Vorher zeigte die Statusleiste "● 2/3" (Anzahl gerade gejointer
 * Notizen), was ohne Kenntnis der Architektur nichts aussagte.
 */
export type SyncPhase =
  | "notConfigured"
  | "signedOut"
  | "paused"
  | "syncing"
  | "connecting"
  | "offline"
  | "attention"
  | "synced";

export type ActivityKind =
  | "pulled"
  | "pushed"
  | "merged"
  | "conflict"
  | "downloaded"
  | "uploaded"
  | "renamed"
  | "deleted"
  | "kept"
  | "error";

export interface ActivityEntry {
  at: number;
  kind: ActivityKind;
  path: string;
  detail?: string;
}

/** Eine Entscheidung, die die Person zu einem Problem direkt in der Seitenleiste treffen kann. */
export interface ProblemAction {
  label: string;
  run: () => void;
}

export interface Problem {
  path: string;
  message: string;
  at: number;
  actions?: ProblemAction[];
}

interface Flags {
  configured: boolean;
  signedIn: boolean;
  paused: boolean;
  connection: TransportState;
}

const MAX_LOG_ENTRIES = 50;

export class SyncActivity {
  private flags: Flags = { configured: false, signedIn: false, paused: false, connection: "offline" };
  private pass: { done: number; total: number } | null = null;
  private lastSynced: number | null = null;
  private readonly problemsByPath = new Map<string, Problem>();
  private readonly entries: ActivityEntry[] = [];
  private readonly listeners = new Set<() => void>();

  constructor(private readonly now: () => number = Date.now) {}

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** Fuer Aenderungen ausserhalb dieses Modells, die Anzeigen trotzdem betreffen (z. B. Anwesenheit). */
  touch(): void {
    this.changed();
  }

  private changed(): void {
    for (const listener of this.listeners) {
      listener();
    }
  }

  update(flags: Partial<Flags>): void {
    const next = { ...this.flags, ...flags };
    const differs = (Object.keys(next) as Array<keyof Flags>).some((key) => next[key] !== this.flags[key]);
    if (differs) {
      this.flags = next;
      this.changed();
    }
  }

  get connection(): TransportState {
    return this.flags.connection;
  }

  beginPass(total: number): void {
    this.pass = { done: 0, total };
    this.changed();
  }

  advancePass(): void {
    if (this.pass) {
      this.pass.done = Math.min(this.pass.done + 1, this.pass.total);
      this.changed();
    }
  }

  endPass(succeeded = true): void {
    this.pass = null;
    if (succeeded) {
      this.lastSynced = this.now();
    }
    this.changed();
  }

  progress(): { done: number; total: number } | null {
    return this.pass ? { ...this.pass } : null;
  }

  lastSyncedAt(): number | null {
    return this.lastSynced;
  }

  reportProblem(path: string, message: string, actions?: ProblemAction[]): void {
    this.problemsByPath.set(path, { path, message, at: this.now(), ...(actions ? { actions } : {}) });
    this.changed();
  }

  clearProblem(path: string): void {
    if (this.problemsByPath.delete(path)) {
      this.changed();
    }
  }

  problems(): Problem[] {
    return [...this.problemsByPath.values()];
  }

  log(kind: ActivityKind, path: string, detail?: string): void {
    this.entries.unshift({ at: this.now(), kind, path, detail });
    this.entries.length = Math.min(this.entries.length, MAX_LOG_ENTRIES);
    this.changed();
  }

  recent(): ActivityEntry[] {
    return [...this.entries];
  }

  phase(): SyncPhase {
    if (!this.flags.configured) {
      return "notConfigured";
    }
    if (!this.flags.signedIn) {
      return "signedOut";
    }
    if (this.flags.paused) {
      return "paused";
    }
    if (this.pass) {
      return "syncing";
    }
    if (this.flags.connection === "offline") {
      return "offline";
    }
    if (this.flags.connection === "connecting") {
      return "connecting";
    }
    if (this.problemsByPath.size > 0) {
      return "attention";
    }
    return "synced";
  }
}

/** Kurze deutsche Relativzeit fuer Statusanzeigen ("vor 3 Min."). */
export function formatRelativeTime(at: number, now: number = Date.now()): string {
  const seconds = Math.max(0, Math.round((now - at) / 1000));
  if (seconds < 45) {
    return "gerade eben";
  }
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) {
    return `vor ${minutes} Min.`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return `vor ${hours} Std.`;
  }
  const days = Math.round(hours / 24);
  return days === 1 ? "gestern" : `vor ${days} Tagen`;
}
