import { type App, Modal, Setting } from "obsidian";
import { explainAccessError } from "../sync/accessPlan";
import { HttpError, type NoteApiClient, type SimilarNote } from "../sync/NoteApiClient";

/**
 * "Ähnliche Notizen" (ADR 0012): Notizen mit verwandtem Inhalt, nach Bedeutung statt nach Wortlaut,
 * berechnet auf dem eigenen Server. Ein Klick öffnet die Notiz.
 */
export class SimilarNotesModal extends Modal {
  constructor(
    app: App,
    private readonly api: NoteApiClient,
    private readonly vaultId: string,
    private readonly noteId: string,
    private readonly path: string,
    private readonly openNote: (path: string) => void,
  ) {
    super(app);
  }

  onOpen(): void {
    this.setTitle(`Ähnliche Notizen: ${this.path.split("/").pop()?.replace(/\.md$/i, "")}`);
    this.contentEl.createEl("p", { cls: "setting-item-description", text: "Wird gesucht…" });
    void this.load();
  }

  onClose(): void {
    this.contentEl.empty();
  }

  private async load(): Promise<void> {
    let similar: SimilarNote[];
    try {
      similar = await this.api.similarNotes(this.vaultId, this.noteId);
    } catch (error) {
      this.contentEl.empty();
      this.contentEl.createEl("p", {
        cls: "stoneintelligence-invite-error",
        text: error instanceof HttpError ? explainAccessError(error.status) : (error as Error).message,
      });
      return;
    }
    this.contentEl.empty();
    if (similar.length === 0) {
      this.contentEl.createEl("p", {
        cls: "setting-item-description",
        text: "Noch nichts gefunden. Ähnlichkeiten entstehen beim Verlinken (Vault-Verwaltung → KI → „Jetzt verlinken“ oder nachts).",
      });
      return;
    }
    for (const note of similar) {
      new Setting(this.contentEl)
        .setName(note.path.split("/").pop()?.replace(/\.md$/i, "") ?? note.path)
        .setDesc(`${Math.round(note.similarity * 100)} % ähnlich${note.heading ? ` · Abschnitt „${note.heading}“` : ""} · ${note.path}`)
        .addButton((button) => button.setButtonText("Öffnen").onClick(() => {
          this.openNote(note.path);
          this.close();
        }));
    }
  }
}
