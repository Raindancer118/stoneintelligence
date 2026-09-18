import * as Y from "yjs";
import { Awareness, applyAwarenessUpdate, encodeAwarenessUpdate, removeAwarenessStates } from "y-protocols/awareness";

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
/** Muss zu {@code SyncFrame.TYPE_CATCHUP_COMPLETE} auf dem Server passen. */
const MESSAGE_TYPE_CATCHUP_COMPLETE = 5;

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

  /**
   * Wird EINMAL aufgerufen, sobald der Server die komplette Late-Joiner-Update-Historie fuer
   * diese Notiz gesendet hat. Ersetzt eine frueher hier genutzte fixe Gnadenfrist im Aufrufer
   * (main.ts `mergeInitialContent`), die unter Last zu kurz sein konnte und dadurch lokalen
   * Inhalt zusaetzlich zum (verspaetet doch noch eintreffenden) Server-Inhalt einspielte.
   */
  onCatchupComplete: (() => void) | null = null;

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
      } else if (messageType === MESSAGE_TYPE_CATCHUP_COMPLETE) {
        this.resendFullStateAsRepair();
        this.onCatchupComplete?.();
      } else {
        Y.applyUpdate(this.doc, payload, this);
      }
    };
    this.socket.onclose = (event) => {
      if (event.code === CLOSE_CODE_NOTE_DELETED) {
        this.onNoteDeleted?.();
        return;
      }
      this.clearRemoteAwarenessStates();
      this.setStatus(event.code === 1000 ? "disconnected" : "error");
    };
  }

  /**
   * Sendet den kompletten aktuellen Doc-Zustand als ein einzelnes zusaetzliches Update, sobald
   * der Late-Joiner-Catchup abgeschlossen ist (P0-Fix, s. docs/sync-comparison-review-2026-09-18.md
   * "Offline updates are silently lost"): `doc.on("update")` sendet nur, WAEHREND ein Socket
   * existiert - ein Edit zwischen `disconnect()` und dem naechsten erfolgreichen `connect()`
   * (Verbindungsabbruch, Obsidian-Neustart mit noch ungespeichertem Journal-Replay, o.ä.) wurde
   * bisher endgueltig verworfen, weil Yjs "update" fuer laengst vergangene Aenderungen nie erneut
   * feuert. Ein volles `encodeStateAsUpdate` ist bei Yjs idempotent anwendbar (bereits bekannte
   * CRDT-Structs werden beim Empfaenger uebersprungen) - erneutes Anwenden auf Server/Peers, die
   * den Stand schon kennen, ist ein No-Op, kein Duplikat. Kein Server-Protokoll-Umbau noetig: der
   * Server bleibt "dumm" (ADR 0002) und sieht nur ein weiteres opakes Update-Blob.
   */
  private resendFullStateAsRepair(): void {
    this.send(MESSAGE_TYPE_DOC_UPDATE, Y.encodeStateAsUpdate(this.doc));
    this.resendLocalAwarenessAsRepair();
  }

  /**
   * Sendet den eigenen Awareness-Zustand nach jedem (Re-)Connect erneut - JOIN selbst erzeugt
   * kein Awareness-Ereignis, ohne diese Republikation bliebe die eigene Praesenz (Cursor) fuer
   * andere unsichtbar, bis sie sich zufaellig das naechste Mal aendert (P1-Fund, s.
   * docs/sync-comparison-review-2026-09-18.md "Awareness has stale-cursor and late-join gaps").
   */
  private resendLocalAwarenessAsRepair(): void {
    if (this.awareness.getLocalState() !== null) {
      this.send(MESSAGE_TYPE_AWARENESS, encodeAwarenessUpdate(this.awareness, [this.doc.clientID]));
    }
  }

  /**
   * Entfernt jeden fremden Awareness-Zustand, sobald die physische Verbindung unerwartet
   * abbricht - ohne das bliebe z. B. ein Cursor eines Peers, dessen Verbindung gerade abgerissen
   * ist, als "Geist" dauerhaft sichtbar, bis (falls ueberhaupt) irgendein anderes Ereignis seinen
   * Zustand ueberschreibt (gleicher P1-Fund wie oben). Entfernt bewusst NICHT den eigenen
   * lokalen Zustand - der bleibt gueltig und wird nach dem naechsten erfolgreichen Reconnect
   * automatisch wieder gesendet (s. {@link resendLocalAwarenessAsRepair}).
   */
  private clearRemoteAwarenessStates(): void {
    const remoteClientIds = [...this.awareness.getStates().keys()].filter((id) => id !== this.doc.clientID);
    if (remoteClientIds.length > 0) {
      removeAwarenessStates(this.awareness, remoteClientIds, this);
    }
  }

  disconnect(): void {
    // Abmeldung MUSS raus, solange der Socket noch offen ist; die Wiederherstellung des eigenen
    // Zustands MUSS danach passieren, sonst wuerde sie als weiteres Awareness-Update rausgehen
    // und die Abmeldung beim Gegenueber sofort wieder rueckgaengig machen.
    const localState = this.socket ? this.awareness.getLocalState() : null;
    if (localState !== null) {
      this.awareness.setLocalState(null);
    }
    this.socket?.close();
    this.socket = null;
    if (localState !== null) {
      this.awareness.setLocalState(localState);
    }
    this.clearRemoteAwarenessStates();
    this.setStatus("disconnected");
  }

  /**
   * Hintergrund zur Abmeldung in {@link disconnect}: ohne sie bliebe der eigene Cursor beim
   * Gegenueber als "Geist" stehen, bis das 30s-Awareness-Timeout greift (Muster und Begruendung
   * aus dem Vorgaengerprojekt `stonesync`, DocumentSession: "Announce our departure BEFORE the
   * socket goes away"). Der lokale Zustand wird danach bewusst wiederhergestellt: `disconnect()`
   * ist hier nicht zwingend endgueltig (der Transport verbindet automatisch neu, und eine Notiz
   * wird beim Schliessen/Oeffnen eines Panes getrennt und wieder verbunden), und
   * {@link resendLocalAwarenessAsRepair} braucht nach dem naechsten Catchup genau diesen Zustand -
   * waere er dauerhaft null, waere diese Person danach fuer alle anderen unsichtbar.
   */
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
