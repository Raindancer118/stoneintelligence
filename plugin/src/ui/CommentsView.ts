import { ItemView, MarkdownView, Notice, TFile, type WorkspaceLeaf } from "obsidian";
import { placeAnchors } from "../sync/commentAnchor";
import { type Member, segments } from "../sync/commentText";
import type { CommentThreadView, NoteApiClient } from "../sync/NoteApiClient";
import { renderComposer } from "./commentComposer";
import { explainCommentError } from "./mentionNotice";

export const VIEW_TYPE_COMMENTS = "stoneintelligence-comments";
const POLL_MS = 20_000;

export interface CommentTarget { noteId: string; path: string; file: TFile; }

/** Was die Kommentar-Ansicht vom Plugin braucht - hält sie vom Plugin-Innenleben getrennt. */
export interface CommentsHost {
  api(): NoteApiClient;
  vaultId(): string | null;
  /** Die aktive, synchronisierte Markdown-Notiz; sonst `null`. */
  activeTarget(): CommentTarget | null;
  /** Ob ich an dieser Notiz verwalte (dann darf ich auch fremde Kommentare löschen). */
  canModerate(path: string): boolean;
  members(): Promise<Member[]>;
  /** Die Ansicht hat die Notiz gezeigt - ihre Hinweise gelten als gelesen. */
  commentsViewed(noteId: string): void;
}

/** Kommentare der aktiven Notiz (ADR 0016): Threads, Antworten, Lösen, Stelle im Text finden. */
export class CommentsView extends ItemView {
  private target: CommentTarget | null = null;
  private threads: CommentThreadView[] | null = null;
  private source = "";
  private showResolved = false;
  private problem = "";
  private pending: { thread: string } | null = null;
  private replying: string | null = null;
  private editing: string | null = null;
  private loadSeq = 0;

  constructor(leaf: WorkspaceLeaf, private readonly host: CommentsHost) {
    super(leaf);
  }

  getViewType(): string { return VIEW_TYPE_COMMENTS; }
  getDisplayText(): string { return "Kommentare"; }
  getIcon(): string { return "message-square"; }

  async onOpen(): Promise<void> {
    this.contentEl.addClass("stoneintelligence-comments");
    this.registerEvent(this.app.workspace.on("file-open", () => void this.follow()));
    this.registerInterval(window.setInterval(() => { if (document.visibilityState === "visible") void this.fetchComments(); }, POLL_MS));
    await this.follow();
  }

  async onClose(): Promise<void> {
    this.contentEl.empty();
  }

  /** Von außen: neu laden, etwa nach „Kommentar zur Auswahl“. */
  async reload(): Promise<void> {
    await this.fetchComments();
  }

  private async follow(): Promise<void> {
    const next = this.host.activeTarget();
    if (next?.noteId !== this.target?.noteId) {
      this.threads = null; this.problem = ""; this.replying = null; this.editing = null;
    }
    this.target = next;
    this.render();
    await this.fetchComments();
  }

  private async fetchComments(): Promise<void> {
    const target = this.target;
    const vaultId = this.host.vaultId();
    if (!target || !vaultId) return;
    const seq = ++this.loadSeq;
    try {
      const [threads, source] = await Promise.all([this.host.api().listComments(vaultId, target.noteId), this.app.vault.cachedRead(target.file)]);
      if (seq !== this.loadSeq || target.noteId !== this.target?.noteId) return;
      this.threads = threads; this.source = source; this.problem = "";
      this.host.commentsViewed(target.noteId);
    } catch (error) {
      if (seq !== this.loadSeq) return;
      this.problem = explainCommentError(error);
      this.threads ??= [];
    }
    this.render();
  }

  /** Aktion mit Fehlermeldung statt Absturz; `true` bei Erfolg. */
  private async run(action: () => Promise<CommentThreadView | void>): Promise<string | null> {
    const vaultId = this.host.vaultId();
    if (!vaultId) return "Kein Vault verbunden.";
    try {
      const result = await action();
      if (result) this.replace(result);
      else await this.fetchComments();
      this.problem = "";
      this.render();
      return null;
    } catch (error) {
      return explainCommentError(error);
    }
  }

  private replace(updated: CommentThreadView): void {
    const list = this.threads ?? [];
    this.threads = list.some((t) => t.id === updated.id) ? list.map((t) => (t.id === updated.id ? updated : t)) : [...list, updated];
  }

  private render(): void {
    const root = this.contentEl;
    root.empty();
    const target = this.target;
    if (!target) {
      root.createEl("p", { cls: "setting-item-description", text: "Öffne eine synchronisierte Notiz, um ihre Kommentare zu sehen." });
      return;
    }
    const vaultId = this.host.vaultId()!;
    const api = this.host.api();
    const title = target.path.split("/").pop()?.replace(/\.md$/i, "") ?? target.path;
    const head = root.createDiv({ cls: "stoneintelligence-comments-head" });
    head.createEl("h4", { text: `Kommentare: ${title}` });
    const filter = head.createEl("label", { cls: "stoneintelligence-comments-filter" });
    const box = filter.createEl("input", { attr: { type: "checkbox" } });
    box.checked = this.showResolved;
    filter.appendText(" Gelöste zeigen");
    box.addEventListener("change", () => { this.showResolved = box.checked; this.render(); });
    if (this.problem) root.createEl("p", { cls: "stoneintelligence-invite-error", text: this.problem });

    renderComposer(root.createDiv(), {
      submitLabel: "Kommentieren",
      members: () => this.host.members(),
      onSubmit: (body) => this.run(() => api.startThread(vaultId, target.noteId, body, null)),
    });
    root.createEl("p", { cls: "setting-item-description", text: "Für eine Textstelle markiere sie im Editor und wähle „Kommentar zur Auswahl“ in der Befehlspalette." });

    if (this.threads === null) {
      root.createEl("p", { cls: "setting-item-description", text: "Kommentare werden geladen…" });
      return;
    }
    const open = this.threads.filter((t) => !t.resolved);
    const shown = placeAnchors(this.source, this.showResolved ? this.threads : open);
    if (shown.length === 0) {
      root.createEl("p", { cls: "setting-item-description", text: this.threads.length ? "Alle Diskussionen sind gelöst." : "Noch keine Kommentare." });
    }
    for (const { item: thread, state } of shown) {
      this.renderThread(root, thread, state === "changed", target, vaultId);
    }
  }

  private renderThread(root: HTMLElement, thread: CommentThreadView, changed: boolean, target: CommentTarget, vaultId: string): void {
    const api = this.host.api();
    const card = root.createDiv({ cls: "stoneintelligence-thread" });
    card.toggleClass("is-resolved", thread.resolved);
    if (thread.anchor) {
      const quote = card.createDiv({ cls: "stoneintelligence-thread-quote" });
      quote.createSpan({ text: `„${thread.anchor.quote}“` });
      if (changed) quote.createSpan({ cls: "stoneintelligence-badge", text: "Stelle geändert" });
      else quote.createEl("button", { cls: "clickable-icon", text: "Zur Stelle" }).addEventListener("click", () => this.reveal(target, thread.anchor!.quote));
    }
    for (const comment of thread.comments) {
      const row = card.createDiv({ cls: "stoneintelligence-comment" });
      const meta = row.createDiv({ cls: "stoneintelligence-comment-meta" });
      meta.createEl("strong", { text: comment.authorName });
      meta.createSpan({ text: new Date(comment.createdAt).toLocaleString("de-DE", { dateStyle: "medium", timeStyle: "short" }) });
      if (comment.editedAt) meta.createSpan({ text: "bearbeitet" });
      if (this.editing === comment.id) {
        renderComposer(row, {
          initial: comment.body, submitLabel: "Speichern", members: () => this.host.members(),
          onSubmit: (body) => this.run(async () => { const updated = await api.editComment(vaultId, comment.id, body); this.editing = null; return updated; }),
          onCancel: () => { this.editing = null; this.render(); },
        });
        continue;
      }
      const body = row.createEl("p", { cls: "stoneintelligence-comment-body" });
      for (const part of segments(comment.body)) {
        if (part.kind === "mention") body.createSpan({ cls: "stoneintelligence-mention", text: `@${part.name}` });
        else body.appendText(part.text);
      }
      const actions = row.createDiv({ cls: "stoneintelligence-comment-actions" });
      if (comment.mine) actions.createEl("button", { cls: "clickable-icon", text: "Bearbeiten" }).addEventListener("click", () => { this.editing = comment.id; this.render(); });
      if (comment.mine || this.host.canModerate(target.path)) {
        actions.createEl("button", { cls: "clickable-icon", text: "Löschen" }).addEventListener("click", () => {
          if (!window.confirm("Diesen Kommentar löschen?")) return;
          void this.run(async () => { await api.deleteComment(vaultId, comment.id); }).then((problem) => { if (problem) new Notice(problem); });
        });
      }
    }
    if (thread.resolved) {
      card.createEl("p", { cls: "setting-item-description", text: `Gelöst von ${thread.resolvedByName ?? "Unbekannt"}` });
    }
    if (this.replying === thread.id) {
      renderComposer(card, {
        submitLabel: "Antworten", members: () => this.host.members(),
        onSubmit: (body) => this.run(async () => { const updated = await api.replyToThread(vaultId, thread.id, body); this.replying = null; return updated; }),
        onCancel: () => { this.replying = null; this.render(); },
      });
    } else {
      const actions = card.createDiv({ cls: "stoneintelligence-thread-actions" });
      actions.createEl("button", { text: "Antworten" }).addEventListener("click", () => { this.replying = thread.id; this.render(); });
      actions.createEl("button", { text: thread.resolved ? "Wieder öffnen" : "Als gelöst markieren" }).addEventListener("click", () => {
        void this.run(() => (thread.resolved ? api.reopenThread(vaultId, thread.id) : api.resolveThread(vaultId, thread.id)))
          .then((problem) => { if (problem) new Notice(problem); });
      });
    }
  }

  /** Das Zitat steht normalisiert (ohne Markdown-Zeichen) im Kommentar; im Quelltext geht nur der wörtliche Treffer. */
  private reveal(target: CommentTarget, quote: string): void {
    const leaf = this.app.workspace.getLeavesOfType("markdown").find((l) => l.view instanceof MarkdownView && l.view.file?.path === target.path);
    const view = leaf?.view;
    const index = this.source.indexOf(quote);
    if (!(view instanceof MarkdownView) || index < 0) {
      new Notice("Die Stelle lässt sich im Text nicht wörtlich finden, vermutlich wegen Formatierung.");
      return;
    }
    void this.app.workspace.revealLeaf(leaf!);
    view.editor.setSelection(view.editor.offsetToPos(index), view.editor.offsetToPos(index + quote.length));
    view.editor.scrollIntoView({ from: view.editor.offsetToPos(index), to: view.editor.offsetToPos(index + quote.length) }, true);
  }
}
