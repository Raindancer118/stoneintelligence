import { ItemView, setIcon, type WorkspaceLeaf } from "obsidian";
import { type ActivityEntry, type ActivityKind, formatRelativeTime, type SyncActivity } from "../sync/SyncActivity";
import { presentStatus, type StatusPresentation } from "./statusPresentation";

export const VIEW_TYPE_STATUS = "stoneintelligence-status";

export interface Collaborator {
  name: string;
  color: string;
}

export interface LiveNote {
  path: string;
  collaborators: Collaborator[];
}

/** Was die Seitenleiste vom Plugin braucht - haelt die View vom Plugin-Innenleben getrennt. */
export interface StatusViewHost {
  readonly activity: SyncActivity;
  accountName(): string | null;
  vaultName(): string | null;
  pendingCount(): number;
  linkedCount(): number;
  liveNotes(): LiveNote[];
  runStatusAction(action: StatusPresentation["action"]): void;
  syncNow(): void;
  setPaused(paused: boolean): void;
  isPaused(): boolean;
  openFile(path: string): void;
  openSettings(): void;
}

const ACTIVITY_TEXT: Record<ActivityKind, { icon: string; text: string }> = {
  pulled: { icon: "download", text: "Änderungen übernommen" },
  pushed: { icon: "upload", text: "Änderungen hochgeladen" },
  merged: { icon: "git-merge", text: "Zusammengeführt" },
  conflict: { icon: "copy", text: "Konfliktkopie angelegt" },
  downloaded: { icon: "file-down", text: "Neu vom Server" },
  uploaded: { icon: "file-up", text: "Neu hochgeladen" },
  renamed: { icon: "pencil", text: "Umbenannt" },
  deleted: { icon: "trash-2", text: "Gelöscht" },
  kept: { icon: "shield", text: "Lokal behalten" },
  error: { icon: "alert-triangle", text: "Fehler" },
};

/**
 * Seitenleiste "StoneIntelligence": oben der Zustand mit der passenden Aktion (anmelden,
 * synchronisieren, fortsetzen), darunter wer gerade in welcher offenen Notiz mitarbeitet, was
 * Aufmerksamkeit braucht und was zuletzt passiert ist - jeweils mit Klick zur Notiz.
 *
 * <p>Die fruehere Fassung listete die technischen Verbindungen je Notiz ("verbindet…",
 * "synchronisiert") und war die einzige Stelle, an der ueberhaupt etwas sichtbar wurde.
 */
export class StatusView extends ItemView {
  private unsubscribe: (() => void) | null = null;
  private renderQueued = false;

  constructor(
    leaf: WorkspaceLeaf,
    private readonly host: StatusViewHost,
  ) {
    super(leaf);
  }

  getViewType(): string {
    return VIEW_TYPE_STATUS;
  }

  getDisplayText(): string {
    return "StoneIntelligence";
  }

  getIcon(): string {
    return "refresh-cw";
  }

  async onOpen(): Promise<void> {
    this.unsubscribe = this.host.activity.subscribe(() => this.scheduleRender());
    // Relative Zeiten ("vor 2 Min.") und Anwesenheit anderer altern auch ohne Ereignis.
    this.registerInterval(window.setInterval(() => this.scheduleRender(), 15_000));
    this.render();
  }

  async onClose(): Promise<void> {
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  /** Buendelt Ereignis-Schauer (z. B. 200 Notizen in einem Durchlauf) zu einem Neuzeichnen je Frame. */
  scheduleRender(): void {
    if (this.renderQueued) {
      return;
    }
    this.renderQueued = true;
    window.requestAnimationFrame(() => {
      this.renderQueued = false;
      this.render();
    });
  }

  private render(): void {
    const container = this.containerEl.children[1] as HTMLElement;
    container.empty();
    container.addClass("stoneintelligence-panel");

    this.renderHeader(container);
    this.renderLiveNotes(container);
    this.renderProblems(container);
    this.renderActivity(container);
    this.renderFooter(container);
  }

  private renderHeader(container: HTMLElement): void {
    const status = presentStatus(this.host.activity, { pending: this.host.pendingCount() });
    const header = container.createDiv({ cls: `stoneintelligence-hero is-${status.tone}` });
    const iconEl = header.createDiv({ cls: "stoneintelligence-hero-icon" });
    setIcon(iconEl, status.icon);
    iconEl.toggleClass("is-spinning", status.spinning);
    const text = header.createDiv({ cls: "stoneintelligence-hero-text" });
    text.createDiv({ cls: "stoneintelligence-hero-label", text: status.label });
    text.createDiv({ cls: "stoneintelligence-hero-detail", text: status.tooltip });

    const actions = container.createDiv({ cls: "stoneintelligence-actions" });
    if (status.action === "settings") {
      this.button(actions, "Einrichten", "settings", () => this.host.openSettings(), true);
      return;
    }
    if (status.action === "login") {
      this.button(actions, "Anmelden", "log-in", () => this.host.runStatusAction("login"), true);
      return;
    }
    const phase = this.host.activity.phase();
    this.button(actions, "Jetzt synchronisieren", "refresh-cw", () => this.host.syncNow(), true, phase === "syncing");
    if (this.host.isPaused()) {
      this.button(actions, "Fortsetzen", "play", () => this.host.setPaused(false));
    } else {
      this.button(actions, "Pausieren", "pause", () => this.host.setPaused(true));
    }
  }

  private renderLiveNotes(container: HTMLElement): void {
    const notes = this.host.liveNotes();
    if (notes.length === 0) {
      return;
    }
    const section = this.section(container, "Geöffnet");
    for (const note of notes) {
      const row = this.fileRow(section, note.path, "file-text");
      const people = row.createDiv({ cls: "stoneintelligence-people" });
      if (note.collaborators.length === 0) {
        people.createSpan({ cls: "stoneintelligence-muted", text: "nur du" });
      }
      for (const person of note.collaborators) {
        const chip = people.createSpan({ cls: "stoneintelligence-person" });
        chip.style.setProperty("--si-person-color", person.color);
        chip.createSpan({ cls: "stoneintelligence-person-dot" });
        chip.createSpan({ text: person.name });
        chip.setAttr("aria-label", `${person.name} ist gerade in dieser Notiz`);
      }
    }
  }

  private renderProblems(container: HTMLElement): void {
    const problems = this.host.activity.problems();
    if (problems.length === 0) {
      return;
    }
    const section = this.section(container, "Braucht Aufmerksamkeit");
    for (const problem of problems) {
      const row = this.fileRow(section, problem.path, "alert-triangle");
      row.addClass("is-problem");
      row.createDiv({ cls: "stoneintelligence-row-detail", text: problem.message });
      if (problem.actions?.length) {
        const actions = row.createDiv({ cls: "stoneintelligence-row-actions" });
        for (const action of problem.actions) {
          const button = actions.createEl("button", { text: action.label });
          button.onclick = () => action.run();
        }
      }
    }
  }

  private renderActivity(container: HTMLElement): void {
    const entries = this.host.activity.recent().slice(0, 20);
    const section = this.section(container, "Letzte Aktivität");
    if (entries.length === 0) {
      section.createDiv({ cls: "stoneintelligence-muted stoneintelligence-empty", text: "Noch nichts passiert." });
      return;
    }
    for (const entry of entries) {
      this.activityRow(section, entry);
    }
  }

  private activityRow(section: HTMLElement, entry: ActivityEntry): void {
    const meta = ACTIVITY_TEXT[entry.kind];
    const row = this.fileRow(section, entry.path, meta.icon);
    row.createDiv({
      cls: "stoneintelligence-row-detail",
      text: `${entry.detail ?? meta.text} · ${formatRelativeTime(entry.at)}`,
    });
  }

  private renderFooter(container: HTMLElement): void {
    const footer = container.createDiv({ cls: "stoneintelligence-footer" });
    const account = this.host.accountName();
    const vault = this.host.vaultName();
    if (account) {
      footer.createDiv({ text: `Angemeldet als ${account}` });
    }
    if (vault) {
      footer.createDiv({ text: `Vault „${vault}“ · ${this.host.linkedCount()} Notizen verknüpft` });
    }
    const settings = footer.createEl("a", { text: "Einstellungen", href: "#" });
    settings.onclick = (event) => {
      event.preventDefault();
      this.host.openSettings();
    };
  }

  private section(container: HTMLElement, title: string): HTMLElement {
    const section = container.createDiv({ cls: "stoneintelligence-section" });
    section.createDiv({ cls: "stoneintelligence-section-title", text: title });
    return section;
  }

  private fileRow(section: HTMLElement, path: string, icon: string): HTMLElement {
    const row = section.createDiv({ cls: "stoneintelligence-row" });
    const head = row.createDiv({ cls: "stoneintelligence-row-head" });
    setIcon(head.createSpan({ cls: "stoneintelligence-row-icon" }), icon);
    const name = head.createEl("a", { cls: "stoneintelligence-row-name", text: basename(path), href: "#" });
    name.setAttr("aria-label", path);
    name.onclick = (event) => {
      event.preventDefault();
      this.host.openFile(path);
    };
    return row;
  }

  private button(parent: HTMLElement, label: string, icon: string, onClick: () => void, cta = false, disabled = false): void {
    const button = parent.createEl("button", { cls: cta ? "mod-cta" : "" });
    setIcon(button.createSpan({ cls: "stoneintelligence-button-icon" }), icon);
    button.createSpan({ text: label });
    button.disabled = disabled;
    button.onclick = onClick;
  }
}

function basename(path: string): string {
  return (path.split("/").pop() ?? path).replace(/\.md$/i, "");
}
