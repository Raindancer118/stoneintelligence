import { getAccessToken } from "./auth";

const baseUrl = import.meta.env.VITE_PLATFORM_API_URL as string;

export interface Vault {
  id: string;
  name: string;
  createdAt: string;
}

export interface Role {
  id: string;
  name: string;
  permissions: string[];
}

export interface Group {
  id: string;
  name: string;
  memberSubjects: string[];
  roleIds: string[];
}


export interface Note {
  id: string; vaultId: string; path: string; noteLevel: number; createdBy: string; createdAt: string;
  /** Dateien (PDFs, Bilder, Anhänge) stehen mit in der Liste; fehlt bei älteren Servern. */
  kind?: "NOTE" | "FILE"; sha256?: string | null; size?: number | null; revision?: number;
}
export interface NotePage { epochId: string; complete: boolean; nextCursor: string | null; notes: Note[]; }
export interface NoteContent { revision: number; updates: string[]; }
export interface PersonSuggestion { username: string; name: string; maskedEmail: string; alreadyMember: boolean; }
export type InviteAccess = "EDIT" | "READ";
export interface InviteResult { status: "ADDED" | "INVITED" | "ALREADY_MEMBER"; displayName: string; }
export interface PendingInvitation { id: string; email: string; access: InviteAccess; invitedBy: string; createdAt: string; expiresAt: string; }
export interface InvitationInfo {
  state: "PENDING" | "ACCEPTED" | "EXPIRED" | "REVOKED"; vaultName: string; invitedBy: string; maskedEmail: string;
  access: InviteAccess; expiresAt: string; enrollmentUrl: string | null;
}
export interface AiService { id: string; name: string; levels: number[]; }
export type AiJobStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export interface AiJob {
  id: string; service: string; requestedBy: string; fileName: string; size: number; level: number; status: AiJobStatus;
  progress: string | null; percent: number | null; error: string | null; changeSetId: string | null; createdAt: string;
  finishedAt: string | null;
  /** Wartend: ab wann der Job wieder geholt wird. */
  availableAt: string | null;
  /** Wartet, bis der KI-Dienst wieder Kontingent hat - kein Fehlversuch. */
  waitingForCapacity: boolean;
}
/** Restmenge eines Zeitfensters; limit 0 = unbekannt. */
export interface AiQuota { remaining: number; limit: number; resetsAt: string | null; }
export interface AiProviderCapacity {
  provider: string; keys: number; usableKeys: number; exhausted: boolean; availableAgainAt: string | null;
  requests: AiQuota | null; tokens: AiQuota | null; credits: { remaining: number; limit: number | null } | null;
  observedAt: string | null;
}
/** Wie viel Kontingent ein KI-Dienst laut letzter Meldung des Workers hat; exhausted null = unbekannt. */
export interface AiServiceCapacity {
  service: string; reportedAt: string | null; stale: boolean; exhausted: boolean | null; availableAgainAt: string | null;
  providers: AiProviderCapacity[];
}
export interface AiChangeSet {
  id: string; service: string; agent: string; requestedBy: string; label: string; createdAt: string; revertedAt: string | null;
}
export interface AiChange { noteId: string; path: string; kind: "CREATED" | "UPDATED" | "FILE_CREATED" | "LINKED"; at: string; }
/** Naechtliche Verlinkung (ADR 0012); `maxLinksPerNote === null` = unbegrenzt. */
export interface LinkingSettings {
  enabled: boolean; linkHumanNotes: boolean; maxLinksPerNote: number | null; service: string | null;
  requestedBy: string | null; lastRunAt: string | null;
}
export interface AiChangeSetDetail { changeSet: AiChangeSet; changes: AiChange[]; }
export interface AiRevertReport { reverted: number; conflicts: { path: string; reason: string }[]; }

export type { AccessReport, Grant, Permission, ScopeType } from "./accessPlan";
export type { HistoryEvent, NoteActivity, NoteHistory } from "./historyText";
import type { HistoryEvent, NoteHistory } from "./historyText";
import type { AccessReport, Grant, Permission, ScopeType } from "./accessPlan";
export interface VaultMember { subject: string; groups: { id: string; name: string }[]; permissions: Permission[]; }
/** `permissions === null` heisst "wie im Vault", eine leere Liste "nichts". */
export interface GrantChange { scopeType: ScopeType; subject: string | null; permissions: Permission[] | null; }

export class ApiError extends Error {
  constructor(public status: number, detail?: string) {
    super(detail ?? ({ 401: "Deine Anmeldung ist abgelaufen. Bitte melde dich erneut an.",
      403: "Du hast für diese Aktion keine Berechtigung.", 404: "Dieser Eintrag ist nicht mehr verfügbar.",
      409: "Der Eintrag wurde inzwischen geändert oder der Pfad ist bereits belegt.",
      429: "Zu viele Anfragen. Bitte versuche es gleich erneut." } as Record<number, string>)[status]
      ?? `Die Anfrage ist fehlgeschlagen (HTTP ${status}). Bitte erneut versuchen.`);
  }
}

/** Fachliche Ablehnungen (HTTP 422) bringen einen fuer Menschen geschriebenen Text mit. */
async function problemDetail(response: Response): Promise<string | undefined> {
  if (response.status !== 422) return undefined;
  try {
    const body = await response.json() as { detail?: unknown };
    return typeof body.detail === "string" ? body.detail : undefined;
  } catch {
    return undefined;
  }
}

function scopeQuery(scopeType: ScopeType, subject: string | null): URLSearchParams {
  return new URLSearchParams(subject === null ? { scopeType } : { scopeType, subject });
}

/** Ohne Login - die Einladungsseite muss auch fuer Personen ohne Konto funktionieren. */
async function publicRequest<T>(path: string): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, { signal: AbortSignal.timeout(20_000) });
  if (!response.ok) throw new ApiError(response.status, await problemDetail(response));
  return await response.json() as T;
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = await getAccessToken();
  const response = await fetch(`${baseUrl}${path}`, {
    signal: AbortSignal.timeout(20_000),
    ...options,
    headers: {
      // Nur JSON-Koerper bekommen den JSON-Typ - bei FormData setzt der Browser Typ und Boundary selbst.
      ...(typeof options.body === "string" ? { "Content-Type": "application/json" } : {}),
      Authorization: `Bearer ${token}`,
      ...options.headers,
    },
  });
  if (!response.ok) {
    throw new ApiError(response.status, await problemDetail(response));
  }
  if (response.status === 204 || response.headers.get("Content-Length") === "0") {
    return undefined as T;
  }
  const body = await response.text();
  return (body.length === 0 ? undefined : JSON.parse(body)) as T;
}

export const api = {
  permissions: (vaultId: string) => request<string[]>(`/api/v1/vaults/${vaultId}/permissions`),
  listNotes: (vaultId: string, cursor?: string) => request<NotePage>(
    `/api/v1/vaults/${vaultId}/notes?${new URLSearchParams({ pageSize: "100", kinds: "note,file", ...(cursor ? { cursor } : {}) })}`),
  /** Inhalt einer Datei - mit Anmeldung geladen, angezeigt über eine Blob-URL (die API liefert immer als Anhang). */
  fileBlob: async (vaultId: string, fileId: string) => {
    const response = await fetch(`${baseUrl}/api/v1/vaults/${vaultId}/files/${fileId}/content`, {
      signal: AbortSignal.timeout(300_000), headers: { Authorization: `Bearer ${await getAccessToken()}` },
    });
    if (!response.ok) throw new ApiError(response.status, await problemDetail(response));
    return await response.blob();
  },
  createNote: (vaultId: string, path: string) => request<Note>(`/api/v1/vaults/${vaultId}/notes`,
    { method: "POST", body: JSON.stringify({ path, noteLevel: 1 }) }),
  renameNote: (vaultId: string, noteId: string, path: string) => request<Note>(`/api/v1/vaults/${vaultId}/notes/${noteId}`,
    { method: "PATCH", body: JSON.stringify({ path }) }),
  deleteNote: (vaultId: string, noteId: string, operationId: string) => request<void>(`/api/v1/vaults/${vaultId}/notes/${noteId}`,
    { method: "DELETE", headers: { "X-Operation-Id": operationId } }),
  noteContent: (vaultId: string, noteId: string) => request<NoteContent>(`/api/v1/vaults/${vaultId}/notes/${noteId}/content`),
  saveContent: (vaultId: string, noteId: string, expectedRevision: number, update: string) => request<{ revision: number }>(
    `/api/v1/vaults/${vaultId}/notes/${noteId}/content`, { method: "POST", body: JSON.stringify({ expectedRevision, update }) }),
  noteHistory: (vaultId: string, noteId: string) => request<NoteHistory>(`/api/v1/vaults/${vaultId}/notes/${noteId}/history`),
  vaultLog: (vaultId: string, path = "", limit = 100) =>
    request<HistoryEvent[]>(`/api/v1/vaults/${vaultId}/audit?${new URLSearchParams({ path, limit: String(limit) })}`),
  listVaults: () => request<Vault[]>("/api/v1/vaults"),
  createVault: (name: string) => request<Vault>("/api/v1/vaults", { method: "POST", body: JSON.stringify({ name }) }),

  listRoles: (vaultId: string) => request<Role[]>(`/api/v1/vaults/${vaultId}/roles`),
  createRole: (vaultId: string, name: string, permissions: string[]) =>
    request<Role>(`/api/v1/vaults/${vaultId}/roles`, { method: "POST", body: JSON.stringify({ name, permissions }) }),

  listGroups: (vaultId: string) => request<Group[]>(`/api/v1/vaults/${vaultId}/groups`),
  createGroup: (vaultId: string, name: string) =>
    request<Group>(`/api/v1/vaults/${vaultId}/groups`, { method: "POST", body: JSON.stringify({ name }) }),
  addMember: (vaultId: string, groupId: string, subject: string) =>
    request<void>(`/api/v1/vaults/${vaultId}/groups/${groupId}/members`, {
      method: "POST",
      body: JSON.stringify({ subject }),
    }),
  removeMember: (vaultId: string, groupId: string, subject: string) =>
    request<void>(`/api/v1/vaults/${vaultId}/groups/${groupId}/members/${encodeURIComponent(subject)}`, {
      method: "DELETE",
    }),
  assignRole: (vaultId: string, groupId: string, roleId: string) =>
    request<void>(`/api/v1/vaults/${vaultId}/groups/${groupId}/roles/${roleId}`, { method: "POST" }),
  unassignRole: (vaultId: string, groupId: string, roleId: string) =>
    request<void>(`/api/v1/vaults/${vaultId}/groups/${groupId}/roles/${roleId}`, { method: "DELETE" }),

  searchPeople: (vaultId: string, query: string) =>
    request<PersonSuggestion[]>(`/api/v1/vaults/${vaultId}/people?${new URLSearchParams({ q: query })}`),
  addPerson: (vaultId: string, username: string, access: InviteAccess) =>
    request<InviteResult>(`/api/v1/vaults/${vaultId}/members`, { method: "POST", body: JSON.stringify({ username, access }) }),
  inviteByEmail: (vaultId: string, email: string, access: InviteAccess) =>
    request<InviteResult>(`/api/v1/vaults/${vaultId}/invitations`, { method: "POST", body: JSON.stringify({ email, access }) }),
  listInvitations: (vaultId: string) => request<PendingInvitation[]>(`/api/v1/vaults/${vaultId}/invitations`),
  revokeInvitation: (vaultId: string, invitationId: string) =>
    request<void>(`/api/v1/vaults/${vaultId}/invitations/${invitationId}`, { method: "DELETE" }),
  describeInvitation: (token: string) => publicRequest<InvitationInfo>(`/api/v1/invitations/${encodeURIComponent(token)}`),
  acceptInvitation: (token: string) =>
    request<{ vaultId: string; vaultName: string }>(`/api/v1/invitations/${encodeURIComponent(token)}/accept`, { method: "POST" }),

  aiServices: () => request<AiService[]>("/api/v1/ai/services"),
  aiCapacity: (serviceId: string) => request<AiServiceCapacity>(`/api/v1/ai/services/${encodeURIComponent(serviceId)}/capacity`),
  listAiJobs: (vaultId: string) => request<AiJob[]>(`/api/v1/vaults/${vaultId}/ai/jobs`),
  /** Eine Datei je Anfrage - so bleibt jede unter dem Server-Limit und scheitert einzeln. */
  uploadAiDocument: (vaultId: string, service: string, level: number, file: File) => {
    const form = new FormData();
    form.append("service", service);
    form.append("level", String(level));
    form.append("files", file, file.name);
    return request<AiJob[]>(`/api/v1/vaults/${vaultId}/ai/jobs`, { method: "POST", body: form, signal: AbortSignal.timeout(120_000) });
  },
  cancelAiJob: (vaultId: string, jobId: string) => request<AiJob>(`/api/v1/vaults/${vaultId}/ai/jobs/${jobId}/cancel`, { method: "POST" }),
  listChangeSets: (vaultId: string) => request<AiChangeSet[]>(`/api/v1/vaults/${vaultId}/ai/change-sets`),
  changeSet: (vaultId: string, changeSetId: string) =>
    request<AiChangeSetDetail>(`/api/v1/vaults/${vaultId}/ai/change-sets/${changeSetId}`),
  revertChangeSet: (vaultId: string, changeSetId: string) =>
    request<AiRevertReport>(`/api/v1/vaults/${vaultId}/ai/change-sets/${changeSetId}/revert`, { method: "POST" }),

  // Rechte je Ordner und Eintrag (ADR 0011)
  noteAccess: (vaultId: string, noteId: string) => request<AccessReport>(`/api/v1/vaults/${vaultId}/notes/${noteId}/access`),
  folderAccess: (vaultId: string, path: string) =>
    request<AccessReport>(`/api/v1/vaults/${vaultId}/folders/access?${new URLSearchParams({ path })}`),
  putNoteGrant: (vaultId: string, noteId: string, change: GrantChange) =>
    request<Grant>(`/api/v1/vaults/${vaultId}/notes/${noteId}/access/grants`, { method: "PUT", body: JSON.stringify(change) }),
  putFolderGrant: (vaultId: string, path: string, change: GrantChange) =>
    request<Grant>(`/api/v1/vaults/${vaultId}/folders/access/grants?${new URLSearchParams({ path })}`, { method: "PUT", body: JSON.stringify(change) }),
  removeNoteGrant: (vaultId: string, noteId: string, scopeType: ScopeType, subject: string | null) =>
    request<void>(`/api/v1/vaults/${vaultId}/notes/${noteId}/access/grants?${scopeQuery(scopeType, subject)}`, { method: "DELETE" }),
  removeFolderGrant: (vaultId: string, path: string, scopeType: ScopeType, subject: string | null) =>
    request<void>(`/api/v1/vaults/${vaultId}/folders/access/grants?${new URLSearchParams({ path })}&${scopeQuery(scopeType, subject)}`, { method: "DELETE" }),
  linkingSettings: (vaultId: string) => request<LinkingSettings>(`/api/v1/vaults/${vaultId}/linking`),
  updateLinking: (vaultId: string, change: { enabled: boolean; linkHumanNotes: boolean; maxLinksPerNote: number | null; service: string | null }) =>
    request<LinkingSettings>(`/api/v1/vaults/${vaultId}/linking`, { method: "PUT", body: JSON.stringify(change) }),
  runLinking: (vaultId: string) => request<AiJob>(`/api/v1/vaults/${vaultId}/linking/run`, { method: "POST" }),
  listMembers: (vaultId: string) => request<VaultMember[]>(`/api/v1/vaults/${vaultId}/members`),
  removeFromVault: (vaultId: string, subject: string) =>
    request<void>(`/api/v1/vaults/${vaultId}/members/${encodeURIComponent(subject)}`, { method: "DELETE" }),
  updateRole: (vaultId: string, roleId: string, name: string | null, permissions: string[] | null) =>
    request<void>(`/api/v1/vaults/${vaultId}/roles/${roleId}`, { method: "PATCH", body: JSON.stringify({ name, permissions }) }),
  deleteRole: (vaultId: string, roleId: string) => request<void>(`/api/v1/vaults/${vaultId}/roles/${roleId}`, { method: "DELETE" }),
  renameGroup: (vaultId: string, groupId: string, name: string) =>
    request<void>(`/api/v1/vaults/${vaultId}/groups/${groupId}`, { method: "PATCH", body: JSON.stringify({ name }) }),
  deleteGroup: (vaultId: string, groupId: string) => request<void>(`/api/v1/vaults/${vaultId}/groups/${groupId}`, { method: "DELETE" }),
  renameVault: (vaultId: string, name: string) =>
    request<Vault>(`/api/v1/vaults/${vaultId}`, { method: "PATCH", body: JSON.stringify({ name }) }),
};
