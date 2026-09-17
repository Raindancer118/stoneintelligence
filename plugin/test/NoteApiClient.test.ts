import { describe, expect, it, vi } from "vitest";
import { NoteApiClient } from "../src/sync/NoteApiClient";

describe("NoteApiClient", () => {
  describe("createVault", () => {
    it("should_returnCreatedVaultId_when_serverRespondsOk", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: "vault-1" }) });
      const getAccessToken = vi.fn().mockResolvedValue("the-access-token");
      const client = new NoteApiClient("https://platform.example", getAccessToken, fakeFetch as unknown as typeof fetch);

      const vaultId = await client.createVault("my-vault");

      expect(vaultId).toBe("vault-1");
      expect(fakeFetch).toHaveBeenCalledWith(
        "https://platform.example/api/v1/vaults",
        expect.objectContaining({
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: "Bearer the-access-token" },
          body: JSON.stringify({ name: "my-vault" }),
        }),
      );
    });

    it("should_throw_when_serverRespondsWithError", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 401 });
      const getAccessToken = vi.fn().mockResolvedValue("token");
      const client = new NoteApiClient("https://platform.example", getAccessToken, fakeFetch as unknown as typeof fetch);

      await expect(client.createVault("my-vault")).rejects.toThrow("401");
    });
  });

  describe("createNote", () => {
    it("should_returnCreatedNoteId_when_serverRespondsOk", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: "note-1" }) });
      const getAccessToken = vi.fn().mockResolvedValue("the-access-token");
      const client = new NoteApiClient("https://platform.example", getAccessToken, fakeFetch as unknown as typeof fetch);

      const noteId = await client.createNote("vault-1", "foo.md", 1);

      expect(noteId).toBe("note-1");
      expect(fakeFetch).toHaveBeenCalledWith(
        "https://platform.example/api/v1/vaults/vault-1/notes",
        expect.objectContaining({
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: "Bearer the-access-token" },
          body: JSON.stringify({ path: "foo.md", noteLevel: 1 }),
        }),
      );
    });

    it("should_throw_when_serverRespondsWithError", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 500 });
      const getAccessToken = vi.fn().mockResolvedValue("token");
      const client = new NoteApiClient("https://platform.example", getAccessToken, fakeFetch as unknown as typeof fetch);

      await expect(client.createNote("vault-1", "foo.md", 1)).rejects.toThrow("500");
    });
  });

  describe("renameNote", () => {
    it("should_sendPatchRequest_when_renaming", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
      const getAccessToken = vi.fn().mockResolvedValue("the-access-token");
      const client = new NoteApiClient("https://platform.example", getAccessToken, fakeFetch as unknown as typeof fetch);

      await client.renameNote("vault-1", "note-1", "new-path.md");

      expect(fakeFetch).toHaveBeenCalledWith(
        "https://platform.example/api/v1/vaults/vault-1/notes/note-1",
        expect.objectContaining({
          method: "PATCH",
          headers: { "Content-Type": "application/json", Authorization: "Bearer the-access-token" },
          body: JSON.stringify({ path: "new-path.md" }),
        }),
      );
    });

    it("should_throw_when_serverRespondsWithError", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 404 });
      const getAccessToken = vi.fn().mockResolvedValue("token");
      const client = new NoteApiClient("https://platform.example", getAccessToken, fakeFetch as unknown as typeof fetch);

      await expect(client.renameNote("vault-1", "note-1", "new.md")).rejects.toThrow("404");
    });
  });

  describe("deleteNote", () => {
    it("should_sendDeleteRequestWithOperationId_when_deleting", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
      const getAccessToken = vi.fn().mockResolvedValue("the-access-token");
      const client = new NoteApiClient("https://platform.example", getAccessToken, fakeFetch as unknown as typeof fetch);

      await client.deleteNote("vault-1", "note-1", "op-123");

      expect(fakeFetch).toHaveBeenCalledWith(
        "https://platform.example/api/v1/vaults/vault-1/notes/note-1",
        expect.objectContaining({
          method: "DELETE",
          headers: { "X-Operation-Id": "op-123", Authorization: "Bearer the-access-token" },
        }),
      );
    });

    it("should_throw_when_serverRespondsWithError", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 403 });
      const getAccessToken = vi.fn().mockResolvedValue("token");
      const client = new NoteApiClient("https://platform.example", getAccessToken, fakeFetch as unknown as typeof fetch);

      await expect(client.deleteNote("vault-1", "note-1", "op-123")).rejects.toThrow("403");
    });
  });
});
