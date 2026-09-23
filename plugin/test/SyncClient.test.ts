import { describe, expect, it, vi } from "vitest";
import * as Y from "yjs";
import { CLOSE_CODE_NOTE_DELETED, SyncClient, type WebSocketLike } from "../src/sync/SyncClient";

const MESSAGE_TYPE_DOC_UPDATE = 0;
const MESSAGE_TYPE_AWARENESS = 1;
const MESSAGE_TYPE_CATCHUP_COMPLETE = 5;

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

    it("should_announceItsDeparture_toPeers_when_disconnecting", () => {
      // Ohne explizite Abmeldung VOR dem Socket-Schluss bliebe der eigene Cursor beim Gegenueber
      // als "Geist" stehen, bis das 30s-Awareness-Timeout greift - live sichtbar als Cursor einer
      // Person, die die Notiz laengst verlassen hat (gleiches Muster wie im Vorgaengerprojekt
      // `stonesync`, DocumentSession: "Announce our departure BEFORE the socket goes away").
      const [socketA, socketB] = FakeWebSocket.pair();
      const clientA = new SyncClient("wss://example.invalid/ws/sync", () => socketA);
      const clientB = new SyncClient("wss://example.invalid/ws/sync", () => socketB);
      clientA.connect();
      clientB.connect();
      clientA.awareness.setLocalStateField("user", { name: "Alice", color: "#e57373" });
      expect(clientB.awareness.getStates().has(clientA.doc.clientID)).toBe(true);

      clientA.disconnect();

      expect(clientB.awareness.getStates().has(clientA.doc.clientID)).toBe(false);
    });

    it("should_keepItsOwnIdentity_after_disconnect_soAReconnectRepublishesIt", () => {
      // Die Abmeldung darf die eigene Identitaet nicht dauerhaft loeschen: der Transport
      // verbindet automatisch neu, und `resendLocalAwarenessAsRepair` sendet nach dem Catchup
      // genau diesen lokalen Zustand erneut - waere er null, waere die Person danach unsichtbar.
      const socket = new FakeWebSocket();
      const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
      client.connect();
      client.awareness.setLocalStateField("user", { name: "Alice", color: "#e57373" });

      client.disconnect();

      expect(client.awareness.getLocalState()?.user).toEqual({ name: "Alice", color: "#e57373" });
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

    it("should_removeRemoteAwarenessStates_when_thePhysicalSocketClosesUnexpectedly", () => {
      // Regression (comparison review P1 "Awareness has stale-cursor and late-join gaps"):
      // without this, a peer whose connection drops leaves a ghost cursor behind forever, since
      // nothing tells this client's awareness that the remote state is now stale.
      const [socketA, socketB] = FakeWebSocket.pair();
      const clientA = new SyncClient("wss://example.invalid/ws/sync", () => socketA);
      const clientB = new SyncClient("wss://example.invalid/ws/sync", () => socketB);
      clientA.connect();
      clientB.connect();
      clientA.awareness.setLocalStateField("cursor", { pos: 42 });
      expect(clientB.awareness.getStates().has(clientA.doc.clientID)).toBe(true);

      socketB.remoteClose(1001, "connection lost");

      expect(clientB.awareness.getStates().has(clientA.doc.clientID)).toBe(false);
    });

    it("should_notClearRemoteAwareness_when_theSocketClosesBecauseTheNoteWasDeleted", () => {
      // TYPE_NOTE_DELETED is handled by onNoteDeleted entirely - the caller (main.ts
      // handleRemoteNoteDeleted) tears the whole session down via stopSync()/disconnect(), which
      // already clears awareness there. Clearing it a second time here would be redundant, not
      // wrong - but this test must actually prove that path is untouched, not just that nothing
      // throws (flagged as vacuous by an independent second-pass review).
      const [socketA, socketB] = FakeWebSocket.pair();
      const clientA = new SyncClient("wss://example.invalid/ws/sync", () => socketA);
      const clientB = new SyncClient("wss://example.invalid/ws/sync", () => socketB);
      const onNoteDeleted = vi.fn();
      clientB.onNoteDeleted = onNoteDeleted;
      clientA.connect();
      clientB.connect();
      clientA.awareness.setLocalStateField("cursor", { pos: 42 });
      expect(clientB.awareness.getStates().has(clientA.doc.clientID)).toBe(true);

      socketB.remoteClose(CLOSE_CODE_NOTE_DELETED, "note deleted");

      expect(onNoteDeleted).toHaveBeenCalledTimes(1);
      expect(clientB.awareness.getStates().has(clientA.doc.clientID)).toBe(true);
    });

    it("should_republishLocalAwarenessState_when_catchupCompletesAfterAReconnect", () => {
      // Regression: JOIN itself generates no awareness event, so after a reconnect a client's own
      // presence could remain invisible to everyone else until its cursor happened to move again.
      const socketA = new FakeWebSocket();
      const socketB = new FakeWebSocket();
      const sockets = [socketA, socketB];
      const client = new SyncClient("wss://example.invalid/ws/sync", () => sockets.shift() as FakeWebSocket);
      client.connect();
      socketA.onopen?.({} as Event);
      client.awareness.setLocalStateField("cursor", { pos: 7 });
      socketA.sent.length = 0;

      client.disconnect();
      client.connect();
      socketB.onopen?.({} as Event);
      socketB.onmessage?.({ data: framed(MESSAGE_TYPE_CATCHUP_COMPLETE, new Uint8Array(0)) } as MessageEvent);

      const awarenessMessages = socketB.sent.filter((raw) => messageType(raw) === MESSAGE_TYPE_AWARENESS);
      expect(awarenessMessages.length).toBeGreaterThan(0);
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

  describe("catchup-complete repair resync (offline-edit data loss)", () => {
    it("should_resendTheFullLocalDocState_when_catchupCompletes_soEditsMadeWhileDisconnectedAreNotLost", () => {
      // Regression for the P0 "offline edits are silently lost and never reconciled" finding
      // (docs/sync-comparison-review-2026-09-18.md): the doc.on("update") listener only sends
      // while a socket exists - an edit made between disconnect() and the next successful
      // connect() was dropped forever, because Yjs never re-fires "update" for old changes.
      const socketA = new FakeWebSocket();
      const socketB = new FakeWebSocket();
      const sockets = [socketA, socketB];
      const client = new SyncClient("wss://example.invalid/ws/sync", () => sockets.shift() as FakeWebSocket);
      client.connect();
      socketA.onopen?.({} as Event);

      client.disconnect();
      client.doc.getText("content").insert(0, "written while offline");

      // Reconnect (mirrors what MultiplexedTransport does after a dropped physical socket: a
      // fresh connect() call, fresh socket, fresh JOIN).
      client.connect();
      socketB.onopen?.({} as Event);
      socketB.onmessage?.({ data: framed(MESSAGE_TYPE_CATCHUP_COMPLETE, new Uint8Array(0)) } as MessageEvent);

      expect(socketB.sent.length).toBeGreaterThan(0);
      const repairDoc = new Y.Doc();
      for (const raw of socketB.sent) {
        if (messageType(raw) !== MESSAGE_TYPE_DOC_UPDATE) {
          continue;
        }
        Y.applyUpdate(repairDoc, new Uint8Array(raw).subarray(1));
      }
      expect(repairDoc.getText("content").toString()).toBe("written while offline");
    });
  });

  describe("repair resend sendet nur, was dem Server fehlt", () => {
    function docUpdatesSent(socket: FakeWebSocket): Uint8Array[] {
      return socket.sent.filter((raw) => messageType(raw) === MESSAGE_TYPE_DOC_UPDATE)
        .map((raw) => new Uint8Array(raw).subarray(1));
    }

    function serverDocWith(text: string): Y.Doc {
      const doc = new Y.Doc();
      doc.getText("content").insert(0, text);
      return doc;
    }

    // Jeder Resend wird serverseitig als neues Update angehaengt und erhoeht die Revision. Ein
    // Resend ohne neuen Inhalt liess zwei Geraete sich bei jedem Abgleich gegenseitig anstossen.
    it("should_sendNothing_when_theServerAlreadyHasEverything", () => {
      const server = serverDocWith("gleich");
      const socket = new FakeWebSocket();
      const client = new SyncClient("", () => socket);
      Y.applyUpdate(client.doc, Y.encodeStateAsUpdate(server));
      client.awareness.setLocalState(null);
      client.connect();
      socket.onopen?.({} as Event);

      socket.onmessage?.({ data: framed(MESSAGE_TYPE_DOC_UPDATE, Y.encodeStateAsUpdate(server)) } as MessageEvent);
      socket.onmessage?.({ data: framed(MESSAGE_TYPE_CATCHUP_COMPLETE, new Uint8Array(0)) } as MessageEvent);

      expect(docUpdatesSent(socket)).toHaveLength(0);
    });

    it("should_sendOnlyTheMissingLocalChange_when_editedOffline", () => {
      const server = serverDocWith("Basis");
      const socket = new FakeWebSocket();
      const client = new SyncClient("", () => socket);
      Y.applyUpdate(client.doc, Y.encodeStateAsUpdate(server));
      client.doc.getText("content").insert(5, " + offline");
      client.connect();
      socket.onopen?.({} as Event);

      socket.onmessage?.({ data: framed(MESSAGE_TYPE_DOC_UPDATE, Y.encodeStateAsUpdate(server)) } as MessageEvent);
      socket.onmessage?.({ data: framed(MESSAGE_TYPE_CATCHUP_COMPLETE, new Uint8Array(0)) } as MessageEvent);

      const sent = docUpdatesSent(socket);
      expect(sent).toHaveLength(1);
      sent.forEach((update) => Y.applyUpdate(server, update));
      expect(server.getText("content").toString()).toBe("Basis + offline");
    });

    it("should_resendAnOfflineDeletion_eventhough_itCreatesNoNewStructs", () => {
      const server = serverDocWith("abc def");
      const socket = new FakeWebSocket();
      const client = new SyncClient("", () => socket);
      Y.applyUpdate(client.doc, Y.encodeStateAsUpdate(server));
      client.doc.getText("content").delete(3, 4);
      client.connect();
      socket.onopen?.({} as Event);

      socket.onmessage?.({ data: framed(MESSAGE_TYPE_DOC_UPDATE, Y.encodeStateAsUpdate(server)) } as MessageEvent);
      socket.onmessage?.({ data: framed(MESSAGE_TYPE_CATCHUP_COMPLETE, new Uint8Array(0)) } as MessageEvent);

      docUpdatesSent(socket).forEach((update) => Y.applyUpdate(server, update));
      expect(server.getText("content").toString()).toBe("abc");
    });
  });

  describe("status tracking", () => {
    it("should_startAsConnecting_then_becomeConnected_when_socketOpens", () => {
      const socket = new FakeWebSocket();
      const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);

      client.connect();
      expect(client.status).toBe("connecting");

      socket.onopen?.({} as Event);
      expect(client.status).toBe("connected");
    });

    it("should_notifyOnStatusChange_when_statusTransitions", () => {
      const socket = new FakeWebSocket();
      const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
      const onStatusChange = vi.fn();
      client.onStatusChange = onStatusChange;

      client.connect();
      socket.onopen?.({} as Event);

      expect(onStatusChange).toHaveBeenCalledWith("connecting");
      expect(onStatusChange).toHaveBeenCalledWith("connected");
    });

    it("should_becomeError_when_socketReportsAnError", () => {
      const socket = new FakeWebSocket();
      const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
      client.connect();

      socket.onerror?.({} as Event);

      expect(client.status).toBe("error");
    });

    it("should_becomeDisconnected_when_socketClosesNormally", () => {
      const socket = new FakeWebSocket();
      const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
      client.connect();
      socket.onopen?.({} as Event);

      socket.remoteClose(1000, "normal closure");

      expect(client.status).toBe("disconnected");
    });

    it("should_becomeError_when_socketClosesAbnormally", () => {
      const socket = new FakeWebSocket();
      const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
      client.connect();
      socket.onopen?.({} as Event);

      socket.remoteClose(1006, "abnormal closure");

      expect(client.status).toBe("error");
    });

    it("should_becomeDisconnected_when_disconnectIsCalled", () => {
      const socket = new FakeWebSocket();
      const client = new SyncClient("wss://example.invalid/ws/sync", () => socket);
      client.connect();
      socket.onopen?.({} as Event);

      client.disconnect();

      expect(client.status).toBe("disconnected");
    });
  });
});
