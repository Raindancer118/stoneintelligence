import { obsidianFetch } from "./obsidianFetch";
import { withRateLimitRetry } from "./retryFetch";

export type AccessTokenProvider = () => Promise<string>;

export interface NoteListItem {
  id: string;
  vaultId: string;
  path: string;
  noteLevel: number;
  /** Hoechste gespeicherte Update-Sequenz - fehlt bei Servern vor dieser Erweiterung. */
  revision?: number;
}

export interface VaultSummary {
  id: string;
  name: string;
  createdAt: string;
}

/** Ob eine (in der Liste fehlende) Notiz geloescht ist oder nur nicht mehr sichtbar. */
export type NoteStatus = "exists" | "deleted" | "forbidden";

/** Fehlgeschlagene Anfrage MIT Statuscode - der Abgleich reagiert auf 403/404/409 unterschiedlich. */
export class HttpError extends Error {
  constructor(
    readonly status: number,
    action: string,
  ) {
    super(`${action}: HTTP ${status}`);
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
  async listNotes(vaultId: string, cursor?: string, pageSize = 100): Promise<ReconciliationPage> {
    const params = new URLSearchParams({ pageSize: String(pageSize) });
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
    const all: NoteListItem[] = [];
    let cursor: string | undefined;
    for (;;) {
      const page = await this.listNotes(vaultId, cursor);
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
}
