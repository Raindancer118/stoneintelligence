import * as Y from "yjs";
import type { WebSocketLike } from "../src/sync/SyncClient";

/**
 * Nachbau des Server-Relays mit exakt dem Protokoll von `SyncFrame`/`SyncRelayService`:
 * [Typ][36 Byte NoteId][Payload]; JOIN -> komplette Update-Historie + CATCHUP_COMPLETE;
 * DOC_UPDATE -> anhaengen + an alle ANDEREN Gejointen; LEAVE -> austragen. Der Server
 * interpretiert die Yjs-Bytes nie. So laufen SyncClient, MultiplexedTransport, catchupConnection
 * und noteContentSync in diesem Test unveraendert wie in Obsidian.
 */
export const DOC_UPDATE = 0;
const JOIN = 2;
const LEAVE = 3;
const CATCHUP_COMPLETE = 5;
const encoder = new TextEncoder();
const decoder = new TextDecoder();

export class RelayServer {
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

  doc(noteId: string): Y.Doc {
    const doc = new Y.Doc();
    for (const update of this.updates.get(noteId) ?? []) {
      Y.applyUpdate(doc, update);
    }
    return doc;
  }

  text(noteId: string): string {
    return this.doc(noteId).getText("content").toString();
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

