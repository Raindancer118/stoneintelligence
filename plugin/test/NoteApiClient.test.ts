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

  describe("Einladen", () => {
    const client = (response: object) => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => response });
      return { fakeFetch, api: new NoteApiClient("https://platform.example", async () => "tok", fakeFetch as unknown as typeof fetch) };
    };

    it("should_searchPeople_withTheQueryEncoded", async () => {
      const { fakeFetch, api } = client([{ username: "anna", name: "Anna", maskedEmail: "a***@x.de", alreadyMember: false }]);

      const people = await api.searchPeople("v", "anna+x");

      expect(people[0].username).toBe("anna");
      expect(fakeFetch.mock.calls[0][0]).toBe("https://platform.example/api/v1/vaults/v/people?q=anna%2Bx");
    });

    it("should_addAPerson_andInviteByEmail_withTheChosenAccess", async () => {
      const { fakeFetch, api } = client({ status: "ADDED", displayName: "Anna" });

      await api.addPerson("v", "anna", "READ");
      await api.inviteByEmail("v", "neu@x.de", "EDIT");

      expect(fakeFetch.mock.calls[0][0]).toBe("https://platform.example/api/v1/vaults/v/members");
      expect(JSON.parse(fakeFetch.mock.calls[0][1].body)).toEqual({ username: "anna", access: "READ" });
      expect(fakeFetch.mock.calls[1][0]).toBe("https://platform.example/api/v1/vaults/v/invitations");
      expect(JSON.parse(fakeFetch.mock.calls[1][1].body)).toEqual({ email: "neu@x.de", access: "EDIT" });
    });

    it("should_surfaceTheServersExplanation_forRejectedInvitations", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 422, json: async () => ({ detail: "Bitte eine gültige E-Mail-Adresse angeben." }) });
      const api = new NoteApiClient("https://platform.example", async () => "t", fakeFetch as unknown as typeof fetch);

      await expect(api.inviteByEmail("v", "x", "EDIT")).rejects.toThrow("Bitte eine gültige E-Mail-Adresse angeben.");
    });

    it("should_readTheOwnPermissions_forAVault", async () => {
      const { fakeFetch, api } = client(["READ", "MANAGE"]);

      expect(await api.permissions("v")).toEqual(["READ", "MANAGE"]);
      expect(fakeFetch.mock.calls[0][0]).toBe("https://platform.example/api/v1/vaults/v/permissions");
    });
  });
  describe("folders", () => {
    const clientWith = (fakeFetch: ReturnType<typeof vi.fn>) =>
      new NoteApiClient("https://platform.example", async () => "t", fakeFetch as unknown as typeof fetch);

    it("should_listTheVaultsFolders", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ["A", "A/B"] });

      await expect(clientWith(fakeFetch).listFolders("v1")).resolves.toEqual(["A", "A/B"]);
      expect(fakeFetch).toHaveBeenCalledWith("https://platform.example/api/v1/vaults/v1/folders",
        expect.objectContaining({ headers: { Authorization: "Bearer t" } }));
    });

    // Ein Server vor der Ordner-Synchronisation kennt den Endpunkt nicht - dann gibt es sie eben nicht.
    it("should_reportAServerWithoutFolderSupport_asNull", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 404 });

      await expect(clientWith(fakeFetch).listFolders("v1")).resolves.toBeNull();
    });

    it("should_createRenameAndDeleteFolders", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
      const client = clientWith(fakeFetch);

      await client.createFolder("v1", "A/B");
      await client.renameFolder("v1", "A", "Z/A");
      await client.deleteFolder("v1", "Z/A b");

      expect(fakeFetch.mock.calls.map(([url, init]) => [url, init.method, init.body])).toEqual([
        ["https://platform.example/api/v1/vaults/v1/folders", "POST", JSON.stringify({ path: "A/B" })],
        ["https://platform.example/api/v1/vaults/v1/folders/rename", "POST", JSON.stringify({ from: "A", to: "Z/A" })],
        ["https://platform.example/api/v1/vaults/v1/folders?path=Z%2FA+b", "DELETE", undefined],
      ]);
    });

    it("should_throwWithStatus_whenAFolderOperationIsRejected", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 403 });

      await expect(clientWith(fakeFetch).createFolder("v1", "A")).rejects.toMatchObject({ status: 403 });
    });
  });
  describe("files", () => {
    const clientWith = (fakeFetch: ReturnType<typeof vi.fn>) =>
      new NoteApiClient("https://platform.example", async () => "t", fakeFetch as unknown as typeof fetch);

    it("should_listNotesAndFiles_whenAskedForFiles", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({
        epochId: "e", complete: true, nextCursor: null,
        notes: [{ id: "f1", vaultId: "v1", path: "a.pdf", noteLevel: 1, kind: "FILE", revision: 2, sha256: "ab", size: 3 }] }) });

      const entries = await clientWith(fakeFetch).listAllEntries("v1");

      expect(entries).toEqual([expect.objectContaining({ id: "f1", kind: "FILE", sha256: "ab" })]);
      expect(fakeFetch.mock.calls[0][0]).toContain("kinds=note%2Cfile");
    });

    it("should_createAFile_andUploadBytesOnTheBaseRevision", async () => {
      const fakeFetch = vi.fn()
        .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ id: "f1" }) })
        .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ revision: 3, sha256: "cd", size: 4 }) });
      const client = clientWith(fakeFetch);
      const bytes = new Uint8Array([1, 2, 3, 4]).buffer;

      await expect(client.createFile("v1", "Bilder/x.png")).resolves.toBe("f1");
      await expect(client.uploadFile("v1", "f1", 2, bytes, "image/png")).resolves.toEqual({ revision: 3, sha256: "cd", size: 4 });

      const [url, init] = fakeFetch.mock.calls[1];
      expect(url).toBe("https://platform.example/api/v1/vaults/v1/files/f1/content");
      expect(init).toMatchObject({ method: "PUT", body: bytes, headers: { "If-Match": "\"2\"", "Content-Type": "image/png" } });
    });

    // Jemand war schneller: das Plugin behaelt seine Fassung als Kopie (ADR 0009 Punkt 3).
    it("should_reportAStaleBase_withTheCurrentRevision", async () => {
      const fakeFetch = vi.fn().mockResolvedValue({ ok: false, status: 409,
        headers: { get: (name: string) => name === "X-Current-Revision" ? "5" : null }, json: async () => ({}) });

      await expect(clientWith(fakeFetch).uploadFile("v1", "f1", 2, new ArrayBuffer(1), "image/png"))
        .rejects.toMatchObject({ name: "FileConflictError", currentRevision: 5 });
    });

    it("should_downloadBytes_withRevisionAndHash", async () => {
      const bytes = new Uint8Array([9, 8, 7]).buffer;
      const fakeFetch = vi.fn().mockResolvedValue({ ok: true, status: 200, arrayBuffer: async () => bytes,
        headers: { get: (name: string) => ({ etag: "\"4\"", "x-content-sha256": "ef" } as Record<string, string>)[name.toLowerCase()] ?? null } });

      await expect(clientWith(fakeFetch).downloadFile("v1", "f1")).resolves.toEqual({ bytes, revision: 4, sha256: "ef" });
    });

    it("should_readTheLimits_orNullOnAServerWithoutFiles", async () => {
      const limits = { maxFileBytes: 200, vaultQuotaBytes: 5000 };
      await expect(clientWith(vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => limits })).fileLimits())
        .resolves.toEqual(limits);
      await expect(clientWith(vi.fn().mockResolvedValue({ ok: false, status: 404 })).fileLimits()).resolves.toBeNull();
    });
  });
});

