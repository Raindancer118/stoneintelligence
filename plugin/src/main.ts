import { Compartment } from "@codemirror/state";
import type { EditorView } from "@codemirror/view";
import { App, Notice, Platform, Plugin, PluginSettingTab, Setting, TFile } from "obsidian";
import { yCollab } from "y-codemirror.next";
import * as Y from "yjs";
import { type SessionSnapshot, StatusView, VIEW_TYPE_STATUS } from "./StatusView";
import { AuthentikAuthClient, TokenRefreshRejectedError, type StoredTokens } from "./sync/AuthentikAuthClient";
import { dedupeInFlight } from "./sync/dedupeInFlight";
import { awaitDesktopRedirectCode, DESKTOP_REDIRECT_URI, openAuthorizationUrlDesktop } from "./sync/desktopAuthRedirect";
import {
  handleMobileRedirectCallback, MOBILE_REDIRECT_ACTION, MOBILE_REDIRECT_URI,
  openAuthorizationUrlMobile, type PendingAuthCallback,
} from "./sync/mobileAuthRedirect";
import { MultiplexedTransport } from "./sync/multiplexedTransport";
import { NoteApiClient } from "./sync/NoteApiClient";
import { OperationJournal } from "./sync/OperationJournal";
import { SyncClient } from "./sync/SyncClient";
import { TicketClient } from "./sync/TicketClient";

interface StoneIntelligenceSettings {
  platformApiUrl: string;
  platformWsUrl: string;
  vaultId: string;
  oidcIssuerUrl: string;
  oidcClientId: string;
  tokens: StoredTokens | null;
  noteIds: Record<string, string>;
}

/** Felder, die eine "Verbindungskonfiguration" ausmachen - alles ausser Tokens/NoteId-Cache. */
type ConnectionConfig = Pick<
  StoneIntelligenceSettings, "platformApiUrl" | "platformWsUrl" | "vaultId" | "oidcIssuerUrl" | "oidcClientId"
>;

const DEFAULT_SETTINGS: StoneIntelligenceSettings = {
  platformApiUrl: "http://localhost:8080",
  platformWsUrl: "ws://localhost:8080",
  vaultId: "",
  oidcIssuerUrl: "",
  oidcClientId: "",
  tokens: null,
  noteIds: {},
};

/**
 * Deterministischer djb2-Hash ohne externe Abhaengigkeit - reicht als Content-Fingerprint fuer
 * das {@link OperationJournal} (Fehlerklasse 1), muss kryptografisch nicht stark sein.
 */
function hashContent(content: string): string {
  let hash = 5381;
  for (let i = 0; i < content.length; i++) {
    hash = ((hash << 5) + hash + content.charCodeAt(i)) | 0;
  }
  return (hash >>> 0).toString(16);
}

interface NoteSyncSession {
  path: string;
  noteId: string;
  client: SyncClient;
  unbindDocObserver: () => void;
  /**
   * true, waehrend die aktive Notiz direkt per CodeMirror-6 (y-codemirror.next) an den Editor
   * gebunden ist - dann uebernehmen yCollab + Obsidians eigenes Autosave die Persistenz
   * zeichengenau, und der grobe Volltext-Bruecken-Pfad (text.observe -> vault.modify /
   * vault.read -> Y.Text-Ersatz) wird fuer diesen Pfad ausgesetzt, um Doppelverarbeitung zu
   * vermeiden.
   */
  hasLiveEditorBinding: boolean;
}

/**
 * Haelt das GESAMTE Vault synchron (nicht nur die geoeffnete Notiz, s. Project.md fuer die
 * Historie dieser Entscheidung) - je Notiz eine eigene WebSocket-Verbindung/Y.Doc-Session.
 * Fuer Hintergrund-Notes: Volltext-Sync (ganzer Dateiinhalt in einem Y.Text). Fuer die AKTIVE
 * Notiz: echtes CodeMirror-6-Zeichen-Binding via y-codemirror.next (yCollab), inkl.
 * Cursor-Presence ueber die geteilte Awareness-Instanz von {@link SyncClient}.
 */
export default class StoneIntelligencePlugin extends Plugin {
  settings: StoneIntelligenceSettings = DEFAULT_SETTINGS;
  private readonly journal = new OperationJournal();
  private readonly sessions = new Map<string, NoteSyncSession>();
  /**
   * Pfade, fuer die `startSync` gerade laeuft, aber noch keine Session in `sessions` eingetragen
   * hat (das passiert erst ganz am Ende). Ohne diesen Guard kann derselbe Pfad zweimal parallel
   * gestartet werden - z. B. wenn `reconcileMissingNotesFromServer()` eine Platzhalterdatei
   * anlegt UND der dadurch ausgeloeste `create`-Vault-Event beide `startSync` fuer denselben
   * Pfad aufrufen, bevor der erste Aufruf ueberhaupt bei `sessions.set(...)` angekommen ist.
   */
  private readonly startingPaths = new Set<string>();
  /**
   * Pfade, die `reconcileMissingNotesFromServer()` gerade selbst per `vault.create()` anlegt -
   * der globale `create`-Event-Handler ueberspringt sie (s. `onload`), damit NICHT zusaetzlich
   * zum expliziten, sequentiellen Aufruf dort ein zweiter, unkontrollierter `startSync`-Versuch
   * lostritt (das wuerde die absichtliche Serialisierung wieder aufheben, s. Klassendoc dort).
   */
  private readonly reconcilingPaths = new Set<string>();
  private noteApiClient!: NoteApiClient;
  private authClient!: AuthentikAuthClient;
  private tokenEndpoint: string | null = null;
  /**
   * Dedupliziert gleichzeitige Refresh-Versuche - ohne das riefen mehrere parallele Aufrufer
   * (Reconciliation, Ticket-Ausstellung, ... - der Multiplex-Transport stoesst oft mehrere
   * gleichzeitig an) alle unabhaengig `refreshAccessToken` mit demselben, noch gueltig
   * aussehenden Refresh-Token auf. Authentik rotiert Refresh-Tokens (macht den alten nach
   * erfolgreicher Einloesung ungueltig) - der zweite, quasi zeitgleiche Versuch scheiterte
   * dadurch garantiert mit HTTP 400, und weil KEIN neuer Token gespeichert wurde, wiederholte
   * sich das bei jedem weiteren Zugriff endlos (live beobachtet: WS blieb dauerhaft auf
   * "verbindet", `POST .../token/` scheiterte alle paar Sekunden mit 400).
   */
  private readonly dedupeTokenRefresh = dedupeInFlight<StoredTokens>();
  private readonly liveBindingCompartment = new Compartment();
  private liveBoundPath: string | null = null;
  /**
   * Monoton wachsender Generation-Zaehler gegen den P1-Fund "Obsidian editor binding can target
   * the wrong file/view" (s. docs/sync-comparison-review-2026-09-18.md): `file-open` feuert bei
   * schnellem A-zu-B-Wechsel zwei ueberlappende, unsequenzierte `updateLiveEditorBinding`-Aufrufe
   * - ohne dieses Gate konnte der spaeter GESTARTETE, aber wegen `startSync`s Await frueher
   * FERTIGE Aufruf fuer A den View ueberschreiben, NACHDEM der Aufruf fuer B bereits korrekt
   * gebunden hatte - der Editor zeigte dann Notiz B an, band aber tatsaechlich an Notiz As Y.Text.
   */
  private liveBindGeneration = 0;
  private statusBarItem!: HTMLElement;
  private pendingAuthCallback: PendingAuthCallback | null = null;
  /**
   * EINE geteilte Sync-Verbindung fuer alle Notizen (Toms ausdruecklicher Wunsch: "maximal drei
   * Verbindungen... am liebsten eine durch die alles geht", s. `multiplexedTransport.ts`) -
   * statt frueher einer eigenen WebSocket-Verbindung PRO Notiz.
   */
  private transport: MultiplexedTransport | null = null;

  /**
   * Liefert ein gueltiges Access-Token, refresht bei Bedarf still im Hintergrund (Phase 3: OIDC
   * ist seit dem Server-seitigen Rollout der einzige Auth-Pfad, s. platform-api SecurityConfig).
   * Als Arrow-Function-Feld deklariert, damit `this` beim Durchreichen an NoteApiClient/
   * TicketClient als Callback erhalten bleibt.
   */
  private getAccessToken = async (): Promise<string> => {
    const tokens = this.settings.tokens;
    if (!tokens) {
      throw new Error("Nicht angemeldet - bitte in den StoneIntelligence-Einstellungen einloggen.");
    }
    if (Date.now() < tokens.expiresAt) {
      return tokens.accessToken;
    }
    const refreshed = await this.dedupeTokenRefresh(() => this.refreshTokens(tokens.refreshToken));
    return refreshed.accessToken;
  };

  /**
   * Loescht die gespeicherten Tokens NUR, wenn Authentik den Refresh-Token per HTTP 400/401
   * definitiv ablehnt (bereits andernorts verbraucht/rotiert, widerrufen, abgelaufen) - das ist
   * `TokenRefreshRejectedError`, s. AuthentikAuthClient.refreshAccessToken. Jeder andere Fehler
   * (5xx, Timeout, noch kein Netzwerk beim Obsidian-Start) laesst die Tokens unveraendert und
   * wirft weiter - sonst loggte ein rein voruebergehender Ausfall den Nutzer bei jedem Neustart
   * aus (live beobachtet: Obsidian startet, das Plugin versucht den Refresh bevor das
   * Betriebssystem das Netzwerk bereitgestellt hat, `discover()`/`refreshAccessToken()` schlagen
   * mit einem Netzwerkfehler fehl, und die alte, undifferenzierte catch-Klausel wertete das
   * faelschlich als "Refresh-Token ungueltig").
   */
  private async refreshTokens(refreshToken: string): Promise<StoredTokens> {
    try {
      if (!this.tokenEndpoint) {
        this.tokenEndpoint = (await this.authClient.discover()).token_endpoint;
      }
      const refreshed = await this.authClient.refreshAccessToken(this.tokenEndpoint, refreshToken);
      this.settings.tokens = refreshed;
      await this.saveData(this.settings);
      return refreshed;
    } catch (error) {
      if (error instanceof TokenRefreshRejectedError) {
        this.settings.tokens = null;
        await this.saveData(this.settings);
        this.stopAllSyncDueToAuthLoss();
      }
      throw error;
    }
  }

  /**
   * Reisst die geteilte Verbindung und alle Notiz-Sessions ab, sobald feststeht, dass die
   * Anmeldung weg ist (Logout ODER eine definitive Refresh-Ablehnung) - ehemals ein P1-Bug (s.
   * docs/sync-comparison-review-2026-09-18.md "Logout/terminal auth failure leaves the
   * authorized socket alive"): weder `logout()` noch eine terminale Token-Ablehnung ruehrten
   * bisher den bereits per Ticket authentifizierten physischen Socket an - der lief unveraendert
   * weiter (inkl. seines 2-Sekunden-Reconnect-Loops gegen einen inzwischen ungueltigen
   * Ticket-Endpunkt), bis der Nutzer sich zufaellig erneut anmeldete. `this.transport` wird auf
   * `null` gesetzt statt nur zerstoert - eine zerstoerte Instanz ist nicht wiederverwendbar, s.
   * deren `destroy()`-Doc; `ensureTransport()` erzeugt bei Bedarf (z. B. nach erneutem Login)
   * automatisch eine frische.
   */
  private stopAllSyncDueToAuthLoss(): void {
    for (const path of [...this.sessions.keys()]) {
      this.stopSync(path);
    }
    this.transport?.destroy();
    this.transport = null;
  }

  /**
   * Redirect-Strategie ist plattformabhaengig: Desktop nutzt einen lokalen Loopback-HTTP-Server
   * (Node `http` gibt es nur dort), Mobile nutzt Obsidians eigenes `obsidian://`-URI-Schema ueber
   * `registerObsidianProtocolHandler` (in `onload()` registriert, s. dort).
   */
  async login(): Promise<void> {
    const redirect = Platform.isDesktopApp
      ? { redirectUri: DESKTOP_REDIRECT_URI, openAuthorizationUrl: openAuthorizationUrlDesktop, awaitCode: awaitDesktopRedirectCode }
      : {
          redirectUri: MOBILE_REDIRECT_URI,
          openAuthorizationUrl: openAuthorizationUrlMobile,
          awaitCode: (state: string) => this.awaitMobileRedirectCode(state),
        };

    const tokens = await this.authClient.login(redirect);
    this.settings.tokens = tokens;
    await this.saveData(this.settings);
    new Notice("StoneIntelligence: Login erfolgreich.");
  }

  async logout(): Promise<void> {
    this.settings.tokens = null;
    await this.saveData(this.settings);
    this.stopAllSyncDueToAuthLoss();
  }

  /** Wartet auf den `obsidian://`-Redirect (Mobile) - der Protokoll-Handler ist in {@link onload} registriert. */
  private awaitMobileRedirectCode(expectedState: string): Promise<string> {
    return new Promise((resolve, reject) => {
      this.pendingAuthCallback = { state: expectedState, resolve, reject };
    });
  }

  async onload(): Promise<void> {
    await this.loadSettings();
    this.authClient = new AuthentikAuthClient({
      issuerUrl: this.settings.oidcIssuerUrl, clientId: this.settings.oidcClientId,
    });
    this.noteApiClient = new NoteApiClient(this.settings.platformApiUrl, this.getAccessToken);
    this.registerObsidianProtocolHandler(MOBILE_REDIRECT_ACTION, (params) => {
      const pending = this.pendingAuthCallback;
      this.pendingAuthCallback = null;
      handleMobileRedirectCallback(pending, params as unknown as Record<string, string>);
    });
    this.addSettingTab(new StoneIntelligenceSettingTab(this.app, this));

    this.registerEditorExtension([this.liveBindingCompartment.of([])]);
    this.registerEvent(
      this.app.workspace.on("file-open", (file) => {
        void this.updateLiveEditorBinding(file instanceof TFile && file.extension === "md" ? file : null);
      }),
    );

    this.registerEvent(
      this.app.vault.on("create", (file) => {
        if (file instanceof TFile && file.extension === "md" && !this.reconcilingPaths.has(file.path)) {
          void this.startSync(file, true);
        }
      }),
    );
    this.registerEvent(
      this.app.vault.on("modify", (file) => {
        if (file instanceof TFile) {
          void this.handleLocalModify(file);
        }
      }),
    );
    this.registerEvent(
      this.app.vault.on("delete", (file) => {
        if (file instanceof TFile) {
          void this.handleLocalDelete(file);
        }
      }),
    );
    this.registerEvent(
      this.app.vault.on("rename", (file, oldPath) => {
        if (file instanceof TFile) {
          void this.handleLocalRename(file, oldPath);
        }
      }),
    );

    this.registerInterval(
      window.setInterval(() => this.journal.evictOlderThan(5 * 60_000), 60_000) as unknown as number,
    );

    this.registerView(VIEW_TYPE_STATUS, (leaf) => new StatusView(leaf, this));
    this.statusBarItem = this.addStatusBarItem();
    this.statusBarItem.addClass("stoneintelligence-status-bar");
    this.statusBarItem.onclick = () => void this.activateStatusView();
    this.updateStatusBar();
    this.registerInterval(window.setInterval(() => this.updateStatusBar(), 2000) as unknown as number);

    this.addCommand({
      id: "stoneintelligence-show-status",
      name: "Status anzeigen",
      callback: () => void this.activateStatusView(),
    });
    this.addCommand({
      id: "stoneintelligence-login",
      name: "Jetzt einloggen",
      callback: async () => {
        try {
          await this.login();
        } catch (error) {
          new Notice(`StoneIntelligence: Login fehlgeschlagen - ${(error as Error).message}`);
        }
      },
    });
    this.addCommand({
      id: "stoneintelligence-resync-all",
      name: "Alle Notizen neu synchronisieren",
      callback: async () => {
        for (const path of [...this.sessions.keys()]) {
          this.stopSync(path);
        }
        await this.syncAllNotes();
        new Notice("StoneIntelligence: Neu synchronisiert.");
      },
    });
    this.addCommand({
      id: "stoneintelligence-resync-active",
      name: "Aktuelle Notiz neu verbinden",
      checkCallback: (checking) => {
        const file = this.app.workspace.getActiveFile();
        const isTrackableNote = file instanceof TFile && file.extension === "md";
        if (checking) {
          return isTrackableNote;
        }
        if (file) {
          this.stopSync(file.path);
          void this.startSync(file, true);
          new Notice(`StoneIntelligence: "${file.path}" wird neu verbunden.`);
        }
        return true;
      },
    });

    this.app.workspace.onLayoutReady(() => {
      void this.syncAllNotes();
    });
  }

  onunload(): void {
    for (const path of [...this.sessions.keys()]) {
      this.stopSync(path);
    }
    // OHNE das blieb der physische Socket offen, wenn beim Unload gerade null Notizen aktiv
    // gejoint waren (`leave()` schliesst ihn nur, wenn `virtualSockets` dadurch leer wird UND
    // ueberhaupt eine Notiz aktiv war - der Transport selbst kannte "Plugin wird entladen" nicht,
    // s. Codex-Verifikationsreview des Auth-Teardown-Fixes).
    this.transport?.destroy();
    this.transport = null;
  }

  async activateStatusView(): Promise<void> {
    const { workspace } = this.app;
    let leaf = workspace.getLeavesOfType(VIEW_TYPE_STATUS)[0];
    if (!leaf) {
      leaf = workspace.getRightLeaf(false) ?? workspace.getLeaf(true);
      await leaf.setViewState({ type: VIEW_TYPE_STATUS, active: true });
    }
    workspace.revealLeaf(leaf);
  }

  isLoggedIn(): boolean {
    return this.settings.tokens !== null;
  }

  getVaultId(): string {
    return this.settings.vaultId;
  }

  getSyncSessions(): SessionSnapshot[] {
    return [...this.sessions.values()].map((session) => ({ path: session.path, status: session.client.status }));
  }

  private updateStatusBar(): void {
    const sessions = [...this.sessions.values()];
    const connected = sessions.filter((s) => s.client.status === "connected").length;
    const errored = sessions.filter((s) => s.client.status === "error").length;

    let text: string;
    if (!this.isLoggedIn()) {
      text = "○ StoneIntelligence: nicht angemeldet";
    } else if (errored > 0) {
      text = `⚠ StoneIntelligence: ${errored} Fehler`;
    } else if (sessions.length === 0) {
      text = "○ StoneIntelligence: keine Notizen";
    } else {
      text = `● StoneIntelligence: ${connected}/${sessions.length}`;
    }
    this.statusBarItem.setText(text);
  }

  private async syncAllNotes(): Promise<void> {
    if (!this.settings.vaultId) {
      return;
    }
    try {
      await this.reconcileMissingNotesFromServer();
    } catch (error) {
      // Reconciliation ist ein Download-Bonus, kein Muss - ein Fehler hier (z. B. Netzwerk) soll
      // nicht verhindern, dass lokal bereits vorhandene Notizen weiterhin synchronisiert werden.
      console.error("StoneIntelligence: Reconciliation fehlgeschlagen", error);
    }
    for (const file of this.app.vault.getMarkdownFiles()) {
      try {
        await this.startSync(file);
      } catch (error) {
        // Ein einzelner fehlgeschlagener Start (z. B. Rate-Limit trotz Retry ausgeschoepft) darf
        // den restlichen Vault-Sync nicht abbrechen - sonst wuerden alle folgenden Notizen
        // ebenfalls nie synchronisiert, nur weil eine einzelne frueh im Durchlauf hakt.
        console.error(`StoneIntelligence: Sync fuer "${file.path}" fehlgeschlagen`, error);
      }
    }
  }

  /**
   * Laedt Notizen herunter, die auf dem Server existieren, aber lokal (noch) fehlen - der Fall,
   * der bisher komplett unbehandelt war: ein neues/leeres Vault auf einem zweiten Geraet bekam
   * NIE etwas heruntergeladen, weil `syncAllNotes()` nur ueber bereits lokal vorhandene Dateien
   * iterierte. Legt fuer jede fehlende Notiz eine leere lokale Platzhalterdatei an und startet
   * ihren Sync SOFORT UND EINZELN (nicht dem asynchronen `create`-Event ueberlassen) - bei
   * Vaults mit vielen fehlenden Notizen (live beobachtet: 177) wuerden sonst bis zu 177
   * WebSocket-Verbindungsversuche quasi gleichzeitig anlaufen; auf Mobile werden die vom
   * OS/der WebView angedrosselt/gequeued, wodurch das kurzlebige Sync-Ticket (30s TTL) laengst
   * abgelaufen ist, bevor der Handshake ueberhaupt drankommt - jede so betroffene Notiz landete
   * dauerhaft auf "Fehler". Sequentiell (eine Notiz nach der anderen fertig verbunden) bleibt
   * langsamer, aber zuverlaessig.
   */
  private async reconcileMissingNotesFromServer(): Promise<void> {
    const localPaths = new Set(this.app.vault.getMarkdownFiles().map((f) => f.path));
    const serverNotes = await this.noteApiClient.listAllNotes(this.settings.vaultId);

    for (const note of serverNotes) {
      if (localPaths.has(note.path)) {
        continue;
      }
      // NoteId VOR dem Anlegen der Datei eintragen: der `create`-Event-Handler ruft ebenfalls
      // `startSync` auf (zusaetzlich zu unserem expliziten Aufruf unten) - dessen `ensureNoteId`
      // wuerde sonst eine ZWEITE Note fuer denselben Pfad anlegen (Fehlerklasse 5) statt die
      // bereits vorhandene Server-Note zu uebernehmen.
      this.settings.noteIds[note.path] = note.id;
      await this.saveSettings();

      const folderPath = note.path.split("/").slice(0, -1).join("/");
      if (folderPath && !this.app.vault.getAbstractFileByPath(folderPath)) {
        await this.app.vault.createFolder(folderPath).catch(() => {
          // Race mit einer anderen gerade heruntergeladenen Notiz im selben Ordner - harmlos.
        });
      }
      this.reconcilingPaths.add(note.path);
      try {
        const created = await this.app.vault.create(note.path, "");
        await this.startSync(created);
      } catch (error) {
        console.error(`StoneIntelligence: Download von "${note.path}" fehlgeschlagen`, error);
      } finally {
        this.reconcilingPaths.delete(note.path);
      }
    }
  }

  private async ensureNoteId(file: TFile): Promise<string> {
    const existing = this.settings.noteIds[file.path];
    if (existing) {
      return existing;
    }
    const noteId = await this.noteApiClient.createNote(this.settings.vaultId, file.path, 1);
    this.settings.noteIds[file.path] = noteId;
    await this.saveSettings();
    return noteId;
  }

  /**
   * Holt die EINE geteilte Sync-Verbindung fuer diesen Vault (erstellt sie beim ersten Aufruf,
   * alle weiteren Aufrufe bekommen dieselbe Instanz zurueck) - der Kern der Multiplexing-
   * Umstellung: vorher hatte jede Notiz ihre eigene WebSocket-Verbindung samt eigenem Ticket.
   * Der Transport selbst holt sich bei JEDEM (Re-)Connect ueber `issueFreshWsUrl` ein frisches
   * Ticket - Tickets sind Single-Use, ein Reconnect mit der urspruenglichen URL waere ein
   * bereits verbrauchtes Ticket und scheiterte garantiert mit 403 (live beobachtet).
   */
  private ensureTransport(): MultiplexedTransport {
    if (!this.transport) {
      this.transport = new MultiplexedTransport(() => this.issueFreshWsUrl(), (url) => new WebSocket(url));
    }
    return this.transport;
  }

  private async issueFreshWsUrl(): Promise<string> {
    const ticketClient = new TicketClient(this.settings.platformApiUrl, this.getAccessToken);
    const ticket = await ticketClient.issueTicket(this.settings.vaultId);
    return `${this.settings.platformWsUrl}/ws/sync?ticket=${encodeURIComponent(ticket.token)}`;
  }

  /**
   * Wartet, bis DIESE Notiz tatsaechlich gejoint ist (nicht nur registriert) - bei einer
   * geteilten, gequeuten Verbindung kann das je nach Position in der Warteschlange dauern (s.
   * `multiplexedTransport.ts`). Ohne dieses Warten wuerde die anschliessende Catchup-Gnadenfrist
   * in `doStartSync` viel zu frueh ablaufen, bevor die Notiz ueberhaupt gejoint wurde.
   *
   * <p>Gibt `false` zurueck, wenn `timeoutMs` ohne echtes "connected" verstreicht - der Aufrufer
   * (`mergeInitialContent`) MUSS das als "noch nicht sicher verbunden" behandeln, NIE als Erfolg
   * (ehemals ein P1-Bug: der Timeout-Pfad liess "unbekannt/nicht verbunden" faelschlich wie
   * "Server ist leer" aussehen, s. docs/sync-comparison-review-2026-09-18.md).
   */
  private awaitConnected(client: SyncClient, timeoutMs = 30_000): Promise<boolean> {
    if (client.status === "connected") {
      return Promise.resolve(true);
    }
    return new Promise((resolve) => {
      const previous = client.onStatusChange;
      const timeout = window.setTimeout(() => {
        client.onStatusChange = previous;
        resolve(false);
      }, timeoutMs);
      client.onStatusChange = (status) => {
        previous?.(status);
        if (status === "connected") {
          window.clearTimeout(timeout);
          client.onStatusChange = previous;
          resolve(true);
        }
      };
    });
  }

  /**
   * Wartet auf das explizite Catchup-Abschlusssignal des Servers (s. `SyncFrame.TYPE_CATCHUP_
   * COMPLETE`) statt auf eine fixe Gnadenfrist zu raten. `timeoutMs` bleibt als reines
   * Sicherheitsnetz (z. B. gegen einen sehr alten Server ohne dieses Signal oder ein verlorenes
   * Frame) - im Normalfall loest das Signal selbst lange vorher auf.
   *
   * <p>Gibt `false` zurueck, wenn der Timeout OHNE das Signal verstreicht. Der Aufrufer darf das
   * NIE als "Server ist leer" interpretieren - ein leeres Y.Text bei Timeout kann genausogut
   * bedeuten "die Historie ist einfach noch unterwegs", exakt der Fehlschluss, den dieses Signal
   * eigentlich verhindern sollte (ehemals ein P1-Bug, s. docs/sync-comparison-review-2026-09-18.md).
   */
  private awaitCatchupComplete(client: SyncClient, timeoutMs = 30_000): Promise<boolean> {
    return new Promise((resolve) => {
      const previous = client.onCatchupComplete;
      const timeout = window.setTimeout(() => {
        client.onCatchupComplete = previous;
        resolve(false);
      }, timeoutMs);
      client.onCatchupComplete = () => {
        previous?.();
        window.clearTimeout(timeout);
        client.onCatchupComplete = previous;
        resolve(true);
      };
    });
  }

  private async startSync(file: TFile, priority = false): Promise<void> {
    if (this.sessions.has(file.path) || this.startingPaths.has(file.path) || !this.settings.vaultId) {
      return;
    }
    this.startingPaths.add(file.path);
    try {
      await this.doStartSync(file, priority);
    } finally {
      this.startingPaths.delete(file.path);
    }
  }

  /**
   * Registriert die Session SOFORT (nicht erst nach vollstaendigem Verbindungsaufbau) und laesst
   * das eigentliche Verbinden+Content-Merge im Hintergrund laufen (s. `mergeInitialContent`).
   *
   * <p>Fruehere Version wartete HIER (blockierend) bis zu 30s pro Notiz auf die Verbindung, BEVOR
   * sie zur naechsten Notiz weiterging - `syncAllNotes()`/`reconcileMissingNotesFromServer()`
   * rufen `startSync` aber SEQUENTIELL fuer jede Notiz auf. Bei einem groesseren Vault (100+
   * Notizen) UND einer gerade langsamen/gestoerten Verbindung summierte sich das zu vielen
   * Minuten, in denen scheinbar ueberhaupt nichts passierte (kein neuer Netzwerk-Request, keine
   * Konsolen-Ausgabe) - live beobachtet nach einem manuellen "Alle Notizen neu synchronisieren".
   * Das eigene Staffeln der Verbindungen ist ohnehin schon Aufgabe von
   * `MultiplexedTransport.joinStaggerMs` - diese zusaetzliche, sequentielle Blockade hier war nur
   * doppelte, schaedliche Drosselung obendrauf.
   */
  private async doStartSync(file: TFile, priority: boolean): Promise<void> {
    const noteId = await this.ensureNoteId(file);
    const transport = this.ensureTransport();

    const doc = new Y.Doc();
    const text = doc.getText("content");

    // WICHTIG: Client zuerst verbinden, DANACH erst lokalen Inhalt einspielen. SyncClients
    // Update-Listener wird im Konstruktor registriert - wuerde man den Inhalt vorher einfuegen,
    // wuerde das erzeugte Yjs-Update verpuffen (kein Listener vorhanden), und die Notiz wuerde
    // nie zum Server uebertragen werden. Der URL-Parameter ist fuer die geteilte Verbindung
    // irrelevant (SyncClient reicht ihn nur an die Factory durch) - die Factory ignoriert ihn und
    // schliesst stattdessen ueber `noteId`/`priority` auf den passenden virtuellen Kanal.
    const client = new SyncClient("", () => transport.createVirtualSocket(noteId, { priority }), doc);
    client.onNoteDeleted = () => this.handleRemoteNoteDeleted(file.path);
    client.connect();

    const session: NoteSyncSession = {
      path: file.path,
      noteId,
      client,
      unbindDocObserver: () => text.unobserve(observer),
      hasLiveEditorBinding: false,
    };

    const observer = (): void => {
      if (session.hasLiveEditorBinding) {
        // yCollab haelt den Editor bereits zeichengenau synchron, Obsidians eigenes Autosave
        // uebernimmt das Schreiben auf die Datei - der grobe Volltext-Pfad wuerde nur
        // redundant/konfliktaer dieselbe Aenderung ein zweites Mal auf die Datei schreiben.
        return;
      }
      void this.applyRemoteContentToFile(file, text.toString());
    };
    text.observe(observer);

    this.sessions.set(file.path, session);

    void this.mergeInitialContent(file, client, doc, text);
  }

  /**
   * Wartet im Hintergrund auf den tatsaechlichen Verbindungsaufbau (bei einer gestaffelten
   * Warteschlange kann das dauern), dann auf eine kurze Catchup-Gnadenfrist, und entscheidet erst
   * DANACH, ob lokaler oder Server-Inhalt gewinnt. Blockiert bewusst NICHT den Aufrufer von
   * `doStartSync` - s. dessen Klassendoc.
   *
   * <p>Ein Timeout in {@link awaitConnected}/{@link awaitCatchupComplete} ist KEIN Erfolg -
   * bricht die Funktion ohne jede Seed-Entscheidung ab und versucht es erneut, solange diese
   * Session noch die aktuelle fuer `file.path` ist (sonst wurde sie zwischenzeitlich per
   * `stopSync`/Neustart ersetzt oder beendet, ein weiterer Versuch waere sinnlos/falsch
   * zugeordnet). `MultiplexedTransport` verbindet ohnehin automatisch neu - dieser Loop nutzt
   * genau diese naechste Gelegenheit, statt selbst zu raten, wann "leer" wirklich leer bedeutet
   * (ehemals ein P1-Bug: Timeout wurde wie ein bestaetigt leerer Server behandelt, s.
   * docs/sync-comparison-review-2026-09-18.md).
   */
  private async mergeInitialContent(file: TFile, client: SyncClient, doc: Y.Doc, text: Y.Text): Promise<void> {
    while (this.sessions.get(file.path)?.client === client) {
      const connected = await this.awaitConnected(client);
      if (!connected) {
        continue;
      }

      // Wartet auf das explizite Server-Signal "Catchup abgeschlossen" (s. SyncFrame.
      // TYPE_CATCHUP_COMPLETE), statt wie frueher eine fixe Gnadenfrist zu raten - die war unter
      // Last (viele/grosse Notizen, gestaffelte Joins auf derselben geteilten Verbindung)
      // nachweislich zu kurz: der lokale Dateiinhalt wurde dann zusaetzlich zum inzwischen doch
      // noch eingetroffenen Server-Inhalt eingespielt (additiv im CRDT, kein "letzter gewinnt") -
      // live beobachtet als verdreifachter Notizinhalt nach mehreren Reconnect-Zyklen.
      const caughtUp = await this.awaitCatchupComplete(client);
      if (!caughtUp) {
        continue;
      }

      const initialContent = await this.app.vault.read(file);
      if (text.length === 0 && initialContent.length > 0) {
        // Kein Server-/Catchup-Inhalt eingetroffen (Y.Text ist leer) - diese Notiz hat noch keine
        // Server-Historie, lokaler Inhalt ist die Wahrheit und wird eingespielt.
        doc.transact(() => {
          text.insert(0, initialContent);
        });
      } else if (text.length > 0 && text.toString() !== initialContent) {
        // Catchup hat Server-Inhalt geliefert, der vom lokalen abweicht - das ist der Normalfall
        // beim ERSTEN Sync einer via reconcileMissingNotesFromServer() heruntergeladenen Notiz
        // (lokal nur ein leerer Platzhalter). Server-Stand gewinnt und wird in die Datei geschrieben.
        await this.applyRemoteContentToFile(file, text.toString());
      }
      return;
    }
  }

  /**
   * Bindet (oder loest) das echte CodeMirror-6-Live-Binding fuer die gerade geoeffnete Notiz.
   * Hintergrund-Notes bleiben beim Volltext-Sync - nur der aktive Editor bekommt yCollab.
   */
  private async updateLiveEditorBinding(file: TFile | null): Promise<void> {
    const generation = ++this.liveBindGeneration;
    const view = this.activeEditorView();

    if (this.liveBoundPath) {
      const previous = this.sessions.get(this.liveBoundPath);
      if (previous) {
        previous.hasLiveEditorBinding = false;
      }
      this.liveBoundPath = null;
    }

    if (!view) {
      return;
    }
    if (!file) {
      view.dispatch({ effects: this.liveBindingCompartment.reconfigure([]) });
      return;
    }

    await this.startSync(file, true);
    if (generation !== this.liveBindGeneration) {
      // Ein neuerer Aufruf (ein weiterer Datei-/Ansichtswechsel waehrend dieses Awaits) hat die
      // Zustaendigkeit fuer `liveBoundPath`/den Compartment bereits uebernommen oder wird das
      // gleich tun - hier NICHTS mehr anfassen, sonst ueberschreibt dieser veraltete Aufruf dessen
      // korrektes Ergebnis mit dem FALSCHEN Y.Text fuer den inzwischen angezeigten View.
      return;
    }
    const session = this.sessions.get(file.path);
    if (!session) {
      view.dispatch({ effects: this.liveBindingCompartment.reconfigure([]) });
      return;
    }

    session.hasLiveEditorBinding = true;
    this.liveBoundPath = file.path;
    view.dispatch({
      effects: this.liveBindingCompartment.reconfigure(
        yCollab(session.client.doc.getText("content"), session.client.awareness),
      ),
    });
  }

  /**
   * Obsidians `Editor`-Wrapper legt die zugrundeliegende CodeMirror-6-`EditorView` nicht in der
   * offiziellen API offen - `editor.cm` ist der in der Community etablierte, aber inoffizielle
   * Zugriffspfad (ueblich bei Plugins, die CM6-Extensions anbinden).
   */
  private activeEditorView(): EditorView | undefined {
    const editor = this.app.workspace.activeEditor?.editor as unknown as { cm?: EditorView } | undefined;
    return editor?.cm;
  }

  private stopSync(path: string): void {
    const session = this.sessions.get(path);
    if (session) {
      session.unbindDocObserver();
      session.client.disconnect();
      this.sessions.delete(path);
    }
  }

  /**
   * Der Server hat die Verbindung mit Close-Code 4404 getrennt (ein ANDERER Client hat die
   * Note geloescht). Die lokale Datei wird bewusst NICHT automatisch geloescht - ein Server-
   * Signal ohne Korrelation zu einer eigenen Operation ist keine ausreichende Grundlage fuer
   * eine irreversible lokale Aktion. Stattdessen: Sync stoppen, NoteId-Zuordnung verwerfen
   * (ein weiterer Edit wuerde sonst versuchen, eine bereits geloeschte Note anzusprechen), und
   * den Nutzer informieren.
   */
  private async handleRemoteNoteDeleted(path: string): Promise<void> {
    this.stopSync(path);
    delete this.settings.noteIds[path];
    await this.saveSettings();
    new Notice(`StoneIntelligence: "${path}" wurde auf einem anderen Geraet geloescht und nicht mehr synchronisiert.`);
  }

  /** Ein remote empfangenes Yjs-Update wurde bereits in den Y.Text gemerged - jetzt auf die Datei anwenden. */
  private async applyRemoteContentToFile(file: TFile, newContent: string): Promise<void> {
    const operationId = crypto.randomUUID();
    const fingerprint = `modify:${file.path}:${hashContent(newContent)}`;
    this.journal.registerSelfInitiated(operationId, [fingerprint]);
    await this.app.vault.modify(file, newContent);
  }

  /** Fehlerklasse 1: unterscheidet die eigene, gerade zurueckgeschriebene Aenderung von echten Nutzeredits. */
  private async handleLocalModify(file: TFile): Promise<void> {
    const session = this.sessions.get(file.path);
    if (!session) {
      // Neue oder bisher ungetrackte Datei (z. B. Plugin-Start nach der ersten Aenderung) -
      // startSync liest den aktuellen Inhalt bereits ein, kein separater Merge noetig. Prioritaet,
      // da eine gerade lokal bearbeitete Datei per Definition die aktive Notiz ist.
      void this.startSync(file, true);
      return;
    }
    if (session.hasLiveEditorBinding) {
      // yCollab schreibt Tastatureingaben bereits direkt ins Y.Text - dieses modify-Event ist
      // nur Obsidians eigenes Autosave, das denselben bereits synchronisierten Inhalt persistiert.
      return;
    }

    const content = await this.app.vault.read(file);
    const fingerprint = `modify:${file.path}:${hashContent(content)}`;
    if (this.journal.correlate(fingerprint)) {
      return;
    }

    const text = session.client.doc.getText("content");
    if (text.toString() === content) {
      return;
    }
    session.client.doc.transact(() => {
      text.delete(0, text.length);
      text.insert(0, content);
    });
  }

  private async handleLocalDelete(file: TFile): Promise<void> {
    const noteId = this.settings.noteIds[file.path];
    this.stopSync(file.path);
    if (!noteId || !this.settings.vaultId) {
      return;
    }

    const operationId = crypto.randomUUID();
    await this.noteApiClient.deleteNote(this.settings.vaultId, noteId, operationId);
    delete this.settings.noteIds[file.path];
    await this.saveSettings();
  }

  private async handleLocalRename(file: TFile, oldPath: string): Promise<void> {
    const noteId = this.settings.noteIds[oldPath];
    if (!noteId || !this.settings.vaultId) {
      // Datei war noch nicht getrackt (z. B. Umbenennung direkt nach App-Start, bevor der
      // initiale Vault-Sync durchlief) - unter dem neuen Pfad frisch aufnehmen.
      void this.startSync(file);
      return;
    }

    const session = this.sessions.get(oldPath);
    this.sessions.delete(oldPath);
    delete this.settings.noteIds[oldPath];
    this.settings.noteIds[file.path] = noteId;
    await this.saveSettings();

    if (session) {
      // Dieselbe WebSocket-Verbindung/NoteId bleibt bestehen, nur der lokale Pfad-Schluessel
      // aendert sich - kein Re-Connect noetig, die NoteId ist stabil (Fehlerklasse 5).
      session.path = file.path;
      this.sessions.set(file.path, session);
    }

    await this.noteApiClient.renameNote(this.settings.vaultId, noteId, file.path);
  }

  async loadSettings(): Promise<void> {
    this.settings = Object.assign({}, DEFAULT_SETTINGS, await this.loadData());
  }

  async saveSettings(): Promise<void> {
    await this.saveData(this.settings);
    // ensures NoteApiClient/AuthClient nachfolgende Reconnects die aktuelle BaseUrl/OIDC-Config
    // nutzen; der Token-Endpoint-Cache wird invalidiert, falls sich die Issuer-URL geaendert hat.
    this.authClient = new AuthentikAuthClient({
      issuerUrl: this.settings.oidcIssuerUrl, clientId: this.settings.oidcClientId,
    });
    this.tokenEndpoint = null;
    this.noteApiClient = new NoteApiClient(this.settings.platformApiUrl, this.getAccessToken);
  }

  /** Legt einen neuen Vault an und uebernimmt dessen ID direkt in die Einstellungen. */
  async createVault(name: string): Promise<void> {
    const vaultId = await this.noteApiClient.createVault(name);
    this.settings.vaultId = vaultId;
    await this.saveSettings();
  }

  /** Serialisiert die reine Verbindungskonfiguration (KEINE Tokens/NoteId-Cache) als JSON. */
  connectionConfigJson(): string {
    const config: ConnectionConfig = {
      platformApiUrl: this.settings.platformApiUrl,
      platformWsUrl: this.settings.platformWsUrl,
      vaultId: this.settings.vaultId,
      oidcIssuerUrl: this.settings.oidcIssuerUrl,
      oidcClientId: this.settings.oidcClientId,
    };
    return JSON.stringify(config, null, 2);
  }

  /** Uebernimmt eine per {@link connectionConfigJson} exportierte Konfiguration - ein Klick statt fuenf Felder. */
  async applyConnectionConfig(json: string): Promise<void> {
    const parsed = JSON.parse(json) as Partial<ConnectionConfig>;
    if (typeof parsed.platformApiUrl === "string") this.settings.platformApiUrl = parsed.platformApiUrl;
    if (typeof parsed.platformWsUrl === "string") this.settings.platformWsUrl = parsed.platformWsUrl;
    if (typeof parsed.vaultId === "string") this.settings.vaultId = parsed.vaultId;
    if (typeof parsed.oidcIssuerUrl === "string") this.settings.oidcIssuerUrl = parsed.oidcIssuerUrl;
    if (typeof parsed.oidcClientId === "string") this.settings.oidcClientId = parsed.oidcClientId;
    await this.saveSettings();
  }
}

class StoneIntelligenceSettingTab extends PluginSettingTab {
  constructor(
    app: App,
    private readonly plugin: StoneIntelligencePlugin,
  ) {
    super(app, plugin);
  }

  display(): void {
    const { containerEl } = this;
    containerEl.empty();
    containerEl.createEl("h2", { text: "StoneIntelligence" });

    new Setting(containerEl)
      .setName("Platform API URL")
      .setDesc("z. B. https://sync.example.com")
      .addText((text) =>
        text.setValue(this.plugin.settings.platformApiUrl).onChange(async (value) => {
          this.plugin.settings.platformApiUrl = value;
          await this.plugin.saveSettings();
        }),
      );

    new Setting(containerEl)
      .setName("Platform WebSocket URL")
      .setDesc("z. B. wss://sync.example.com")
      .addText((text) =>
        text.setValue(this.plugin.settings.platformWsUrl).onChange(async (value) => {
          this.plugin.settings.platformWsUrl = value;
          await this.plugin.saveSettings();
        }),
      );

    new Setting(containerEl).setName("Vault-ID").addText((text) =>
      text.setValue(this.plugin.settings.vaultId).onChange(async (value) => {
        this.plugin.settings.vaultId = value;
        await this.plugin.saveSettings();
      }),
    );

    let newVaultName = "";
    new Setting(containerEl)
      .setName("Neuen Vault anlegen")
      .setDesc("Braucht eine gueltige Anmeldung (s. u.) - der anlegende Account bekommt automatisch volle Rechte im neuen Vault.")
      .addText((text) => text.setPlaceholder("Name des Vaults").onChange((value) => (newVaultName = value)))
      .addButton((button) =>
        button.setButtonText("Anlegen").onClick(async () => {
          if (!newVaultName.trim()) {
            new Notice("StoneIntelligence: Bitte einen Vault-Namen eingeben.");
            return;
          }
          try {
            await this.plugin.createVault(newVaultName.trim());
            new Notice("StoneIntelligence: Vault angelegt und Vault-ID uebernommen.");
            this.display();
          } catch (error) {
            new Notice(`StoneIntelligence: Vault anlegen fehlgeschlagen - ${(error as Error).message}`);
          }
        }),
      );

    new Setting(containerEl)
      .setName("OIDC Issuer URL")
      .setDesc("z. B. https://portal.tstieh.de/application/o/stoneintelligence/")
      .addText((text) =>
        text.setValue(this.plugin.settings.oidcIssuerUrl).onChange(async (value) => {
          this.plugin.settings.oidcIssuerUrl = value;
          await this.plugin.saveSettings();
        }),
      );

    new Setting(containerEl)
      .setName("OIDC Client-ID")
      .setDesc("Public Client (PKCE), kein Client-Secret noetig.")
      .addText((text) =>
        text.setValue(this.plugin.settings.oidcClientId).onChange(async (value) => {
          this.plugin.settings.oidcClientId = value;
          await this.plugin.saveSettings();
        }),
      );

    new Setting(containerEl)
      .setName("Anmeldung")
      .setDesc(this.plugin.settings.tokens ? "Angemeldet." : "Nicht angemeldet.")
      .addButton((button) =>
        button.setButtonText("Login").onClick(async () => {
          try {
            await this.plugin.login();
            this.display();
          } catch (error) {
            new Notice(`StoneIntelligence: Login fehlgeschlagen - ${(error as Error).message}`);
          }
        }),
      )
      .addButton((button) =>
        button.setButtonText("Logout").onClick(async () => {
          await this.plugin.logout();
          this.display();
        }),
      );

    containerEl.createEl("h3", { text: "Verbindungskonfiguration teilen" });
    containerEl.createEl("p", {
      text: "Server-URL, Vault-ID und OIDC-Angaben auf einen Schlag zwischen Geraeten uebertragen, "
        + "statt jedes Feld einzeln abzutippen (enthaelt KEINE Login-Tokens).",
    });

    new Setting(containerEl)
      .setName("Verbindungsdaten kopieren")
      .addButton((button) =>
        button.setButtonText("In Zwischenablage kopieren").onClick(async () => {
          await navigator.clipboard.writeText(this.plugin.connectionConfigJson());
          new Notice("StoneIntelligence: Verbindungsdaten kopiert.");
        }),
      );

    new Setting(containerEl)
      .setName("Verbindungsdaten einfuegen")
      .addButton((button) =>
        button.setButtonText("Aus Zwischenablage uebernehmen").onClick(async () => {
          try {
            const json = await navigator.clipboard.readText();
            await this.plugin.applyConnectionConfig(json);
            new Notice("StoneIntelligence: Verbindungsdaten uebernommen.");
            this.display();
          } catch (error) {
            new Notice(`StoneIntelligence: Verbindungsdaten konnten nicht uebernommen werden - ${(error as Error).message}`);
          }
        }),
      );
  }
}
