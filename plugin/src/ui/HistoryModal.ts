import { type App, Modal } from "obsidian";
import { explainAccessError } from "../sync/accessPlan";
import { activityLines, describeEvent, formatDate } from "../sync/historyText";
import { type HistoryEvent, HttpError, type NoteApiClient, type NoteHistory } from "../sync/NoteApiClient";
import type { ShareTarget } from "./ShareModal";

/**
 * "Verlauf und Protokoll…": bei einer Datei, wer sie angelegt, zuletzt bearbeitet und zuletzt
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
        this.renderNote(await this.api.noteHistory(this.vaultId, this.target.noteId));
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

  private renderNote(history: NoteHistory): void {
    this.contentEl.empty();
    const summary = this.contentEl.createEl("ul", { cls: "stoneintelligence-history-summary" });
    for (const line of activityLines(history.activity)) {
      summary.createEl("li", { text: line });
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
}
