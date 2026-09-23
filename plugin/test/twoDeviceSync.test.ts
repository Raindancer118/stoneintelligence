import { describe, expect, it } from "vitest";
import * as Y from "yjs";
import { connectForCatchup } from "../src/sync/catchupConnection";
import { MultiplexedTransport } from "../src/sync/multiplexedTransport";
import { type ContentSyncPorts, syncNoteContent } from "../src/sync/noteContentSync";
import type { WebSocketLike } from "../src/sync/SyncClient";

/**
 * Nachbau des Server-Relays mit exakt dem Protokoll von `SyncFrame`/`SyncRelayService`:
 * [Typ][36 Byte NoteId][Payload]; JOIN -> komplette Update-Historie + CATCHUP_COMPLETE;
 * DOC_UPDATE -> anhaengen + an alle ANDEREN Gejointen; LEAVE -> austragen. Der Server
 * interpretiert die Yjs-Bytes nie. So laufen SyncClient, MultiplexedTransport, catchupConnection
 * und noteContentSync in diesem Test unveraendert wie in Obsidian.
 */
const DOC_UPDATE = 0;
const JOIN = 2;
const LEAVE = 3;
const CATCHUP_COMPLETE = 5;
const encoder = new TextEncoder();
const decoder = new TextDecoder();

class RelayServer {
  readonly updates = new Map<string, Uint8Array[]>();
  private readonly joined = new Map<string, Set<ServerSideSocket>>();
  private readonly sockets = new Set<ServerSideSocket>();
  online = true;

  /** Netz weg: bestehende Verbindungen brechen ab, neue kommen nicht zustande. */
  goOffline(): void {
    this.online = false;
    for (const socket of this.sockets) {
      socket.isOpen = false;
      this.drop(socket);
      socket.onclose?.({ code: 1006, reason: "network lost" } as CloseEvent);
    }
    this.sockets.clear();
  }

  connect(): ServerSideSocket {
    const socket = new ServerSideSocket(this);
    this.sockets.add(socket);
    queueMicrotask(() => {
      if (this.online) {
        socket.isOpen = true;
        socket.onopen?.(new Event("open"));
      } else {
        socket.onclose?.({ code: 1006, reason: "unreachable" } as CloseEvent);
      }
    });
    return socket;
  }

  receive(from: ServerSideSocket, frame: Uint8Array): void {
    const type = frame[0];
    const noteId = decoder.decode(frame.subarray(1, 37));
    const payload = frame.slice(37);
    if (type === JOIN) {
      const members = this.joined.get(noteId) ?? new Set();
      members.add(from);
      this.joined.set(noteId, members);
      for (const update of this.updates.get(noteId) ?? []) {
        from.deliver(DOC_UPDATE, noteId, update);
      }
      from.deliver(CATCHUP_COMPLETE, noteId, new Uint8Array(0));
    } else if (type === LEAVE) {
      this.joined.get(noteId)?.delete(from);
    } else if (type === DOC_UPDATE) {
      const log = this.updates.get(noteId) ?? [];
      log.push(payload);
      this.updates.set(noteId, log);
      for (const member of this.joined.get(noteId) ?? []) {
        if (member !== from) {
          member.deliver(DOC_UPDATE, noteId, payload);
        }
      }
    }
  }

  drop(socket: ServerSideSocket): void {
    for (const members of this.joined.values()) {
      members.delete(socket);
    }
  }

  text(noteId: string): string {
    const doc = new Y.Doc();
    for (const update of this.updates.get(noteId) ?? []) {
      Y.applyUpdate(doc, update);
    }
    return doc.getText("content").toString();
  }
}

class ServerSideSocket implements WebSocketLike {
  binaryType = "arraybuffer";
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  isOpen = false;

  constructor(private readonly server: RelayServer) {}

  send(data: ArrayBuffer): void {
    if (!this.isOpen) {
      throw new DOMException("not open", "InvalidStateError");
    }
    const frame = new Uint8Array(data.slice(0));
    // Netzwerk ist asynchron - Zustellung erst im naechsten Tick.
    setTimeout(() => this.server.receive(this, frame), 0);
  }

  close(): void {
    this.isOpen = false;
    this.server.drop(this);
    this.onclose?.({ code: 1000, reason: "closed" } as CloseEvent);
  }

  deliver(type: number, noteId: string, payload: Uint8Array): void {
    const frame = new Uint8Array(1 + 36 + payload.length);
    frame[0] = type;
    frame.set(encoder.encode(noteId), 1);
    frame.set(payload, 37);
    setTimeout(() => {
      if (this.isOpen) {
        this.onmessage?.({ data: frame.buffer } as MessageEvent);
      }
    }, 0);
  }
}

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
