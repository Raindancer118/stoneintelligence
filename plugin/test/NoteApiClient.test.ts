import { describe, expect, it, vi } from "vitest";
import { HttpError, NoteApiClient } from "../src/sync/NoteApiClient";

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

  describe("listNotes", () => {
    it("should_returnReconciliationPage_when_serverRespondsOk", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({
          epochId: "epoch-1", complete: true, nextCursor: null,
          notes: [{ id: "note-1", vaultId: "vault-1", path: "foo.md", noteLevel: 1 }],
        }),
      });
      const getAccessToken = vi.fn().mockResolvedValue("the-access-token");
      const client = new NoteApiClient("https://platform.example", getAccessToken, fakeFetch as unknown as typeof fetch);

      const page = await client.listNotes("vault-1");

      expect(page.complete).toBe(true);
      expect(page.notes).toEqual([{ id: "note-1", vaultId: "vault-1", path: "foo.md", noteLevel: 1 }]);
      expect(fakeFetch).toHaveBeenCalledWith(
        "https://platform.example/api/v1/vaults/vault-1/notes?pageSize=100",
        expect.objectContaining({ headers: { Authorization: "Bearer the-access-token" } }),
      );
    });

    it("should_includeCursor_when_given", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({
        ok: true, json: async () => ({ epochId: "e", complete: true, nextCursor: null, notes: [] }),
      });
      const getAccessToken = vi.fn().mockResolvedValue("token");
      const client = new NoteApiClient("https://platform.example", getAccessToken, fakeFetch as unknown as typeof fetch);

      await client.listNotes("vault-1", "cursor-abc");

      expect(fakeFetch).toHaveBeenCalledWith(
        "https://platform.example/api/v1/vaults/vault-1/notes?pageSize=100&cursor=cursor-abc",
        expect.anything(),
      );
    });

    it("should_throw_when_serverRespondsWithError", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 403 });
      const getAccessToken = vi.fn().mockResolvedValue("token");
      const client = new NoteApiClient("https://platform.example", getAccessToken, fakeFetch as unknown as typeof fetch);

      await expect(client.listNotes("vault-1")).rejects.toThrow("403");
    });
  });

  describe("listAllNotes", () => {
    it("should_followCursor_untilComplete_andConcatenateAllPages", async () => {
      const fakeFetch = vi.fn()
        .mockResolvedValueOnce({
          ok: true,
          json: async () => ({
            epochId: "e", complete: false, nextCursor: "cursor-2",
            notes: [{ id: "note-1", vaultId: "vault-1", path: "a.md", noteLevel: 1 }],
          }),
        })
        .mockResolvedValueOnce({
          ok: true,
          json: async () => ({
            epochId: "e", complete: true, nextCursor: null,
            notes: [{ id: "note-2", vaultId: "vault-1", path: "b.md", noteLevel: 1 }],
          }),
        });
      const getAccessToken = vi.fn().mockResolvedValue("token");
      const client = new NoteApiClient("https://platform.example", getAccessToken, fakeFetch as unknown as typeof fetch);

      const notes = await client.listAllNotes("vault-1");

      expect(notes.map((n) => n.path)).toEqual(["a.md", "b.md"]);
      expect(fakeFetch).toHaveBeenCalledTimes(2);
      expect(fakeFetch.mock.calls[1][0]).toContain("cursor=cursor-2");
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

  describe("Fehler mit Statuscode", () => {
    // Der Abgleich muss 409 (Pfad existiert schon) und 403 (keine Rechte) unterscheiden koennen,
    // statt aus einer Fehlermeldung zu raten.
    it("should_exposeTheHttpStatus_when_creatingANoteFails", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 409 });
      const client = new NoteApiClient("https://platform.example", async () => "t", fakeFetch as unknown as typeof fetch);

      const error = await client.createNote("v", "a.md", 1).catch((caught: unknown) => caught);

      expect(error).toBeInstanceOf(HttpError);
      expect((error as HttpError).status).toBe(409);
    });
  });

  describe("listVaults", () => {
    it("should_returnTheVaultsTheUserCanAccess", async () => {
      const vaults = [{ id: "v1", name: "Team", createdAt: "2026-09-01T00:00:00Z" }];
      const fakeFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => vaults });
      const client = new NoteApiClient("https://platform.example", async () => "tok", fakeFetch as unknown as typeof fetch);

      expect(await client.listVaults()).toEqual(vaults);
      expect(fakeFetch).toHaveBeenCalledWith(
        "https://platform.example/api/v1/vaults",
        expect.objectContaining({ headers: { Authorization: "Bearer tok" } }),
      );
    });
  });

  describe("noteStatus", () => {
    it.each([
      [200, "exists"],
      [404, "deleted"],
      [403, "forbidden"],
    ])("should_map_HTTP_%i_to_%s", async (status, expected) => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: status === 200, status });
      const client = new NoteApiClient("https://platform.example", async () => "t", fakeFetch as unknown as typeof fetch);

      expect(await client.noteStatus("v", "n")).toBe(expected);
      expect(fakeFetch).toHaveBeenCalledWith("https://platform.example/api/v1/vaults/v/notes/n", expect.anything());
    });

    it("should_throw_on_unexpectedStatus_insteadOfGuessing", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 503 });
      const client = new NoteApiClient("https://platform.example", async () => "t", fakeFetch as unknown as typeof fetch);

      await expect(client.noteStatus("v", "n")).rejects.toBeInstanceOf(HttpError);
    });
  });

  describe("deleteNote", () => {
    it("should_treatAnAlreadyDeletedNote_asSuccess", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 404 });
      const client = new NoteApiClient("https://platform.example", async () => "t", fakeFetch as unknown as typeof fetch);

      await expect(client.deleteNote("v", "n", "op")).resolves.toBeUndefined();
    });
  });
});
