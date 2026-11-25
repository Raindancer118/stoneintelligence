import { type App, Modal } from "obsidian";
import type { CommentAnchor } from "../sync/commentAnchor";
import type { Member } from "../sync/commentText";
import { renderComposer } from "./commentComposer";
import { explainCommentError } from "./mentionNotice";

/** „Kommentar zur Auswahl“: die markierte Stelle als Zitat, darunter der Kommentar. */
export class CommentModal extends Modal {
  constructor(
    app: App,
    private readonly noteTitle: string,
    private readonly anchor: CommentAnchor,
    private readonly members: () => Promise<Member[]>,
    private readonly send: (body: string, anchor: CommentAnchor) => Promise<void>,
  ) {
    super(app);
  }

  onOpen(): void {
    this.setTitle(`Kommentar zu „${this.noteTitle}“`);
    this.contentEl.addClass("stoneintelligence-comment-modal");
    this.contentEl.createEl("blockquote", { cls: "stoneintelligence-thread-quote", text: `„${this.anchor.quote}“` });
    renderComposer(this.contentEl, {
      submitLabel: "Kommentieren",
      members: this.members,
      onSubmit: async (body) => {
        try {
          await this.send(body, this.anchor);
          this.close();
          return null;
        } catch (error) {
          return explainCommentError(error);
        }
      },
    }).focus();
  }

  onClose(): void {
    this.contentEl.empty();
  }
}
