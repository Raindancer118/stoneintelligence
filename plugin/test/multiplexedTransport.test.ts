import { describe, expect, it, vi } from "vitest";
import type { WebSocketLike } from "../src/sync/SyncClient";
import { MultiplexedTransport } from "../src/sync/multiplexedTransport";

const NOTE_ID_A = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
const NOTE_ID_B = "11111111-1111-1111-1111-111111111111";

const TYPE_DOC_UPDATE = 0;
const TYPE_JOIN = 2;
const TYPE_LEAVE = 3;
const TYPE_NOTE_DELETED = 4;
const CLOSE_CODE_NOTE_DELETED = 4404;

/**
 * Minimaler In-Memory-WebSocket-Fake, den der Transport wie ein echtes WebSocket behandelt.
 * `isOpen` bildet nach, dass ein ECHTES WebSocket synchron `InvalidStateError` wirft, wenn
 * `send()` waehrend readyState CONNECTING aufgerufen wird - genau das hat live einen Absturz
 * ausgeloest (Awareness-Update genau in diesem Fenster).
 */
class FakeRealSocket implements WebSocketLike {
  binaryType = "";
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  readonly sent: Uint8Array[] = [];
  isOpen = false;

  send(data: ArrayBuffer): void {
    if (!this.isOpen) {
      throw new DOMException("Still in CONNECTING state.", "InvalidStateError");
    }
    this.sent.push(new Uint8Array(data));
  }

  close(): void {
    this.isOpen = false;
    this.onclose?.({ code: 1000, reason: "closed" } as CloseEvent);
  }

  open(): void {
    this.isOpen = true;
    this.onopen?.(new Event("open"));
  }

  deliver(bytes: Uint8Array): void {
    this.onmessage?.({ data: bytes.buffer } as MessageEvent);
  }
}

function frame(type: number, noteId: string, payload: number[] = []): Uint8Array {
  const noteIdBytes = new TextEncoder().encode(noteId);
  const framed = new Uint8Array(1 + noteIdBytes.length + payload.length);
  framed[0] = type;
  framed.set(noteIdBytes, 1);
  framed.set(payload, 1 + noteIdBytes.length);
  return framed;
}

/**
 * Die URL/das Ticket wird jetzt PRO Verbindungsversuch frisch async geholt (Regression-Fix:
 * ein Reconnect mit wiederverwendeter URL scheiterte immer mit 403, weil das eingebettete
 * Single-Use-Ticket schon verbraucht war) - dadurch vergeht zwischen `createVirtualSocket()`
 * und dem tatsaechlichen `createRealSocket()`-Aufruf mindestens ein Mikrotask-Tick. Tests warten
 * deshalb explizit auf `onopen`, bevor sie `.open()` aufrufen.
 */
async function waitUntilConnecting(socket: FakeRealSocket): Promise<void> {
  await vi.waitFor(() => expect(socket.onopen).not.toBeNull());
}

describe("MultiplexedTransport", () => {
  it("should_createExactlyOneRealSocket_forMultipleVirtualSockets", async () => {
    const realSocket = new FakeRealSocket();
    const createRealSocket = vi.fn().mockReturnValue(realSocket);
    const transport = new MultiplexedTransport(async () => "wss://example.invalid", createRealSocket, { sleep: vi.fn() });

    transport.createVirtualSocket(NOTE_ID_A);
    transport.createVirtualSocket(NOTE_ID_B);
    await waitUntilConnecting(realSocket);

    expect(createRealSocket).toHaveBeenCalledTimes(1);
  });

  it("should_sendJoinFrame_andDispatchOpen_forEachVirtualSocket_afterRealSocketOpens", async () => {
    const realSocket = new FakeRealSocket();
    const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => realSocket, { sleep: vi.fn() });
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
    const openA = vi.fn();
    vsA.onopen = openA;
    await waitUntilConnecting(realSocket);

    realSocket.open();
    await vi.waitFor(() => expect(openA).toHaveBeenCalledTimes(1));

    expect(realSocket.sent).toHaveLength(1);
    expect(realSocket.sent[0][0]).toBe(TYPE_JOIN);
  });

  it("should_staggerJoins_withSleepBetweenEach_when_multipleNotesArePending", async () => {
    const realSocket = new FakeRealSocket();
    const sleep = vi.fn().mockResolvedValue(undefined);
    const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => realSocket, { sleep, joinStaggerMs: 150 });
    transport.createVirtualSocket(NOTE_ID_A);
    transport.createVirtualSocket(NOTE_ID_B);
    await waitUntilConnecting(realSocket);

    realSocket.open();
    await vi.waitFor(() => expect(realSocket.sent).toHaveLength(2));

    expect(sleep).toHaveBeenCalledWith(150);
  });

  it("should_joinPrioritizedNote_beforeAlreadyQueuedBackgroundNotes", async () => {
    const realSocket = new FakeRealSocket();
    const sleep = vi.fn().mockResolvedValue(undefined);
    const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => realSocket, { sleep });
    transport.createVirtualSocket(NOTE_ID_B);
    transport.createVirtualSocket(NOTE_ID_A, { priority: true });
    await waitUntilConnecting(realSocket);

    realSocket.open();
    await vi.waitFor(() => expect(realSocket.sent).toHaveLength(2));

    const firstJoinedNoteId = new TextDecoder().decode(realSocket.sent[0].subarray(1, 37));
    expect(firstJoinedNoteId).toBe(NOTE_ID_A);
  });

  it("should_routeIncomingMessage_toTheVirtualSocketMatchingItsNoteId_strippingTheNoteIdPrefix", async () => {
    const realSocket = new FakeRealSocket();
    const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => realSocket, { sleep: vi.fn() });
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
    const vsB = transport.createVirtualSocket(NOTE_ID_B);
    const messagesA = vi.fn();
    const messagesB = vi.fn();
    vsA.onmessage = messagesA;
    vsB.onmessage = messagesB;
    await waitUntilConnecting(realSocket);
    realSocket.open();
    await vi.waitFor(() => expect(realSocket.sent).toHaveLength(2));

    realSocket.deliver(frame(TYPE_DOC_UPDATE, NOTE_ID_A, [42]));

    expect(messagesA).toHaveBeenCalledTimes(1);
    expect(messagesB).not.toHaveBeenCalled();
    const delivered = new Uint8Array(messagesA.mock.calls[0][0].data as ArrayBuffer);
    expect(Array.from(delivered)).toEqual([TYPE_DOC_UPDATE, 42]);
  });

  it("should_prefixOutgoingVirtualSocketSend_withItsNoteId", async () => {
    const realSocket = new FakeRealSocket();
    const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => realSocket, { sleep: vi.fn() });
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
    await waitUntilConnecting(realSocket);
    realSocket.open();
    await vi.waitFor(() => expect(realSocket.sent).toHaveLength(1));

    vsA.send(new Uint8Array([TYPE_DOC_UPDATE, 7, 8]).buffer as ArrayBuffer);

    expect(realSocket.sent).toHaveLength(2);
    const sent = realSocket.sent[1];
    expect(sent[0]).toBe(TYPE_DOC_UPDATE);
    expect(new TextDecoder().decode(sent.subarray(1, 37))).toBe(NOTE_ID_A);
    expect(Array.from(sent.subarray(37))).toEqual([7, 8]);
  });

  it("should_sendLeaveFrame_when_virtualSocketCloses", async () => {
    const realSocket = new FakeRealSocket();
    const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => realSocket, { sleep: vi.fn() });
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
    await waitUntilConnecting(realSocket);
    realSocket.open();
    await vi.waitFor(() => expect(realSocket.sent).toHaveLength(1));

    vsA.close();

    expect(realSocket.sent).toHaveLength(2);
    expect(realSocket.sent[1][0]).toBe(TYPE_LEAVE);
  });

  it("should_dispatchCloseToAllVirtualSockets_when_realSocketCloses", async () => {
    const realSocket = new FakeRealSocket();
    const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => realSocket, { sleep: vi.fn().mockResolvedValue(undefined) });
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
    const vsB = transport.createVirtualSocket(NOTE_ID_B);
    const closeA = vi.fn();
    const closeB = vi.fn();
    vsA.onclose = closeA;
    vsB.onclose = closeB;
    await waitUntilConnecting(realSocket);

    realSocket.close();

    expect(closeA).toHaveBeenCalledTimes(1);
    expect(closeB).toHaveBeenCalledTimes(1);
  });

  it("should_dispatchErrorToAllVirtualSockets_when_realSocketErrors", async () => {
    const realSocket = new FakeRealSocket();
    const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => realSocket, { sleep: vi.fn().mockResolvedValue(undefined) });
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
    const errorA = vi.fn();
    vsA.onerror = errorA;
    await waitUntilConnecting(realSocket);

    realSocket.onerror?.(new Event("error"));

    expect(errorA).toHaveBeenCalledTimes(1);
  });

  it("should_createAFreshRealSocket_afterTheFirstOneCloses", async () => {
    // Regression: ohne diesen Reconnect blieb JEDE Notiz, die NACH dem ersten Verbindungsabbruch
    // registriert wurde, fuer immer auf "connecting" stehen - der Transport hielt an der toten
    // Verbindung fest und hat nie eine neue erstellt (live beobachtet: "verbindet" haengt ewig).
    const firstSocket = new FakeRealSocket();
    const secondSocket = new FakeRealSocket();
    const createRealSocket = vi.fn().mockReturnValueOnce(firstSocket).mockReturnValueOnce(secondSocket);
    const sleep = vi.fn().mockResolvedValue(undefined);
    const transport = new MultiplexedTransport(async () => "wss://example.invalid", createRealSocket, { sleep });
    transport.createVirtualSocket(NOTE_ID_A);
    await waitUntilConnecting(firstSocket);
    firstSocket.open();
    await vi.waitFor(() => expect(firstSocket.sent).toHaveLength(1));

    firstSocket.close();
    transport.createVirtualSocket(NOTE_ID_B);

    await vi.waitFor(() => expect(createRealSocket).toHaveBeenCalledTimes(2));
  });

  it("should_fetchAFreshUrlAndTicket_forEveryConnectionAttempt", async () => {
    // Der eigentliche Regression-Kern: Tickets sind Single-Use - ein Reconnect MUSS eine neue
    // URL/Ticket holen, niemals die urspruengliche wiederverwenden (live beobachtet: derselbe
    // Ticket-Token in Dutzenden Reconnect-Versuchen in Folge, jedes Mal HTTP 403).
    const firstSocket = new FakeRealSocket();
    const secondSocket = new FakeRealSocket();
    const createRealSocket = vi.fn().mockReturnValueOnce(firstSocket).mockReturnValueOnce(secondSocket);
    const getUrl = vi.fn().mockResolvedValueOnce("wss://example.invalid?ticket=first")
      .mockResolvedValueOnce("wss://example.invalid?ticket=second");
    const sleep = vi.fn().mockResolvedValue(undefined);
    const transport = new MultiplexedTransport(getUrl, createRealSocket, { sleep, reconnectDelayMs: 500 });
    transport.createVirtualSocket(NOTE_ID_A);
    await waitUntilConnecting(firstSocket);
    firstSocket.open();
    await vi.waitFor(() => expect(firstSocket.sent).toHaveLength(1));

    firstSocket.close();
    await vi.waitFor(() => expect(sleep).toHaveBeenCalledWith(500));
    await vi.waitFor(() => expect(createRealSocket).toHaveBeenCalledTimes(2));

    expect(getUrl).toHaveBeenCalledTimes(2);
    expect(createRealSocket).toHaveBeenNthCalledWith(2, "wss://example.invalid?ticket=second");
  });

  it("should_rejoinAllRegisteredNotes_afterAutomaticReconnect", async () => {
    const firstSocket = new FakeRealSocket();
    const secondSocket = new FakeRealSocket();
    const createRealSocket = vi.fn().mockReturnValueOnce(firstSocket).mockReturnValueOnce(secondSocket);
    const sleep = vi.fn().mockResolvedValue(undefined);
    const transport = new MultiplexedTransport(
      async () => "wss://example.invalid", createRealSocket, { sleep, reconnectDelayMs: 500 },
    );
    transport.createVirtualSocket(NOTE_ID_A);
    await waitUntilConnecting(firstSocket);
    firstSocket.open();
    await vi.waitFor(() => expect(firstSocket.sent).toHaveLength(1));

    firstSocket.close();
    await vi.waitFor(() => expect(sleep).toHaveBeenCalledWith(500));
    await waitUntilConnecting(secondSocket);
    secondSocket.open();

    await vi.waitFor(() => expect(secondSocket.sent).toHaveLength(1));
    expect(secondSocket.sent[0][0]).toBe(TYPE_JOIN);
  });

  it("should_notReconnect_when_noVirtualSocketsAreRegisteredAnymore", async () => {
    const firstSocket = new FakeRealSocket();
    const createRealSocket = vi.fn().mockReturnValueOnce(firstSocket);
    const sleep = vi.fn().mockResolvedValue(undefined);
    const transport = new MultiplexedTransport(async () => "wss://example.invalid", createRealSocket, { sleep });
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
    await waitUntilConnecting(firstSocket);
    firstSocket.open();
    await vi.waitFor(() => expect(firstSocket.sent).toHaveLength(1));
    vsA.close();

    firstSocket.close();
    await new Promise((resolve) => setTimeout(resolve, 10));

    expect(createRealSocket).toHaveBeenCalledTimes(1);
  });

  it("should_dispatchOnlyThatVirtualSocketsClose_when_noteDeletedFrameArrives", async () => {
    // Regression: die geloeschte Notiz darf NUR ihr eigenes virtuelles Socket schliessen, NICHT
    // die geteilte Verbindung - sonst wuerde das Loeschen einer Notiz den Sync aller anderen,
    // ueber dieselbe Verbindung laufenden Notizen mitreissen.
    const realSocket = new FakeRealSocket();
    const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => realSocket, { sleep: vi.fn() });
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
    const vsB = transport.createVirtualSocket(NOTE_ID_B);
    const closeA = vi.fn();
    const closeB = vi.fn();
    const messagesB = vi.fn();
    vsA.onclose = closeA;
    vsB.onclose = closeB;
    vsB.onmessage = messagesB;
    await waitUntilConnecting(realSocket);
    realSocket.open();
    await vi.waitFor(() => expect(realSocket.sent).toHaveLength(2));

    realSocket.deliver(frame(TYPE_NOTE_DELETED, NOTE_ID_A));

    expect(closeA).toHaveBeenCalledWith(expect.objectContaining({ code: CLOSE_CODE_NOTE_DELETED }));
    expect(closeB).not.toHaveBeenCalled();

    // Notiz B funktioniert auf derselben Verbindung weiterhin ganz normal.
    realSocket.deliver(frame(TYPE_DOC_UPDATE, NOTE_ID_B, [1, 2, 3]));
    expect(messagesB).toHaveBeenCalledTimes(1);
  });

  it("should_dispatchErrorAndScheduleReconnect_when_theUrlProviderRejects", async () => {
    // Regression: schlug das Holen einer frischen URL/Ticket fehl (z. B. weil der Token-Refresh
    // gerade mit HTTP 400 scheitert, s. main.ts getAccessToken), blieb die virtuelle Verbindung
    // fuer immer auf "connecting" stehen - es wurde nie ein echtes Socket erzeugt, also feuerten
    // auch nie dessen onerror/onclose-Handler, die sonst einen Reconnect anstossen wuerden.
    const realSocket = new FakeRealSocket();
    const getUrl = vi.fn()
      .mockRejectedValueOnce(new Error("token refresh failed"))
      .mockResolvedValueOnce("wss://example.invalid");
    const sleep = vi.fn().mockResolvedValue(undefined);
    const transport = new MultiplexedTransport(getUrl, () => realSocket, { sleep, reconnectDelayMs: 500 });
    const errorA = vi.fn();
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
    vsA.onerror = errorA;

    await vi.waitFor(() => expect(errorA).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => expect(sleep).toHaveBeenCalledWith(500));
    await waitUntilConnecting(realSocket);

    expect(getUrl).toHaveBeenCalledTimes(2);
  });

  it("should_silentlyDropTheSend_instead_ofThrowing_when_socketIsStillConnecting", async () => {
    // Regression: ein Awareness-Update (z. B. Mausbewegung) das genau waehrend eines (Re-)
    // Connects feuert, liess `sendFramed` bisher `InvalidStateError` synchron werfen (echte
    // WebSockets werfen das, wenn `send()` waehrend readyState CONNECTING aufgerufen wird) - das
    // schlug live als unbehandelte Exception mitten im Yjs-Awareness-Event-Dispatch durch.
    const realSocket = new FakeRealSocket();
    const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => realSocket, { sleep: vi.fn() });
    transport.createVirtualSocket(NOTE_ID_A);
    await waitUntilConnecting(realSocket);
    // realSocket.open() bewusst NICHT aufgerufen - Socket bleibt im Fake auf isOpen=false,
    // simuliert readyState CONNECTING.

    expect(() => transport.sendFramed(TYPE_DOC_UPDATE, NOTE_ID_A, new Uint8Array([1]))).not.toThrow();
    expect(realSocket.sent).toHaveLength(0);
  });

  describe("destroy", () => {
    // Regression (comparison review P1 "Logout/terminal auth failure leaves the authorized
    // socket alive", docs/sync-comparison-review-2026-09-18.md): logout() only cleared stored
    // tokens, the already ticket-authenticated physical socket and its infinite 2s reconnect loop
    // kept running regardless.

    it("should_closeTheRealSocket_when_destroyed", async () => {
      const realSocket = new FakeRealSocket();
      const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => realSocket, { sleep: vi.fn() });
      transport.createVirtualSocket(NOTE_ID_A);
      await waitUntilConnecting(realSocket);
      realSocket.open();

      transport.destroy();

      expect(realSocket.isOpen).toBe(false);
    });

    it("should_stopReconnecting_when_destroyed", async () => {
      const realSocket = new FakeRealSocket();
      const createRealSocket = vi.fn().mockReturnValue(realSocket);
      const sleep = vi.fn().mockResolvedValue(undefined);
      const transport = new MultiplexedTransport(async () => "wss://example.invalid", createRealSocket, { sleep });
      transport.createVirtualSocket(NOTE_ID_A);
      await waitUntilConnecting(realSocket);
      realSocket.open();
      createRealSocket.mockClear();

      transport.destroy();
      // Even a stray close event arriving right after destroy() must not schedule a reconnect.
      realSocket.onclose?.({ code: 1006, reason: "" } as CloseEvent);
      await Promise.resolve();
      await Promise.resolve();

      expect(createRealSocket).not.toHaveBeenCalled();
    });

    it("should_notCreateARealSocket_when_aVirtualSocketIsRequestedAfterDestroy", async () => {
      const realSocket = new FakeRealSocket();
      const createRealSocket = vi.fn().mockReturnValue(realSocket);
      const transport = new MultiplexedTransport(async () => "wss://example.invalid", createRealSocket, { sleep: vi.fn() });
      transport.createVirtualSocket(NOTE_ID_A);
      await waitUntilConnecting(realSocket);
      realSocket.open();
      transport.destroy();
      createRealSocket.mockClear();

      transport.createVirtualSocket(NOTE_ID_B);
      await Promise.resolve();
      await Promise.resolve();

      expect(createRealSocket).not.toHaveBeenCalled();
    });
  });
});
