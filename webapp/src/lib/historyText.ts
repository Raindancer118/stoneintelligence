import { type Permission, permissionsLabel } from "./accessPlan";

/**
 * Protokoll-Eintraege als Saetze fuer "Verlauf und Protokoll" (ADR 0011). Rein und getestet;
 * der Server liefert nur Aktion + Nutzdaten. Haengt nur von accessPlan ab - die Webapp nutzt eine
 * exakte Kopie (webapp/src/lib/historyText.ts, per Test abgesichert).
 */

export interface NoteActivity {
  createdBy: string; createdAt: string;
  lastEditedBy: string | null; lastEditedAt: string | null;
  lastOpenedBy: string | null; lastOpenedAt: string | null;
}
export interface HistoryEvent {
  actor: string; action: string; payload: Record<string, unknown>; occurredAt: string;
  noteId: string | null; path: string | null; paths: string[];
}
/** `activity === null`: der Eintrag ist geloescht, es bleibt sein Protokoll. */
export interface NoteHistory { activity: NoteActivity | null; events: HistoryEvent[]; }

export function describeActor(actor: string): string {
  return actor.startsWith("ki:") ? `KI „${actor.slice(3)}“` : actor;
}

export function describeEvent(event: HistoryEvent): string {
  const who = describeActor(event.actor);
  const payload = event.payload;
  const text = (key: string): string => (typeof payload[key] === "string" ? (payload[key] as string) : "");
  const name = displayName(text("path") || event.path || "");
  switch (event.action) {
    case "note.created":
      return `${who} hat „${name}“ angelegt`;
    case "note.content-updated":
      return `${who} hat „${name}“ bearbeitet`;
    case "note.deleted":
      return `${who} hat „${name}“ gelöscht`;
    case "note.renamed": {
      const from = text("from");
      const to = text("to");
      return parent(from) === parent(to)
        ? `${who} hat „${displayName(from)}“ in „${displayName(to)}“ umbenannt`
        : `${who} hat „${displayName(from)}“ nach „${parent(to) || "oberste Ebene"}“ verschoben`;
    }
    case "file.created":
      return `${who} hat die Datei „${fileName(event)}“ hinzugefügt`;
    case "file.content-updated":
      return `${who} hat eine neue Fassung von „${fileName(event)}“ hochgeladen`;
    case "ACCESS_GRANTED":
      return `${who} hat für ${scopeText(payload)} ${placeText(payload)} ${permissionsLabel((payload.permissions ?? null) as Permission[] | null)} festgelegt`;
    case "ACCESS_REVOKED":
      return `${who} hat die Freigabe für ${scopeText(payload)} ${placeText(payload, "bei")} entfernt`;
    case "MEMBER_ADDED":
      return `${who} hat ${text("subject")} zu einer Gruppe hinzugefügt`;
    case "MEMBER_LEFT_GROUP":
      return `${who} hat ${text("subject")} aus einer Gruppe genommen`;
    case "MEMBER_REMOVED":
      return text("subject") === event.actor ? `${who} hat den Vault verlassen` : `${who} hat ${text("subject")} aus dem Vault genommen`;
    case "ROLE_ASSIGNED":
      return `${who} hat einer Gruppe eine Rolle zugewiesen`;
    case "ROLE_UNASSIGNED":
      return `${who} hat einer Gruppe eine Rolle entzogen`;
    case "ROLE_CHANGED":
      return `${who} hat eine Rolle geändert`;
    case "ROLE_DELETED":
      return `${who} hat eine Rolle gelöscht`;
    case "GROUP_CHANGED":
      return `${who} hat eine Gruppe umbenannt`;
    case "GROUP_DELETED":
      return `${who} hat eine Gruppe gelöscht`;
    default:
      return `${who}: ${event.action}`;
  }
}

export function activityLines(activity: NoteActivity, timeZone?: string): string[] {
  const at = (iso: string): string => formatDate(iso, timeZone);
  return [
    `Angelegt von ${describeActor(activity.createdBy)} am ${at(activity.createdAt)}`,
    activity.lastEditedBy && activity.lastEditedAt
      ? `Zuletzt bearbeitet von ${describeActor(activity.lastEditedBy)} am ${at(activity.lastEditedAt)}`
      : "Seit dem Anlegen nicht bearbeitet",
    activity.lastOpenedBy && activity.lastOpenedAt
      ? `Zuletzt geöffnet von ${describeActor(activity.lastOpenedBy)} am ${at(activity.lastOpenedAt)}`
      : "Noch von niemandem geöffnet",
  ];
}

export function formatDate(iso: string, timeZone?: string): string {
  return new Intl.DateTimeFormat("de-DE", {
    day: "numeric", month: "long", year: "numeric", hour: "2-digit", minute: "2-digit", ...(timeZone ? { timeZone } : {}),
  }).format(new Date(iso)).replace(" um ", ", ");
}

function scopeText(payload: Record<string, unknown>): string {
  switch (payload.scopeType) {
    case "EVERYONE":
      return "alle Mitglieder";
    case "GROUP":
      return "eine Gruppe";
    default:
      return String(payload.subject ?? "");
  }
}

function placeText(payload: Record<string, unknown>, entryPreposition = "für"): string {
  const path = typeof payload.path === "string" ? payload.path : "";
  if (payload.target === "folder") {
    return path === "" ? "im ganzen Vault" : `im Ordner „${path}“`;
  }
  return `${entryPreposition} „${displayName(path)}“`;
}

function fileName(event: HistoryEvent): string {
  const path = (typeof event.payload.path === "string" ? event.payload.path : "") || event.path || "";
  return path.split("/").pop() ?? path;
}

function displayName(path: string): string {
  return (path.split("/").pop() ?? path).replace(/\.md$/i, "");
}

function parent(path: string): string {
  return path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "";
}
