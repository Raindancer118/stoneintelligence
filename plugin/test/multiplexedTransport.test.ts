import { describe, expect, it, vi } from "vitest";
import type { WebSocketLike } from "../src/sync/SyncClient";
import { MultiplexedTransport } from "../src/sync/multiplexedTransport";

const NOTE_ID_A = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
const NOTE_ID_B = "11111111-1111-1111-1111-111111111111";

const TYPE_DOC_UPDATE = 0;
const TYPE_JOIN = 2;
const TYPE_LEAVE = 3;

/** Minimaler In-Memory-WebSocket-Fake, den der Transport wie ein echtes WebSocket behandelt. */
class FakeRealSocket implements WebSocketLike {
  binaryType = "";
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  readonly sent: Uint8Array[] = [];

  send(data: ArrayBuffer): void {
    this.sent.push(new Uint8Array(data));
  }

  close(): void {
    this.onclose?.({ code: 1000, reason: "closed" } as CloseEvent);
  }

  open(): void {
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

describe("MultiplexedTransport", () => {
  it("should_createExactlyOneRealSocket_forMultipleVirtualSockets", () => {
    const createRealSocket = vi.fn().mockReturnValue(new FakeRealSocket());
    const transport = new MultiplexedTransport("wss://example.invalid", createRealSocket, { sleep: vi.fn() });

    transport.createVirtualSocket(NOTE_ID_A);
    transport.createVirtualSocket(NOTE_ID_B);

    expect(createRealSocket).toHaveBeenCalledTimes(1);
  });

  it("should_sendJoinFrame_andDispatchOpen_forEachVirtualSocket_afterRealSocketOpens", async () => {
    const realSocket = new FakeRealSocket();
    const transport = new MultiplexedTransport("wss://example.invalid", () => realSocket, { sleep: vi.fn() });
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
    const openA = vi.fn();
    vsA.onopen = openA;

    realSocket.open();
    await vi.waitFor(() => expect(openA).toHaveBeenCalledTimes(1));

    expect(realSocket.sent).toHaveLength(1);
    expect(realSocket.sent[0][0]).toBe(TYPE_JOIN);
  });

  it("should_staggerJoins_withSleepBetweenEach_when_multipleNotesArePending", async () => {
    const realSocket = new FakeRealSocket();
    const sleep = vi.fn().mockResolvedValue(undefined);
    const transport = new MultiplexedTransport("wss://example.invalid", () => realSocket, { sleep, joinStaggerMs: 150 });
    transport.createVirtualSocket(NOTE_ID_A);
    transport.createVirtualSocket(NOTE_ID_B);

    realSocket.open();
    await vi.waitFor(() => expect(realSocket.sent).toHaveLength(2));

    expect(sleep).toHaveBeenCalledWith(150);
  });

  it("should_joinPrioritizedNote_beforeAlreadyQueuedBackgroundNotes", async () => {
    const realSocket = new FakeRealSocket();
    const sleep = vi.fn().mockResolvedValue(undefined);
    const transport = new MultiplexedTransport("wss://example.invalid", () => realSocket, { sleep });
    transport.createVirtualSocket(NOTE_ID_B);
    transport.createVirtualSocket(NOTE_ID_A, { priority: true });

    realSocket.open();
    await vi.waitFor(() => expect(realSocket.sent).toHaveLength(2));

    const firstJoinedNoteId = new TextDecoder().decode(realSocket.sent[0].subarray(1, 37));
    expect(firstJoinedNoteId).toBe(NOTE_ID_A);
  });

  it("should_routeIncomingMessage_toTheVirtualSocketMatchingItsNoteId_strippingTheNoteIdPrefix", async () => {
    const realSocket = new FakeRealSocket();
    const transport = new MultiplexedTransport("wss://example.invalid", () => realSocket, { sleep: vi.fn() });
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
    const vsB = transport.createVirtualSocket(NOTE_ID_B);
    const messagesA = vi.fn();
    const messagesB = vi.fn();
    vsA.onmessage = messagesA;
    vsB.onmessage = messagesB;
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
    const transport = new MultiplexedTransport("wss://example.invalid", () => realSocket, { sleep: vi.fn() });
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
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
    const transport = new MultiplexedTransport("wss://example.invalid", () => realSocket, { sleep: vi.fn() });
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
    realSocket.open();
    await vi.waitFor(() => expect(realSocket.sent).toHaveLength(1));

    vsA.close();

    expect(realSocket.sent).toHaveLength(2);
    expect(realSocket.sent[1][0]).toBe(TYPE_LEAVE);
  });

  it("should_dispatchCloseToAllVirtualSockets_when_realSocketCloses", () => {
    const realSocket = new FakeRealSocket();
    const transport = new MultiplexedTransport("wss://example.invalid", () => realSocket, { sleep: vi.fn() });
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
    const vsB = transport.createVirtualSocket(NOTE_ID_B);
    const closeA = vi.fn();
    const closeB = vi.fn();
    vsA.onclose = closeA;
    vsB.onclose = closeB;

    realSocket.close();

    expect(closeA).toHaveBeenCalledTimes(1);
    expect(closeB).toHaveBeenCalledTimes(1);
  });

  it("should_dispatchErrorToAllVirtualSockets_when_realSocketErrors", () => {
    const realSocket = new FakeRealSocket();
    const transport = new MultiplexedTransport("wss://example.invalid", () => realSocket, { sleep: vi.fn() });
    const vsA = transport.createVirtualSocket(NOTE_ID_A);
    const errorA = vi.fn();
    vsA.onerror = errorA;

    realSocket.onerror?.(new Event("error"));

    expect(errorA).toHaveBeenCalledTimes(1);
  });
});
