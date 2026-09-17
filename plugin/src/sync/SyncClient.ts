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

/** Muss zu {@code SyncRelayService.CLOSE_CODE_NOTE_DELETED} auf dem Server passen. */
export const CLOSE_CODE_NOTE_DELETED = 4404;

export type SyncStatus = "connecting" | "connected" | "disconnected" | "error";

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

  /** Wird aufgerufen, wenn der Server die Verbindung mit {@link CLOSE_CODE_NOTE_DELETED} trennt. */
  onNoteDeleted: (() => void) | null = null;

  /** Fuer Sichtbarkeit (Status-Leiste/-Ansicht) - kein Teil des Sync-Protokolls selbst. */
  status: SyncStatus = "connecting";
  onStatusChange: ((status: SyncStatus) => void) | null = null;

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
    this.setStatus("connecting");
    this.socket = this.wsFactory(this.wsUrl);
    this.socket.binaryType = "arraybuffer";
    this.socket.onopen = () => this.setStatus("connected");
    this.socket.onerror = () => this.setStatus("error");
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
    this.socket.onclose = (event) => {
      if (event.code === CLOSE_CODE_NOTE_DELETED) {
        this.onNoteDeleted?.();
        return;
      }
      this.setStatus(event.code === 1000 ? "disconnected" : "error");
    };
  }

  disconnect(): void {
    this.socket?.close();
    this.socket = null;
    this.setStatus("disconnected");
  }

  private setStatus(status: SyncStatus): void {
    this.status = status;
    this.onStatusChange?.(status);
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
