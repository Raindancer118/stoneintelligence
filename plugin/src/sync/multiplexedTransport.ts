import type { WebSocketFactory, WebSocketLike } from "./SyncClient";

/** Muss zum Server (SyncFrame.java) passen: Standard-UUID-Stringform, immer 36 ASCII-Zeichen. */
const NOTE_ID_LENGTH = 36;

const TYPE_JOIN = 2;
const TYPE_LEAVE = 3;
/** Muss zum Server (SyncFrame.java) passen: genau diese Notiz wurde geloescht, Verbindung bleibt bestehen. */
const TYPE_NOTE_DELETED = 4;
/** Muss zu {@code SyncClient.CLOSE_CODE_NOTE_DELETED} passen - SyncClient reagiert bereits darauf, bleibt unveraendert. */
const CLOSE_CODE_NOTE_DELETED = 4404;

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();

/**
 * Verhaelt sich fuer {@link SyncClient} exakt wie ein echtes WebSocket, ist aber in Wahrheit ein
 * "Kanal" innerhalb einer geteilten {@link MultiplexedTransport}-Verbindung. `SyncClient` bleibt
 * dadurch komplett unveraendert und ungetestet-unberuehrt - es sieht weiterhin nur
 * `[Typ-Byte][Payload]`, die NoteId wird transparent vom Transport hinzugefuegt/entfernt.
 */
class VirtualSocket implements WebSocketLike {
  binaryType = "";
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;

  constructor(
    private readonly noteId: string,
    private readonly transport: MultiplexedTransport,
  ) {}

  send(data: ArrayBuffer): void {
    const bytes = new Uint8Array(data);
    this.transport.sendFramed(bytes[0], this.noteId, bytes.subarray(1));
  }

  close(): void {
    this.transport.leave(this.noteId);
  }

  dispatchOpen(): void {
    this.onopen?.(new Event("open"));
  }

  dispatchMessage(type: number, payload: Uint8Array): void {
    const framed = new Uint8Array(1 + payload.length);
    framed[0] = type;
    framed.set(payload, 1);
    this.onmessage?.({ data: framed.buffer } as MessageEvent);
  }

  dispatchClose(code: number, reason: string): void {
    this.onclose?.({ code, reason } as CloseEvent);
  }

  dispatchError(): void {
    this.onerror?.(new Event("error"));
  }
}

export interface MultiplexedTransportOptions {
  /** Wartezeit zwischen zwei JOIN-Nachrichten - verhindert einen Burst aus hunderten gleichzeitigen Joins. */
  joinStaggerMs?: number;
  /** Wartezeit vor einem automatischen Reconnect-Versuch, nachdem die Verbindung abgebrochen ist. */
  reconnectDelayMs?: number;
  sleep?: (ms: number) => Promise<void>;
}

/**
 * Liefert eine frische WS-URL samt frischem, noch ungenutztem Sync-Ticket - MUSS bei JEDEM
 * Verbindungsaufbau neu aufgerufen werden (nicht einmal gecacht). Tickets sind Single-Use: ein
 * Reconnect mit der urspruenglichen URL wuerde ein laengst eingeloestes (oder bei einem
 * fehlgeschlagenen ersten Versuch nie erfolgreich eingeloestes, aber trotzdem verbrauchtes)
 * Ticket erneut verwenden und garantiert mit 403 scheitern - live beobachtet: derselbe
 * Ticket-Token in Dutzenden Reconnect-Versuchen in Folge, jedes Mal 403.
 */
export type WsUrlProvider = () => Promise<string>;

/**
 * EINE geteilte WebSocket-Verbindung fuer beliebig viele Notizen (statt vorher: eine Verbindung
 * PRO Notiz) - Toms ausdruecklicher Wunsch nach "maximal drei Verbindungen, am liebsten eine
 * durch die alles geht". Neue Notizen werden nicht sofort gejoint, sondern in eine Warteschlange
 * gestellt und nacheinander mit {@link MultiplexedTransportOptions.joinStaggerMs} Abstand
 * abgearbeitet ("queuen und Stueck fuer Stueck abarbeiten") - live beobachtet: ein Burst aus 177
 * gleichzeitigen Verbindungs-/Join-Versuchen liess auf Mobile die kurzlebigen Sync-Tickets ablaufen,
 * bevor die WebSocket-Verbindung (vom OS/der WebView gedrosselt) ueberhaupt zustande kam.
 */
export class MultiplexedTransport {
  private realSocket: WebSocketLike | null = null;
  private isOpen = false;
  private everConnected = false;
  private connecting = false;
  private readonly virtualSockets = new Map<string, VirtualSocket>();
  private readonly joinQueue: string[] = [];
  private draining = false;

  private readonly joinStaggerMs: number;
  private readonly reconnectDelayMs: number;
  private readonly sleep: (ms: number) => Promise<void>;

  constructor(
    private readonly getUrl: WsUrlProvider,
    private readonly createRealSocket: WebSocketFactory,
    options: MultiplexedTransportOptions = {},
  ) {
    this.joinStaggerMs = options.joinStaggerMs ?? 150;
    this.reconnectDelayMs = options.reconnectDelayMs ?? 2000;
    this.sleep = options.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
  }

  /**
   * Registriert eine neue Notiz. `priority: true` (die gerade aktiv geoeffnete Notiz) stellt sich
   * VOR bereits wartende Hintergrund-Notizen - sonst muesste die aktive Notiz hinter einem
   * moeglicherweise langen Hintergrund-Rueckstand auf ihren Join warten.
   */
  createVirtualSocket = (noteId: string, options: { priority?: boolean } = {}): WebSocketLike => {
    const vs = new VirtualSocket(noteId, this);
    this.virtualSockets.set(noteId, vs);
    void this.ensureRealSocketConnecting();
    if (options.priority) {
      this.joinQueue.unshift(noteId);
    } else {
      this.joinQueue.push(noteId);
    }
    void this.drainJoinQueue();
    return vs;
  };

  /**
   * Holt bei JEDEM Aufruf eine FRISCHE URL/Ticket (s. {@link WsUrlProvider}) - ein Reconnect darf
   * niemals die alte URL wiederverwenden, das eingebettete Ticket ist nach dem ersten
   * (erfolgreichen ODER fehlgeschlagenen) Redemption-Versuch bereits verbraucht.
   */
  private async ensureRealSocketConnecting(): Promise<void> {
    if (this.realSocket || this.connecting) {
      return;
    }
    this.connecting = true;
    try {
      const url = await this.getUrl();
      const socket = this.createRealSocket(url);
      socket.binaryType = "arraybuffer";
      socket.onopen = () => {
        this.isOpen = true;
        if (this.everConnected) {
          // RECONNECT (nicht der allererste Connect): der Server kennt keine alten Joins einer
          // vorherigen, jetzt toten Verbindung mehr - ALLE aktuell registrierten Notizen muessen
          // neu gejoint werden. Beim allerersten Connect NICHT ueberschreiben - die Queue
          // enthaelt dort bereits die korrekte, priorisierte Reihenfolge aus `createVirtualSocket`.
          this.joinQueue.length = 0;
          this.joinQueue.push(...this.virtualSockets.keys());
        }
        this.everConnected = true;
        void this.drainJoinQueue();
      };
      socket.onmessage = (ev) => this.handleIncoming(new Uint8Array(ev.data as ArrayBuffer));
      socket.onclose = (ev) => {
        this.isOpen = false;
        // OHNE dieses Zuruecksetzen bliebe `realSocket` fuer immer auf die tote Verbindung
        // zeigen - jede danach registrierte oder bereits wartende Notiz haette nie wieder eine
        // Chance auf `onopen`/`onerror` und wuerde fuer immer auf "connecting" stehen bleiben
        // (live beobachtet: Mobile-Status haengt dauerhaft bei "verbindet").
        this.realSocket = null;
        for (const vs of this.virtualSockets.values()) {
          vs.dispatchClose(ev.code, ev.reason);
        }
        this.scheduleReconnectIfNeeded();
      };
      socket.onerror = () => {
        this.isOpen = false;
        this.realSocket = null;
        for (const vs of this.virtualSockets.values()) {
          vs.dispatchError();
        }
        this.scheduleReconnectIfNeeded();
      };
      this.realSocket = socket;
    } catch (error) {
      // Die URL/Ticket-Beschaffung selbst ist fehlgeschlagen (z. B. Token-Refresh mit HTTP 400,
      // s. main.ts getAccessToken) - es wurde nie ein echtes Socket erzeugt, dessen onerror/
      // onclose sonst einen Reconnect anstoessen wuerde. OHNE diesen expliziten Pfad bliebe jede
      // registrierte Notiz fuer immer auf "connecting" stehen (live beobachtet).
      for (const vs of this.virtualSockets.values()) {
        vs.dispatchError();
      }
      this.scheduleReconnectIfNeeded();
    } finally {
      this.connecting = false;
    }
  }

  /** Automatischer Reconnect, solange noch mindestens eine Notiz verbunden bleiben will. */
  private scheduleReconnectIfNeeded(): void {
    if (this.virtualSockets.size === 0) {
      return;
    }
    void this.sleep(this.reconnectDelayMs).then(() => {
      if (this.virtualSockets.size > 0 && !this.realSocket) {
        void this.ensureRealSocketConnecting();
      }
    });
  }

  private async drainJoinQueue(): Promise<void> {
    if (this.draining || !this.isOpen) {
      return;
    }
    this.draining = true;
    try {
      while (this.joinQueue.length > 0) {
        const noteId = this.joinQueue.shift() as string;
        const vs = this.virtualSockets.get(noteId);
        if (!vs) {
          continue;
        }
        this.sendFramed(TYPE_JOIN, noteId, new Uint8Array(0));
        vs.dispatchOpen();
        if (this.joinQueue.length > 0) {
          await this.sleep(this.joinStaggerMs);
        }
      }
    } finally {
      this.draining = false;
    }
  }

  leave(noteId: string): void {
    this.virtualSockets.delete(noteId);
    const queueIndex = this.joinQueue.indexOf(noteId);
    if (queueIndex >= 0) {
      this.joinQueue.splice(queueIndex, 1);
      return;
    }
    if (this.isOpen) {
      this.sendFramed(TYPE_LEAVE, noteId, new Uint8Array(0));
    }
  }

  sendFramed(type: number, noteId: string, payload: Uint8Array): void {
    if (!this.realSocket) {
      return;
    }
    const noteIdBytes = textEncoder.encode(noteId);
    const framed = new Uint8Array(1 + noteIdBytes.length + payload.length);
    framed[0] = type;
    framed.set(noteIdBytes, 1);
    framed.set(payload, 1 + noteIdBytes.length);
    this.realSocket.send(framed.buffer as ArrayBuffer);
  }

  private handleIncoming(raw: Uint8Array): void {
    if (raw.length < 1 + NOTE_ID_LENGTH) {
      return;
    }
    const type = raw[0];
    const noteId = textDecoder.decode(raw.subarray(1, 1 + NOTE_ID_LENGTH));
    const payload = raw.subarray(1 + NOTE_ID_LENGTH);
    const vs = this.virtualSockets.get(noteId);
    if (!vs) {
      return;
    }
    if (type === TYPE_NOTE_DELETED) {
      // Betrifft NUR diese eine Notiz, NICHT die geteilte Verbindung - deshalb ein synthetisches
      // Close ausschliesslich auf ihrem virtuellen Socket (SyncClient reagiert bereits auf
      // Close-Code 4404 mit `onNoteDeleted`, bleibt dafuer unveraendert), statt die echte
      // Verbindung zu trennen und damit alle anderen gejointen Notizen mitzureissen.
      this.virtualSockets.delete(noteId);
      vs.dispatchClose(CLOSE_CODE_NOTE_DELETED, "note deleted");
      return;
    }
    vs.dispatchMessage(type, payload);
  }
}
