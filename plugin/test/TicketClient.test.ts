import { describe, expect, it, vi } from "vitest";
import { TicketClient } from "../src/sync/TicketClient";

describe("TicketClient", () => {
  it("should_issueTicket_when_serverRespondsOk", async () => {
    const fakeFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ token: "abc", expiresAt: "2026-09-20T10:00:30Z" }),
    });
    const client = new TicketClient("https://platform.example", "tom", fakeFetch as unknown as typeof fetch);

    const ticket = await client.issueTicket("vault-1", "note-1");

    expect(ticket.token).toBe("abc");
    expect(fakeFetch).toHaveBeenCalledWith(
      "https://platform.example/api/v1/vaults/vault-1/notes/note-1/sync-tickets",
      expect.objectContaining({ method: "POST", headers: { "X-Actor": "tom" } }),
    );
  });

  it("should_throw_when_serverRespondsWithError", async () => {
    const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 401 });
    const client = new TicketClient("https://platform.example", "tom", fakeFetch as unknown as typeof fetch);

    await expect(client.issueTicket("vault-1", "note-1")).rejects.toThrow("401");
  });
});
