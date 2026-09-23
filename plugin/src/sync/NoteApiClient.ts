import type { AiChangeSet, AiChangeSetView, AiRevertReport } from "../ui/aiChanges";
import { obsidianFetch } from "./obsidianFetch";
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
  kind?: "NOTE" | "FILE";
  /** Nur bei Dateien mit Inhalt. */
  sha256?: string | null;
  size?: number | null;
}

export interface FileLimits { maxFileBytes: number; vaultQuotaBytes: number; }
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
}

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
      body: JSON.stringify({ path, noteLevel }),
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
  async listNotes(vaultId: string, cursor?: string, pageSize = 100, includeFiles = false): Promise<ReconciliationPage> {
    const params = new URLSearchParams({ pageSize: String(pageSize), ...(includeFiles ? { kinds: "note,file" } : {}) });
    if (cursor) {
      params.set("cursor", cursor);
    }
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/notes?${params.toString()}`, {
      headers: { Authorization: `Bearer ${await this.getAccessToken()}` },
    });
    if (!response.ok) {
      throw new HttpError(response.status, "failed to list notes");
    }
    return (await response.json()) as ReconciliationPage;
  }

  /** Laeuft `listNotes` bis `complete === true` durch und gibt alle Notizen des Vaults zurueck. */
  async listAllNotes(vaultId: string): Promise<NoteListItem[]> {
    return this.listAll(vaultId, false);
  }

  /** Notizen UND Dateien; ein Server ohne Dateien liefert einfach nur Notizen (ohne `kind`). */
  async listAllEntries(vaultId: string): Promise<NoteListItem[]> {
    return this.listAll(vaultId, true);
  }

  private async listAll(vaultId: string, includeFiles: boolean): Promise<NoteListItem[]> {
    const all: NoteListItem[] = [];
    let cursor: string | undefined;
    for (;;) {
      const page = await this.listNotes(vaultId, cursor, 100, includeFiles);
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
