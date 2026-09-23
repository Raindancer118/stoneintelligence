import { describe, expect, it } from "vitest";
import * as Y from "yjs";
import { type BinaryAdapter, NoteStateStore } from "../src/sync/NoteStateStore";

class MemoryAdapter implements BinaryAdapter {
  readonly files = new Map<string, Uint8Array>();
  readonly dirs = new Set<string>();

  async exists(path: string): Promise<boolean> {
    return this.files.has(path) || this.dirs.has(path);
  }

  async readBinary(path: string): Promise<ArrayBuffer> {
    const data = this.files.get(path);
    if (!data) {
      throw new Error(`ENOENT ${path}`);
    }
    return data.slice().buffer;
  }

  async writeBinary(path: string, data: ArrayBuffer): Promise<void> {
    const parent = path.split("/").slice(0, -1).join("/");
    if (!this.dirs.has(parent)) {
      throw new Error(`parent missing: ${parent}`);
    }
    this.files.set(path, new Uint8Array(data));
  }

  async remove(path: string): Promise<void> {
    this.files.delete(path);
  }

  async mkdir(path: string): Promise<void> {
    const parts = path.split("/");
    for (let i = 1; i <= parts.length; i++) {
      this.dirs.add(parts.slice(0, i).join("/"));
    }
  }
}

function stateWith(text: string): Uint8Array {
  const doc = new Y.Doc();
  doc.getText("content").insert(0, text);
  return Y.encodeStateAsUpdate(doc);
}

describe("NoteStateStore", () => {
  it("should_roundTripAStoredState_perVaultAndNote", async () => {
    const store = new NoteStateStore(new MemoryAdapter(), ".obsidian/plugins/si/sync-state");

    await store.save("vault-1", "note-1", stateWith("hallo"));
    const loaded = await store.load("vault-1", "note-1");

    const doc = new Y.Doc();
    Y.applyUpdate(doc, loaded as Uint8Array);
    expect(doc.getText("content").toString()).toBe("hallo");
    expect(await store.load("vault-2", "note-1")).toBeNull();
  });

  it("should_returnNull_forUnknownNotes", async () => {
    const store = new NoteStateStore(new MemoryAdapter(), "state");

    expect(await store.load("v", "unbekannt")).toBeNull();
  });

  // Ein beim Absturz halb geschriebener Zustand darf nicht als "gemeinsame Basis" gelten - lieber
  // Erstkontakt-Regeln (im Zweifel Konfliktkopie) als ein kaputter Merge.
  it("should_treatACorruptState_asMissing", async () => {
    const adapter = new MemoryAdapter();
    const store = new NoteStateStore(adapter, "state");
    await store.save("v", "n", stateWith("x"));
    adapter.files.set("state/v/n.yjs", new Uint8Array([255, 1, 2, 3, 4]));

    expect(await store.load("v", "n")).toBeNull();
  });

  it("should_forgetAState_onRemove", async () => {
    const store = new NoteStateStore(new MemoryAdapter(), "state");
    await store.save("v", "n", stateWith("x"));

    await store.remove("v", "n");

    expect(await store.load("v", "n")).toBeNull();
  });
});
