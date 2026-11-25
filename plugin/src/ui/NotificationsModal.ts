import { type App, Modal, Setting } from "obsidian";
import type { CommentNotification, NoteApiClient } from "../sync/NoteApiClient";
import { explainCommentError } from "./mentionNotice";

/** „Hinweise anzeigen“: Erwähnungen aus allen Vaults, ein Klick öffnet die Notiz mit ihren Kommentaren. */
export class NotificationsModal extends Modal {
  constructor(
    app: App,
    private readonly api: NoteApiClient,
    private readonly openItem: (item: CommentNotification) => void,
  ) {
    super(app);
  }

  onOpen(): void {
    this.setTitle("Hinweise");
    this.contentEl.createEl("p", { cls: "setting-item-description", text: "Wird geladen…" });
    void this.load();
  }

  onClose(): void {
    this.contentEl.empty();
  }

  private async load(): Promise<void> {
    try {
      const { items, unread } = await this.api.notifications();
      this.contentEl.empty();
      if (items.length === 0) {
        this.contentEl.createEl("p", { cls: "setting-item-description", text: "Keine Hinweise." });
        return;
      }
      for (const item of items) {
        const title = (item.notePath.split("/").pop() ?? item.notePath).replace(/\.md$/i, "");
        new Setting(this.contentEl)
          .setName(`${item.actorName} hat dich erwähnt${item.read ? "" : " (neu)"}`)
          .setDesc(`${item.vaultName ? `${item.vaultName} · ` : ""}${title}: ${item.excerpt}`)
          .addButton((button) => button.setButtonText("Öffnen").onClick(() => {
            this.close();
            this.openItem(item);
          }));
      }
      if (unread > 0) {
        new Setting(this.contentEl).addButton((button) => button.setButtonText("Alle als gelesen markieren").onClick(async () => {
          await this.api.markNotificationsRead();
          await this.load();
        }));
      }
    } catch (error) {
      this.contentEl.empty();
      this.contentEl.createEl("p", { cls: "stoneintelligence-invite-error", text: explainCommentError(error) });
    }
  }
}
