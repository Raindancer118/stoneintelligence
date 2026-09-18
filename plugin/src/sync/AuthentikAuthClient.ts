import { obsidianFetch } from "./obsidianFetch";

export interface OidcSettings {
  issuerUrl: string;
  clientId: string;
}

export interface StoredTokens {
  accessToken: string;
  refreshToken: string;
  expiresAt: number;
}

interface OidcDiscoveryDocument {
  authorization_endpoint: string;
  token_endpoint: string;
  userinfo_endpoint?: string;
}

interface TokenResponseBody {
  access_token: string;
  refresh_token?: string;
  expires_in: number;
}

/**
 * Wird ausschliesslich geworfen, wenn Authentik den Refresh-Grant mit HTTP 400/401 ablehnt - das
 * ist die einzige Antwort, die tatsaechlich bedeutet "dieser Refresh-Token ist ungueltig"
 * (abgelaufen, widerrufen, bereits rotiert). Jeder andere Fehler (5xx, Timeout, kein Netzwerk)
 * sagt nichts ueber die Gueltigkeit des Tokens aus - Aufrufer duerfen NUR bei diesem Fehlertyp
 * die gespeicherte Anmeldung loeschen, sonst wirft ein voruebergehender Ausfall (z. B. noch kein
 * Netzwerk beim Obsidian-Start) den Nutzer bei jedem Neustart aus der Anmeldung.
 */
export class TokenRefreshRejectedError extends Error {
  constructor(status: number) {
    super(`token refresh rejected: HTTP ${status}`);
    this.name = "TokenRefreshRejectedError";
  }
}

/**
 * OAuth2 Authorization Code + PKCE gegen Authentik (Plan.md Abschnitt 4.4: "SSO/OIDC als
 * einziger Auth-Pfad fuer Menschen ... Plugin-Login"). Kein Client-Secret - das Plugin ist ein
 * Public Client, PKCE (S256) ersetzt das Secret als Schutz gegen Code-Interception.
 *
 * <p>{@link login} ist plattform-agnostisch (s. dessen Doc-Kommentar) - funktioniert seit
 * Einfuehrung des `obsidian://`-Redirects (s. main.ts/desktopAuthRedirect.ts) sowohl auf
 * Desktop als auch auf Mobile.
 */
export class AuthentikAuthClient {
  constructor(
    private readonly settings: OidcSettings,
    // obsidianFetch statt globalem fetch: natives fetch() im Electron-Renderer unterliegt
    // weiterhin normaler Browser-CORS-Durchsetzung, und Obsidians eigener Origin laesst sich bei
    // Authentik nicht als erlaubte CORS-Origin registrieren (live beobachtet: discover() schlug
    // mit "Failed to fetch" fehl). S. obsidianFetch.ts fuer Details.
    private readonly fetchImpl: typeof fetch = obsidianFetch,
  ) {}

  static base64UrlEncode(bytes: Uint8Array): string {
    let binary = "";
    for (const byte of bytes) {
      binary += String.fromCharCode(byte);
    }
    return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }

  /** Verhindert reflected XSS in der lokalen Callback-Antwortseite (der `error`-Query-Parameter ist von aussen kontrollierbar). */
  static escapeHtml(value: string): string {
    return value
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  static generateRandomToken(): string {
    const bytes = new Uint8Array(32);
    crypto.getRandomValues(bytes);
    return AuthentikAuthClient.base64UrlEncode(bytes);
  }

  static async generateCodeChallenge(verifier: string): Promise<string> {
    const data = new TextEncoder().encode(verifier);
    const digest = await crypto.subtle.digest("SHA-256", data);
    return AuthentikAuthClient.base64UrlEncode(new Uint8Array(digest));
  }

  static buildAuthorizationUrl(
    authorizationEndpoint: string,
    clientId: string,
    redirectUri: string,
    codeChallenge: string,
    state: string,
  ): string {
    const url = new URL(authorizationEndpoint);
    url.searchParams.set("response_type", "code");
    url.searchParams.set("client_id", clientId);
    url.searchParams.set("redirect_uri", redirectUri);
    url.searchParams.set("scope", "openid profile email");
    url.searchParams.set("code_challenge", codeChallenge);
    url.searchParams.set("code_challenge_method", "S256");
    url.searchParams.set("state", state);
    return url.toString();
  }

  /**
   * `previousRefreshToken` faengt den Fall auf, dass der Refresh-Grant (anders als der initiale
   * Code-Exchange) KEIN `refresh_token`-Feld zurueckliefert - bei Authentik ohne aktivierte
   * Rotation der Normalfall, nicht die Ausnahme. Ohne diesen Fallback wuerde `refreshToken`
   * `undefined`, `JSON.stringify` liesse das Feld beim Speichern verschwinden, und der naechste
   * Refresh-Versuch schickte buchstaeblich den String "undefined" an Authentik (live per
   * Server-Log bestaetigt: "Refresh token does not exist", token: "undefined") - garantierter
   * HTTP-400 und Komplettverlust der Anmeldung bei jedem folgenden Neustart.
   */
  private static toStoredTokens(body: TokenResponseBody, previousRefreshToken?: string): StoredTokens {
    const SAFETY_MARGIN_SECONDS = 30;
    return {
      accessToken: body.access_token,
      refreshToken: body.refresh_token ?? previousRefreshToken ?? "",
      expiresAt: Date.now() + (body.expires_in - SAFETY_MARGIN_SECONDS) * 1000,
    };
  }

  /**
   * Holt die Profil-Claims des angemeldeten Nutzers vom `userinfo`-Endpunkt. Authentik liefert
   * hier den vollen Anzeigenamen (`name`) - im Gegensatz zum Access-Token ist das unabhaengig
   * davon, welche Claims der Provider in den Token schreibt, und es steht auch dann zur
   * Verfuegung, wenn gerade kein dekodierbares JWT vorliegt.
   *
   * <p>Gibt bei jedem Fehler `null` zurueck statt zu werfen: der Name ist reine Kosmetik am
   * Cursor-Label, ein Ausfall des Endpunkts darf den Sync niemals aufhalten.
   */
  async fetchUserInfo(userinfoEndpoint: string, accessToken: string): Promise<Record<string, unknown> | null> {
    try {
      const response = await this.fetchImpl(userinfoEndpoint, {
        headers: { Authorization: `Bearer ${accessToken}` },
      });
      if (!response.ok) {
        return null;
      }
      const body = (await response.json()) as unknown;
      return typeof body === "object" && body !== null ? (body as Record<string, unknown>) : null;
    } catch {
      return null;
    }
  }

  async discover(): Promise<OidcDiscoveryDocument> {
    const issuer = this.settings.issuerUrl.replace(/\/$/, "");
    const response = await this.fetchImpl(`${issuer}/.well-known/openid-configuration`);
    if (!response.ok) {
      throw new Error(`OIDC discovery failed: HTTP ${response.status}`);
    }
    return (await response.json()) as OidcDiscoveryDocument;
  }

  /**
   * `redirectUri` MUSS exakt der aus der Autorisierungsanfrage sein (RFC 6749 4.1.3) - Authentik
   * lehnt den Tausch sonst ab. Frueher hier fest verdrahtet auf die Desktop-Loopback-URI, was nur
   * zufaellig nie auffiel, solange es der einzige Redirect-Pfad war.
   */
  async exchangeCodeForTokens(
    tokenEndpoint: string, code: string, codeVerifier: string, redirectUri: string,
  ): Promise<StoredTokens> {
    const response = await this.fetchImpl(tokenEndpoint, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        client_id: this.settings.clientId,
        code,
        redirect_uri: redirectUri,
        code_verifier: codeVerifier,
      }).toString(),
    });
    if (!response.ok) {
      throw new Error(`token exchange failed: HTTP ${response.status}`);
    }
    return AuthentikAuthClient.toStoredTokens((await response.json()) as TokenResponseBody);
  }

  async refreshAccessToken(tokenEndpoint: string, refreshToken: string): Promise<StoredTokens> {
    const response = await this.fetchImpl(tokenEndpoint, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        client_id: this.settings.clientId,
        refresh_token: refreshToken,
      }).toString(),
    });
    if (!response.ok) {
      if (response.status === 400 || response.status === 401) {
        throw new TokenRefreshRejectedError(response.status);
      }
      throw new Error(`token refresh failed: HTTP ${response.status}`);
    }
    return AuthentikAuthClient.toStoredTokens((await response.json()) as TokenResponseBody, refreshToken);
  }

  /**
   * Voller interaktiver Login: OIDC-Discovery, PKCE-Paar erzeugen, Systembrowser oeffnen (per
   * {@link redirect}.openAuthorizationUrl), auf den Redirect warten (per
   * {@link redirect}.awaitCode), Code gegen Tokens tauschen. Bewusst plattform-agnostisch: WIE der
   * Browser geoeffnet wird und WIE der Redirect abgefangen wird, unterscheidet sich zwischen
   * Desktop (lokaler Loopback-HTTP-Server + `electron.shell.openExternal`, s. desktopAuthRedirect.ts)
   * und Mobile (Obsidians eigenes `obsidian://`-URI-Schema + `registerObsidianProtocolHandler`,
   * s. main.ts) - dieser Client kennt nur die Strategie-Schnittstelle, nicht die Plattform.
   */
  async login(redirect: {
    redirectUri: string;
    openAuthorizationUrl: (url: string) => void | Promise<void>;
    awaitCode: (expectedState: string) => Promise<string>;
  }): Promise<StoredTokens> {
    const discovery = await this.discover();
    const verifier = AuthentikAuthClient.generateRandomToken();
    const challenge = await AuthentikAuthClient.generateCodeChallenge(verifier);
    const state = AuthentikAuthClient.generateRandomToken();

    const codePromise = redirect.awaitCode(state);
    const authorizationUrl = AuthentikAuthClient.buildAuthorizationUrl(
      discovery.authorization_endpoint, this.settings.clientId, redirect.redirectUri, challenge, state,
    );
    await redirect.openAuthorizationUrl(authorizationUrl);

    const code = await codePromise;
    return this.exchangeCodeForTokens(discovery.token_endpoint, code, verifier, redirect.redirectUri);
  }
}
