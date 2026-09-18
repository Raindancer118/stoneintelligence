import { describe, expect, it, vi } from "vitest";
import { AuthentikAuthClient, TokenRefreshRejectedError } from "../src/sync/AuthentikAuthClient";

const TEST_REDIRECT_URI = "http://127.0.0.1:42813/callback";

describe("AuthentikAuthClient", () => {
  describe("generateCodeChallenge", () => {
    it("should_produceStableS256Challenge_forAGivenVerifier", async () => {
      const challenge = await AuthentikAuthClient.generateCodeChallenge("fixed-test-verifier");

      // RFC 7636 S256: base64url(sha256(verifier)) - fixed input must always yield this exact
      // challenge, computed independently against the reference algorithm.
      expect(challenge).toBe("MUwD_EznVGd6_rl9PxRqJRcLmZ9rQDdCtX8IbiCk1hM");
    });

    it("should_produceDifferentChallenges_forDifferentVerifiers", async () => {
      const challengeA = await AuthentikAuthClient.generateCodeChallenge("verifier-a");
      const challengeB = await AuthentikAuthClient.generateCodeChallenge("verifier-b");

      expect(challengeA).not.toBe(challengeB);
    });
  });

  describe("escapeHtml", () => {
    it("should_escapeAllHtmlSpecialCharacters_toPreventReflectedXss", () => {
      const escaped = AuthentikAuthClient.escapeHtml('<script>alert("xss")</script>&\'');

      expect(escaped).toBe("&lt;script&gt;alert(&quot;xss&quot;)&lt;/script&gt;&amp;&#39;");
      expect(escaped).not.toContain("<script>");
    });
  });

  describe("generateRandomToken", () => {
    it("should_produceDifferentTokens_onEachCall", () => {
      const first = AuthentikAuthClient.generateRandomToken();
      const second = AuthentikAuthClient.generateRandomToken();

      expect(first).not.toBe(second);
      expect(first.length).toBeGreaterThan(20);
    });
  });

  describe("buildAuthorizationUrl", () => {
    it("should_includeAllRequiredPkceAndOidcParameters", () => {
      const url = AuthentikAuthClient.buildAuthorizationUrl(
        "https://portal.tstieh.de/application/o/authorize/",
        "my-client-id",
        TEST_REDIRECT_URI,
        "the-challenge",
        "the-state",
      );

      const parsed = new URL(url);
      expect(parsed.searchParams.get("response_type")).toBe("code");
      expect(parsed.searchParams.get("client_id")).toBe("my-client-id");
      expect(parsed.searchParams.get("redirect_uri")).toBe(TEST_REDIRECT_URI);
      expect(parsed.searchParams.get("code_challenge")).toBe("the-challenge");
      expect(parsed.searchParams.get("code_challenge_method")).toBe("S256");
      expect(parsed.searchParams.get("state")).toBe("the-state");
    });
  });

  describe("discover", () => {
    it("should_fetchWellKnownDiscoveryDocument_fromIssuerUrl", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ authorization_endpoint: "https://issuer/auth", token_endpoint: "https://issuer/token" }),
      });
      const client = new AuthentikAuthClient(
        { issuerUrl: "https://issuer/", clientId: "client" }, fakeFetch as unknown as typeof fetch,
      );

      const discovery = await client.discover();

      expect(fakeFetch).toHaveBeenCalledWith("https://issuer/.well-known/openid-configuration");
      expect(discovery.authorization_endpoint).toBe("https://issuer/auth");
    });

    it("should_throw_when_discoveryFails", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 404 });
      const client = new AuthentikAuthClient(
        { issuerUrl: "https://issuer", clientId: "client" }, fakeFetch as unknown as typeof fetch,
      );

      await expect(client.discover()).rejects.toThrow("404");
    });
  });

  describe("exchangeCodeForTokens", () => {
    it("should_returnStoredTokens_withExpiresAtComputedFromExpiresIn", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ access_token: "at", refresh_token: "rt", expires_in: 3600 }),
      });
      const client = new AuthentikAuthClient(
        { issuerUrl: "https://issuer", clientId: "my-client" }, fakeFetch as unknown as typeof fetch,
      );
      const before = Date.now();

      const tokens = await client.exchangeCodeForTokens(
        "https://issuer/token", "the-code", "the-verifier", "obsidian://stoneintelligence-auth",
      );

      expect(tokens.accessToken).toBe("at");
      expect(tokens.refreshToken).toBe("rt");
      expect(tokens.expiresAt).toBeGreaterThan(before + 3500 * 1000);
      const [, options] = fakeFetch.mock.calls[0] as [string, RequestInit];
      expect(options.body).toContain("grant_type=authorization_code");
      expect(options.body).toContain("client_id=my-client");
      expect(options.body).toContain("code=the-code");
      expect(options.body).toContain("code_verifier=the-verifier");
      expect(options.body).toContain(`redirect_uri=${encodeURIComponent("obsidian://stoneintelligence-auth")}`);
    });

    it("should_useTheGivenRedirectUri_notAFixedOne", async () => {
      // Regression: der Redirect-URI-Parameter MUSS exakt dem der Autorisierungsanfrage
      // entsprechen (RFC 6749 4.1.3) - frueher war hier fest die Desktop-Loopback-URI verdrahtet,
      // was fuer jeden anderen Redirect-Pfad (Webapp, Mobile) einen Token-Exchange-Fehlschlag
      // erzeugt haette.
      const fakeFetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ access_token: "at", refresh_token: "rt", expires_in: 3600 }),
      });
      const client = new AuthentikAuthClient(
        { issuerUrl: "https://issuer", clientId: "my-client" }, fakeFetch as unknown as typeof fetch,
      );

      await client.exchangeCodeForTokens("https://issuer/token", "code", "verifier", "https://kb.tstieh.de/callback");

      const [, options] = fakeFetch.mock.calls[0] as [string, RequestInit];
      expect(options.body).toContain(`redirect_uri=${encodeURIComponent("https://kb.tstieh.de/callback")}`);
    });

    it("should_throw_when_exchangeFails", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 400 });
      const client = new AuthentikAuthClient(
        { issuerUrl: "https://issuer", clientId: "client" }, fakeFetch as unknown as typeof fetch,
      );

      await expect(
        client.exchangeCodeForTokens("https://issuer/token", "code", "verifier", "obsidian://stoneintelligence-auth"),
      ).rejects.toThrow("400");
    });
  });

  describe("login", () => {
    function fakeFetchFor(discovery: object, tokens: object): typeof fetch {
      return vi.fn()
        .mockResolvedValueOnce({ ok: true, json: async () => discovery })
        .mockResolvedValueOnce({ ok: true, json: async () => tokens }) as unknown as typeof fetch;
    }

    it("should_openAuthorizationUrl_withGivenRedirectUri_andExchangeTheReturnedCode", async () => {
      const fakeFetch = fakeFetchFor(
        { authorization_endpoint: "https://issuer/auth", token_endpoint: "https://issuer/token" },
        { access_token: "at", refresh_token: "rt", expires_in: 3600 },
      );
      const client = new AuthentikAuthClient({ issuerUrl: "https://issuer", clientId: "my-client" }, fakeFetch);
      const openAuthorizationUrl = vi.fn();
      let capturedState = "";
      const awaitCode = vi.fn().mockImplementation(async (state: string) => {
        capturedState = state;
        return "the-code";
      });

      const tokens = await client.login({
        redirectUri: "obsidian://stoneintelligence-auth",
        openAuthorizationUrl,
        awaitCode,
      });

      expect(tokens.accessToken).toBe("at");
      const openedUrl = new URL(openAuthorizationUrl.mock.calls[0][0] as string);
      expect(openedUrl.searchParams.get("redirect_uri")).toBe("obsidian://stoneintelligence-auth");
      expect(openedUrl.searchParams.get("state")).toBe(capturedState);
      const [, exchangeOptions] = (fakeFetch as ReturnType<typeof vi.fn>).mock.calls[1] as [string, RequestInit];
      expect(exchangeOptions.body).toContain("code=the-code");
      expect(exchangeOptions.body).toContain(`redirect_uri=${encodeURIComponent("obsidian://stoneintelligence-auth")}`);
    });

    it("should_propagate_when_awaitCodeRejects", async () => {
      const fakeFetch = fakeFetchFor(
        { authorization_endpoint: "https://issuer/auth", token_endpoint: "https://issuer/token" },
        {},
      );
      const client = new AuthentikAuthClient({ issuerUrl: "https://issuer", clientId: "my-client" }, fakeFetch);

      await expect(client.login({
        redirectUri: "obsidian://stoneintelligence-auth",
        openAuthorizationUrl: vi.fn(),
        awaitCode: vi.fn().mockRejectedValue(new Error("state mismatch - moeglicher CSRF-Versuch")),
      })).rejects.toThrow("state mismatch");
    });
  });

  describe("refreshAccessToken", () => {
    it("should_sendRefreshTokenGrant_andReturnNewTokens", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ access_token: "new-at", refresh_token: "new-rt", expires_in: 3600 }),
      });
      const client = new AuthentikAuthClient(
        { issuerUrl: "https://issuer", clientId: "my-client" }, fakeFetch as unknown as typeof fetch,
      );

      const tokens = await client.refreshAccessToken("https://issuer/token", "old-rt");

      expect(tokens.accessToken).toBe("new-at");
      const [, options] = fakeFetch.mock.calls[0] as [string, RequestInit];
      expect(options.body).toContain("grant_type=refresh_token");
      expect(options.body).toContain("refresh_token=old-rt");
    });

    it("should_throw_when_refreshFails", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 401 });
      const client = new AuthentikAuthClient(
        { issuerUrl: "https://issuer", clientId: "client" }, fakeFetch as unknown as typeof fetch,
      );

      await expect(client.refreshAccessToken("https://issuer/token", "rt")).rejects.toThrow("401");
    });

    it("should_throwTokenRefreshRejectedError_when_authentikDefinitivelyRejectsTheRefreshToken", async () => {
      // 400/401 on the refresh grant means Authentik itself declared the refresh token invalid
      // (expired, revoked, already-rotated) - only THIS case justifies wiping the stored login.
      for (const status of [400, 401]) {
        const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status });
        const client = new AuthentikAuthClient(
          { issuerUrl: "https://issuer", clientId: "client" }, fakeFetch as unknown as typeof fetch,
        );

        await expect(client.refreshAccessToken("https://issuer/token", "rt"))
          .rejects.toBeInstanceOf(TokenRefreshRejectedError);
      }
    });

    it("should_throwAPlainError_notTokenRefreshRejectedError_when_theFailureIsTransient", async () => {
      // A 5xx (server temporarily down) or a rejected fetch (no network yet at Obsidian startup,
      // DNS not resolved, offline) says nothing about whether the refresh token itself is still
      // valid - treating it as a rejection would log the user out on every hiccup, exactly the
      // "logged out again" bug this guards against.
      const serverError = vi.fn().mockResolvedValue({ ok: false, status: 503 });
      const clientForServerError = new AuthentikAuthClient(
        { issuerUrl: "https://issuer", clientId: "client" }, serverError as unknown as typeof fetch,
      );
      await expect(clientForServerError.refreshAccessToken("https://issuer/token", "rt"))
        .rejects.not.toBeInstanceOf(TokenRefreshRejectedError);

      const networkFailure = vi.fn().mockRejectedValue(new Error("net::ERR_INTERNET_DISCONNECTED"));
      const clientForNetworkFailure = new AuthentikAuthClient(
        { issuerUrl: "https://issuer", clientId: "client" }, networkFailure as unknown as typeof fetch,
      );
      await expect(clientForNetworkFailure.refreshAccessToken("https://issuer/token", "rt"))
        .rejects.not.toBeInstanceOf(TokenRefreshRejectedError);
    });

    it("should_keepTheOldRefreshToken_when_theProviderOmitsANewOneInTheRefreshResponse", async () => {
      // Regression, live gefunden ueber Authentik-Server-Logs ("Refresh token does not exist",
      // token: "undefined"): Authentik liefert im Refresh-Grant (anders als beim initialen
      // Code-Exchange) KEIN `refresh_token`-Feld zurueck, solange keine Rotation konfiguriert ist.
      // Vorher wurde `body.refresh_token` (also `undefined`) blind uebernommen und gespeichert -
      // `JSON.stringify` liess das Feld beim Speichern komplett wegfallen, und beim naechsten
      // Refresh (typischerweise beim naechsten Obsidian-Neustart) wurde buchstaeblich der
      // STRING "undefined" als `refresh_token` an Authentik geschickt (URLSearchParams
      // stringified jeden Wert), was garantiert mit HTTP 400 scheiterte und die gesamte
      // Anmeldung loeschte.
      const fakeFetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ access_token: "new-at", expires_in: 3600 }),
      });
      const client = new AuthentikAuthClient(
        { issuerUrl: "https://issuer", clientId: "my-client" }, fakeFetch as unknown as typeof fetch,
      );

      const tokens = await client.refreshAccessToken("https://issuer/token", "old-rt");

      expect(tokens.accessToken).toBe("new-at");
      expect(tokens.refreshToken).toBe("old-rt");
    });

    it("should_useTheNewRefreshToken_when_theProviderDoesReturnOne", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ access_token: "new-at", refresh_token: "rotated-rt", expires_in: 3600 }),
      });
      const client = new AuthentikAuthClient(
        { issuerUrl: "https://issuer", clientId: "my-client" }, fakeFetch as unknown as typeof fetch,
      );

      const tokens = await client.refreshAccessToken("https://issuer/token", "old-rt");

      expect(tokens.refreshToken).toBe("rotated-rt");
    });
  });
});
