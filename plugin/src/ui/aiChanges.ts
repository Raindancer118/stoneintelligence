/**
 * Was die Liste der KI-Laeufe in Obsidian zeigt - rein, damit es ohne Obsidian testbar ist.
 * Ein Lauf ("Change-Set") ist alles, was die KI aus einem hochgeladenen Dokument geschrieben hat;
 * rueckgaengig gemacht wird er immer als Ganzes (ADR 0008).
 */
export interface AiChangeSet {
  id: string; service: string; agent: string; requestedBy: string; label: string; createdAt: string; revertedAt: string | null;
}
/** `LINKED`: gesetzte Links in einer vorhandenen Notiz (ADR 0012, naechtliche Verlinkung). */
export interface AiChange { noteId: string; path: string; kind: "CREATED" | "UPDATED" | "FILE_CREATED" | "LINKED"; at: string; }
export interface AiChangeSetView { changeSet: AiChangeSet; changes: AiChange[]; }
export interface AiRevertReport { reverted: number; conflicts: { path: string; reason: string }[]; }

const plural = (count: number, one: string, many: string): string => `${count} ${count === 1 ? one : many}`;

export function aiChangeSummary(view: AiChangeSetView): string {
  const files = new Set(view.changes.filter((change) => change.kind === "FILE_CREATED").map((change) => change.path)).size;
  const notes = new Set(view.changes.filter((change) => change.kind === "CREATED" || change.kind === "UPDATED").map((change) => change.path)).size;
  const linked = new Set(view.changes.filter((change) => change.kind === "LINKED").map((change) => change.path)).size;
  const parts = [linked > 0 && notes === 0 && files === 0 ? `Links in ${plural(linked, "Notiz", "Notizen")}` : plural(notes, "Notiz", "Notizen")];
  if (files > 0) {
    parts[0] += `, ${plural(files, "Datei", "Dateien")}`;
  }
  parts.push(view.changeSet.agent.replace(/^ki:/, ""));
  if (view.changeSet.revertedAt) {
    parts.push("rückgängig gemacht");
  }
  return parts.join(" · ");
}

/** Laeufe, die die geoeffnete Notiz geschrieben oder geaendert haben, zuerst - sonst neueste zuerst. */
export function orderForActiveFile(views: AiChangeSetView[], activePath: string | null): (AiChangeSetView & { touchesActive: boolean })[] {
  const marked = views.map((view) => ({
    ...view,
    touchesActive: activePath !== null && view.changes.some((change) => change.path === activePath),
  }));
  return [...marked.filter((view) => view.touchesActive), ...marked.filter((view) => !view.touchesActive)];
}

export function revertReportText(report: AiRevertReport): string {
  const done = `${plural(report.reverted, "Änderung", "Änderungen")} rückgängig gemacht.`;
  if (report.conflicts.length === 0) {
    return done;
  }
  const kept = report.conflicts.map((conflict) => `${conflict.path} (${conflict.reason})`).join(", ");
  return `${done} Stehen geblieben: ${kept}.`;
}
