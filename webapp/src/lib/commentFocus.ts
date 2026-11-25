/** Vom Hinweis zum Kommentar: die Glocke merkt sich Notiz und Diskussion, der Editor öffnet dann das Kommentar-Panel. */
let pending: { noteId: string; threadId: string } | null = null;
const listeners = new Set<() => void>();

export function requestCommentFocus(noteId: string, threadId: string): void {
  pending = { noteId, threadId };
  listeners.forEach(listener => listener());
}

/** Gibt die gemerkte Diskussion zurück, wenn sie zu dieser Notiz gehört, und vergisst sie. */
export function takeCommentFocus(noteId: string): string | null {
  if (pending?.noteId !== noteId) return null;
  const { threadId } = pending;
  pending = null;
  return threadId;
}

export function onCommentFocus(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
