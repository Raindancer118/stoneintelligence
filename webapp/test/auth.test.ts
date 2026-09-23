import { beforeEach, describe, expect, it, vi } from "vitest";
const { storedUser, signinSilent } = vi.hoisted(() => ({ storedUser: vi.fn(), signinSilent: vi.fn() }));
vi.mock("oidc-client-ts", () => ({ UserManager: class { getUser = storedUser; signinSilent = signinSilent; } }));
import { getUser, getAccessToken } from "../src/lib/auth";
beforeEach(() => { storedUser.mockReset(); signinSilent.mockReset(); });
describe("stored login", () => {
  it("returns to login when the stored user has expired and cannot be renewed", async () => {
    storedUser.mockResolvedValue({ expired: true, access_token: "expired-token" });
    expect(await getUser()).toBeNull();
    await expect(getAccessToken()).rejects.toThrow("nicht angemeldet");
    expect(signinSilent).not.toHaveBeenCalled();
  });
  it("keeps a valid session", async () => {
    const user = { expired: false, expires_in: 3000, access_token: "valid-token" };
    storedUser.mockResolvedValue(user);
    await expect(getUser()).resolves.toBe(user);
    await expect(getAccessToken()).resolves.toBe("valid-token");
  });
  it("allows login when session storage is unavailable", async () => {
    storedUser.mockRejectedValue(new Error("storage unavailable"));
    expect(await getUser()).toBeNull();
  });
});

// Bisher lief die Anmeldung im Dashboard nach der Lebensdauer des Access-Tokens einfach ab, obwohl
// ein Refresh-Token da war - jede Anfrage endete dann in "Deine Anmeldung ist abgelaufen".
describe("renewal with the refresh token", () => {
  it("renews an expired access token silently instead of logging out", async () => {
    storedUser.mockResolvedValue({ expired: true, refresh_token: "r", access_token: "old" });
    signinSilent.mockResolvedValue({ expired: false, expires_in: 3600, access_token: "fresh" });

    await expect(getAccessToken()).resolves.toBe("fresh");
    expect(signinSilent).toHaveBeenCalledTimes(1);
  });

  it("renews shortly before expiry, so a request never goes out with a dying token", async () => {
    storedUser.mockResolvedValue({ expired: false, expires_in: 20, refresh_token: "r", access_token: "almost-old" });
    signinSilent.mockResolvedValue({ expired: false, expires_in: 3600, access_token: "fresh" });

    await expect(getAccessToken()).resolves.toBe("fresh");
  });

  it("keeps the dashboard open after a reload with an expired token", async () => {
    const renewed = { expired: false, expires_in: 3600, access_token: "fresh" };
    storedUser.mockResolvedValue({ expired: true, refresh_token: "r", access_token: "old" });
    signinSilent.mockResolvedValue(renewed);

    await expect(getUser()).resolves.toBe(renewed);
  });

  it("renews only once for parallel requests", async () => {
    storedUser.mockResolvedValue({ expired: true, refresh_token: "r", access_token: "old" });
    let finish: (user: unknown) => void = () => undefined;
    signinSilent.mockReturnValue(new Promise((resolve) => (finish = resolve)));

    const both = Promise.all([getAccessToken(), getAccessToken()]);
    finish({ expired: false, expires_in: 3600, access_token: "fresh" });

    await expect(both).resolves.toEqual(["fresh", "fresh"]);
    expect(signinSilent).toHaveBeenCalledTimes(1);
  });

  it("falls back to login when the refresh token is no longer accepted", async () => {
    storedUser.mockResolvedValue({ expired: true, refresh_token: "r", access_token: "old" });
    signinSilent.mockRejectedValue(new Error("invalid_grant"));

    await expect(getAccessToken()).rejects.toThrow("nicht angemeldet");
    expect(await getUser()).toBeNull();
  });
});
