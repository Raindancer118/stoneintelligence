import type { AccessTokenProvider } from "./NoteApiClient";

export interface SyncTicket {
  token: string;
  expiresAt: string;
}

/**
 * Holt kurzlebige Single-Use-Tickets fuer den WebSocket-Handshake (Plan.md Abschnitt 3). Die
 * Ticket-AUSSTELLUNG verlangt seit Phase 3 ein gueltiges OIDC-Bearer-Token - das ausgestellte
 * Ticket selbst bleibt der alleinige Auth-Nachweis fuer die anschliessende WS-Verbindung.
 */
export class TicketClient {
  constructor(
    private readonly baseUrl: string,
    private readonly getAccessToken: AccessTokenProvider,
    private readonly fetchImpl: typeof fetch = (...args) => fetch(...args),
  ) {}

  async issueTicket(vaultId: string, noteId: string): Promise<SyncTicket> {
    const response = await this.fetchImpl(
      `${this.baseUrl}/api/v1/vaults/${vaultId}/notes/${noteId}/sync-tickets`,
      { method: "POST", headers: { Authorization: `Bearer ${await this.getAccessToken()}` } },
    );
    if (!response.ok) {
      throw new Error(`failed to issue sync ticket: HTTP ${response.status}`);
    }
    return (await response.json()) as SyncTicket;
  }
}
