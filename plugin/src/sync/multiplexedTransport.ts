import type { WebSocketFactory, WebSocketLike } from "./SyncClient";

/** Muss zum Server (SyncFrame.java) passen: Standard-UUID-Stringform, immer 36 ASCII-Zeichen. */
const NOTE_ID_LENGTH = 36;

const TYPE_JOIN = 2;
const TYPE_LEAVE = 3;
/** Muss zum Server (SyncFrame.java) passen: genau diese Notiz wurde geloescht, Verbindung bleibt bestehen. */
const TYPE_NOTE_DELETED = 4;
/**
 * Vault-weite Bestandsereignisse (Server->Client, muessen zu `SyncFrame` passen): Notiz angelegt
 * / geloescht / umbenannt, Payload ist der Pfad als UTF-8. Betreffen den gesamten Vault, nicht
 * einen Notiz-Raum - sie kommen gerade auch fuer Notizen an, die dieses Geraet NICHT gejoint hat
 * (seit nur noch geoeffnete Notizen joinen, ist das der Regelfall).
 */
export const VAULT_NOTE_CREATED = 6;
export const VAULT_NOTE_DELETED = 7;
export const VAULT_NOTE_RENAMED = 8;
/** Inhalt einer (hier nicht gejointen) Notiz hat sich geaendert - nur nach Abo, s. {@link TYPE_SUBSCRIBE_CONTENT_UPDATES}. */
export const VAULT_NOTE_UPDATED = 9;
/** Client->Server: "ich verstehe VAULT_NOTE_UPDATED" - aeltere Plugins bekommen Typ 9 so nie. */
const TYPE_SUBSCRIBE_CONTENT_UPDATES = 10;
/** Client->Server: "ich verstehe VAULT_FOLDERS_CHANGED" - aus demselben Grund opt-in wie Typ 10. */
const TYPE_SUBSCRIBE_FOLDER_EVENTS = 11;
/** Client->Server: "ich kenne Dateien" (ADR 0009) - ihre Ereignisse kommen dann in den Frames 6-9. */
const TYPE_SUBSCRIBE_FILE_EVENTS = 13;
/** Ordner unter diesem Pfad angelegt/geloescht/verschoben - Anlass, die Ordnerliste zu holen. */
export const VAULT_FOLDERS_CHANGED = 12;
/** Client->Server: "ich verstehe VAULT_ACCESS_CHANGED" (ADR 0011). */
const TYPE_SUBSCRIBE_ACCESS_EVENTS = 14;
/** Rechte im Vault haben sich geaendert - ohne Pfad; Anlass, Liste und Rechte neu zu holen. */
export const VAULT_ACCESS_CHANGED = 15;
const NIL_NOTE_ID = "00000000-0000-0000-0000-000000000000";
const TYPE_VAULT_NOTE_CREATED = VAULT_NOTE_CREATED;
const TYPE_VAULT_NOTE_DELETED = VAULT_NOTE_DELETED;
const TYPE_VAULT_NOTE_RENAMED = VAULT_NOTE_RENAMED;
const VAULT_EVENT_TYPES = new Set([TYPE_VAULT_NOTE_CREATED, TYPE_VAULT_NOTE_DELETED, TYPE_VAULT_NOTE_RENAMED, VAULT_NOTE_UPDATED, VAULT_FOLDERS_CHANGED, VAULT_ACCESS_CHANGED]);

export type VaultEventHandler = (messageType: number, noteId: string, path: string) => void;
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

/** Zustand der EINEN physischen Verbindung - Grundlage fuer die Statusanzeige im Plugin. */
export type TransportState = "offline" | "connecting" | "online";

export interface MultiplexedTransportOptions {
  /** Wartezeit zwischen zwei JOIN-Nachrichten - verhindert einen Burst aus hunderten gleichzeitigen Joins. */
  joinStaggerMs?: number;
  /**
   * Wartezeit vor dem ERSTEN automatischen Reconnect-Versuch, nachdem die Verbindung abgebrochen
   * ist. Jeder weitere erfolglose Versuch verdoppelt sie (mit etwas Zufall, damit nicht alle
   * Geraete nach einem Server-Neustart im selben Takt anklopfen), bis {@link maxReconnectDelayMs}.
   */
  reconnectDelayMs?: number;
  maxReconnectDelayMs?: number;
  /** Zufallsquelle fuer den Jitter - in Tests fest, sonst `Math.random`. */
  random?: () => number;
  onStateChange?: (state: TransportState) => void;
  /** Inhalts-Ankuendigungen geschlossener Notizen abonnieren (nach jedem Connect erneut). */
  subscribeContentUpdates?: boolean;
  /** Ordner-Ankuendigungen abonnieren (nach jedem Connect erneut). */
  subscribeFolderEvents?: boolean;
  /** Datei-Ankuendigungen abonnieren (nach jedem Connect erneut). */
  subscribeFileEvents?: boolean;
  /** Rechte-Ankuendigungen abonnieren (nach jedem Connect erneut). */
  subscribeAccessEvents?: boolean;
  /** Nach JEDEM erfolgreichen (Re-)Connect - Anlass, verpasste Vault-Ereignisse per Abgleich nachzuholen. */
  onConnected?: () => void;
  sleep?: (ms: number) => Promise<void>;
  /** Empfaengt vault-weite Bestandsereignisse (Anlage/Loeschung/Umbenennung einer Notiz). */
  onVaultEvent?: VaultEventHandler;
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
  /**
   * Endgueltig heruntergefahren (Logout/terminale Auth-Ablehnung/Plugin-Unload) - unterscheidet
   * sich von einem gewoehnlichen Verbindungsabbruch dadurch, dass NIE wieder automatisch neu
   * verbunden wird. Ohne das lief der 2-Sekunden-Reconnect-Loop nach einem Logout unveraendert
   * weiter und haemmerte mit dem (jetzt ungueltigen) Ticket-Endpunkt weiter auf den Server ein
   * (P1-Fund, s. docs/sync-comparison-review-2026-09-18.md "Logout/terminal auth failure leaves
   * the authorized socket alive").
   */
  private destroyed = false;
  /**
   * Nach {@link start}: Verbindung auch ohne gejointe Notiz halten und immer wieder aufbauen -
   * sonst kommen vault-weite Bestandsereignisse nur an, solange zufaellig eine Notiz offen ist.
   */
  private keepAlive = false;
  private reconnectAttempt = 0;
  /** Nur der zuletzt geplante Reconnect darf feuern (onerror UND onclose planen beide einen). */
  private reconnectToken = 0;
  state: TransportState = "offline";

  private readonly joinStaggerMs: number;
  private readonly reconnectDelayMs: number;
  private readonly maxReconnectDelayMs: number;
  private readonly random: () => number;
  private readonly onStateChange: ((state: TransportState) => void) | null;
  private readonly onConnected: (() => void) | null;
  private readonly subscribeContentUpdates: boolean;
  private readonly subscribeFolderEvents: boolean;
  private readonly subscribeFileEvents: boolean;
  private readonly subscribeAccessEvents: boolean;
  private readonly sleep: (ms: number) => Promise<void>;
  private readonly onVaultEvent: VaultEventHandler | null;

  constructor(
    private readonly getUrl: WsUrlProvider,
    private readonly createRealSocket: WebSocketFactory,
    options: MultiplexedTransportOptions = {},
  ) {
    this.joinStaggerMs = options.joinStaggerMs ?? 150;
    this.reconnectDelayMs = options.reconnectDelayMs ?? 2000;
    this.maxReconnectDelayMs = options.maxReconnectDelayMs ?? 30_000;
    this.random = options.random ?? Math.random;
    this.onStateChange = options.onStateChange ?? null;
    this.onConnected = options.onConnected ?? null;
    this.subscribeContentUpdates = options.subscribeContentUpdates ?? false;
    this.subscribeFolderEvents = options.subscribeFolderEvents ?? false;
    this.subscribeFileEvents = options.subscribeFileEvents ?? false;
    this.subscribeAccessEvents = options.subscribeAccessEvents ?? false;
    this.sleep = options.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
    this.onVaultEvent = options.onVaultEvent ?? null;
  }

  /** Baut die Verbindung sofort auf und haelt sie dauerhaft, auch ganz ohne gejointe Notiz. */
  start(): void {
    this.keepAlive = true;
    void this.ensureRealSocketConnecting();
  }

  /**
   * Sofort neu verbinden statt den laufenden Backoff abzuwarten - z. B. wenn der Nutzer
   * "Jetzt synchronisieren" waehlt oder das Betriebssystem meldet, dass das Netz wieder da ist.
   */
  reconnectNow(): void {
    if (this.destroyed) {
      return;
    }
    this.reconnectToken++;
    this.reconnectAttempt = 0;
    void this.ensureRealSocketConnecting();
  }

  private setState(state: TransportState): void {
    if (this.state !== state) {
      this.state = state;
      this.onStateChange?.(state);
    }
  }

  /**
   * Registriert eine neue Notiz. `priority: true` (die gerade aktiv geoeffnete Notiz) stellt sich
   * VOR bereits wartende Hintergrund-Notizen - sonst muesste die aktive Notiz hinter einem
   * moeglicherweise langen Hintergrund-Rueckstand auf ihren Join warten.
   */
  createVirtualSocket = (noteId: string, options: { priority?: boolean } = {}): WebSocketLike => {
    const vs = new VirtualSocket(noteId, this);
    if (this.destroyed) {
      return vs;
    }
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
    if (this.destroyed || this.realSocket || this.connecting) {
      return;
    }
    this.connecting = true;
    this.setState("connecting");
    try {
      const url = await this.getUrl();
      if (this.destroyed) {
        // destroy() lief WAEHREND dieses Awaits (z. B. Logout genau waehrend ein Ticket-Request
        // unterwegs war) - die fruehe destroyed-Pruefung oben hat das nicht mehr gesehen. Ohne
        // diesen zweiten Check haette der bereits initiierte Ticket-Abruf trotzdem noch ein
        // echtes Socket erzeugt und in `this.realSocket` abgelegt, obwohl destroy() laengst
        // "fertig" war - eine per Logout eigentlich beendete Verbindung waere doch noch
        // aufgebaut worden (Fund aus dem zweiten Codex-Vergleichsreview).
        return;
      }
      const socket = this.createRealSocket(url);
      socket.binaryType = "arraybuffer";
      socket.onopen = () => {
        this.isOpen = true;
        this.reconnectAttempt = 0;
        this.setState("online");
        if (this.subscribeContentUpdates) {
          this.sendFramed(TYPE_SUBSCRIBE_CONTENT_UPDATES, NIL_NOTE_ID, new Uint8Array(0));
        }
        if (this.subscribeFolderEvents) {
          this.sendFramed(TYPE_SUBSCRIBE_FOLDER_EVENTS, NIL_NOTE_ID, new Uint8Array(0));
        }
        if (this.subscribeFileEvents) {
          this.sendFramed(TYPE_SUBSCRIBE_FILE_EVENTS, NIL_NOTE_ID, new Uint8Array(0));
        }
        if (this.subscribeAccessEvents) {
          this.sendFramed(TYPE_SUBSCRIBE_ACCESS_EVENTS, NIL_NOTE_ID, new Uint8Array(0));
        }
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
        this.onConnected?.();
      };
      socket.onmessage = (ev) => this.handleIncoming(new Uint8Array(ev.data as ArrayBuffer));
      socket.onclose = (ev) => {
        this.isOpen = false;
        this.setState("offline");
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
        this.setState("offline");
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
      this.setState("offline");
      for (const vs of this.virtualSockets.values()) {
        vs.dispatchError();
      }
      this.scheduleReconnectIfNeeded();
    } finally {
      this.connecting = false;
    }
  }

  private wantsConnection(): boolean {
    return !this.destroyed && (this.keepAlive || this.virtualSockets.size > 0);
  }

  private nextReconnectDelay(): number {
    if (this.reconnectAttempt === 0) {
      return this.reconnectDelayMs;
    }
    const exponential = Math.min(this.reconnectDelayMs * 2 ** this.reconnectAttempt, this.maxReconnectDelayMs);
    return Math.round(exponential * (1 + 0.3 * this.random()));
  }

  /**
   * Automatischer Reconnect mit exponentiellem Backoff, solange die Verbindung gewollt ist
   * (Dauerverbindung nach {@link start} oder mindestens eine gejointe Notiz). Frueher: fixe 2s,
   * endlos - ein laengerer Server-Ausfall bedeutete ein Ticket-Request alle 2s von jedem Geraet.
   */
  private scheduleReconnectIfNeeded(): void {
    if (!this.wantsConnection()) {
      return;
    }
    const token = ++this.reconnectToken;
    void this.sleep(this.nextReconnectDelay()).then(() => {
      if (token !== this.reconnectToken || !this.wantsConnection() || this.realSocket) {
        return;
      }
      this.reconnectAttempt++;
      void this.ensureRealSocketConnecting();
    });
  }

  /**
   * Beendet die geteilte Verbindung endgueltig - kein weiterer automatischer Reconnect, egal was
   * als naechstes passiert. Aufzurufen bei Logout, einer terminalen (nicht-transienten)
   * Auth-Ablehnung, oder Plugin-Unload. Danach ist dieser Transport nicht mehr nutzbar; fuer eine
   * neue Verbindung (z. B. nach erneutem Login) wird eine frische `MultiplexedTransport`-Instanz
   * gebraucht.
   */
  destroy(): void {
    this.destroyed = true;
    this.keepAlive = false;
    this.reconnectToken++;
    this.virtualSockets.clear();
    this.joinQueue.length = 0;
    this.realSocket?.close();
    this.realSocket = null;
    this.isOpen = false;
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
        // Nie synchron: bei bereits offener Verbindung laeuft dieser Code noch INNERHALB von
        // `createVirtualSocket` - der Aufrufer (SyncClient.connect) setzt `onopen` aber erst
        // danach. Synchron ausgeloest ging das Ereignis ins Leere und die Notiz hing bis zum
        // Timeout auf "verbindet".
        queueMicrotask(() => {
          if (this.virtualSockets.get(noteId) === vs) {
            vs.dispatchOpen();
          }
        });
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
    // Nicht nur auf `realSocket` pruefen, sondern auch, ob es TATSAECHLICH offen ist: waehrend
    // eines (Re-)Connects existiert das Socket-Objekt bereits (readyState CONNECTING), ein
    // `send()`-Aufruf darauf wirft aber synchron `InvalidStateError` - z. B. wenn ein
    // Awareness-Update (Mausbewegung/Cursor) genau in diesem Fenster feuert. Best-effort wie der
    // Rest dieses "dummen" Relays: die Nachricht wird dann verworfen statt eine Exception zu
    // werfen, die im Yjs-Event-Dispatch nach oben durchschlagen wuerde.
    if (!this.realSocket || !this.isOpen) {
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

    // VOR der Socket-Zuordnung: ein Bestandsereignis betrifft typischerweise eine Notiz, die
    // dieses Geraet gar nicht gejoint hat - es hat also keinen virtuellen Socket, und die
    // Zustellung darf nicht daran haengen. Ausserdem darf der Pfad-Text niemals als vermeintliches
    // Yjs-Update im CRDT einer offenen Notiz landen.
    if (VAULT_EVENT_TYPES.has(type)) {
      this.onVaultEvent?.(type, noteId, textDecoder.decode(payload));
      return;
    }

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
