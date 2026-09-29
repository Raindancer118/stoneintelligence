import { type App, Modal, Setting } from "obsidian";
import { explainAccessError } from "../sync/accessPlan";
import { type FolderChild, HttpError } from "../sync/NoteApiClient";

/**
 * Ordner des Servers Ebene fuer Ebene durchgehen und einen als Arbeitsbereich waehlen (ADR 0013).
 * Laedt immer nur eine Ebene - in einem Konzern-Vault gibt es zehntausende Ordner.
 */
export class FolderPickerModal extends Modal {
  private path = "";

  constructor(
    app: App,
    private readonly children: (path: string) => Promise<FolderChild[]>,
    private readonly chosen: (path: string) => void,
    private readonly taken: (path: string) => boolean,
  ) {
    super(app);
  }

  onOpen(): void {
    this.setTitle("Arbeitsbereich hinzufügen");
    void this.show("");
  }

  onClose(): void {
    this.contentEl.empty();
  }

  private async show(path: string): Promise<void> {
    this.path = path;
    this.contentEl.empty();
    const crumbs = this.contentEl.createDiv({ cls: "stoneintelligence-crumbs" });
    const parts = path ? path.split("/") : [];
    const root = crumbs.createEl("button", { text: "Vault" });
    root.addEventListener("click", () => void this.show(""));
    parts.forEach((part, index) => {
      crumbs.createSpan({ text: " / " });
      const crumb = crumbs.createEl("button", { text: part });
      crumb.addEventListener("click", () => void this.show(parts.slice(0, index + 1).join("/")));
    });
    if (path) {
      new Setting(this.contentEl)
        .setName(`„${parts.at(-1)}“ mit allem darin`)
        .addButton((button) => button.setCta().setButtonText(this.taken(path) ? "Schon dabei" : "Hinzufügen")
          .setDisabled(this.taken(path))
          .onClick(() => {
            this.chosen(path);
            this.close();
          }));
    }
    const list = this.contentEl.createDiv();
    list.createEl("p", { cls: "setting-item-description", text: "Wird geladen…" });
    let folders: FolderChild[];
    try {
      folders = await this.children(path);
    } catch (error) {
      list.empty();
      list.createEl("p", {
        cls: "stoneintelligence-invite-error",
        text: error instanceof HttpError ? explainAccessError(error.status) : (error as Error).message,
      });
      return;
    }
    if (this.path !== path) {
      return;
    }
    list.empty();
    if (folders.length === 0) {
      list.createEl("p", { cls: "setting-item-description", text: path ? "Keine Unterordner." : "Noch keine Ordner, die du sehen darfst." });
    }
    for (const folder of folders) {
      const name = folder.path.split("/").pop() ?? folder.path;
      const row = new Setting(list).setName(name);
      if (folder.hasChildren) {
        row.addExtraButton((button) => button.setIcon("chevron-right").setTooltip("Öffnen").onClick(() => void this.show(folder.path)));
      }
      row.addButton((button) => button.setButtonText(this.taken(folder.path) ? "Schon dabei" : "Hinzufügen")
        .setDisabled(this.taken(folder.path))
        .onClick(() => {
          this.chosen(folder.path);
          this.close();
        }));
    }
  }
}
