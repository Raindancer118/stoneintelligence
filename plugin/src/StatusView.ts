import { ItemView, WorkspaceLeaf } from "obsidian";
import type { SyncStatus } from "./sync/SyncClient";

export const VIEW_TYPE_STATUS = "stoneintelligence-status";

export interface SessionSnapshot {
  path: string;
  status: SyncStatus;
}

/** Minimale Schnittstelle, die {@link StatusView} vom Plugin braucht - haelt die View unabhaengig testbar. */
export interface StatusViewHost {
  isLoggedIn(): boolean;
  getVaultId(): string;
  getSyncSessions(): SessionSnapshot[];
}

const STATUS_LABEL: Record<SyncStatus, string> = {
  connecting: "verbindet…",
  connected: "synchronisiert",
  disconnected: "getrennt",
  error: "Fehler",
};

/**
 * Sichtbarkeits-Ansicht (Auftragsqueue-Ersatz): zeigt Anmeldestatus, konfigurierten Vault und
 * pro getrackter Notiz die live WebSocket-Verbindung an - ohne diese Ansicht war fuer den Nutzer
 * von aussen nicht erkennbar, ob das Plugin ueberhaupt etwas tut. Pollt bewusst statt eines
 * Event-Bus quer durchs Plugin zu verdrahten - fuer eine reine Anzeige alle 1,5s reicht das und
 * bleibt simpel.
 */
export class StatusView extends ItemView {
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
    return "gem";
  }

  async onOpen(): Promise<void> {
    this.render();
    this.registerInterval(window.setInterval(() => this.render(), 1500) as unknown as number);
  }

  private render(): void {
    const container = this.containerEl.children[1];
    container.empty();
    container.addClass("stoneintelligence-status-view");

    container.createEl("h4", { text: "Verbindung" });
    const connectionList = container.createEl("ul", { cls: "stoneintelligence-status-list" });
    this.statusRow(connectionList, this.host.isLoggedIn() ? "connected" : "disconnected",
      this.host.isLoggedIn() ? "Angemeldet" : "Nicht angemeldet");
    this.statusRow(connectionList, this.host.getVaultId() ? "connected" : "disconnected",
      this.host.getVaultId() ? `Vault ${this.host.getVaultId()}` : "Kein Vault konfiguriert");

    const sessions = this.host.getSyncSessions();
    container.createEl("h4", { text: `Notizen (${sessions.length})` });
    if (sessions.length === 0) {
      container.createEl("p", { cls: "stoneintelligence-status-empty", text: "Keine Notiz wird gerade synchronisiert." });
      return;
    }
    const notesList = container.createEl("ul", { cls: "stoneintelligence-status-list" });
    for (const session of sessions.sort((a, b) => a.path.localeCompare(b.path))) {
      this.statusRow(notesList, session.status, session.path);
    }
  }

  private statusRow(list: HTMLElement, status: SyncStatus, label: string): void {
    const item = list.createEl("li", { cls: `stoneintelligence-status-row is-${status}` });
    item.createEl("span", { cls: "stoneintelligence-status-dot" });
    item.createEl("span", { cls: "stoneintelligence-status-text", text: label });
    item.createEl("span", { cls: "stoneintelligence-status-state", text: STATUS_LABEL[status] });
  }
}
