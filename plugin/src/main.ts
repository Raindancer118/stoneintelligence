import { Compartment } from "@codemirror/state";
import type { EditorView } from "@codemirror/view";
import { App, MarkdownView, Notice, Platform, Plugin, PluginSettingTab, Setting, TFile } from "obsidian";
import { yCollab } from "y-codemirror.next";
import * as Y from "yjs";
import { type SessionSnapshot, StatusView, VIEW_TYPE_STATUS } from "./StatusView";
import { actorDisplayNameFromAccessToken, displayNameFromClaims, pickUserColor } from "./sync/actorIdentity";
import { AuthentikAuthClient, TokenRefreshRejectedError, type StoredTokens } from "./sync/AuthentikAuthClient";
import { dedupeInFlight } from "./sync/dedupeInFlight";
import { awaitDesktopRedirectCode, DESKTOP_REDIRECT_URI, openAuthorizationUrlDesktop } from "./sync/desktopAuthRedirect";
import {
  handleMobileRedirectCallback, MOBILE_REDIRECT_ACTION, MOBILE_REDIRECT_URI,
  openAuthorizationUrlMobile, type PendingAuthCallback,
} from "./sync/mobileAuthRedirect";
import { MultiplexedTransport } from "./sync/multiplexedTransport";
import { NoteApiClient } from "./sync/NoteApiClient";
import { planEditorBindings } from "./sync/editorBindingPlan";
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
  /**
   * Zwischengespeicherter Anzeigename aus Authentiks `userinfo` - Label des eigenen Cursors bei
   * allen anderen. Bewusst persistiert: er wird einmal nach dem Login aufgeloest, damit das
   * Anlegen einer Sync-Session keinen Netzwerkaufruf braucht und auch dann einen Namen hat, wenn
   * das Access-Token gerade abgelaufen/erneuert wird (genau dann stand vorher "Unbekannt").
   */
  displayName: string | null;
}

/** Felder, die eine "Verbindungskonfiguration" ausmachen - alles ausser Tokens/NoteId-Cache. */
type ConnectionConfig = Pick<
  StoneIntelligenceSettings, "platformApiUrl" | "platformWsUrl" | "vaultId" | "oidcIssuerUrl" | "oidcClientId"
>;

/**
 * Architekturentscheidung 2026-09-18 (auf Toms ausdruecklichen Wunsch, nach Vorbild des
 * Vorgaenger-Projekts `stonesync`, das NIE das ganze Vault live hielt, sondern nur gerade
 * geoeffnete Notizen): die geteilte WebSocket-Verbindung traegt nur die tatsaechlich GEOEFFNETEN
 * Notizen live (Zeichen-Sync + Cursor-Presence) - praktisch ein bis drei Panes, und weiterhin nur
 * EINE physische Verbindung. Alle anderen bekannten Notizen werden periodisch per kurzem
 * Verbindungs-Connect-Catchup-Leave-Zyklus abgeglichen (`syncNoteInBackground`) - dieselbe
 * Yjs-Sync-Maschinerie wie die aktive Notiz, nur nicht dauerhaft gejoint. Kein Server-Umbau
 * noetig: der Server unterscheidet ohnehin nicht zwischen einem lang- und einem kurzlebigen Join.
 */
const BACKGROUND_POLL_INTERVAL_MS = 90_000;
/** Kurzer Timeout je Hintergrund-Notiz - ein einzelner haengender Versuch darf den gesamten Poll-Durchlauf nicht blockieren; naechster Versuch folgt beim naechsten Intervall-Tick. */
const BACKGROUND_SYNC_TIMEOUT_MS = 8_000;
/** Abstand zwischen zwei Bindungsversuchen, wenn Obsidians CM6-View noch nicht bereitsteht. */
const BINDING_RETRY_DELAY_MS = 300;
/** Obergrenze der Wiederholungen (≈ 6s), damit ein dauerhaft view-loses Pane nicht endlos pollt. */
const BINDING_RETRY_LIMIT = 20;

const DEFAULT_SETTINGS: StoneIntelligenceSettings = {
  platformApiUrl: "http://localhost:8080",
  platformWsUrl: "ws://localhost:8080",
  vaultId: "",
  oidcIssuerUrl: "",
  oidcClientId: "",
  tokens: null,
  noteIds: {},
  displayName: null,
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
   * true, waehrend diese Notiz direkt per CodeMirror-6 (y-codemirror.next) an einen Editor
   * gebunden ist - dann uebernehmen yCollab + Obsidians eigenes Autosave die Persistenz
   * zeichengenau, und der grobe Volltext-Bruecken-Pfad (text.observe -> vault.modify /
   * vault.read -> Y.Text-Ersatz) wird fuer diesen Pfad ausgesetzt, um Doppelverarbeitung zu
   * vermeiden.
   */
  hasLiveEditorBinding: boolean;
}

/**
 * Haelt das GESAMTE Vault synchron, aber nur die gerade GEOEFFNETEN Notizen dauerhaft ueber
 * die geteilte WebSocket-Verbindung live - echtes CodeMirror-6-Zeichen-Binding via
 * y-codemirror.next (yCollab), inkl. Cursor-Presence ueber die geteilte Awareness-Instanz von
 * {@link SyncClient}. Alle anderen Notizen werden periodisch per kurzem Connect-Catchup-Leave-
 * Zyklus abgeglichen, s. {@link BACKGROUND_POLL_INTERVAL_MS} und `syncNoteInBackground` (Project.md
 * fuer die volle Historie dieser Entscheidung - Vorgaengerversion hielt das gesamte Vault
 * dauerhaft gejoint).
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
  /**
   * Verhindert ueberlappende `pollBackgroundNotes`-Durchlaeufe: bei einem groesseren Vault (oder
   * einer gerade langsamen Verbindung) kann ein einzelner Durchlauf laenger dauern als
   * `BACKGROUND_POLL_INTERVAL_MS`, `window.setInterval` wartet das aber nicht ab - ohne dieses
   * Flag koennten zwei parallele Durchlaeufe fuer DIESELBE Notiz gleichzeitig
   * `MultiplexedTransport.createVirtualSocket` mit derselben `noteId` aufrufen und sich
   * gegenseitig den virtuellen Kanal wegnehmen.
   */
  private backgroundPollRunning = false;
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
  /**
   * Pfad → die konkrete EditorView-Instanz, an die dieser Pfad gerade tatsaechlich live gebunden
   * ist. Ersetzt das fruehere einzelne `liveBoundPath`: gebunden wird jedes offene Markdown-Pane,
   * nicht nur "das aktive" (s. {@link openMarkdownEditors} fuer die Begruendung). Der Vergleich
   * laeuft ueber Instanz-Identitaet, nicht nur ueber den Pfad - Obsidian verwendet beim Oeffnen
   * einer anderen Datei im selben Pane dieselbe CM6-View weiter.
   */
  private readonly boundViews = new Map<string, EditorView>();
  /** Gesetzt von {@link openMarkdownEditors}, wenn ein offenes Pane seine CM6-View noch nicht hatte. */
  private editorViewPending = false;
  private bindingRetries = 0;
  private bindingRetryTimer: number | null = null;
  /**
   * Monoton wachsender Generation-Zaehler gegen den P1-Fund "Obsidian editor binding can target
   * the wrong file/view" (s. docs/sync-comparison-review-2026-09-18.md): die Bindungs-Events
   * feuern bei schnellem A-zu-B-Wechsel mehrfach ueberlappend und unsequenziert - ohne dieses
   * Gate konnte der spaeter GESTARTETE, aber wegen `startSync`s Await frueher FERTIGE Durchlauf
   * fuer A einen View ueberschreiben, NACHDEM der Durchlauf fuer B bereits korrekt gebunden hatte
   * - der Editor zeigte dann Notiz B an, band aber tatsaechlich an Notiz As Y.Text.
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
        // Aktiv melden, nicht nur die Statusleiste von "angemeldet" auf "nicht angemeldet"
        // umspringen lassen: der Sync steht ab hier vollstaendig still, und ohne Hinweis war der
        // einzige Beleg dafuer eine Fehlermeldung in der Entwicklerkonsole (live so passiert).
        // 0 = bleibt stehen, bis sie weggeklickt wird - eine nach 5s verschwindende Meldung
        // wuerde genau dann uebersehen, wenn sie auftritt (beim Start, vor dem ersten Blick).
        new Notice(
          "StoneIntelligence: Anmeldung abgelaufen, Sync gestoppt. Bitte ueber den Befehl "
            + "\"StoneIntelligence: Anmelden\" (oder die Plugin-Einstellungen) neu anmelden.",
          0,
        );
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
   * Anzeigename fuer den eigenen Cursor: bevorzugt der zwischengespeicherte Wert aus Authentiks
   * `userinfo` (voller Name), sonst der Claim aus dem Access-Token.
   *
   * <p>Der Token-Pfad allein reichte nicht: er wurde beim Anlegen JEDER Session synchron aus
   * `settings.tokens` gelesen - war dort in dem Moment kein Token (z. B. direkt nach einer
   * abgelehnten Token-Erneuerung, oder bevor der erste Login durch war), stand am Cursor
   * dauerhaft "Unbekannt", und zwar bis zum Neuaufbau genau dieser Session.
   */
  private actorDisplayName(): string {
    return this.settings.displayName ?? actorDisplayNameFromAccessToken(this.settings.tokens?.accessToken);
  }

  /**
   * Loest den Anzeigenamen ueber Authentiks `userinfo` auf, speichert ihn und zieht ihn bei
   * bereits laufenden Sessions nach - sonst behielten Sessions, die vor der Aufloesung angelegt
   * wurden, ihr altes Label bis zum naechsten Neuaufbau. Scheitert bewusst leise: ohne Namen
   * greift {@link actorDisplayName} auf den Token-Claim zurueck, der Sync laeuft unveraendert.
   */
  private async refreshDisplayName(): Promise<void> {
    try {
      const discovery = await this.authClient.discover();
      if (!discovery.userinfo_endpoint) {
        return;
      }
      const claims = await this.authClient.fetchUserInfo(discovery.userinfo_endpoint, await this.getAccessToken());
      const name = displayNameFromClaims(claims);
      if (name === "Unbekannt") {
        return;
      }
      this.settings.displayName = name;
      await this.saveData(this.settings);
      for (const session of this.sessions.values()) {
        session.client.awareness.setLocalStateField("user", { name, color: pickUserColor(name) });
      }
    } catch (error) {
      console.debug("StoneIntelligence: Anzeigename konnte nicht aufgeloest werden", error);
    }
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
    await this.refreshDisplayName();
    new Notice("StoneIntelligence: Login erfolgreich.");
  }

  async logout(): Promise<void> {
    this.settings.tokens = null;
    this.settings.displayName = null;
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
    // Drei Events statt nur `file-open`: `file-open` allein feuert nachweislich zu frueh (die
    // Ziel-View existiert dann teils noch nicht bzw. zeigt noch die vorherige Datei),
    // `active-leaf-change` deckt Pane-/Tab-Wechsel ab, `layout-change` das Oeffnen/Schliessen und
    // Teilen von Panes. Alle drei muenden in denselben idempotenten Abgleich - mehrfaches
    // Feuern fuer dasselbe Ergebnis ist folgenlos (s. `planEditorBindings`).
    this.registerEvent(this.app.workspace.on("file-open", () => void this.syncOpenEditorBindings()));
    this.registerEvent(this.app.workspace.on("active-leaf-change", () => void this.syncOpenEditorBindings()));
    this.registerEvent(this.app.workspace.on("layout-change", () => void this.syncOpenEditorBindings()));

    this.registerEvent(
      this.app.vault.on("create", (file) => {
        if (file instanceof TFile && file.extension === "md" && !this.reconcilingPaths.has(file.path)) {
          // Einmaliger sofortiger Abgleich (Initial-Push, falls schon lokaler Inhalt vorhanden
          // ist) statt dauerhaftem Live-Join - wird die Datei direkt danach auch geoeffnet
          // (Obsidians ueblicher "Neue Notiz"-Ablauf), uebernimmt der separate `file-open`-Handler
          // die eigentliche Live-Bindung.
          void this.syncNoteInBackground(file.path, { preferLocal: true });
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
    this.registerInterval(
      window.setInterval(() => void this.pollBackgroundNotes(), BACKGROUND_POLL_INTERVAL_MS) as unknown as number,
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
        for (const [path, view] of [...this.boundViews]) {
          this.detachLiveBinding(path, view);
        }
        await this.syncAllNotes();
        await this.pollBackgroundNotes();
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
          const boundView = this.boundViews.get(file.path);
          if (boundView) {
            this.detachLiveBinding(file.path, boundView);
          } else {
            this.stopSync(file.path);
          }
          void this.syncOpenEditorBindings();
          new Notice(`StoneIntelligence: "${file.path}" wird neu verbunden.`);
        }
        return true;
      },
    });

    this.app.workspace.onLayoutReady(() => {
      // Nachtraeglich fuer bestehende Anmeldungen: wer schon eingeloggt war, als es den
      // zwischengespeicherten Anzeigenamen noch nicht gab, soll ihn bekommen, ohne sich dafuer
      // neu anmelden zu muessen.
      if (this.isLoggedIn() && !this.settings.displayName) {
        void this.refreshDisplayName();
      }
      void this.syncAllNotes();
    });
  }

  onunload(): void {
    window.clearTimeout(this.bindingRetryTimer ?? undefined);
    this.bindingRetryTimer = null;
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

  /**
   * Laeuft beim Plugin-Start einmal durch: laedt fehlende Notizen vom Server herunter und bindet
   * eine bereits offene aktive Notiz sofort live, statt auf den naechsten Poll-Tick zu warten.
   * Der eigentliche Hintergrundabgleich ALLER Notizen laeuft separat und dauerhaft ueber den in
   * `onload` registrierten Intervall-Timer (`pollBackgroundNotes`), s. Klassendoc.
   */
  private async syncAllNotes(): Promise<void> {
    if (!this.settings.vaultId) {
      return;
    }
    try {
      await this.reconcileMissingNotesFromServer();
    } catch (error) {
      // Reconciliation ist ein Download-Bonus, kein Muss - ein Fehler hier (z. B. Netzwerk) soll
      // nicht verhindern, dass die aktive Notiz weiterhin synchronisiert wird.
      console.error("StoneIntelligence: Reconciliation fehlgeschlagen", error);
    }
    await this.syncOpenEditorBindings();
  }

  /**
   * Gleicht ALLE bekannten Notizen AUSSER den gerade live gebundenen ab - ein Durchlauf des
   * periodischen Hintergrund-Timers (`BACKGROUND_POLL_INTERVAL_MS`). Bewusst SEQUENTIELL (eine
   * Notiz nach der anderen fertig abgeglichen, bevor die naechste startet), analog zum bisherigen
   * `syncAllNotes`-Muster - vermeidet unkontrollierten Verbindungs-/Join-Burst auf der geteilten
   * Verbindung bei einem groesseren Vault.
   */
  private async pollBackgroundNotes(): Promise<void> {
    if (!this.settings.vaultId || this.backgroundPollRunning) {
      return;
    }
    this.backgroundPollRunning = true;
    try {
      for (const path of Object.keys(this.settings.noteIds)) {
        if (this.boundViews.has(path)) {
          continue;
        }
        try {
          await this.syncNoteInBackground(path, { preferLocal: false });
        } catch (error) {
          console.error(`StoneIntelligence: Hintergrund-Sync fuer "${path}" fehlgeschlagen`, error);
        }
      }
    } finally {
      this.backgroundPollRunning = false;
    }
  }

  /**
   * Gleicht EINE einzelne Notiz per kurzlebigem Connect-Catchup-Leave-Zyklus ab (s. Klassendoc) -
   * nutzt dieselbe {@link SyncClient}/{@link MultiplexedTransport}-Maschinerie wie die aktive
   * Notiz, verlaesst die Verbindung aber sofort wieder danach, statt dauerhaft gejoint zu bleiben.
   * No-op, wenn diese Notiz bereits live gebunden ist (die kuemmert sich selbst um ihren Stand)
   * oder gerade woanders im Start-Prozess ist.
   *
   * <p>`preferLocal: true` (frisch angelegte/lokal geaenderte Notiz) laesst den soeben bekannten
   * lokalen Inhalt gewinnen, wenn er vom Server-Stand abweicht - das ist genau DIESE Aenderung,
   * die uebertragen werden soll. `preferLocal: false` (periodischer Poll ohne konkreten Anlass)
   * laesst wie bisher den Server gewinnen, sobald er ueberhaupt Inhalt hat (dieselbe Heuristik wie
   * `mergeInitialContent` fuer die aktive Notiz) - ein reiner Zeit-Tick ist kein Beleg dafuer, dass
   * die lokale Datei die neuere ist.
   */
  private async syncNoteInBackground(path: string, options: { preferLocal: boolean }): Promise<void> {
    if (this.sessions.has(path) || this.startingPaths.has(path) || !this.settings.vaultId) {
      return;
    }
    const file = this.app.vault.getAbstractFileByPath(path);
    if (!(file instanceof TFile)) {
      return;
    }

    const noteId = await this.ensureNoteId(file);
    const transport = this.ensureTransport();
    const doc = new Y.Doc();
    const text = doc.getText("content");
    const client = new SyncClient("", () => transport.createVirtualSocket(noteId, { priority: false }), doc);
    client.connect();
    try {
      const connected = await this.awaitConnected(client, BACKGROUND_SYNC_TIMEOUT_MS);
      if (!connected) {
        return;
      }
      const caughtUp = await this.awaitCatchupComplete(client, BACKGROUND_SYNC_TIMEOUT_MS);
      if (!caughtUp) {
        return;
      }

      const localContent = await this.app.vault.read(file);
      const serverContent = text.toString();
      if (serverContent === localContent) {
        return;
      }
      if (options.preferLocal || serverContent.length === 0) {
        doc.transact(() => {
          text.delete(0, text.length);
          text.insert(0, localContent);
        });
      } else {
        await this.applyRemoteContentToFile(file, serverContent);
      }
    } finally {
      client.disconnect();
    }
  }

  /**
   * Laedt Notizen herunter, die auf dem Server existieren, aber lokal (noch) fehlen - der Fall,
   * der sonst komplett unbehandelt waere: ein neues/leeres Vault auf einem zweiten Geraet bekaeme
   * NIE etwas heruntergeladen, weil der Hintergrund-Poll nur ueber bereits BEKANNTE (in
   * `settings.noteIds` eingetragene) Notizen iteriert. Legt fuer jede fehlende Notiz eine leere
   * lokale Platzhalterdatei an und synchronisiert sie SOFORT UND EINZELN (nicht dem naechsten
   * Poll-Tick ueberlassen) - bei Vaults mit vielen fehlenden Notizen (live beobachtet: 177, damals
   * noch mit dauerhaften WS-Joins) blieb ein Burst gleichzeitiger Verbindungsversuche sonst haengen;
   * sequentiell (eine Notiz nach der anderen fertig abgeglichen) bleibt langsamer, aber zuverlaessig.
   */
  private async reconcileMissingNotesFromServer(): Promise<void> {
    const localPaths = new Set(this.app.vault.getMarkdownFiles().map((f) => f.path));
    const serverNotes = await this.noteApiClient.listAllNotes(this.settings.vaultId);

    for (const note of serverNotes) {
      if (localPaths.has(note.path)) {
        continue;
      }
      // NoteId VOR dem Anlegen der Datei eintragen: der `create`-Event-Handler ruft ebenfalls
      // `syncNoteInBackground` auf (zusaetzlich zu unserem expliziten Aufruf unten) - dessen
      // `ensureNoteId` wuerde sonst eine ZWEITE Note fuer denselben Pfad anlegen (Fehlerklasse 5)
      // statt die bereits vorhandene Server-Note zu uebernehmen.
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
        await this.syncNoteInBackground(created.path, { preferLocal: false });
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
    // Identitaet VOR dem Verbinden setzen (Muster aller drei untersuchten Obsidian-Yjs-Plugins:
    // Provider erzeugen -> sofort `user` setzen -> dann verbinden). Andersherum gibt es ein
    // Fenster, in dem bereits Awareness-Verkehr laeuft, waehrend der eigene Zustand noch keine
    // Identitaet traegt - Gegenueber sehen dann einen namen- und farblosen Cursor.
    // Ohne dieses Feld haette y-codemirror.next fuer diesen Client keine Identitaet zum Anzeigen
    // (`state.user` blieb bisher komplett ungesetzt) - Remote-Cursor faellt in diesem Fall auf
    // eine generische, nicht unterscheidbare Standarddarstellung zurueck ("Anonymous", ein
    // einzelnes Blau fuer alle). Name kommt aus demselben `preferred_username`-Claim, den der
    // Server serverseitig als Actor-Identitaet nutzt; Farbe ist deterministisch aus dem Namen
    // abgeleitet (uebernommen aus dem Vorgaenger-Projekt `stonesync`), damit dieselbe Person auf
    // jedem Geraet/in jeder Session dieselbe Cursor-Farbe hat.
    const actorName = this.actorDisplayName();
    client.awareness.setLocalStateField("user", { name: actorName, color: pickUserColor(actorName) });
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
   * Alle tatsaechlich offenen Markdown-Panes samt ihrer CodeMirror-6-`EditorView`.
   *
   * <p>Die fruehere Fassung nutzte `workspace.activeEditor?.editor.cm` und band nur die EINE
   * aktive Notiz. Das war der Grund, warum fremde Cursor live nicht ankamen: beim `file-open`-
   * Ereignis zeigt `activeEditor` haeufig noch nicht auf die gerade geoeffnete Datei, der Zugriff
   * lieferte `undefined`, und die Bindung wurde stillschweigend uebersprungen - kein yCollab,
   * also weder Zeichen-Sync noch Awareness-Cursor, ohne jede Fehlermeldung. Das Vorgaengerprojekt
   * `stonesync` zaehlte stattdessen die offenen Panes auf (SyncManager.openMarkdownEditors) -
   * dieses Muster ist hier uebernommen.
   *
   * <p>Obsidians `Editor`-Wrapper legt die zugrundeliegende `EditorView` nicht in der offiziellen
   * API offen; `editor.cm` ist der in der Community etablierte, aber inoffizielle Zugriffspfad.
   */
  private openMarkdownEditors(): Array<{ file: TFile; view: EditorView }> {
    const result: Array<{ file: TFile; view: EditorView }> = [];
    for (const leaf of this.app.workspace.getLeavesOfType("markdown")) {
      const markdownView = leaf.view;
      if (!(markdownView instanceof MarkdownView)) {
        continue;
      }
      const file = markdownView.file;
      if (!file || file.extension !== "md") {
        continue;
      }
      // Die Vault-Index-Pruefung ist kein Ueberfluss: nach dem Loeschen einer offenen Datei
      // meldet das Leaf kurzzeitig noch eine Stale-TFile. Wuerde man die binden, schriebe der
      // Catchup den alten Inhalt in den noch sichtbaren Editor und Obsidian persistierte ihn
      // zurueck - die geloeschte Datei waere wieder da (im Vorgaengerprojekt live beobachtet).
      if (!(this.app.vault.getAbstractFileByPath(file.path) instanceof TFile)) {
        continue;
      }
      const view = (markdownView.editor as unknown as { cm?: EditorView }).cm;
      if (!view) {
        // Pane existiert, seine CM6-View aber noch nicht - Obsidian baut sie erst kurz NACH dem
        // ausloesenden Workspace-Ereignis auf. Das ist kein Grund aufzugeben (genau daran
        // scheiterte die Bindung bisher stillschweigend), sondern einer, es gleich erneut zu
        // versuchen - s. `scheduleBindingRetry`.
        this.editorViewPending = true;
        continue;
      }
      result.push({ file, view });
    }
    return result;
  }

  /**
   * Gleicht die CodeMirror-6-Live-Bindungen mit den tatsaechlich offenen Panes ab: jede offene
   * Notiz bekommt eine dauerhafte Session + yCollab (Zeichen-Sync UND Cursor-Presence), jede
   * geschlossene faellt zurueck in den periodischen Hintergrund-Poll-Pool.
   *
   * <p>Das erweitert die Architekturentscheidung vom 2026-09-18 (s. Klassendoc) vom Sonderfall
   * "genau eine aktive Notiz" auf "genau die offenen Notizen" - es bleibt bei EINER geteilten
   * WebSocket-Verbindung (`MultiplexedTransport`), offene Panes sind nur zusaetzliche JOINs
   * darauf, keine zusaetzlichen Verbindungen. In der Praxis sind das ein bis drei Panes.
   */
  private async syncOpenEditorBindings(): Promise<void> {
    const generation = ++this.liveBindGeneration;
    this.editorViewPending = false;
    const open = this.openMarkdownEditors();
    if (this.editorViewPending) {
      this.scheduleBindingRetry();
    } else {
      this.bindingRetries = 0;
    }
    const plan = planEditorBindings(
      open.map((entry) => ({ path: entry.file.path, view: entry.view })),
      this.boundViews,
    );

    for (const { path, view } of plan.unbind) {
      this.detachLiveBinding(path, view);
    }

    for (const { path } of plan.bind) {
      const entry = open.find((candidate) => candidate.file.path === path);
      if (!entry) {
        continue;
      }
      await this.startSync(entry.file, true);
      if (generation !== this.liveBindGeneration) {
        // Ein neuerer Durchlauf (weiterer Pane-/Dateiwechsel waehrend dieses Awaits) hat die
        // Zustaendigkeit uebernommen - hier nichts mehr anfassen, sonst ueberschreibt dieser
        // veraltete Durchlauf dessen korrektes Ergebnis mit dem FALSCHEN Y.Text.
        return;
      }
      // Waehrend des Awaits kann das Pane geschlossen oder auf eine andere Datei umgestellt
      // worden sein - dann zeigt diese View diesen Pfad nicht mehr und darf nicht gebunden werden.
      const stillShown = this.openMarkdownEditors().some(
        (candidate) => candidate.file.path === path && candidate.view === entry.view,
      );
      if (!stillShown) {
        continue;
      }
      const session = this.sessions.get(path);
      if (!session) {
        continue;
      }
      session.hasLiveEditorBinding = true;
      this.boundViews.set(path, entry.view);
      // ZWEI getrennte Transaktionen, nicht eine: `ySync` aus y-codemirror.next ist ein
      // modulweites ViewPlugin-Singleton. Konfiguriert man den Compartment direkt von einem
      // yCollab auf ein anderes um, sieht CodeMirror dasselbe Plugin und erzeugt es NICHT neu -
      // es behaelt den im Konstruktor erfassten alten Y.Text samt dessen Observer. Folge (im Test
      // reproduziert): Aenderungen an der ZUVOR gebundenen Notiz wurden weiterhin in diesen
      // Editor geschrieben, obwohl er laengst eine andere Notiz anzeigt. Der Zwischenschritt auf
      // die leere Konfiguration nimmt das Plugin wirklich aus der Konfiguration und zerstoert es.
      entry.view.dispatch({ effects: this.liveBindingCompartment.reconfigure([]) });
      entry.view.dispatch({
        effects: this.liveBindingCompartment.reconfigure(
          yCollab(session.client.doc.getText("content"), session.client.awareness),
        ),
      });
    }
  }

  /**
   * Versucht den Bindungsabgleich kurz darauf erneut, wenn mindestens ein offenes Pane seine
   * CM6-View noch nicht bereitgestellt hatte. Begrenzt auf {@link BINDING_RETRY_LIMIT} Versuche
   * je Ereignis, damit ein dauerhaft view-loses Pane (z. B. eine Nicht-Editor-Ansicht) keine
   * Endlosschleife ausloest; der Zaehler wird bei jedem erfolgreichen Durchlauf ohne offenen
   * Rest zurueckgesetzt. Muster uebernommen aus dem Referenzplugin `obsidian-collab`, das dasselbe
   * Timing-Problem mit einem gestaffelten Retry loest statt mit einem einmaligen Zugriff.
   */
  private scheduleBindingRetry(): void {
    if (this.bindingRetries >= BINDING_RETRY_LIMIT) {
      return;
    }
    this.bindingRetries++;
    window.clearTimeout(this.bindingRetryTimer ?? undefined);
    this.bindingRetryTimer = window.setTimeout(() => void this.syncOpenEditorBindings(), BINDING_RETRY_DELAY_MS);
  }

  /**
   * Loest die CM6-Bindung eines Panes und beendet die zugehoerige Session - die Notiz faellt
   * damit zurueck in den periodischen Hintergrund-Poll-Pool.
   */
  private detachLiveBinding(path: string, view: EditorView): void {
    this.boundViews.delete(path);
    try {
      view.dispatch({ effects: this.liveBindingCompartment.reconfigure([]) });
    } catch (error) {
      // Die View kann zusammen mit ihrem geschlossenen Leaf bereits zerstoert sein - harmlos.
      console.debug("StoneIntelligence: Editor-Bindung konnte nicht geloest werden", path, error);
    }
    this.stopSync(path);
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
      // Eine Hintergrund-Notiz (nicht live gebunden) wurde lokal veraendert - z. B. durch ein
      // anderes Plugin, ein externes Werkzeug, oder weil der Poll-Zyklus fuer diese Datei noch nie
      // gelaufen ist. `preferLocal: true`, weil DIESE Aenderung gerade erst passiert ist und
      // uebertragen werden soll - anders als beim periodischen Poll ohne konkreten Anlass.
      void this.syncNoteInBackground(file.path, { preferLocal: true });
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
      void this.syncNoteInBackground(file.path, { preferLocal: true });
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
      const boundView = this.boundViews.get(oldPath);
      if (boundView) {
        // Ohne das wuerde `syncOpenEditorBindings` beim naechsten Durchlauf faelschlich versuchen,
        // eine Session unter dem inzwischen umbenannten (nicht mehr existenten) alten Pfad zu
        // stoppen, statt die tatsaechlich gebundene (jetzt umbenannte) Session zu erkennen.
        this.boundViews.delete(oldPath);
        this.boundViews.set(file.path, boundView);
      }
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
