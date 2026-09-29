import { type App, Modal, Notice } from "obsidian";
import { explainAccessError } from "../sync/accessPlan";
import { activityLines, describeEvent, formatDate } from "../sync/historyText";
import { type HistoryEvent, HttpError, type NoteApiClient, type NoteHistory } from "../sync/NoteApiClient";
import { describeVersion, explainVersionError, lineDiff, type NoteVersion, type VersionList } from "../sync/versionText";
import { confirmAction } from "./ConfirmModal";
import type { ShareTarget } from "./ShareModal";

const SHOWN_VERSIONS = 20;
/** Mehr liefert der Server nicht auf einmal. */
const MAX_VERSIONS = 500;

/**
 * "Verlauf und Protokoll…": bei einer Notiz ihre Versionen (ansehen, vergleichen, wiederherstellen), bei einer Datei, wer sie angelegt, zuletzt bearbeitet und zuletzt
 * geoeffnet hat und was mit ihr geschah; bei einem Ordner (oder dem ganzen Vault) dessen Protokoll.
 * Der Server zeigt jeder Person nur, was sie sehen darf.
 */
export class HistoryModal extends Modal {
  constructor(
    app: App,
    private readonly api: NoteApiClient,
    private readonly vaultId: string,
    private readonly target: ShareTarget,
  ) {
    super(app);
  }

  onOpen(): void {
    const label = this.target.path === "" ? "ganzer Vault" : this.target.path;
    this.setTitle(`Verlauf: ${label}`);
    this.contentEl.addClass("stoneintelligence-history");
    this.contentEl.createEl("p", { cls: "setting-item-description", text: "Wird geladen…" });
    void this.load();
  }

  onClose(): void {
    this.contentEl.empty();
  }

  private async load(): Promise<void> {
    try {
      if (this.target.kind === "entry") {
        const noteId = this.target.noteId;
        const [history, versions] = await Promise.all([
          this.api.noteHistory(this.vaultId, noteId),
          isNote(this.target.path) ? this.api.noteVersions(this.vaultId, noteId).catch(() => null) : Promise.resolve(null),
        ]);
        this.renderNote(history, versions);
      } else {
        this.renderEvents(await this.api.vaultLog(this.vaultId, this.target.path), true);
      }
    } catch (error) {
      this.contentEl.empty();
      this.contentEl.createEl("p", {
        cls: "stoneintelligence-invite-error",
        text: error instanceof HttpError ? explainAccessError(error.status) : (error as Error).message,
      });
    }
  }

  private renderNote(history: NoteHistory, versions: VersionList | null): void {
    this.contentEl.empty();
    const summary = this.contentEl.createEl("ul", { cls: "stoneintelligence-history-summary" });
    for (const line of history.activity ? activityLines(history.activity) : ["Gelöscht – es bleibt das Protokoll."]) {
      summary.createEl("li", { text: line });
    }
    if (versions && versions.versions.length > 0 && this.target.kind === "entry") {
      this.renderVersions(this.contentEl.createDiv(), this.target.noteId, versions, SHOWN_VERSIONS);
    }
    // Neueste zuerst - der Server liefert die Ereignisse eines Eintrags chronologisch.
    this.renderEvents([...history.events].reverse(), false);
  }

  private renderEvents(events: HistoryEvent[], showPlace: boolean): void {
    if (showPlace) {
      this.contentEl.empty();
    }
    this.contentEl.createEl("h4", { text: "Protokoll" });
    if (events.length === 0) {
      this.contentEl.createEl("p", { cls: "setting-item-description", text: "Noch nichts, was du sehen darfst." });
      return;
    }
    const list = this.contentEl.createEl("ol", { cls: "stoneintelligence-history-events" });
    for (const event of events) {
      const item = list.createEl("li");
      item.createEl("span", { cls: "stoneintelligence-history-when", text: formatDate(event.occurredAt) });
      item.createEl("span", { text: describeEvent(event) });
    }
  }

  private renderVersions(section: HTMLElement, noteId: string, list: VersionList, shown: number): void {
    section.empty();
    section.createEl("h4", { text: "Versionen" });
    const items = section.createEl("ol", { cls: "stoneintelligence-history-events stoneintelligence-versions" });
    list.versions.slice(0, shown).forEach((version, index) => {
      const item = items.createEl("li");
      const { who, when } = describeVersion(version);
      const button = item.createEl("button", { cls: "stoneintelligence-version", attr: { "aria-expanded": "false" } });
      button.createEl("span", { cls: "stoneintelligence-history-when", text: when });
      button.createEl("span", { text: index === 0 ? `${who} · aktueller Stand` : who });
      const detail = item.createDiv({ cls: "stoneintelligence-version-detail" });
      detail.hide();
      button.addEventListener("click", () => {
        const open = button.getAttribute("aria-expanded") === "true";
        button.setAttribute("aria-expanded", String(!open));
        if (open) {
          detail.hide();
          return;
        }
        detail.show();
        void this.renderVersionDetail(detail, noteId, version, index === 0, () => this.reloadVersions(section, noteId));
      });
    });
    const more = list.total - Math.min(shown, list.versions.length);
    if (more > 0 && list.versions.length < MAX_VERSIONS) {
      section.createEl("button", { text: `${more} ältere Versionen zeigen` }).addEventListener("click", () => {
        if (shown < list.versions.length) {
          this.renderVersions(section, noteId, list, list.versions.length);
          return;
        }
        void this.api.noteVersions(this.vaultId, noteId, MAX_VERSIONS)
          .then((all) => this.renderVersions(section, noteId, all, all.versions.length))
          .catch((error) => new Notice(messageOf(error)));
      });
    } else if (more > 0) {
      section.createEl("p", { cls: "setting-item-description", text: `Die ältesten ${more} Versionen sind hier nicht aufgeführt.` });
    }
  }

  private async renderVersionDetail(detail: HTMLElement, noteId: string, version: NoteVersion, current: boolean,
                                    reload: () => void): Promise<void> {
    detail.empty();
    detail.createEl("p", { cls: "setting-item-description", text: "Wird geladen…" });
    try {
      const shown = await this.api.noteVersion(this.vaultId, noteId, version.revision);
      detail.empty();
      const rows = lineDiff(shown.current, shown.text);
      if (current || rows.length === 0) {
        detail.createEl("p", { cls: "setting-item-description", text: "So sieht die Notiz gerade aus." });
        return;
      }
      detail.createEl("p", { cls: "setting-item-description", text: "Was sich gegenüber jetzt ändern würde:" });
      const table = detail.createDiv({ cls: "stoneintelligence-diff" });
      for (const row of rows) {
        table.createDiv({ cls: `stoneintelligence-diff-${row.kind}`, text: row.text === "" ? " " : row.text });
      }
      const restore = detail.createEl("button", { cls: "mod-cta", text: "Diese Version wiederherstellen" });
      restore.addEventListener("click", () => void this.restore(noteId, version, restore, reload));
    } catch (error) {
      detail.empty();
      detail.createEl("p", { cls: "stoneintelligence-invite-error", text: messageOf(error) });
    }
  }

  private async restore(noteId: string, version: NoteVersion, button: HTMLButtonElement, reload: () => void): Promise<void> {
    const { who, when } = describeVersion(version);
    const confirmed = await confirmAction(this.app, "Version wiederherstellen",
      `Die Notiz bekommt wieder den Text vom ${when} (${who}). Das ist eine neue Änderung: alle Versionen dazwischen bleiben erhalten und lassen sich ebenso zurückholen.`,
      "Wiederherstellen");
    if (!confirmed) {
      return;
    }
    button.disabled = true;
    try {
      const result = await this.api.restoreVersion(this.vaultId, noteId, version.revision);
      new Notice(result.changed ? "Version wiederhergestellt." : "Die Notiz hatte diesen Text schon.");
      reload();
    } catch (error) {
      button.disabled = false;
      new Notice(messageOf(error));
    }
  }

  private reloadVersions(section: HTMLElement, noteId: string): void {
    void this.api.noteVersions(this.vaultId, noteId)
      .then((list) => this.renderVersions(section, noteId, list, SHOWN_VERSIONS))
      .catch((error) => new Notice(messageOf(error)));
  }
}

function isNote(path: string): boolean {
  return path.toLowerCase().endsWith(".md");
}

function messageOf(error: unknown): string {
  return error instanceof HttpError ? explainVersionError(error.status) : (error as Error).message;
}
