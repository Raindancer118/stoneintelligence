import { beforeEach, describe, expect, it, vi } from "vitest";
import { contentTypeFor, FileSync, type FileSyncPorts, type FileVaultPort } from "../src/sync/fileSync";
import { emptyVaultState, type VaultSyncState } from "../src/settings";
import { FileConflictError } from "../src/sync/NoteApiClient";
import type { ServerFile } from "../src/sync/filePlan";

const enc = (text: string) => new TextEncoder().encode(text).buffer as ArrayBuffer;
const dec = (bytes: ArrayBuffer) => new TextDecoder().decode(bytes);

/** Ein Obsidian-Vault im Speicher: Inhalt und Stat je Pfad. */
class MemoryVault implements FileVaultPort {
  files = new Map<string, { bytes: ArrayBuffer; mtime: number }>();
  trashed: string[] = [];
  clock = 1000;
  put(path: string, text: string) { this.files.set(path, { bytes: enc(text), mtime: ++this.clock }); }
  text(path: string) { const file = this.files.get(path); return file ? dec(file.bytes) : undefined; }
  list() { return [...this.files].map(([path, f]) => ({ path, mtime: f.mtime, size: f.bytes.byteLength })); }
  stat(path: string) { const f = this.files.get(path); return f ? { mtime: f.mtime, size: f.bytes.byteLength } : null; }
  async read(path: string) { return this.files.get(path)!.bytes; }
  async write(path: string, bytes: ArrayBuffer) { this.files.set(path, { bytes, mtime: ++this.clock }); }
  async rename(from: string, to: string) { this.files.set(to, this.files.get(from)!); this.files.delete(from); }
  async trash(path: string) { this.files.delete(path); this.trashed.push(path); }
}

/** platform-api im Kleinen: Dateien mit Fassungen, Konflikt bei veralteter Basis. */
class FakeServer {
  files = new Map<string, { path: string; revision: number; bytes: ArrayBuffer; sha256: string }>();
  deleted = new Set<string>();
  next = 1;
  async sha(bytes: ArrayBuffer) { return [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))].map(b => b.toString(16).padStart(2, "0")).join(""); }
  async seed(path: string, text: string) { const id = `s${this.next++}`; this.files.set(id, { path, revision: 1, bytes: enc(text), sha256: await this.sha(enc(text)) }); return id; }
  list(): ServerFile[] { return [...this.files].map(([id, f]) => ({ id, path: f.path, revision: f.revision, sha256: f.revision ? f.sha256 : null })); }
  api = {
    createFile: vi.fn(async (_vault: string, path: string) => { const id = `f${this.next++}`; this.files.set(id, { path, revision: 0, bytes: new ArrayBuffer(0), sha256: "" }); return id; }),
    uploadFile: vi.fn(async (_vault: string, id: string, base: number, bytes: ArrayBuffer) => {
      const file = this.files.get(id)!;
      if (file.revision !== base) throw new FileConflictError(file.revision);
      file.revision++; file.bytes = bytes; file.sha256 = await this.sha(bytes);
      return { revision: file.revision, sha256: file.sha256, size: bytes.byteLength };
    }),
    downloadFile: vi.fn(async (_vault: string, id: string) => { const f = this.files.get(id)!; return { bytes: f.bytes, revision: f.revision, sha256: f.sha256 }; }),
    noteStatus: vi.fn(async (_vault: string, id: string) => (this.deleted.has(id) ? "deleted" : "exists") as "deleted" | "exists"),
  };
}

let vault: MemoryVault;
let server: FakeServer;
let state: VaultSyncState;
let problems: Record<string, string>;
let decisions: string[];
let sync: FileSync;

beforeEach(() => {
  vault = new MemoryVault();
  server = new FakeServer();
  state = emptyVaultState();
  problems = {};
  decisions = [];
  const ports: FileSyncPorts = {
    vaultId: () => "v1", vault, api: server.api, state: () => state, save: () => undefined,
    report: (path, message) => { problems[path] = message; }, clearProblem: (path) => { delete problems[path]; },
    log: () => undefined, now: () => new Date(2026, 8, 23, 14, 5),
    askDeletionDecision: (path) => { decisions.push(path); },
    contentType: (path) => (path.endsWith(".pdf") ? "application/pdf" : "application/octet-stream"),
  };
  sync = new FileSync(ports);
});

async function pass() {
  await sync.reconcile(server.list(), 1000);
}

describe("FileSync", () => {
  it("uploads a file that exists only here, and remembers its state", async () => {
    vault.put("Skript.pdf", "%PDF eins");

    await pass();

    const [id] = Object.values(state.fileIds);
    expect(dec(server.files.get(id)!.bytes)).toBe("%PDF eins");
    expect(state.fileMeta[id]).toMatchObject({ revision: 1, size: 9 });
    await pass();
    expect(server.api.uploadFile).toHaveBeenCalledTimes(1);
  });

  it("downloads files of other devices, and does not upload them back", async () => {
    await server.seed("Bilder/Foto.png", "PNG-Bytes");

    await pass();
    await pass();

    expect(vault.text("Bilder/Foto.png")).toBe("PNG-Bytes");
    expect(server.api.uploadFile).not.toHaveBeenCalled();
  });

  it("uploads a change on the revision it is based on, and downloads newer server versions", async () => {
    const id = await server.seed("a.pdf", "v1");
    await pass();

    vault.put("a.pdf", "v2 hier");
    await pass();
    expect(server.files.get(id)!.revision).toBe(2);

    const remote = server.files.get(id)!;
    remote.revision = 3; remote.bytes = enc("v3 anderswo"); remote.sha256 = await server.sha(remote.bytes);
    await pass();
    expect(vault.text("a.pdf")).toBe("v3 anderswo");
  });

  // Nur der Zeitstempel hat sich geaendert (z. B. Kopie zurueckgespielt): nichts hochladen.
  it("does not upload when only the timestamp changed", async () => {
    await server.seed("a.pdf", "gleich");
    await pass();

    vault.put("a.pdf", "gleich");
    await pass();

    expect(server.api.uploadFile).not.toHaveBeenCalled();
  });

  // Binaerdateien lassen sich nicht zusammenfuehren - keine Fassung darf verloren gehen (ADR 0009 Punkt 3).
  it("keeps both versions when a file changed here and elsewhere", async () => {
    const id = await server.seed("Skript.pdf", "Basis");
    await pass();
    vault.put("Skript.pdf", "meine Fassung");
    const remote = server.files.get(id)!;
    remote.revision = 2; remote.bytes = enc("deren Fassung"); remote.sha256 = await server.sha(remote.bytes);

    await pass();

    expect(vault.text("Skript.pdf")).toBe("deren Fassung");
    expect(vault.text("Skript (Konflikt 2026-09-23 14-05).pdf")).toBe("meine Fassung");
    await pass();
    const copyId = state.fileIds["Skript (Konflikt 2026-09-23 14-05).pdf"];
    expect(dec(server.files.get(copyId)!.bytes)).toBe("meine Fassung");
  });

  it("turns a rejected upload on a stale base into a conflict copy as well", async () => {
    const id = await server.seed("a.pdf", "Basis");
    await pass();
    vault.put("a.pdf", "meine");
    server.files.get(id)!.revision = 2;
    server.files.get(id)!.bytes = enc("deren");
    server.files.get(id)!.sha256 = await server.sha(enc("deren"));

    await sync.reconcile(server.list().map((f) => ({ ...f, revision: 1 })), 1000);

    expect(vault.text("a.pdf")).toBe("deren");
    expect([...vault.files.keys()].some((path) => path.includes("Konflikt"))).toBe(true);
  });

  it("adopts a server file with the same path and content without transferring it", async () => {
    await server.seed("a.pdf", "gleich");
    vault.put("a.pdf", "gleich");

    await pass();

    expect(Object.keys(state.fileIds)).toEqual(["a.pdf"]);
    expect(server.api.downloadFile).not.toHaveBeenCalled();
    expect(server.api.uploadFile).not.toHaveBeenCalled();
  });

  it("keeps a local file with the same path but other content as a copy", async () => {
    await server.seed("a.pdf", "Server");
    vault.put("a.pdf", "lokal");

    await pass();

    expect(vault.text("a.pdf")).toBe("Server");
    expect(vault.text("a (Konflikt 2026-09-23 14-05).pdf")).toBe("lokal");
  });

  it("moves a file renamed elsewhere", async () => {
    const id = await server.seed("a.pdf", "x");
    await pass();
    server.files.get(id)!.path = "Archiv/a.pdf";

    await pass();

    expect(vault.text("Archiv/a.pdf")).toBe("x");
    expect(vault.files.has("a.pdf")).toBe(false);
    expect(state.fileIds).toEqual({ "Archiv/a.pdf": id });
  });

  it("trashes a file deleted elsewhere when it is unchanged here", async () => {
    const id = await server.seed("a.pdf", "x");
    await pass();

    await sync.remoteDeleted(id);

    expect(vault.trashed).toEqual(["a.pdf"]);
    expect(state.fileIds).toEqual({});
  });

  // Wie bei Notizen: eine Loeschung darf keine ungesicherte Arbeit fressen - erkannt am Inhalt.
  it("asks before deleting a file that was changed here", async () => {
    const id = await server.seed("a.pdf", "x");
    await pass();
    vault.put("a.pdf", "hier weitergearbeitet");

    await sync.remoteDeleted(id);

    expect(vault.trashed).toEqual([]);
    expect(decisions).toEqual(["a.pdf"]);
  });

  it("asks the server whether a file missing from the list was deleted", async () => {
    const id = await server.seed("a.pdf", "x");
    await pass();
    server.files.delete(id);
    server.deleted.add(id);

    await pass();

    expect(vault.trashed).toEqual(["a.pdf"]);
  });

  it("uploads a kept file again as a new one", async () => {
    const id = await server.seed("a.pdf", "x");
    await pass();
    vault.put("a.pdf", "behalten");
    server.files.delete(id);

    await sync.keepAfterDeletion("a.pdf");

    const newId = state.fileIds["a.pdf"];
    expect(newId).not.toBe(id);
    expect(dec(server.files.get(newId)!.bytes)).toBe("behalten");
  });

  it("reports files above the server limit instead of uploading them", async () => {
    vault.put("film.mp4", "x".repeat(2000));

    await pass();

    expect(problems["film.mp4"]).toMatch(/größer als/);
    expect(server.api.createFile).not.toHaveBeenCalled();
  });
});

describe("contentTypeFor", () => {
  it("names common types by extension, and nothing else", () => {
    expect(contentTypeFor("Bilder/Foto.JPG")).toBe("image/jpeg");
    expect(contentTypeFor("Skript.pdf")).toBe("application/pdf");
    expect(contentTypeFor("Board.canvas")).toBe("application/json");
    expect(contentTypeFor("ohne-endung")).toBe("application/octet-stream");
    expect(contentTypeFor(".versteckt")).toBe("application/octet-stream");
  });
});

