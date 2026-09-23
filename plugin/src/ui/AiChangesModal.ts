import { type App, Modal, Setting } from "obsidian";
import { HttpError, type NoteApiClient } from "../sync/NoteApiClient";
import { type AiChangeSetView, aiChangeSummary, orderForActiveFile, revertReportText } from "./aiChanges";

/** So viele Laeufe werden mit ihren Aenderungen geladen - aeltere stehen im Web-Dashboard. */
const SHOWN = 20;

/**
 * Die KI-Laeufe des Vaults, aus Obsidian heraus rueckgaengig zu machen: jeder Lauf ist alles, was
 * die KI aus einem Dokument geschrieben hat (Notizen, Quellnotiz, das abgelegte Original).
 * Rueckgaengig nimmt ihn als Ganzes zurueck; was seitdem jemand geaendert hat, bleibt stehen.
 * Die Loeschungen kommen danach ueber den normalen Sync auf alle Geraete.
 */
export class AiChangesModal extends Modal {
  private views: (AiChangeSetView & { touchesActive: boolean })[] = [];
  private loading = true;
  private busy: string | null = null;
  private confirming: string | null = null;
  private message = "";
  private error = "";
  private listEl!: HTMLElement;

  constructor(
    app: App,
    private readonly api: NoteApiClient,
    private readonly vaultId: string,
    private readonly activePath: string | null,
  ) {
    super(app);
  }

  onOpen(): void {
    this.setTitle("KI-Änderungen");
    this.contentEl.addClass("stoneintelligence-ai-changes");
    this.contentEl.createEl("p", {
      cls: "setting-item-description",
      text: "Jeder Eintrag ist ein Dokument, das die KI gelesen hat, mit allem, was sie daraus geschrieben hat. "
        + "Rückgängig machen entfernt es wieder – was seitdem jemand geändert hat, bleibt stehen.",
    });
    this.listEl = this.contentEl.createDiv();
    this.render();
    void this.load();
  }

  onClose(): void {
    this.contentEl.empty();
  }

  private async load(): Promise<void> {
    try {
      const sets = (await this.api.listAiChangeSets(this.vaultId)).slice(0, SHOWN);
      const views = await Promise.all(sets.map((set) => this.api.aiChangeSet(this.vaultId, set.id)));
      this.views = orderForActiveFile(views, this.activePath);
    } catch (error) {
      this.error = explain(error);
    }
    this.loading = false;
    this.render();
  }

  private async revert(view: AiChangeSetView): Promise<void> {
    if (this.busy) {
      return;
    }
    if (this.confirming !== view.changeSet.id) {
      this.confirming = view.changeSet.id;
      this.render();
      return;
    }
    this.busy = view.changeSet.id;
    this.confirming = null;
    this.message = "";
    this.error = "";
    this.render();
    try {
      const report = await this.api.revertAiChangeSet(this.vaultId, view.changeSet.id);
      this.message = `„${view.changeSet.label}“: ${revertReportText(report)}`;
      const updated = await this.api.aiChangeSet(this.vaultId, view.changeSet.id);
      this.views = this.views.map((entry) => entry.changeSet.id === updated.changeSet.id ? { ...updated, touchesActive: entry.touchesActive } : entry);
    } catch (error) {
      this.error = explain(error);
    } finally {
      this.busy = null;
      this.render();
    }
  }

  private render(): void {
    const root = this.listEl;
    if (!root) {
      return;
    }
    root.empty();
    if (this.message) {
      root.createEl("p", { cls: "stoneintelligence-invite-message", text: this.message });
    }
    if (this.error) {
      root.createEl("p", { cls: "stoneintelligence-invite-error", text: this.error });
    }
    if (this.loading) {
      root.createEl("p", { cls: "setting-item-description", text: "Wird geladen…" });
      return;
    }
    if (this.views.length === 0 && !this.error) {
      root.createEl("p", { cls: "setting-item-description", text: "Die KI hat in diesem Vault noch nichts geschrieben." });
      return;
    }
    const date = new Intl.DateTimeFormat("de-DE", { day: "numeric", month: "long", hour: "2-digit", minute: "2-digit" });
    for (const view of this.views) {
      const set = view.changeSet;
      const row = new Setting(root)
        .setName(set.label)
        .setDesc(`${date.format(new Date(set.createdAt))} · ${aiChangeSummary(view)}${view.touchesActive ? " · hat die geöffnete Notiz geschrieben" : ""}`);
      if (view.touchesActive) {
        row.settingEl.addClass("stoneintelligence-ai-active");
      }
      if (!set.revertedAt) {
        row.addButton((button) => {
          const confirming = this.confirming === set.id;
          button.setButtonText(this.busy === set.id ? "Wird rückgängig gemacht…" : confirming ? "Wirklich rückgängig machen?" : "Rückgängig machen")
            .setDisabled(this.busy !== null)
            .onClick(() => void this.revert(view));
          if (confirming) {
            button.setWarning();
          }
        });
      }
    }
  }
}

function explain(error: unknown): string {
  if (error instanceof HttpError && error.status === 403) {
    return "Dafür fehlen dir in diesem Vault die Rechte (Bearbeiten und Löschen).";
  }
  if (error instanceof HttpError && error.status === 404) {
    return "Diesen Eintrag gibt es nicht mehr.";
  }
  return (error as Error).message;
}
