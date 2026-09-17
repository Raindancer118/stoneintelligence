export type AccessTokenProvider = () => Promise<string>;

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
    private readonly fetchImpl: typeof fetch = (...args) => fetch(...args),
  ) {}

  /** Legt einen neuen Vault an; der anlegende Actor bekommt serverseitig automatisch volle Rechte darin. */
  async createVault(name: string): Promise<string> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${await this.getAccessToken()}` },
      body: JSON.stringify({ name }),
    });
    if (!response.ok) {
      throw new Error(`failed to create vault: HTTP ${response.status}`);
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
      throw new Error(`failed to create note: HTTP ${response.status}`);
    }
    const created = (await response.json()) as { id: string };
    return created.id;
  }

  async renameNote(vaultId: string, noteId: string, newPath: string): Promise<void> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/notes/${noteId}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${await this.getAccessToken()}` },
      body: JSON.stringify({ path: newPath }),
    });
    if (!response.ok) {
      throw new Error(`failed to rename note: HTTP ${response.status}`);
    }
  }

  async deleteNote(vaultId: string, noteId: string, operationId: string): Promise<void> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/notes/${noteId}`, {
      method: "DELETE",
      headers: { "X-Operation-Id": operationId, Authorization: `Bearer ${await this.getAccessToken()}` },
    });
    if (!response.ok) {
      throw new Error(`failed to delete note: HTTP ${response.status}`);
    }
  }
}
