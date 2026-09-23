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

export interface PathRule {
  pathPrefix: string;
  scopeSubject: string | null;
  effect: "ALLOW" | "DENY";
}

export interface Note {
  id: string; vaultId: string; path: string; noteLevel: number; createdBy: string; createdAt: string;
}
export interface NotePage { epochId: string; complete: boolean; nextCursor: string | null; notes: Note[]; }
export interface NoteContent { revision: number; updates: string[]; }
export interface AuditEvent { actor: string; action: string; payload: Record<string, unknown>; occurredAt: string; }
export interface PersonSuggestion { username: string; name: string; maskedEmail: string; alreadyMember: boolean; }
export type InviteAccess = "EDIT" | "READ";
export interface InviteResult { status: "ADDED" | "INVITED" | "ALREADY_MEMBER"; displayName: string; }
export interface PendingInvitation { id: string; email: string; access: InviteAccess; invitedBy: string; createdAt: string; expiresAt: string; }
export interface InvitationInfo {
  state: "PENDING" | "ACCEPTED" | "EXPIRED" | "REVOKED"; vaultName: string; invitedBy: string; maskedEmail: string;
  access: InviteAccess; expiresAt: string; enrollmentUrl: string | null;
}
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
      ...(options.body ? { "Content-Type": "application/json" } : {}),
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
    `/api/v1/vaults/${vaultId}/notes?${new URLSearchParams({ pageSize: "100", ...(cursor ? { cursor } : {}) })}`),
  createNote: (vaultId: string, path: string) => request<Note>(`/api/v1/vaults/${vaultId}/notes`,
    { method: "POST", body: JSON.stringify({ path, noteLevel: 1 }) }),
  renameNote: (vaultId: string, noteId: string, path: string) => request<Note>(`/api/v1/vaults/${vaultId}/notes/${noteId}`,
    { method: "PATCH", body: JSON.stringify({ path }) }),
  deleteNote: (vaultId: string, noteId: string, operationId: string) => request<void>(`/api/v1/vaults/${vaultId}/notes/${noteId}`,
    { method: "DELETE", headers: { "X-Operation-Id": operationId } }),
  noteContent: (vaultId: string, noteId: string) => request<NoteContent>(`/api/v1/vaults/${vaultId}/notes/${noteId}/content`),
  saveContent: (vaultId: string, noteId: string, expectedRevision: number, update: string) => request<{ revision: number }>(
    `/api/v1/vaults/${vaultId}/notes/${noteId}/content`, { method: "POST", body: JSON.stringify({ expectedRevision, update }) }),
  noteAudit: (vaultId: string, noteId: string) => request<AuditEvent[]>(`/api/v1/vaults/${vaultId}/notes/${noteId}/audit`),
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

  listPathRules: (vaultId: string) => request<PathRule[]>(`/api/v1/vaults/${vaultId}/path-rules`),
  createPathRule: (vaultId: string, pathPrefix: string, scopeSubject: string | null, effect: "ALLOW" | "DENY") =>
    request<PathRule>(`/api/v1/vaults/${vaultId}/path-rules`, {
      method: "POST",
      body: JSON.stringify({ pathPrefix, scopeSubject, effect }),
    }),
};
