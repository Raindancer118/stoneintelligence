import { type CommentNotification, HttpError } from "../sync/NoteApiClient";

/** Der Server begründet abgelehnte Erwähnungen selbst (422); sonst ein kurzer Satz je Status. */
export function explainCommentError(error: unknown): string {
  if (error instanceof HttpError) {
    if (error.status === 422) return error.message;
    if (error.status === 403) return "Dafür hast du an dieser Notiz keine Berechtigung.";
    if (error.status === 404) return "Diesen Kommentar gibt es nicht mehr.";
    if (error.status === 429) return "Zu viele Anfragen. Bitte versuche es gleich erneut.";
    return `Der Server lehnte ab (HTTP ${error.status}).`;
  }
  return error instanceof Error ? error.message : "Die Aktion ist fehlgeschlagen.";
}

const EXCERPT = 100;

/** Ungelesene Erwähnungen, die in dieser Sitzung noch nicht als Hinweis gezeigt wurden. */
export function unseenMentions(items: CommentNotification[], seen: ReadonlySet<string>): CommentNotification[] {
  return items.filter((item) => !item.read && !seen.has(item.id));
}

export function noticeText(item: CommentNotification): string {
  const title = (item.notePath.split("/").pop() ?? item.notePath).replace(/\.md$/i, "");
  const excerpt = item.excerpt.length > EXCERPT ? `${item.excerpt.slice(0, EXCERPT - 1).trimEnd()}…` : item.excerpt;
  return `${item.actorName} hat dich in „${title}“ erwähnt: ${excerpt}`;
}

/** Bei vielen auf einmal (etwa nach dem Start) ein Hinweis statt eines Schwalls. */
export function summaryText(items: CommentNotification[]): string {
  return `${items.length} neue ${items.length === 1 ? "Erwähnung" : "Erwähnungen"}. „Hinweise anzeigen“ in der Befehlspalette öffnet sie.`;
}
