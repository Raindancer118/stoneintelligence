import * as Y from "yjs";

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
 * Client-Seite des "dummen" Yjs-Relays (Plan.md Abschnitt 2/8.4): der Server interpretiert die
 * Bytes nicht, er verteilt sie nur. {@link doc} ist die Source of Truth (ADR 0002) - die
 * Markdown-Datei im Vault ist nur eine Serialisierung davon.
 */
export class SyncClient {
  readonly doc: Y.Doc;
  private socket: WebSocketLike | null = null;

  constructor(
    private readonly wsUrl: string,
    private readonly wsFactory: WebSocketFactory,
    doc: Y.Doc = new Y.Doc(),
  ) {
    this.doc = doc;
    this.doc.on("update", (update: Uint8Array, origin: unknown) => {
      // origin === this heisst: das Update kam aus applyUpdate() unten (ein empfangenes
      // Server-Update) - das NICHT zurueckschicken, sonst haben wir Fehlerklasse-1-artige
      // Echo-Schleifen auf Yjs-Ebene statt auf Vault-Event-Ebene.
      if (origin === this) {
        return;
      }
      this.send(update);
    });
  }

  connect(): void {
    this.socket = this.wsFactory(this.wsUrl);
    this.socket.binaryType = "arraybuffer";
    this.socket.onmessage = (event) => {
      const update = new Uint8Array(event.data as ArrayBuffer);
      Y.applyUpdate(this.doc, update, this);
    };
  }

  disconnect(): void {
    this.socket?.close();
    this.socket = null;
  }

  private send(update: Uint8Array): void {
    if (!this.socket) {
      return;
    }
    const buffer = update.buffer.slice(update.byteOffset, update.byteOffset + update.byteLength);
    this.socket.send(buffer as ArrayBuffer);
  }
}
