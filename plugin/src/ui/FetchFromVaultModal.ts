import { type App, SuggestModal } from "obsidian";
import type { NoteListItem } from "../sync/NoteApiClient";

/**
 * „Aus dem Vault holen…" (ADR 0013): mit Arbeitsbereichen liegt nicht alles auf dem Geraet. Die
 * Suche laeuft auf dem Server (Titel und Pfad); die gewaehlte Notiz wird geholt und bleibt aktuell.
 */
export class FetchFromVaultModal extends SuggestModal<NoteListItem> {
  private latest = 0;

  constructor(
    app: App,
    private readonly search: (query: string) => Promise<NoteListItem[]>,
    private readonly chosen: (item: NoteListItem) => void,
  ) {
    super(app);
    this.setPlaceholder("Titel oder Pfad einer Notiz im Vault");
    this.emptyStateText = "Nichts gefunden, das du lesen darfst.";
  }

  async getSuggestions(query: string): Promise<NoteListItem[]> {
    const trimmed = query.trim();
    if (trimmed.length < 2) {
      return [];
    }
    const request = ++this.latest;
    await new Promise((resolve) => window.setTimeout(resolve, 200));
    if (request !== this.latest) {
      return [];
    }
    try {
      return await this.search(trimmed);
    } catch {
      return [];
    }
  }

  renderSuggestion(item: NoteListItem, el: HTMLElement): void {
    el.createDiv({ text: item.path.split("/").pop()?.replace(/\.md$/i, "") ?? item.path });
    el.createEl("small", { cls: "setting-item-description", text: item.path });
  }

  onChooseSuggestion(item: NoteListItem): void {
    this.chosen(item);
  }
}
