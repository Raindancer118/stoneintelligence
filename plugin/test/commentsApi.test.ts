import { describe, expect, it, vi } from "vitest";
import { NoteApiClient } from "../src/sync/NoteApiClient";

const reply = (body: unknown = {}) => vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) });
const client = (fetchImpl: ReturnType<typeof vi.fn>) =>
  new NoteApiClient("https://platform.example", async () => "tok", fetchImpl as unknown as typeof fetch);

describe("comments API", () => {
  it("should_startAThreadWithTheQuotedPassage", async () => {
    const fake = reply({ id: "t1" });

    await client(fake).startThread("v", "n", "Hallo", { quote: "Satz", prefix: "a ", suffix: " b" });

    expect(fake).toHaveBeenCalledWith("https://platform.example/api/v1/vaults/v/notes/n/comments", expect.objectContaining({
      method: "POST", body: JSON.stringify({ body: "Hallo", anchor: { quote: "Satz", prefix: "a ", suffix: " b" } }),
    }));
  });

  it("should_useTheRightVerbAndPath_forEveryThreadAction", async () => {
    const fake = reply();
    const api = client(fake);

    await api.listComments("v", "n");
    await api.replyToThread("v", "t", "ok");
    await api.editComment("v", "c", "neu");
    await api.deleteComment("v", "c");
    await api.resolveThread("v", "t");
    await api.reopenThread("v", "t");

    const calls = fake.mock.calls.map(([url, init]) => `${(init as RequestInit).method ?? "GET"} ${String(url).replace("https://platform.example", "")}`);
    expect(calls).toEqual([
      "GET /api/v1/vaults/v/notes/n/comments",
      "POST /api/v1/vaults/v/comment-threads/t/replies",
      "PATCH /api/v1/vaults/v/comments/c",
      "DELETE /api/v1/vaults/v/comments/c",
      "POST /api/v1/vaults/v/comment-threads/t/resolve",
      "POST /api/v1/vaults/v/comment-threads/t/reopen",
    ]);
  });

  it("should_readAndMarkNotifications", async () => {
    const fake = reply({ items: [], unread: 0 });
    const api = client(fake);

    expect(await api.notifications()).toEqual({ items: [], unread: 0 });
    await api.markNotificationsRead(["a"]);
    await api.markNotificationsRead();

    expect(fake.mock.calls[1][1]).toMatchObject({ method: "POST", body: JSON.stringify({ ids: ["a"] }) });
    expect(fake.mock.calls[2][1]).toMatchObject({ method: "POST", body: JSON.stringify({ ids: [] }) });
  });

  it("should_showTheServersReasonForARefusedMention", async () => {
    const fake = vi.fn().mockResolvedValue({ ok: false, status: 422, json: async () => ({ detail: "Ben darf diese Notiz nicht lesen." }) });

    await expect(client(fake).replyToThread("v", "t", "x")).rejects.toThrow("Ben darf diese Notiz nicht lesen.");
  });
});
