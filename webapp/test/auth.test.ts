import { beforeEach, describe, expect, it, vi } from "vitest";
const { storedUser } = vi.hoisted(() => ({ storedUser: vi.fn() }));
vi.mock("oidc-client-ts", () => ({ UserManager: class { getUser = storedUser; } }));
import { getUser, getAccessToken } from "../src/lib/auth";
beforeEach(() => { storedUser.mockReset(); });
describe("stored login", () => {
  it("returns to login when the stored user has expired", async () => {
    storedUser.mockResolvedValue({ expired: true, access_token: "expired-token" });
    expect(await getUser()).toBeNull();
    await expect(getAccessToken()).rejects.toThrow("nicht angemeldet");
  });
  it("keeps a valid session", async () => {
    const user = { expired: false, access_token: "valid-token" };
    storedUser.mockResolvedValue(user);
    await expect(getUser()).resolves.toBe(user);
    await expect(getAccessToken()).resolves.toBe("valid-token");
  });
  it("allows login when session storage is unavailable", async () => {
    storedUser.mockRejectedValue(new Error("storage unavailable"));
    expect(await getUser()).toBeNull();
  });
});
