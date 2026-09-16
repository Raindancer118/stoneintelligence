/** REST-Client fuer ID-first Note-CRUD gegen platform-api (Plan.md Abschnitt 3, Fehlerklasse 5). */
export class NoteApiClient {
  constructor(
    private readonly baseUrl: string,
    private readonly actor: string,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  async createNote(vaultId: string, path: string, noteLevel: number): Promise<string> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/notes`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Actor": this.actor },
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
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ path: newPath }),
    });
    if (!response.ok) {
      throw new Error(`failed to rename note: HTTP ${response.status}`);
    }
  }

  async deleteNote(vaultId: string, noteId: string, operationId: string): Promise<void> {
    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/vaults/${vaultId}/notes/${noteId}`, {
      method: "DELETE",
      headers: { "X-Operation-Id": operationId, "X-Actor": this.actor },
    });
    if (!response.ok) {
      throw new Error(`failed to delete note: HTTP ${response.status}`);
    }
  }
}
