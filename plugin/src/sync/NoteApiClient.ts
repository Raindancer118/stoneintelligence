import type { AiChangeSet, AiChangeSetView, AiRevertReport } from "../ui/aiChanges";
import type { AccessReport, Grant, Permission, ScopeType } from "./accessPlan";
import { obsidianFetch } from "./obsidianFetch";
import type { CommentAnchor } from "./commentAnchor";
import type { FeedChanges } from "./serverMirror";
import type { AskLike } from "./askStatus";
import type { DeviceReportResult } from "./deviceSession";
import type { PluginPolicy } from "./pluginPolicy";
import { withRateLimitRetry } from "./retryFetch";

export type AccessTokenProvider = () => Promise<string>;

export interface NoteListItem {
  id: string;
  vaultId: string;
  path: string;
  noteLevel: number;
  /** Hoechste gespeicherte Update-Sequenz - fehlt bei Servern vor dieser Erweiterung. Bei Dateien: die Fassung (0 = noch leer). */
  revision?: number;
  /** Fehlt bei Servern ohne Datei-Synchronisation (ADR 0009) - dann ist alles eine Notiz. */
  kind?: "NOTE" | "FILE" | "CANVAS";
  /** Nur bei Dateien mit Inhalt. */
  sha256?: string | null;
  size?: number | null;
  /** Was ich hier darf (ADR 0011) - fehlt bei Servern vor 0.24. */
  permissions?: Permission[] | null;
  /** Ob eine Freigabe diesen Eintrag betrifft - nur fuer Verwaltende gesetzt. */
  shared?: boolean | null;
}

export interface CommentView {
  id: string; author: string; authorName: string; mine: boolean; body: string; createdAt: string; editedAt: string | null;
}
/** `anchor === null`: Kommentar zur ganzen Notiz. */
export interface CommentThreadView {
  id: string; noteId: string; anchor: CommentAnchor | null; resolved: boolean; resolvedByName: string | null;
  resolvedAt: string | null; createdAt: string; comments: CommentView[];
}
export interface CommentNotification {
  id: string; vaultId: string; vaultName: string; noteId: string; notePath: string; threadId: string; commentId: string;
  actorName: string; excerpt: string; createdAt: string; read: boolean;
}
export interface CommentNotifications { items: CommentNotification[]; unread: number; }

/** Ein Gerät im Register (ADR 0019): letzte Meldung des Plugins und Bewertung gegen die Richtlinie. */
export interface DeviceView {
  deviceId: string; subject: string; person: string; name: string; platform: string; obsidianVersion: string; pluginVersion: string;
  restrictedMode: boolean; plugins: { id: string; version: string | null }[]; firstSeen: string; lastSeen: string;
  status: "COMPLIANT" | "NON_COMPLIANT" | "REVOKED" | "WIPE_PENDING" | "UNKNOWN"; compliant: boolean; offending: string[];
  revoked: boolean; wipePending: boolean; wipedAt: string | null;
}
export interface DeviceReportBody {
  name: string; platform: string; obsidianVersion: string; pluginVersion: string; restrictedMode: boolean;
  plugins: { id: string; version: string }[]; wiped: boolean;
}

export interface GroupRef { id: string; name: string; }
export interface VaultMember { subject: string; displayName?: string | null; groups: GroupRef[]; permissions: Permission[]; }
/** `idpGroup`: verknuepfte Gruppe des Identitaetsanbieters; ihre Leute stehen nur in `idpMemberCount` (ADR 0013). */
export interface VaultGroup {
  id: string; name: string; memberSubjects: string[]; memberNames?: Record<string, string>; roleIds: string[];
  idpGroup?: string | null; idpMemberCount?: number | null;
}
export interface VaultRole { id: string; name: string; permissions: Permission[]; }
export type { HistoryEvent, NoteActivity, NoteHistory } from "./historyText";
import type { HistoryEvent, NoteHistory } from "./historyText";
import type { Restored, VersionList, VersionText } from "./versionText";
/** `local`: laeuft auf eigener Infrastruktur, Auszuege verlassen den Server nicht (ADR 0017); fehlt bei aelteren Servern. */
export interface AiService { id: string; name: string; levels: number[]; local?: boolean; }
/** "Frag den Vault" (ADR 0017): eine Quelle der Antwort - Notiz, Stelle und kurzer Auszug. */
export interface AskCitation { n: number; noteId: string; path: string; heading: string; excerpt: string; }
export interface AskQuestion {
  id: string; question: string; service: string; status: AskLike["status"]; progress: string | null; percent: number | null;
  error: string | null; waitingForCapacity: boolean; availableAt: string | null; askedAt: string; answeredAt: string | null;
  /** Markdown mit [n]-Zitaten; leer bei `restricted` oder `NOT_ANSWERABLE`. */
  answer: string | null; citations: AskCitation[];
  /** Eine zitierte Notiz ist nicht mehr lesbar - die Antwort ist verborgen. */
  restricted: boolean;
  /** Notizen mit passenden Stellen, die ein externer Dienst nicht bekam (Verfasser nicht einverstanden). */
  omittedForConsent: number;
}
export type AiJobStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export interface AiJob {
  id: string; service: string; requestedBy: string; fileName: string; size: number; level: number; status: AiJobStatus;
  progress: string | null; percent: number | null; error: string | null; changeSetId: string | null; createdAt: string;
  finishedAt: string | null; availableAt: string | null; waitingForCapacity: boolean;
}
/** Naechtliche Verlinkung eines Vaults (ADR 0012); `maxLinksPerNote === null` = unbegrenzt. */
export type LinkingMode = "LITERAL" | "SEMANTIC" | "AI";
/** Eine aehnliche Notiz (ADR 0012): ihr passendster Abschnitt und wie aehnlich (0..1). */
export interface SimilarNote { noteId: string; path: string; heading: string | null; similarity: number; }
export interface LinkingSettings {
  enabled: boolean; mode?: LinkingMode; linkHumanNotes: boolean; maxLinksPerNote: number | null; service: string | null;
  requestedBy: string | null; lastRunAt: string | null;
  /** Meine Einwilligung zur KI-Pruefung eigener Notizen, und wie viele Mitglieder insgesamt eingewilligt haben. */
  aiConsent?: boolean; aiConsentCount?: number;
}
/** Wie viel Kontingent ein KI-Dienst laut letzter Worker-Meldung hat; `exhausted === null` = unbekannt. */
export interface AiCapacity { service: string; reportedAt: string | null; stale: boolean; exhausted: boolean | null; availableAgainAt: string | null; }
/** `permissions === null` heisst "wie im Vault", eine leere Liste "nichts". */
export interface GrantChange { scopeType: ScopeType; subject: string | null; permissions: Permission[] | null; }

/** `canvas`: der Server synchronisiert Canvas live (ADR 0018); fehlt bei aelteren Servern. */
export interface FileLimits { maxFileBytes: number; vaultQuotaBytes: number; canvas?: boolean; }

/** Welche Arten ein Listenaufruf liefern soll: nur Notizen, mit Dateien, oder zusaetzlich mit Canvas. */
export type EntryKinds = boolean | "canvas";

export function kindsParam(kinds: EntryKinds): string {
  return kinds === "canvas" ? "note,file,canvas" : kinds ? "note,file" : "note";
}
export interface UploadedFile { revision: number; sha256: string; size: number; }
export interface DownloadedFile { bytes: ArrayBuffer; revision: number; sha256: string; }

/** Die Datei wurde inzwischen anderswo geaendert; {@link currentRevision} ist die Fassung des Servers. */
export class FileConflictError extends Error {
  constructor(readonly currentRevision: number) {
    super(`file changed meanwhile (revision ${currentRevision})`);
    this.name = "FileConflictError";
  }
}

export interface VaultSummary {
  id: string;
  name: string;
  createdAt: string;
  /** Verwaltende haben festgelegt: Geraete synchronisieren nur ihre Arbeitsbereiche (ADR 0013). Fehlt bei aelteren Servern. */
  selectiveSync?: boolean;
}

export interface FolderChild { path: string; hasChildren: boolean; }

export type InviteAccess = "EDIT" | "READ";
export interface PersonSuggestion { username: string; name: string; maskedEmail: string; alreadyMember: boolean; }
export interface InviteResult { status: "ADDED" | "INVITED" | "ALREADY_MEMBER"; displayName: string; }
export interface PendingInvitation { id: string; email: string; access: InviteAccess; expiresAt: string; }

/** Ob eine (in der Liste fehlende) Notiz geloescht ist oder nur nicht mehr sichtbar. */
export type NoteStatus = "exists" | "deleted" | "forbidden";

/** Fehlgeschlagene Anfrage MIT Statuscode - der Abgleich reagiert auf 403/404/409 unterschiedlich. */
export class HttpError extends Error {
  constructor(
    readonly status: number,
    action: string,
    /** Fuer Menschen geschriebene Begruendung des Servers (HTTP 422), falls vorhanden. */
    detail?: string,
  ) {
    super(detail ?? `${action}: HTTP ${status}`);
    this.name = "HttpError";
  }
}

export interface ReconciliationPage {
  epochId: string;
  complete: boolean;
  nextCursor: string | null;
  notes: NoteListItem[];
}

/**
 * REST-Client fuer ID-first Note-CRUD gegen platform-api (Plan.md Abschnitt 3, Fehlerklasse 5).
 *
 * <p>Seit Phase 3 (OIDC) schickt der Client ein {@code Authorization: Bearer}-Token statt des
 * frueheren, vom Client selbst behaupteten {@code X-Actor}-Headers - der Actor wird jetzt
 * serverseitig aus dem validierten Token abgeleitet ({@code preferred_username}-Claim).
 */
export interface AuditRetention {
  days: number | null;
  minimumDays: number;
}

export class NoteApiClient {
  constructor(
    private readonly baseUrl: string,
    private readonly getAccessToken: AccessTokenProvider,
    private readonly fetchImpl: typeof fetch = withRateLimitRetry(obsidianFetch),
  ) {}

  /** Legt einen neuen Vault an; der anlegende Actor bekommt serverseitig automatisch volle Rechte darin. */
  async createVault(name: string): Promise<string> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${await this.getAccessToken()}` },
      body: JSON.stringify({ name }),
    });
    if (!response.ok) {
      throw new HttpError(response.status, "failed to create vault");
    }
    const created = (await response.json()) as { id: string };
    return created.id;
  }

  async createNote(vaultId: string, path: string, noteLevel: number): Promise<string> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/notes`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${await this.getAccessToken()}` },
      body: JSON.stringify(path.toLowerCase().endsWith(".canvas") ? { path, noteLevel, kind: "canvas" } : { path, noteLevel }),
    });
    if (!response.ok) {
      throw new HttpError(response.status, "failed to create note");
    }
    const created = (await response.json()) as { id: string };
    return created.id;
  }

  /**
   * Fehlerklasse 2 (Plan.md Abschnitt 3): eine Seite ist erst dann vollstaendig, wenn
   * `complete === true` - ein `nextCursor` OHNE `complete` darf niemals als Grundlage fuer lokale
   * Loeschungen/Vollstaendigkeitsannahmen dienen. `listAllNotes` (unten) kapselt das Paging.
   */
  async listNotes(vaultId: string, cursor?: string, pageSize = 100, includeFiles: EntryKinds = false,
                  scope: string[] | null = null): Promise<ReconciliationPage> {
    const params = new URLSearchParams({ pageSize: String(pageSize), ...(includeFiles ? { kinds: kindsParam(includeFiles) } : {}) });
    if (cursor) {
      params.set("cursor", cursor);
    }
    (scope ?? []).forEach((area) => params.append("scope", area));
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/notes?${params.toString()}`, {
      headers: { Authorization: `Bearer ${await this.getAccessToken()}` },
    });
    if (!response.ok) {
      throw new HttpError(response.status, "failed to list notes");
    }
    return (await response.json()) as ReconciliationPage;
  }

  /** Laeuft `listNotes` bis `complete === true` durch und gibt alle Notizen des Vaults zurueck. */
  async listAllNotes(vaultId: string, scope: string[] | null = null): Promise<NoteListItem[]> {
    return this.listAll(vaultId, false, scope);
  }

  /** Notizen UND Dateien; ein Server ohne Dateien liefert einfach nur Notizen (ohne `kind`). */
  async listAllEntries(vaultId: string, scope: string[] | null = null, kinds: EntryKinds = true): Promise<NoteListItem[]> {
    return this.listAll(vaultId, kinds, scope);
  }

  /** `scope`: nur diese Arbeitsbereiche (ADR 0013), `null` = der ganze Vault. */
  private async listAll(vaultId: string, includeFiles: EntryKinds, scope: string[] | null = null): Promise<NoteListItem[]> {
    const all: NoteListItem[] = [];
    let cursor: string | undefined;
    for (;;) {
      const page = await this.listNotes(vaultId, cursor, scope ? 500 : 100, includeFiles, scope);
      all.push(...page.notes);
      if (page.complete) {
        return all;
      }
      cursor = page.nextCursor ?? undefined;
      if (!cursor) {
        return all;
      }
    }
  }

  async renameNote(vaultId: string, noteId: string, newPath: string): Promise<void> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/notes/${noteId}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${await this.getAccessToken()}` },
      body: JSON.stringify({ path: newPath }),
    });
    if (!response.ok) {
      throw new HttpError(response.status, "failed to rename note");
    }
  }

  /** `null`, wenn der Server keine Dateien kennt (ADR 0009). */
  async fileLimits(): Promise<FileLimits | null> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/files/limits`, {
      headers: { Authorization: `Bearer ${await this.getAccessToken()}` },
    });
    if (response.status === 404) {
      return null;
    }
    if (!response.ok) {
      throw new HttpError(response.status, "failed to read file limits");
    }
    return (await response.json()) as FileLimits;
  }

  async createFile(vaultId: string, path: string): Promise<string> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/files`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${await this.getAccessToken()}` },
      body: JSON.stringify({ path }),
    });
    if (!response.ok) {
      throw new HttpError(response.status, "failed to create file");
    }
    return ((await response.json()) as { id: string }).id;
  }

  /** Neue Fassung auf Basis von `baseRevision` (0 = erste); ist sie veraltet: {@link FileConflictError}. */
  async uploadFile(vaultId: string, fileId: string, baseRevision: number, bytes: ArrayBuffer, contentType: string): Promise<UploadedFile> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/files/${fileId}/content`, {
      method: "PUT",
      headers: { "If-Match": `"${baseRevision}"`, "Content-Type": contentType, Authorization: `Bearer ${await this.getAccessToken()}` },
      body: bytes,
    });
    if (response.status === 409) {
      throw new FileConflictError(Number(response.headers?.get?.("X-Current-Revision") ?? "0"));
    }
    if (!response.ok) {
      throw new HttpError(response.status, "failed to upload file", await problemDetail(response));
    }
    return (await response.json()) as UploadedFile;
  }

  async downloadFile(vaultId: string, fileId: string): Promise<DownloadedFile> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/files/${fileId}/content`, {
      headers: { Authorization: `Bearer ${await this.getAccessToken()}` },
    });
    if (!response.ok) {
      throw new HttpError(response.status, "failed to download file");
    }
    const revision = Number((response.headers.get("ETag") ?? "").replace(/^W\//, "").replace(/"/g, ""));
    return { bytes: await response.arrayBuffer(), revision, sha256: response.headers.get("X-Content-SHA256") ?? "" };
  }

  /** Ordner des Vaults; `null`, wenn der Server noch keine Ordner-Synchronisation kennt. */
  /**
   * Aenderungs-Feed (ADR 0013). Ohne `since` nur ein Start-Cursor. `null`: der Server kennt den Feed
   * noch nicht - dann weiter mit der vollen Liste.
   */
  async changes(vaultId: string, since: string | null, includeFiles: EntryKinds, scope: string[] | null = null): Promise<FeedChanges | null> {
    const params = new URLSearchParams({ limit: "1000", kinds: kindsParam(includeFiles) });
    if (since !== null) {
      params.set("since", since);
    }
    (scope ?? []).forEach((area) => params.append("scope", area));
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/changes?${params.toString()}`, {
      headers: { Authorization: `Bearer ${await this.getAccessToken()}` },
    });
    if (response.status === 404) {
      return null;
    }
    if (!response.ok) {
      throw new HttpError(response.status, "failed to read changes", await problemDetail(response));
    }
    return (await response.json()) as FeedChanges;
  }

  /** Titel und Pfad, auf dem Server gesucht - nur was ich lesen darf. */
  async searchNotes(vaultId: string, query: string, limit = 30): Promise<NoteListItem[]> {
    const params = new URLSearchParams({ q: query, limit: String(limit), kinds: "note,file" });
    return (await this.json<{ notes: NoteListItem[] }>(`/api/v1/vaults/${vaultId}/notes/search?${params.toString()}`,
      "failed to search notes")).notes;
  }

  /** Ein Eintrag mit genau diesem Pfad, ueber die Server-Suche statt die ganze Liste. */
  async findByPath(vaultId: string, path: string): Promise<NoteListItem | null> {
    return (await this.searchNotes(vaultId, path, 200)).find((note) => note.path === path) ?? null;
  }

  /** Direkte, lesbare Unterordner (`""` = oberste Ebene) - fuer Ordnerauswahl ohne alle Ordner zu laden. */
  async folderChildren(vaultId: string, path: string): Promise<FolderChild[]> {
    return this.json<FolderChild[]>(`/api/v1/vaults/${vaultId}/folders/children?${new URLSearchParams({ path })}`, "failed to list folders");
  }

  async listFolders(vaultId: string): Promise<string[] | null> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/folders`, {
      headers: { Authorization: `Bearer ${await this.getAccessToken()}` },
    });
    if (response.status === 404) {
      return null;
    }
    if (!response.ok) {
      throw new HttpError(response.status, "failed to list folders");
    }
    return (await response.json()) as string[];
  }

  async createFolder(vaultId: string, path: string): Promise<void> {
    await this.folderRequest(`${this.baseUrl}/api/v1/vaults/${vaultId}/folders`, "POST", { path }, "create folder");
  }

  async renameFolder(vaultId: string, from: string, to: string): Promise<void> {
    await this.folderRequest(`${this.baseUrl}/api/v1/vaults/${vaultId}/folders/rename`, "POST", { from, to }, "rename folder");
  }

  async deleteFolder(vaultId: string, path: string): Promise<void> {
    const query = new URLSearchParams({ path }).toString();
    await this.folderRequest(`${this.baseUrl}/api/v1/vaults/${vaultId}/folders?${query}`, "DELETE", undefined, "delete folder");
  }

  private async folderRequest(url: string, method: string, body: unknown, action: string): Promise<void> {
    const headers: Record<string, string> = { Authorization: `Bearer ${await this.getAccessToken()}` };
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    const response = await this.fetchImpl(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    if (!response.ok) {
      throw new HttpError(response.status, `failed to ${action}`);
    }
  }

  async deleteNote(vaultId: string, noteId: string, operationId: string): Promise<void> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/notes/${noteId}`, {
      method: "DELETE",
      headers: { "X-Operation-Id": operationId, Authorization: `Bearer ${await this.getAccessToken()}` },
    });
    // 404: bereits geloescht (z. B. Wiederholung einer offline gemerkten Loeschung) - Ziel erreicht.
    if (!response.ok && response.status !== 404) {
      throw new HttpError(response.status, "failed to delete note");
    }
  }

  async listVaults(): Promise<VaultSummary[]> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults`, {
      headers: { Authorization: `Bearer ${await this.getAccessToken()}` },
    });
    if (!response.ok) {
      throw new HttpError(response.status, "failed to list vaults");
    }
    return (await response.json()) as VaultSummary[];
  }

  async noteStatus(vaultId: string, noteId: string): Promise<NoteStatus> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/notes/${noteId}`, {
      headers: { Authorization: `Bearer ${await this.getAccessToken()}` },
    });
    if (response.ok) {
      return "exists";
    }
    if (response.status === 404) {
      return "deleted";
    }
    if (response.status === 403) {
      return "forbidden";
    }
    throw new HttpError(response.status, "failed to read note");
  }

  async permissions(vaultId: string): Promise<string[]> {
    return this.json<string[]>(`/api/v1/vaults/${vaultId}/permissions`, "failed to read permissions");
  }

  async searchPeople(vaultId: string, query: string): Promise<PersonSuggestion[]> {
    return this.json<PersonSuggestion[]>(`/api/v1/vaults/${vaultId}/people?${new URLSearchParams({ q: query })}`, "failed to search people");
  }

  async addPerson(vaultId: string, username: string, access: InviteAccess): Promise<InviteResult> {
    return this.json<InviteResult>(`/api/v1/vaults/${vaultId}/members`, "failed to add member", { username, access });
  }

  async inviteByEmail(vaultId: string, email: string, access: InviteAccess): Promise<InviteResult> {
    return this.json<InviteResult>(`/api/v1/vaults/${vaultId}/invitations`, "failed to invite", { email, access });
  }

  async listInvitations(vaultId: string): Promise<PendingInvitation[]> {
    return this.json<PendingInvitation[]>(`/api/v1/vaults/${vaultId}/invitations`, "failed to list invitations");
  }

  async revokeInvitation(vaultId: string, invitationId: string): Promise<void> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/invitations/${invitationId}`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${await this.getAccessToken()}` },
    });
    if (!response.ok) {
      throw new HttpError(response.status, "failed to revoke invitation", await problemDetail(response));
    }
  }

  /** Die KI-Laeufe des Vaults, neueste zuerst (ADR 0008). */
  async listAiChangeSets(vaultId: string): Promise<AiChangeSet[]> {
    return this.json<AiChangeSet[]>(`/api/v1/vaults/${vaultId}/ai/change-sets`, "failed to list AI changes");
  }

  async aiChangeSet(vaultId: string, changeSetId: string): Promise<AiChangeSetView> {
    return this.json<AiChangeSetView>(`/api/v1/vaults/${vaultId}/ai/change-sets/${changeSetId}`, "failed to load AI change");
  }

  /** Braucht Bearbeiten- und Loeschrecht; was seit der KI jemand geaendert hat, bleibt stehen. */
  async revertAiChangeSet(vaultId: string, changeSetId: string): Promise<AiRevertReport> {
    return this.json<AiRevertReport>(`/api/v1/vaults/${vaultId}/ai/change-sets/${changeSetId}/revert`, "failed to undo AI change", {});
  }

  /** Wer hier was darf und woher (Verwaltende sehen alle, sonst nur die eigenen Rechte). */
  async noteAccess(vaultId: string, noteId: string): Promise<AccessReport> {
    return this.json<AccessReport>(`/api/v1/vaults/${vaultId}/notes/${noteId}/access`, "failed to read access");
  }

  /** `path` = Ordnerpfad, `""` = der ganze Vault. */
  async folderAccess(vaultId: string, path: string): Promise<AccessReport> {
    return this.json<AccessReport>(`/api/v1/vaults/${vaultId}/folders/access?${new URLSearchParams({ path })}`, "failed to read access");
  }

  async putNoteGrant(vaultId: string, noteId: string, change: GrantChange): Promise<Grant> {
    return this.send<Grant>("PUT", `/api/v1/vaults/${vaultId}/notes/${noteId}/access/grants`, "failed to share", change);
  }

  async putFolderGrant(vaultId: string, path: string, change: GrantChange): Promise<Grant> {
    return this.send<Grant>("PUT", `/api/v1/vaults/${vaultId}/folders/access/grants?${new URLSearchParams({ path })}`, "failed to share", change);
  }

  async removeNoteGrant(vaultId: string, noteId: string, scopeType: ScopeType, subject: string | null): Promise<void> {
    await this.send<void>("DELETE", `/api/v1/vaults/${vaultId}/notes/${noteId}/access/grants?${scopeQuery(scopeType, subject)}`, "failed to remove share");
  }

  async removeFolderGrant(vaultId: string, path: string, scopeType: ScopeType, subject: string | null): Promise<void> {
    await this.send<void>("DELETE", `/api/v1/vaults/${vaultId}/folders/access/grants?${new URLSearchParams({ path })}&${scopeQuery(scopeType, subject)}`, "failed to remove share");
  }

  /** Alle Freigaben, die ich verwalten darf - fuer die Kennzeichen im Dateibaum. */
  async listGrants(vaultId: string): Promise<Grant[]> {
    return this.json<Grant[]>(`/api/v1/vaults/${vaultId}/access/grants`, "failed to list shares");
  }

  async listMembers(vaultId: string): Promise<VaultMember[]> {
    return this.json<VaultMember[]>(`/api/v1/vaults/${vaultId}/members`, "failed to list members");
  }

  async listGroups(vaultId: string): Promise<VaultGroup[]> {
    return this.json<VaultGroup[]>(`/api/v1/vaults/${vaultId}/groups`, "failed to list groups");
  }

  async listRoles(vaultId: string): Promise<VaultRole[]> {
    return this.json<VaultRole[]>(`/api/v1/vaults/${vaultId}/roles`, "failed to list roles");
  }

  async createRole(vaultId: string, name: string, permissions: Permission[]): Promise<VaultRole> {
    return this.json<VaultRole>(`/api/v1/vaults/${vaultId}/roles`, "failed to create role", { name, permissions });
  }

  /** `null` laesst den Teil, wie er ist. */
  async updateRole(vaultId: string, roleId: string, name: string | null, permissions: Permission[] | null): Promise<void> {
    await this.send<void>("PATCH", `/api/v1/vaults/${vaultId}/roles/${roleId}`, "failed to change role", { name, permissions });
  }

  async deleteRole(vaultId: string, roleId: string): Promise<void> {
    await this.send<void>("DELETE", `/api/v1/vaults/${vaultId}/roles/${roleId}`, "failed to delete role");
  }

  async createGroup(vaultId: string, name: string): Promise<VaultGroup> {
    return this.json<VaultGroup>(`/api/v1/vaults/${vaultId}/groups`, "failed to create group", { name });
  }

  async renameGroup(vaultId: string, groupId: string, name: string): Promise<void> {
    await this.send<void>("PATCH", `/api/v1/vaults/${vaultId}/groups/${groupId}`, "failed to rename group", { name });
  }

  async deleteGroup(vaultId: string, groupId: string): Promise<void> {
    await this.send<void>("DELETE", `/api/v1/vaults/${vaultId}/groups/${groupId}`, "failed to delete group");
  }

  /** Wer beim Identitaetsanbieter in `idpGroup` ist, ist Mitglied der Gruppe (ADR 0013). */
  async linkIdpGroup(vaultId: string, groupId: string, idpGroup: string): Promise<void> {
    await this.send<void>("PUT", `/api/v1/vaults/${vaultId}/groups/${groupId}/idp-group`, "failed to link the group", { idpGroup });
  }

  async unlinkIdpGroup(vaultId: string, groupId: string): Promise<void> {
    await this.send<void>("DELETE", `/api/v1/vaults/${vaultId}/groups/${groupId}/idp-group`, "failed to unlink the group");
  }

  async addGroupMember(vaultId: string, groupId: string, subject: string): Promise<void> {
    await this.send<void>("POST", `/api/v1/vaults/${vaultId}/groups/${groupId}/members`, "failed to add to group", { subject });
  }

  async removeGroupMember(vaultId: string, groupId: string, subject: string): Promise<void> {
    await this.send<void>("DELETE", `/api/v1/vaults/${vaultId}/groups/${groupId}/members/${encodeURIComponent(subject)}`, "failed to remove from group");
  }

  async assignRole(vaultId: string, groupId: string, roleId: string): Promise<void> {
    await this.send<void>("POST", `/api/v1/vaults/${vaultId}/groups/${groupId}/roles/${roleId}`, "failed to assign role");
  }

  async unassignRole(vaultId: string, groupId: string, roleId: string): Promise<void> {
    await this.send<void>("DELETE", `/api/v1/vaults/${vaultId}/groups/${groupId}/roles/${roleId}`, "failed to unassign role");
  }

  /** Jemanden ganz aus dem Vault nehmen - oder, mit dem eigenen Namen, den Vault verlassen. */
  async removeFromVault(vaultId: string, subject: string): Promise<void> {
    await this.send<void>("DELETE", `/api/v1/vaults/${vaultId}/members/${encodeURIComponent(subject)}`, "failed to remove member");
  }

  /** Nur Verwaltende: alle Geraete synchronisieren nur ihre Arbeitsbereiche (ADR 0013). */
  async setSelectiveSync(vaultId: string, selectiveSync: boolean): Promise<VaultSummary> {
    return this.send<VaultSummary>("PATCH", `/api/v1/vaults/${vaultId}`, "failed to change vault", { selectiveSync });
  }

  /** Nur Verwaltende: Aufbewahrungsfrist des Protokolls in Tagen, `null` = unbegrenzt (ADR 0015). */
  async auditRetention(vaultId: string): Promise<AuditRetention> {
    return this.json<AuditRetention>(`/api/v1/vaults/${vaultId}/audit/retention`, "failed to read audit retention");
  }

  async setAuditRetention(vaultId: string, days: number | null): Promise<AuditRetention> {
    return this.send<AuditRetention>("PUT", `/api/v1/vaults/${vaultId}/audit/retention`, "failed to change audit retention", { days });
  }

  /** Plugin-Richtlinie des Vaults (ADR 0019); lesen darf jedes Mitglied, ändern nur Verwaltende. */
  async pluginPolicy(vaultId: string): Promise<PluginPolicy> {
    return this.json<PluginPolicy>(`/api/v1/vaults/${vaultId}/plugin-policy`, "failed to read plugin policy");
  }

  async setPluginPolicy(vaultId: string, policy: Pick<PluginPolicy, "mode" | "allowed">): Promise<PluginPolicy> {
    return this.send<PluginPolicy>("PUT", `/api/v1/vaults/${vaultId}/plugin-policy`, "failed to change plugin policy", policy);
  }

  /** Meldet dieses Gerät (Plugins, Versionen); die Antwort sagt, ob es synchronisieren darf oder löschen soll. */
  async reportDevice(vaultId: string, deviceId: string, report: DeviceReportBody): Promise<DeviceReportResult> {
    return this.send<DeviceReportResult>("PUT", `/api/v1/vaults/${vaultId}/devices/${encodeURIComponent(deviceId)}`, "failed to report device", report);
  }

  async listDevices(vaultId: string): Promise<DeviceView[]> {
    return this.json<DeviceView[]>(`/api/v1/vaults/${vaultId}/devices`, "failed to list devices");
  }

  async revokeDevice(vaultId: string, deviceId: string): Promise<void> {
    await this.send<void>("POST", `/api/v1/vaults/${vaultId}/devices/${encodeURIComponent(deviceId)}/revoke`, "failed to revoke device", {});
  }

  async wipeDevice(vaultId: string, deviceId: string): Promise<void> {
    await this.send<void>("POST", `/api/v1/vaults/${vaultId}/devices/${encodeURIComponent(deviceId)}/wipe`, "failed to wipe device", {});
  }

  async reinstateDevice(vaultId: string, deviceId: string): Promise<void> {
    await this.send<void>("POST", `/api/v1/vaults/${vaultId}/devices/${encodeURIComponent(deviceId)}/reinstate`, "failed to reinstate device", {});
  }

  async renameVault(vaultId: string, name: string): Promise<VaultSummary> {
    return this.send<VaultSummary>("PATCH", `/api/v1/vaults/${vaultId}`, "failed to rename vault", { name });
  }

  async noteHistory(vaultId: string, noteId: string): Promise<NoteHistory> {
    return this.json<NoteHistory>(`/api/v1/vaults/${vaultId}/notes/${noteId}/history`, "failed to load history");
  }

  /** Neueste zuerst; `path` = Ordner, `""` = der ganze Vault. */
  async vaultLog(vaultId: string, path: string, limit = 100): Promise<HistoryEvent[]> {
    return this.json<HistoryEvent[]>(`/api/v1/vaults/${vaultId}/audit?${new URLSearchParams({ path, limit: String(limit) })}`, "failed to load log");
  }

  /** Neueste Version zuerst. */
  async noteVersions(vaultId: string, noteId: string, limit = 100): Promise<VersionList> {
    return this.json<VersionList>(`/api/v1/vaults/${vaultId}/notes/${noteId}/versions?limit=${limit}`, "failed to load versions");
  }

  /** Text der Version und der aktuelle Text daneben - fuer den Vergleich. */
  async noteVersion(vaultId: string, noteId: string, revision: number): Promise<VersionText> {
    return this.json<VersionText>(`/api/v1/vaults/${vaultId}/notes/${noteId}/versions/${revision}`, "failed to load version");
  }

  /** Setzt den Text als neue Aenderung auf die Version zurueck; der Verlauf dazwischen bleibt. */
  async restoreVersion(vaultId: string, noteId: string, revision: number): Promise<Restored> {
    return this.json<Restored>(`/api/v1/vaults/${vaultId}/notes/${noteId}/versions/${revision}/restore`, "failed to restore version", {});
  }

  async aiServices(): Promise<AiService[]> {
    return this.json<AiService[]>("/api/v1/ai/services", "failed to list AI services");
  }

  async aiCapacity(serviceId: string): Promise<AiCapacity> {
    return this.json<AiCapacity>(`/api/v1/ai/services/${encodeURIComponent(serviceId)}/capacity`, "failed to read AI capacity");
  }

  async listAiJobs(vaultId: string): Promise<AiJob[]> {
    return this.json<AiJob[]>(`/api/v1/vaults/${vaultId}/ai/jobs`, "failed to list AI jobs");
  }

  async cancelAiJob(vaultId: string, jobId: string): Promise<AiJob> {
    return this.json<AiJob>(`/api/v1/vaults/${vaultId}/ai/jobs/${jobId}/cancel`, "failed to cancel AI job", {});
  }

  /** Dateien, die schon im Vault liegen, einlesen lassen - jede mit ihrem eigenen Level. */
  async readFilesWithAi(vaultId: string, service: string, fileIds: string[]): Promise<AiJob[]> {
    return this.json<AiJob[]>(`/api/v1/vaults/${vaultId}/ai/jobs/from-files`, "failed to start AI", { service, fileIds });
  }

  /** Eine Frage an den Vault stellen; die Antwort entsteht als KI-Job, die Frage ist sofort in der Liste. */
  async askVault(vaultId: string, question: string, service: string | null): Promise<AskQuestion> {
    return this.json<AskQuestion>(`/api/v1/vaults/${vaultId}/ask`, "failed to ask the vault", { question, service });
  }

  /** Die eigenen Fragen, neueste zuerst. */
  async askHistory(vaultId: string): Promise<AskQuestion[]> {
    return this.json<AskQuestion[]>(`/api/v1/vaults/${vaultId}/ask`, "failed to list questions");
  }

  async cancelAsk(vaultId: string, questionId: string): Promise<AskQuestion> {
    return this.json<AskQuestion>(`/api/v1/vaults/${vaultId}/ask/${questionId}/cancel`, "failed to cancel question", {});
  }

  async deleteAsk(vaultId: string, questionId: string): Promise<void> {
    await this.send<void>("DELETE", `/api/v1/vaults/${vaultId}/ask/${questionId}`, "failed to delete question");
  }

  async linkingSettings(vaultId: string): Promise<LinkingSettings> {
    return this.json<LinkingSettings>(`/api/v1/vaults/${vaultId}/linking`, "failed to read linking");
  }

  async updateLinking(vaultId: string, change: { enabled: boolean; linkHumanNotes: boolean; maxLinksPerNote: number | null; service: string | null; mode?: LinkingMode | null }): Promise<LinkingSettings> {
    return this.send<LinkingSettings>("PUT", `/api/v1/vaults/${vaultId}/linking`, "failed to change linking", change);
  }

  /** Notizen mit aehnlichem Inhalt - nur solche, die ich lesen darf; leer, solange noch kein Lauf indiziert hat. */
  async similarNotes(vaultId: string, noteId: string, limit = 10): Promise<SimilarNote[]> {
    return this.json<SimilarNote[]>(`/api/v1/vaults/${vaultId}/notes/${noteId}/similar?limit=${limit}`, "failed to find similar notes");
  }

  async listComments(vaultId: string, noteId: string): Promise<CommentThreadView[]> {
    return this.json<CommentThreadView[]>(`/api/v1/vaults/${vaultId}/notes/${noteId}/comments`, "failed to load comments");
  }

  async startThread(vaultId: string, noteId: string, body: string, anchor: CommentAnchor | null): Promise<CommentThreadView> {
    return this.send<CommentThreadView>("POST", `/api/v1/vaults/${vaultId}/notes/${noteId}/comments`, "failed to comment", anchor ? { body, anchor } : { body });
  }

  async replyToThread(vaultId: string, threadId: string, body: string): Promise<CommentThreadView> {
    return this.send<CommentThreadView>("POST", `/api/v1/vaults/${vaultId}/comment-threads/${threadId}/replies`, "failed to reply", { body });
  }

  async editComment(vaultId: string, commentId: string, body: string): Promise<CommentThreadView> {
    return this.send<CommentThreadView>("PATCH", `/api/v1/vaults/${vaultId}/comments/${commentId}`, "failed to edit comment", { body });
  }

  async deleteComment(vaultId: string, commentId: string): Promise<void> {
    await this.send<void>("DELETE", `/api/v1/vaults/${vaultId}/comments/${commentId}`, "failed to delete comment");
  }

  async resolveThread(vaultId: string, threadId: string): Promise<CommentThreadView> {
    return this.send<CommentThreadView>("POST", `/api/v1/vaults/${vaultId}/comment-threads/${threadId}/resolve`, "failed to resolve thread", {});
  }

  async reopenThread(vaultId: string, threadId: string): Promise<CommentThreadView> {
    return this.send<CommentThreadView>("POST", `/api/v1/vaults/${vaultId}/comment-threads/${threadId}/reopen`, "failed to reopen thread", {});
  }

  /** Erwähnungen an mich, über alle Vaults; nur Notizen, die ich (noch) lesen darf. */
  async notifications(): Promise<CommentNotifications> {
    return this.json<CommentNotifications>("/api/v1/notifications", "failed to load notifications");
  }

  /** Ohne `ids` alle als gelesen markieren. */
  async markNotificationsRead(ids: string[] = []): Promise<void> {
    await this.send<void>("POST", "/api/v1/notifications/read", "failed to mark notifications read", { ids });
  }

  /** Eigene Einwilligung (Art. 49 DSGVO), dass Auszuege meiner Notizen bei der KI-geprueften Verlinkung an den Anbieter gehen. */
  async setLinkingConsent(vaultId: string, consent: boolean): Promise<LinkingSettings> {
    return this.send<LinkingSettings>("PUT", `/api/v1/vaults/${vaultId}/linking/consent`, "failed to change consent", { consent });
  }

  /** "Jetzt verlinken" - im eigenen Namen, braucht Schreibrecht im Vault. */
  async runLinking(vaultId: string): Promise<AiJob> {
    return this.json<AiJob>(`/api/v1/vaults/${vaultId}/linking/run`, "failed to start linking", {});
  }

  /** Wie {@link json}, aber mit beliebiger Methode; leere Antworten (204/200 ohne Inhalt) ergeben `undefined`. */
  private async send<T>(method: string, path: string, action: string, body?: object): Promise<T> {
    const response = await this.fetchImpl(`${this.baseUrl}${path}`, {
      method,
      ...(body ? { body: JSON.stringify(body) } : {}),
      headers: {
        ...(body ? { "Content-Type": "application/json" } : {}),
        Authorization: `Bearer ${await this.getAccessToken()}`,
      },
    });
    if (!response.ok) {
      throw new HttpError(response.status, action, await problemDetail(response));
    }
    const text = typeof response.text === "function" ? await response.text() : "";
    return (text ? JSON.parse(text) : undefined) as T;
  }

  private async json<T>(path: string, action: string, body?: object): Promise<T> {
    const response = await this.fetchImpl(`${this.baseUrl}${path}`, {
      ...(body ? { method: "POST", body: JSON.stringify(body) } : {}),
      headers: {
        ...(body ? { "Content-Type": "application/json" } : {}),
        Authorization: `Bearer ${await this.getAccessToken()}`,
      },
    });
    if (!response.ok) {
      throw new HttpError(response.status, action, await problemDetail(response));
    }
    return (await response.json()) as T;
  }
}

function scopeQuery(scopeType: ScopeType, subject: string | null): URLSearchParams {
  return new URLSearchParams(subject === null ? { scopeType } : { scopeType, subject });
}

async function problemDetail(response: Response): Promise<string | undefined> {
  if (response.status !== 422) {
    return undefined;
  }
  try {
    const body = (await response.json()) as { detail?: unknown };
    return typeof body.detail === "string" ? body.detail : undefined;
  } catch {
    return undefined;
  }
}
