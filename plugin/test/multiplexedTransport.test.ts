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
  closeCallCount = 0;

  send(data: ArrayBuffer): void {
    if (!this.isOpen) {
      throw new DOMException("Still in CONNECTING state.", "InvalidStateError");
    }
    this.sent.push(new Uint8Array(data));
  }

  close(): void {
    this.closeCallCount += 1;
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

/** Ein vault-weites Bestandsereignis: Payload ist der Notizpfad als UTF-8. */
function vaultFrame(type: number, noteId: string, path: string): Uint8Array {
  return frame(type, noteId, [...new TextEncoder().encode(path)]);
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

    it("should_closeAndDiscardTheSocket_when_destroyHappensWhileTheTicketFetchIsStillPending", async () => {
      // Regression (Codex-Verifikationsreview, s. docs/sync-comparison-review-2026-09-18.md
      // Nachfolge-Report /tmp/codex-research/theoretical-optimum-report.md, Fund #7):
      // ensureRealSocketConnecting prueft `destroyed` nur VOR dem `await this.getUrl()` - laeuft
      // destroy() waehrend dieses Awaits, war der Ticket-Abruf schon "durch" die fruehe Pruefung
      // und erzeugte danach ungeprueft trotzdem ein echtes Socket. Ein Logout waehrend eines noch
      // ausstehenden Ticket-Requests hinterliess so genau die Verbindung, die destroy() eigentlich
      // beenden sollte.
      const realSocket = new FakeRealSocket();
      const createRealSocket = vi.fn().mockReturnValue(realSocket);
      let resolveUrl: (url: string) => void = () => {};
      const pendingUrl = new Promise<string>((resolve) => {
        resolveUrl = resolve;
      });
      const transport = new MultiplexedTransport(() => pendingUrl, createRealSocket, { sleep: vi.fn() });

      transport.createVirtualSocket(NOTE_ID_A);
      await Promise.resolve();
      transport.destroy();
      resolveUrl("wss://example.invalid");
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();

      // The pending ticket fetch must not be allowed to construct a real socket after destroy()
      // already ran - creating one and immediately closing it would also be acceptable, but the
      // implementation simply never constructs it once destroyed is observed post-await.
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

  describe("vault-weite Bestandsereignisse", () => {
    it("meldet Anlage/Loeschung/Umbenennung auch fuer Notizen, die gar nicht gejoint sind", async () => {
      // Genau der Zweck dieser Nachrichten: seit nur noch geoeffnete Notizen gejoint werden, gibt
      // es fuer die betroffene Notiz typischerweise KEINEN virtuellen Socket - die Zustellung darf
      // deshalb nicht an der Socket-Zuordnung haengen.
      const events: Array<{ type: number; noteId: string; path: string }> = [];
      const socket = new FakeRealSocket();
      const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => socket, {
        sleep: vi.fn(),
        onVaultEvent: (type, noteId, path) => events.push({ type, noteId, path }),
      });
      transport.createVirtualSocket(NOTE_ID_B);
      await waitUntilConnecting(socket);
      socket.open();

      const fremdeNoteId = "22222222-2222-2222-2222-222222222222";
      socket.deliver(vaultFrame(6, fremdeNoteId, "Ordner/Neu.md"));
      socket.deliver(vaultFrame(8, fremdeNoteId, "Ordner/Umbenannt.md"));
      socket.deliver(vaultFrame(7, fremdeNoteId, "Ordner/Umbenannt.md"));

      expect(events).toEqual([
        { type: 6, noteId: fremdeNoteId, path: "Ordner/Neu.md" },
        { type: 8, noteId: fremdeNoteId, path: "Ordner/Umbenannt.md" },
        { type: 7, noteId: fremdeNoteId, path: "Ordner/Umbenannt.md" },
      ]);
    });

    it("reicht ein Bestandsereignis NICHT als Dokument-Update an den virtuellen Socket durch", async () => {
      // Sonst landete der Pfad-Text als vermeintliches Yjs-Update im CRDT der offenen Notiz.
      const socket = new FakeRealSocket();
      const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => socket, {
        sleep: vi.fn(),
        onVaultEvent: () => undefined,
      });
      const vs = transport.createVirtualSocket(NOTE_ID_A);
      const received: ArrayBuffer[] = [];
      vs.onmessage = (ev) => received.push(ev.data as ArrayBuffer);
      await waitUntilConnecting(socket);
      socket.open();

      socket.deliver(vaultFrame(7, NOTE_ID_A, "Egal.md"));

      expect(received).toHaveLength(0);
    });
  });

  // SyncClient setzt `onopen` erst NACHDEM die Factory den virtuellen Socket zurueckgegeben hat.
  // Bei bereits offener Verbindung wurde das Open-Ereignis frueher synchron in der Factory
  // ausgeloest - ins Leere. Jede spaeter gejointe Notiz hing dann bis zum Timeout auf "verbindet".
  it("should_deliverOpen_toHandlersAttachedAfterCreation_when_theConnectionIsAlreadyOpen", async () => {
    const realSocket = new FakeRealSocket();
    const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => realSocket, { sleep: vi.fn() });
    transport.start();
    await waitUntilConnecting(realSocket);
    realSocket.open();

    const vs = transport.createVirtualSocket(NOTE_ID_A);
    const onopen = vi.fn();
    vs.onopen = onopen;

    await vi.waitFor(() => expect(onopen).toHaveBeenCalledTimes(1));
    expect(realSocket.sent[0][0]).toBe(TYPE_JOIN);
  });

  describe("Inhalts-Ankuendigungen", () => {
    it("should_subscribeToContentAnnouncements_afterEveryConnect", async () => {
      const first = new FakeRealSocket();
      const second = new FakeRealSocket();
      const sockets = [first, second];
      const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => sockets.shift() as FakeRealSocket, {
        sleep: vi.fn().mockResolvedValue(undefined), subscribeContentUpdates: true,
      });
      transport.start();
      await waitUntilConnecting(first);
      first.open();
      first.close();
      await waitUntilConnecting(second);
      second.open();

      for (const socket of [first, second]) {
        expect(socket.sent.some((frameBytes) => frameBytes[0] === 10)).toBe(true);
      }
    });

    it("should_reportContentChanges_asVaultEvents", async () => {
      const socket = new FakeRealSocket();
      const events: Array<[number, string, string]> = [];
      const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => socket, {
        sleep: vi.fn(), onVaultEvent: (type, noteId, path) => events.push([type, noteId, path]),
      });
      transport.start();
      await waitUntilConnecting(socket);
      socket.open();

      socket.deliver(vaultFrame(9, NOTE_ID_A, "Protokoll.md"));

      expect(events).toEqual([[9, NOTE_ID_A, "Protokoll.md"]]);
    });
  });

  describe("Datei-Ankuendigungen", () => {
    it("should_subscribeToFileAnnouncements_afterConnecting", async () => {
      const socket = new FakeRealSocket();
      const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => socket, {
        sleep: vi.fn(), subscribeFileEvents: true,
      });
      transport.start();
      await waitUntilConnecting(socket);
      socket.open();

      expect(socket.sent.some((frameBytes) => frameBytes[0] === 13)).toBe(true);
    });
  });

  describe("Ordner-Ankuendigungen", () => {
    it("should_subscribeToFolderAnnouncements_afterConnecting", async () => {
      const socket = new FakeRealSocket();
      const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => socket, {
        sleep: vi.fn(), subscribeFolderEvents: true,
      });
      transport.start();
      await waitUntilConnecting(socket);
      socket.open();

      expect(socket.sent.some((frameBytes) => frameBytes[0] === 11)).toBe(true);
    });

    // Der Pfad darf nie als Yjs-Update in einer offenen Notiz landen.
    it("should_reportFolderChanges_asVaultEvents", async () => {
      const socket = new FakeRealSocket();
      const events: Array<[number, string, string]> = [];
      const transport = new MultiplexedTransport(async () => "wss://example.invalid", () => socket, {
        sleep: vi.fn(), onVaultEvent: (type, noteId, path) => events.push([type, noteId, path]),
      });
      transport.start();
      await waitUntilConnecting(socket);
      socket.open();

      socket.deliver(vaultFrame(12, "00000000-0000-0000-0000-000000000000", "Projekt/Alt"));

      expect(events).toEqual([[12, "00000000-0000-0000-0000-000000000000", "Projekt/Alt"]]);
    });
  });

  describe("Dauerverbindung fuer Vault-Ereignisse", () => {
    // Ohne gejointe Notiz gab es bisher gar keine Verbindung - und damit auch keine vault-weiten
    // Bestandsereignisse. Ein Geraet ohne offene Notiz erfuhr von Anlage/Loeschung/Umbenennung
    // auf anderen Geraeten erst beim naechsten Neustart.
    it("should_connect_without_anyJoinedNote_when_started", async () => {
      const socket = new FakeRealSocket();
      const createRealSocket = vi.fn().mockReturnValue(socket);
      const transport = new MultiplexedTransport(async () => "wss://example.invalid", createRealSocket, { sleep: vi.fn() });

      transport.start();

      await waitUntilConnecting(socket);
      expect(createRealSocket).toHaveBeenCalledTimes(1);
    });

    it("should_keepReconnecting_after_start_even_withoutJoinedNotes", async () => {
      const first = new FakeRealSocket();
      const second = new FakeRealSocket();
      const createRealSocket = vi.fn().mockReturnValueOnce(first).mockReturnValueOnce(second);
      const sleep = vi.fn().mockResolvedValue(undefined);
      const transport = new MultiplexedTransport(async () => "wss://example.invalid", createRealSocket, { sleep });
      transport.start();
      await waitUntilConnecting(first);
      first.open();

      first.close();

      await vi.waitFor(() => expect(createRealSocket).toHaveBeenCalledTimes(2));
    });

    it("should_reportConnectionState_andEveryReconnect", async () => {
      const first = new FakeRealSocket();
      const second = new FakeRealSocket();
      const createRealSocket = vi.fn().mockReturnValueOnce(first).mockReturnValueOnce(second);
      const states: string[] = [];
      const onConnected = vi.fn();
      const transport = new MultiplexedTransport(async () => "wss://example.invalid", createRealSocket, {
        sleep: vi.fn().mockResolvedValue(undefined),
        onStateChange: (state) => states.push(state),
        onConnected,
      });
      transport.start();
      await waitUntilConnecting(first);
      first.open();
      first.close();
      await waitUntilConnecting(second);
      second.open();

      expect(transport.state).toBe("online");
      expect(onConnected).toHaveBeenCalledTimes(2);
      expect(states).toEqual(["connecting", "online", "offline", "connecting", "online"]);
    });

    it("should_backOffExponentially_whileTheServerStaysUnreachable", async () => {
      const sleep = vi.fn().mockResolvedValue(undefined);
      let attempts = 0;
      const getUrl = vi.fn(async () => {
        attempts++;
        if (attempts > 4) {
          await new Promise(() => undefined);
        }
        throw new Error("offline");
      });
      const transport = new MultiplexedTransport(getUrl, vi.fn(), {
        sleep, reconnectDelayMs: 1000, maxReconnectDelayMs: 5000, random: () => 0,
      });

      transport.start();

      await vi.waitFor(() => expect(sleep).toHaveBeenCalledTimes(4));
      expect(sleep.mock.calls.map(([ms]) => ms)).toEqual([1000, 2000, 4000, 5000]);
    });

    it("should_resetTheBackoff_afterASuccessfulConnect", async () => {
      const sockets = [new FakeRealSocket(), new FakeRealSocket(), new FakeRealSocket()];
      let socketIndex = 0;
      const sleep = vi.fn().mockResolvedValue(undefined);
      let failNext = true;
      const getUrl = vi.fn(async () => {
        if (failNext) {
          failNext = false;
          throw new Error("offline");
        }
        return "wss://example.invalid";
      });
      const transport = new MultiplexedTransport(getUrl, () => sockets[socketIndex++], {
        sleep, reconnectDelayMs: 1000, random: () => 0,
      });
      transport.start();
      await waitUntilConnecting(sockets[0]);
      sockets[0].open();

      sockets[0].close();

      await vi.waitFor(() => expect(sleep).toHaveBeenCalledTimes(2));
      expect(sleep.mock.calls.map(([ms]) => ms)).toEqual([1000, 1000]);
    });

    it("should_reconnectImmediately_when_askedTo_insteadOfWaitingForTheBackoff", async () => {
      const socket = new FakeRealSocket();
      let attempts = 0;
      const getUrl = vi.fn(async () => {
        attempts++;
        if (attempts === 1) {
          throw new Error("offline");
        }
        return "wss://example.invalid";
      });
      const transport = new MultiplexedTransport(getUrl, () => socket, {
        sleep: () => new Promise(() => undefined),
      });
      transport.start();
      await vi.waitFor(() => expect(transport.state).toBe("offline"));

      transport.reconnectNow();

      await waitUntilConnecting(socket);
    });
  });
});
