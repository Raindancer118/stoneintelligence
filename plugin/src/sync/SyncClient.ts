import * as Y from "yjs";
import { Awareness, applyAwarenessUpdate, encodeAwarenessUpdate } from "y-protocols/awareness";

/**
 * Schmale Abstraktion ueber das Browser-/Electron-WebSocket, damit {@link SyncClient} ohne
 * echte Netzwerkverbindung testbar ist.
 */
export interface WebSocketLike {
  binaryType: string;
  onopen: ((ev: Event) => void) | null;
  onmessage: ((ev: MessageEvent) => void) | null;
  onclose: ((ev: CloseEvent) => void) | null;
  onerror: ((ev: Event) => void) | null;
  send(data: ArrayBuffer): void;
  close(code?: number, reason?: string): void;
}

export type WebSocketFactory = (url: string) => WebSocketLike;

/**
 * Nachrichtentyp-Framing (erstes Byte), muss zum Server (SyncWebSocketHandler) passen: nur
 * Dokument-Updates werden dort persistiert, Awareness-/Cursor-Nachrichten sind ephemer
 * (Anforderungen.md: "Über Websocket-Verbindungen sollen die Cursor anderer Nutzer live
 * sichtbar sein").
 */
const MESSAGE_TYPE_DOC_UPDATE = 0;
const MESSAGE_TYPE_AWARENESS = 1;

/**
 * Client-Seite des "dummen" Yjs-Relays (Plan.md Abschnitt 2/8.4): der Server interpretiert die
 * Bytes nicht, er verteilt sie nur. {@link doc} ist die Source of Truth (ADR 0002) - die
 * Markdown-Datei im Vault ist nur eine Serialisierung davon. {@link awareness} traegt
 * Cursor-/Presence-Information und wird nie persistiert.
 */
export class SyncClient {
  readonly doc: Y.Doc;
  readonly awareness: Awareness;
  private socket: WebSocketLike | null = null;

  constructor(
    private readonly wsUrl: string,
    private readonly wsFactory: WebSocketFactory,
    doc: Y.Doc = new Y.Doc(),
  ) {
    this.doc = doc;
    this.awareness = new Awareness(doc);

    this.doc.on("update", (update: Uint8Array, origin: unknown) => {
      // origin === this heisst: das Update kam aus applyUpdate() unten (ein empfangenes
      // Server-Update) - das NICHT zurueckschicken, sonst Echo-Schleifen auf Yjs-Ebene.
      if (origin === this) {
        return;
      }
      this.send(MESSAGE_TYPE_DOC_UPDATE, update);
    });

    this.awareness.on(
      "update",
      ({ added, updated, removed }: { added: number[]; updated: number[]; removed: number[] }, origin: unknown) => {
        if (origin === this) {
          return;
        }
        const changedClients = added.concat(updated, removed);
        this.send(MESSAGE_TYPE_AWARENESS, encodeAwarenessUpdate(this.awareness, changedClients));
      },
    );
  }

  connect(): void {
    this.socket = this.wsFactory(this.wsUrl);
    this.socket.binaryType = "arraybuffer";
    this.socket.onmessage = (event) => {
      const raw = new Uint8Array(event.data as ArrayBuffer);
      if (raw.length === 0) {
        return;
      }
      const messageType = raw[0];
      const payload = raw.subarray(1);
      if (messageType === MESSAGE_TYPE_AWARENESS) {
        applyAwarenessUpdate(this.awareness, payload, this);
      } else {
        Y.applyUpdate(this.doc, payload, this);
      }
    };
  }

  disconnect(): void {
    this.socket?.close();
    this.socket = null;
  }

  private send(messageType: number, payload: Uint8Array): void {
    if (!this.socket) {
      return;
    }
    const framed = new Uint8Array(1 + payload.length);
    framed[0] = messageType;
    framed.set(payload, 1);
    this.socket.send(framed.buffer as ArrayBuffer);
  }
}
