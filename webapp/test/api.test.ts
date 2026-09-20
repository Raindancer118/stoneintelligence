import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "../src/lib/api";
vi.mock("../src/lib/auth", () => ({ getAccessToken: vi.fn().mockResolvedValue("test-token") }));
afterEach(() => vi.unstubAllGlobals());
describe("API responses", () => {
  it.each([200, 204])("accepts empty HTTP %s without Content-Length", async (status) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status })));
    await expect(api.addMember("vault", "group", "tom")).resolves.toBeUndefined();
  });
  it("returns JSON data", async () => {
    const vaults = [{ id: "vault", name: "Notes", createdAt: "2026-09-19" }];
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(vaults)));
    await expect(api.listVaults()).resolves.toEqual(vaults);
  });
  it("does not hide HTTP failures with empty bodies", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 403 })));
    await expect(api.addMember("vault", "group", "tom")).rejects.toMatchObject({ status: 403 });
  });
  it("does not hide malformed JSON", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("broken")));
    await expect(api.listVaults()).rejects.toThrow();
  });
});
