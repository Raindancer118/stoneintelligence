import { describe, expect, it, vi } from "vitest";
import { AuthentikAuthClient, OIDC_REDIRECT_URI } from "../src/sync/AuthentikAuthClient";

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
        OIDC_REDIRECT_URI,
        "the-challenge",
        "the-state",
      );

      const parsed = new URL(url);
      expect(parsed.searchParams.get("response_type")).toBe("code");
      expect(parsed.searchParams.get("client_id")).toBe("my-client-id");
      expect(parsed.searchParams.get("redirect_uri")).toBe(OIDC_REDIRECT_URI);
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

      const tokens = await client.exchangeCodeForTokens("https://issuer/token", "the-code", "the-verifier");

      expect(tokens.accessToken).toBe("at");
      expect(tokens.refreshToken).toBe("rt");
      expect(tokens.expiresAt).toBeGreaterThan(before + 3500 * 1000);
      const [, options] = fakeFetch.mock.calls[0] as [string, RequestInit];
      expect(options.body).toContain("grant_type=authorization_code");
      expect(options.body).toContain("client_id=my-client");
      expect(options.body).toContain("code=the-code");
      expect(options.body).toContain("code_verifier=the-verifier");
    });

    it("should_throw_when_exchangeFails", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 400 });
      const client = new AuthentikAuthClient(
        { issuerUrl: "https://issuer", clientId: "client" }, fakeFetch as unknown as typeof fetch,
      );

      await expect(client.exchangeCodeForTokens("https://issuer/token", "code", "verifier")).rejects.toThrow("400");
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
  });
});
