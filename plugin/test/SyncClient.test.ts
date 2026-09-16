import { describe, expect, it } from "vitest";
import * as Y from "yjs";
import { SyncClient, type WebSocketLike } from "../src/sync/SyncClient";

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

  static pair(): [FakeWebSocket, FakeWebSocket] {
    const a = new FakeWebSocket();
    const b = new FakeWebSocket();
    a.peer = b;
    b.peer = a;
    return [a, b];
  }
}

describe("SyncClient", () => {
  it("should_applyIncomingBinaryMessage_toItsYDoc", () => {
    const socket = new FakeWebSocket();
    const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
    client.connect();

    const sourceDoc = new Y.Doc();
    sourceDoc.getText("content").insert(0, "hello");
    const update = Y.encodeStateAsUpdate(sourceDoc);

    socket.onmessage?.({ data: update.buffer as ArrayBuffer } as MessageEvent);

    expect(client.doc.getText("content").toString()).toBe("hello");
  });

  it("should_notEchoBack_when_applyingAnIncomingUpdate", () => {
    const socket = new FakeWebSocket();
    const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
    client.connect();

    const sourceDoc = new Y.Doc();
    sourceDoc.getText("content").insert(0, "hello");
    const update = Y.encodeStateAsUpdate(sourceDoc);

    socket.onmessage?.({ data: update.buffer as ArrayBuffer } as MessageEvent);

    expect(socket.sent).toHaveLength(0);
  });

  it("should_sendLocalDocChanges_overTheSocket", () => {
    const socket = new FakeWebSocket();
    const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
    client.connect();

    client.doc.getText("content").insert(0, "local edit");

    expect(socket.sent).toHaveLength(1);
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
