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
}

interface TokenResponseBody {
  access_token: string;
  refresh_token: string;
  expires_in: number;
}

export const OIDC_REDIRECT_PORT = 42813;
export const OIDC_REDIRECT_URI = `http://127.0.0.1:${OIDC_REDIRECT_PORT}/callback`;

/**
 * OAuth2 Authorization Code + PKCE gegen Authentik (Plan.md Abschnitt 4.4: "SSO/OIDC als
 * einziger Auth-Pfad fuer Menschen ... Plugin-Login"). Kein Client-Secret - das Plugin ist ein
 * Public Client, PKCE (S256) ersetzt das Secret als Schutz gegen Code-Interception.
 *
 * <p>{@link login} funktioniert NUR auf dem Desktop (Node-`http` fuer den Loopback-Callback via
 * Electron) - auf Mobile gibt es noch keinen Redirect-Mechanismus. Bekannte Einschraenkung,
 * s. Project.md.
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

  private static toStoredTokens(body: TokenResponseBody): StoredTokens {
    const SAFETY_MARGIN_SECONDS = 30;
    return {
      accessToken: body.access_token,
      refreshToken: body.refresh_token,
      expiresAt: Date.now() + (body.expires_in - SAFETY_MARGIN_SECONDS) * 1000,
    };
  }

  async discover(): Promise<OidcDiscoveryDocument> {
    const issuer = this.settings.issuerUrl.replace(/\/$/, "");
    const response = await this.fetchImpl(`${issuer}/.well-known/openid-configuration`);
    if (!response.ok) {
      throw new Error(`OIDC discovery failed: HTTP ${response.status}`);
    }
    return (await response.json()) as OidcDiscoveryDocument;
  }

  async exchangeCodeForTokens(tokenEndpoint: string, code: string, codeVerifier: string): Promise<StoredTokens> {
    const response = await this.fetchImpl(tokenEndpoint, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        client_id: this.settings.clientId,
        code,
        redirect_uri: OIDC_REDIRECT_URI,
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
      throw new Error(`token refresh failed: HTTP ${response.status}`);
    }
    return AuthentikAuthClient.toStoredTokens((await response.json()) as TokenResponseBody);
  }

  /**
   * Voller interaktiver Login: OIDC-Discovery, PKCE-Paar erzeugen, Systembrowser oeffnen,
   * auf den Redirect an einen lokalen Loopback-Server warten, Code gegen Tokens tauschen.
   */
  async login(): Promise<StoredTokens> {
    const discovery = await this.discover();
    const verifier = AuthentikAuthClient.generateRandomToken();
    const challenge = await AuthentikAuthClient.generateCodeChallenge(verifier);
    const state = AuthentikAuthClient.generateRandomToken();

    const codePromise = AuthentikAuthClient.awaitRedirectCode(state);
    const authorizationUrl = AuthentikAuthClient.buildAuthorizationUrl(
      discovery.authorization_endpoint, this.settings.clientId, OIDC_REDIRECT_URI, challenge, state,
    );

    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const { shell } = require("electron");
    await shell.openExternal(authorizationUrl);

    const code = await codePromise;
    return this.exchangeCodeForTokens(discovery.token_endpoint, code, verifier);
  }

  private static awaitRedirectCode(expectedState: string): Promise<string> {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const http = require("http") as typeof import("http");
    return new Promise((resolve, reject) => {
      const server = http.createServer((req, res) => {
        const requestUrl = new URL(req.url ?? "", OIDC_REDIRECT_URI);
        if (requestUrl.pathname !== "/callback") {
          res.writeHead(404).end();
          return;
        }
        const code = requestUrl.searchParams.get("code");
        const state = requestUrl.searchParams.get("state");
        const error = requestUrl.searchParams.get("error");

        res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
        res.end(error
          ? `<html><body>Login fehlgeschlagen: ${AuthentikAuthClient.escapeHtml(error)}. Dieses Fenster kann geschlossen werden.</body></html>`
          : "<html><body>Login erfolgreich - dieses Fenster kann geschlossen werden.</body></html>");
        server.close();

        if (error) {
          reject(new Error(`authorization failed: ${error}`));
        } else if (state !== expectedState) {
          reject(new Error("state mismatch - moeglicher CSRF-Versuch"));
        } else if (!code) {
          reject(new Error("no authorization code in callback"));
        } else {
          resolve(code);
        }
      });
      server.listen(OIDC_REDIRECT_PORT, "127.0.0.1");
    });
  }
}
