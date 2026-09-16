export interface SyncTicket {
  token: string;
  expiresAt: string;
}

/** Holt kurzlebige Single-Use-Tickets fuer den WebSocket-Handshake (Plan.md Abschnitt 3). */
export class TicketClient {
  constructor(
    private readonly baseUrl: string,
    private readonly actor: string,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  async issueTicket(vaultId: string, noteId: string): Promise<SyncTicket> {
    const response = await this.fetchImpl(
      `${this.baseUrl}/api/v1/vaults/${vaultId}/notes/${noteId}/sync-tickets`,
      { method: "POST", headers: { "X-Actor": this.actor } },
    );
    if (!response.ok) {
      throw new Error(`failed to issue sync ticket: HTTP ${response.status}`);
    }
    return (await response.json()) as SyncTicket;
  }
}
