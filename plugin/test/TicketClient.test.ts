import { describe, expect, it, vi } from "vitest";
import { TicketClient } from "../src/sync/TicketClient";

describe("TicketClient", () => {
  it("should_issueVaultScopedTicket_when_serverRespondsOk", async () => {
    const fakeFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ token: "abc", expiresAt: "2026-09-20T10:00:30Z" }),
    });
    const getAccessToken = vi.fn().mockResolvedValue("the-access-token");
    const client = new TicketClient("https://platform.example", getAccessToken, fakeFetch as unknown as typeof fetch);

    const ticket = await client.issueTicket("vault-1");

    expect(ticket.token).toBe("abc");
    expect(fakeFetch).toHaveBeenCalledWith(
      "https://platform.example/api/v1/vaults/vault-1/sync-tickets",
      expect.objectContaining({ method: "POST", headers: { Authorization: "Bearer the-access-token" } }),
    );
  });

  it("should_throw_when_serverRespondsWithError", async () => {
    const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 401 });
    const getAccessToken = vi.fn().mockResolvedValue("token");
    const client = new TicketClient("https://platform.example", getAccessToken, fakeFetch as unknown as typeof fetch);

    await expect(client.issueTicket("vault-1")).rejects.toThrow("401");
  });

  it("should_sendTheDeviceId_andSurfaceTheServersRefusalWithItsCode", async () => {
    const fakeFetch = vi.fn().mockResolvedValue({
      ok: false, status: 403, json: async () => ({ code: "DEVICE_NOT_COMPLIANT", detail: "Nicht erlaubte Plugins sind aktiv: kanban." }),
    });
    const client = new TicketClient("https://platform.example", vi.fn().mockResolvedValue("t"), fakeFetch as unknown as typeof fetch);

    await expect(client.issueTicket("vault-1", "dev-12345678")).rejects.toMatchObject({
      name: "DeviceGateError", code: "DEVICE_NOT_COMPLIANT", message: "Nicht erlaubte Plugins sind aktiv: kanban.",
    });
    expect(fakeFetch).toHaveBeenCalledWith(expect.any(String),
      expect.objectContaining({ headers: { Authorization: "Bearer t", "X-Device-Id": "dev-12345678" } }));
  });
});
