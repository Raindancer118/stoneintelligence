import { describe, expect, it, vi } from "vitest";
import { NoteApiClient } from "../src/sync/NoteApiClient";

describe("NoteApiClient", () => {
  describe("createNote", () => {
    it("should_returnCreatedNoteId_when_serverRespondsOk", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: "note-1" }) });
      const client = new NoteApiClient("https://platform.example", "tom", fakeFetch as unknown as typeof fetch);

      const noteId = await client.createNote("vault-1", "foo.md", 1);

      expect(noteId).toBe("note-1");
      expect(fakeFetch).toHaveBeenCalledWith(
        "https://platform.example/api/v1/vaults/vault-1/notes",
        expect.objectContaining({
          method: "POST",
          headers: { "Content-Type": "application/json", "X-Actor": "tom" },
          body: JSON.stringify({ path: "foo.md", noteLevel: 1 }),
        }),
      );
    });

    it("should_throw_when_serverRespondsWithError", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 500 });
      const client = new NoteApiClient("https://platform.example", "tom", fakeFetch as unknown as typeof fetch);

      await expect(client.createNote("vault-1", "foo.md", 1)).rejects.toThrow("500");
    });
  });

  describe("renameNote", () => {
    it("should_sendPatchRequest_when_renaming", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
      const client = new NoteApiClient("https://platform.example", "tom", fakeFetch as unknown as typeof fetch);

      await client.renameNote("vault-1", "note-1", "new-path.md");

      expect(fakeFetch).toHaveBeenCalledWith(
        "https://platform.example/api/v1/vaults/vault-1/notes/note-1",
        expect.objectContaining({
          method: "PATCH",
          headers: { "Content-Type": "application/json", "X-Actor": "tom" },
          body: JSON.stringify({ path: "new-path.md" }),
        }),
      );
    });

    it("should_throw_when_serverRespondsWithError", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 404 });
      const client = new NoteApiClient("https://platform.example", "tom", fakeFetch as unknown as typeof fetch);

      await expect(client.renameNote("vault-1", "note-1", "new.md")).rejects.toThrow("404");
    });
  });

  describe("deleteNote", () => {
    it("should_sendDeleteRequestWithOperationId_when_deleting", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
      const client = new NoteApiClient("https://platform.example", "tom", fakeFetch as unknown as typeof fetch);

      await client.deleteNote("vault-1", "note-1", "op-123");

      expect(fakeFetch).toHaveBeenCalledWith(
        "https://platform.example/api/v1/vaults/vault-1/notes/note-1",
        expect.objectContaining({
          method: "DELETE",
          headers: { "X-Operation-Id": "op-123", "X-Actor": "tom" },
        }),
      );
    });

    it("should_throw_when_serverRespondsWithError", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 403 });
      const client = new NoteApiClient("https://platform.example", "tom", fakeFetch as unknown as typeof fetch);

      await expect(client.deleteNote("vault-1", "note-1", "op-123")).rejects.toThrow("403");
    });
  });
});
