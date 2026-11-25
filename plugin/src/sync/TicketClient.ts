import type { AccessTokenProvider } from "./NoteApiClient";
import { gateErrorFrom } from "./deviceGate";
import { obsidianFetch } from "./obsidianFetch";
import { withRateLimitRetry } from "./retryFetch";

export interface SyncTicket {
  token: string;
  expiresAt: string;
}

/**
 * Holt kurzlebige Single-Use-Tickets fuer den WebSocket-Handshake (Plan.md Abschnitt 3). Die
 * Ticket-AUSSTELLUNG verlangt seit Phase 3 ein gueltiges OIDC-Bearer-Token - das ausgestellte
 * Ticket selbst bleibt der alleinige Auth-Nachweis fuer die anschliessende WS-Verbindung.
 *
 * <p>Vault-, nicht mehr notenskopiert (s. {@link MultiplexedTransport}): EINE Verbindung joint
 * darueber beliebig viele Notiz-Raeume per JOIN-Kontrollnachricht, statt fuer jede Notiz ein
 * eigenes Ticket UND eine eigene Verbindung zu brauchen.
 */
export class TicketClient {
  constructor(
    private readonly baseUrl: string,
    private readonly getAccessToken: AccessTokenProvider,
    private readonly fetchImpl: typeof fetch = withRateLimitRetry(obsidianFetch),
  ) {}

  /** {@code deviceId}: die Geräte-Kennung (ADR 0019); der Server verweigert das Ticket, wenn das Gerät nicht synchronisieren darf. */
  async issueTicket(vaultId: string, deviceId?: string): Promise<SyncTicket> {
    const response = await this.fetchImpl(
      `${this.baseUrl}/api/v1/vaults/${vaultId}/sync-tickets`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${await this.getAccessToken()}`, ...(deviceId ? { "X-Device-Id": deviceId } : {}) },
      },
    );
    if (!response.ok) {
      const refusal = await gateErrorFrom(response);
      if (refusal) {
        throw refusal;
      }
      throw new Error(`failed to issue sync ticket: HTTP ${response.status}`);
    }
    return (await response.json()) as SyncTicket;
  }
}
