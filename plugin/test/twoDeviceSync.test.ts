import { describe, expect, it } from "vitest";
import * as Y from "yjs";
import { connectForCatchup } from "../src/sync/catchupConnection";
import { MultiplexedTransport } from "../src/sync/multiplexedTransport";
import { type ContentSyncPorts, syncNoteContent } from "../src/sync/noteContentSync";
import { RelayServer } from "./fakeRelay";

/** Ein Geraet: eigene Dateien, eigener lokaler Yjs-Zustand, eigene geteilte Verbindung. */
class Device implements ContentSyncPorts {
  readonly files = new Map<string, string>();
  readonly states = new Map<string, Uint8Array>();
  readonly transport: MultiplexedTransport;

  constructor(server: RelayServer) {
    this.transport = new MultiplexedTransport(async () => "wss://relay.test", () => server.connect(), {
      joinStaggerMs: 0, reconnectDelayMs: 5, maxReconnectDelayMs: 20,
    });
    this.transport.start();
  }

  loadState = async (noteId: string) => this.states.get(noteId) ?? null;
  saveState = async (noteId: string, state: Uint8Array) => void this.states.set(noteId, state);
  readFile = async (path: string) => this.files.get(path) ?? "";
  writeFile = async (path: string, content: string) => void this.files.set(path, content);
  writeConflictCopy = async (path: string, content: string) => {
    const copy = path.replace(/\.md$/, " (Konflikt).md");
    this.files.set(copy, content);
    return copy;
  };
  connect = (noteId: string, doc: Y.Doc) => connectForCatchup(this.transport, noteId, doc, 500);

  sync(noteId: string, path: string) {
    return syncNoteContent(this, noteId, path);
  }
}

const NOTE = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 20));
}

describe("Zwei Geraete ueber die geteilte Verbindung (Relay-Nachbau)", () => {
  it("should_carryANewNote_fromOneDeviceToTheOther", async () => {
    const server = new RelayServer();
    const laptop = new Device(server);
    const phone = new Device(server);
    laptop.files.set("a.md", "# Einkauf\n- Milch\n");

    expect((await laptop.sync(NOTE, "a.md")).outcome).toBe("pushed");
    await settle();
    phone.files.set("a.md", "");
    expect((await phone.sync(NOTE, "a.md")).outcome).toBe("pulled");

    expect(phone.files.get("a.md")).toBe("# Einkauf\n- Milch\n");
  });

  it("should_mergeEditsMadeOnBothDevices_whileOneWasOffline", async () => {
    const server = new RelayServer();
    const laptop = new Device(server);
    const phone = new Device(server);
    laptop.files.set("a.md", "# Einkauf\n- Milch\n");
    await laptop.sync(NOTE, "a.md");
    await settle();
    phone.files.set("a.md", "");
    await phone.sync(NOTE, "a.md");

    phone.files.set("a.md", "# Einkauf\n- Milch\n- Brot\n");
    laptop.files.set("a.md", "# Einkauf fuer Samstag\n- Milch\n");
    server.goOffline();
    expect((await phone.sync(NOTE, "a.md")).outcome).toBe("offline");
    server.online = true;
    phone.transport.reconnectNow();
    laptop.transport.reconnectNow();

    await laptop.sync(NOTE, "a.md");
    await settle();
    await phone.sync(NOTE, "a.md");
    await settle();
    await laptop.sync(NOTE, "a.md");

    const expected = "# Einkauf fuer Samstag\n- Milch\n- Brot\n";
    expect(phone.files.get("a.md")).toBe(expected);
    expect(laptop.files.get("a.md")).toBe(expected);
    expect(server.text(NOTE)).toBe(expected);
  });

  it("should_neverDuplicateContent_acrossRepeatedSyncs", async () => {
    const server = new RelayServer();
    const laptop = new Device(server);
    const phone = new Device(server);
    laptop.files.set("a.md", "Einmal.");
    phone.files.set("a.md", "");

    for (let round = 0; round < 5; round++) {
      await laptop.sync(NOTE, "a.md");
      await settle();
      await phone.sync(NOTE, "a.md");
      await settle();
    }

    expect(server.text(NOTE)).toBe("Einmal.");
    expect(phone.files.get("a.md")).toBe("Einmal.");
  });
});
