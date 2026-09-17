import { describe, expect, it, vi } from "vitest";
import * as Y from "yjs";
import { CLOSE_CODE_NOTE_DELETED, SyncClient, type WebSocketLike } from "../src/sync/SyncClient";

const MESSAGE_TYPE_DOC_UPDATE = 0;
const MESSAGE_TYPE_AWARENESS = 1;

function framed(type: number, payload: Uint8Array): ArrayBuffer {
  const buffer = new Uint8Array(1 + payload.length);
  buffer[0] = type;
  buffer.set(payload, 1);
  return buffer.buffer;
}

function messageType(buffer: ArrayBuffer): number {
  return new Uint8Array(buffer)[0];
}

/** Minimaler In-Memory-WebSocket-Fake: zwei Instanzen, ueber ein gemeinsames Array verbunden. */
class FakeWebSocket implements WebSocketLike {
  binaryType = "";
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  readonly sent: ArrayBuffer[] = [];
  peer: FakeWebSocket | null = null;

  send(data: ArrayBuffer): void {
    this.sent.push(data);
    this.peer?.onmessage?.({ data } as MessageEvent);
  }

  close(): void {
    this.onclose?.({ code: 1000, reason: "closed" } as CloseEvent);
  }

  /** Simuliert einen SERVER-seitig ausgeloesten Verbindungsabbruch (z. B. Note geloescht). */
  remoteClose(code: number, reason: string): void {
    this.onclose?.({ code, reason } as CloseEvent);
  }

  static pair(): [FakeWebSocket, FakeWebSocket] {
    const a = new FakeWebSocket();
    const b = new FakeWebSocket();
    a.peer = b;
    b.peer = a;
    return [a, b];
  }
}

describe("SyncClient", () => {
  describe("document updates", () => {
    it("should_applyIncomingDocUpdateMessage_toItsYDoc", () => {
      const socket = new FakeWebSocket();
      const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
      client.connect();

      const sourceDoc = new Y.Doc();
      sourceDoc.getText("content").insert(0, "hello");
      const update = Y.encodeStateAsUpdate(sourceDoc);

      socket.onmessage?.({ data: framed(MESSAGE_TYPE_DOC_UPDATE, update) } as MessageEvent);

      expect(client.doc.getText("content").toString()).toBe("hello");
    });

    it("should_notEchoBack_when_applyingAnIncomingDocUpdate", () => {
      const socket = new FakeWebSocket();
      const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
      client.connect();

      const sourceDoc = new Y.Doc();
      sourceDoc.getText("content").insert(0, "hello");
      const update = Y.encodeStateAsUpdate(sourceDoc);

      socket.onmessage?.({ data: framed(MESSAGE_TYPE_DOC_UPDATE, update) } as MessageEvent);

      expect(socket.sent).toHaveLength(0);
    });

    it("should_sendLocalDocChanges_withDocUpdateTypeByte", () => {
      const socket = new FakeWebSocket();
      const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
      client.connect();

      client.doc.getText("content").insert(0, "local edit");

      expect(socket.sent).toHaveLength(1);
      expect(messageType(socket.sent[0])).toBe(MESSAGE_TYPE_DOC_UPDATE);
    });

    it("should_convergeToSameState_when_twoClientsEditConcurrentlyOverAFakeNetwork", () => {
      const [socketA, socketB] = FakeWebSocket.pair();
      const clientA = new SyncClient("wss://example.invalid/ws/sync", () => socketA);
      const clientB = new SyncClient("wss://example.invalid/ws/sync", () => socketB);
      clientA.connect();
      clientB.connect();

      clientA.doc.getText("content").insert(0, "from-a ");
      clientB.doc.getText("content").insert(0, "from-b ");

      expect(clientA.doc.getText("content").toString()).toBe(clientB.doc.getText("content").toString());
    });
  });

  describe("awareness (cursor presence)", () => {
    it("should_sendAwarenessTypeByte_when_localAwarenessStateChanges", () => {
      const socket = new FakeWebSocket();
      const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
      client.connect();

      client.awareness.setLocalStateField("cursor", { pos: 42 });

      expect(socket.sent).toHaveLength(1);
      expect(messageType(socket.sent[0])).toBe(MESSAGE_TYPE_AWARENESS);
    });

    it("should_propagateCursorPosition_toAConnectedPeer_overAFakeNetwork", () => {
      const [socketA, socketB] = FakeWebSocket.pair();
      const clientA = new SyncClient("wss://example.invalid/ws/sync", () => socketA);
      const clientB = new SyncClient("wss://example.invalid/ws/sync", () => socketB);
      clientA.connect();
      clientB.connect();

      clientA.awareness.setLocalStateField("cursor", { pos: 42 });

      const remoteState = clientB.awareness.getStates().get(clientA.doc.clientID);
      expect(remoteState?.cursor).toEqual({ pos: 42 });
    });

    it("should_notAffectDocContent_when_onlyAwarenessChanges", () => {
      const [socketA, socketB] = FakeWebSocket.pair();
      const clientA = new SyncClient("wss://example.invalid/ws/sync", () => socketA);
      const clientB = new SyncClient("wss://example.invalid/ws/sync", () => socketB);
      clientA.connect();
      clientB.connect();
      clientA.doc.getText("content").insert(0, "hello");

      clientA.awareness.setLocalStateField("cursor", { pos: 3 });

      expect(clientB.doc.getText("content").toString()).toBe("hello");
    });
  });

  describe("remote note deletion", () => {
    it("should_invokeOnNoteDeleted_when_socketClosesWithNoteDeletedCode", () => {
      const socket = new FakeWebSocket();
      const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
      const onNoteDeleted = vi.fn();
      client.onNoteDeleted = onNoteDeleted;
      client.connect();

      socket.remoteClose(CLOSE_CODE_NOTE_DELETED, "note deleted");

      expect(onNoteDeleted).toHaveBeenCalledTimes(1);
    });

    it("should_notInvokeOnNoteDeleted_when_socketClosesWithAnOrdinaryCode", () => {
      const socket = new FakeWebSocket();
      const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
      const onNoteDeleted = vi.fn();
      client.onNoteDeleted = onNoteDeleted;
      client.connect();

      socket.remoteClose(1000, "normal closure");

      expect(onNoteDeleted).not.toHaveBeenCalled();
    });
  });
});
