import { activeMention, insertMention, type Member, mentionToken, suggest } from "../sync/commentText";

export interface ComposerOptions {
  initial?: string;
  submitLabel: string;
  /** Mitglieder für die Vorschläge nach `@`; wird erst beim ersten Fokus geholt. */
  members: () => Promise<Member[]>;
  /** Gibt eine Fehlermeldung zurück oder `null`, wenn gespeichert wurde. */
  onSubmit: (body: string) => Promise<string | null>;
  onCancel?: () => void;
}

/** Eingabefeld mit @-Vorschlägen für Kommentare, in der Seitenleiste und im Dialog gleich. */
export function renderComposer(parent: HTMLElement, options: ComposerOptions): { focus(): void } {
  const root = parent.createDiv({ cls: "stoneintelligence-composer" });
  const field = root.createEl("textarea", {
    cls: "stoneintelligence-composer-field",
    attr: { rows: "3", maxlength: "4000", placeholder: "Schreibe einen Kommentar. Mit @ erwähnst du jemanden.", "aria-label": "Kommentar" },
  });
  field.value = options.initial ?? "";
  const list = root.createDiv({ cls: "stoneintelligence-mention-list" });
  list.hide();
  const error = root.createEl("p", { cls: "stoneintelligence-invite-error" });
  error.hide();
  const actions = root.createDiv({ cls: "stoneintelligence-composer-actions" });
  const submit = actions.createEl("button", { cls: "mod-cta", text: options.submitLabel });
  if (options.onCancel) {
    actions.createEl("button", { text: "Abbrechen" }).addEventListener("click", () => options.onCancel?.());
  }

  let members: Member[] | null = null;
  let choices: Member[] = [];
  let chosen = 0;
  let dismissedAt = -1;
  const label = (m: Member) => m.displayName?.trim() || m.subject;

  const loadMembers = async () => {
    if (members) return;
    members = [];
    try { members = await options.members(); } catch { /* ohne Vorschläge geht Tippen weiter */ }
  };
  const closeList = () => { choices = []; list.empty(); list.hide(); };
  const pick = (member: Member) => {
    const caret = field.selectionStart;
    const mention = activeMention(field.value, caret);
    if (!mention) return;
    const next = insertMention(field.value, mention.start, caret, label(member), member.subject);
    field.value = next.text;
    field.setSelectionRange(next.caret, next.caret);
    closeList();
    field.focus();
  };
  const refresh = () => {
    const caret = field.selectionStart;
    const mention = activeMention(field.value, caret);
    if (!mention || mention.start === dismissedAt || !members) { closeList(); return; }
    choices = suggest(members.filter((m) => mentionToken(label(m), m.subject)), mention.query, null);
    chosen = 0;
    list.empty();
    if (choices.length === 0) { list.hide(); return; }
    list.show();
    choices.forEach((member, index) => {
      const row = list.createDiv({ cls: "stoneintelligence-mention-option", text: label(member) });
      row.toggleClass("is-selected", index === chosen);
      row.addEventListener("mousedown", (event) => { event.preventDefault(); pick(member); });
    });
  };
  const highlight = () => Array.from(list.children).forEach((row, index) => (row as HTMLElement).toggleClass("is-selected", index === chosen));
  const send = async () => {
    const body = field.value.trim();
    if (!body || submit.disabled) return;
    submit.disabled = true;
    error.hide();
    const problem = await options.onSubmit(body);
    submit.disabled = false;
    if (problem) { error.setText(problem); error.show(); return; }
    field.value = "";
    closeList();
  };

  field.addEventListener("focus", () => void loadMembers().then(refresh));
  field.addEventListener("input", refresh);
  field.addEventListener("click", refresh);
  field.addEventListener("keyup", (event) => { if (!["ArrowDown", "ArrowUp", "Enter", "Tab", "Escape"].includes(event.key)) refresh(); });
  field.addEventListener("keydown", (event) => {
    if (choices.length) {
      if (event.key === "ArrowDown") { event.preventDefault(); chosen = (chosen + 1) % choices.length; highlight(); return; }
      if (event.key === "ArrowUp") { event.preventDefault(); chosen = (chosen - 1 + choices.length) % choices.length; highlight(); return; }
      if (event.key === "Enter" || event.key === "Tab") { event.preventDefault(); pick(choices[chosen]!); return; }
      if (event.key === "Escape") { event.preventDefault(); dismissedAt = activeMention(field.value, field.selectionStart)?.start ?? -1; closeList(); return; }
    }
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) { event.preventDefault(); void send(); }
  });
  submit.addEventListener("click", () => void send());
  return { focus: () => field.focus() };
}
