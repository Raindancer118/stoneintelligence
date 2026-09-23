import { Compartment } from "@codemirror/state";
import type { EditorView } from "@codemirror/view";
import { MarkdownView, Notice, Platform, Plugin, setIcon, TAbstractFile, TFile, TFolder } from "obsidian";
import * as Y from "yjs";
import {
  type FolderOp, isExcluded, isExcludedFolder, isNoteOp, migrateSettings, queueDelete, queueFolderOp, queueRename,
  type StoneIntelligenceSettings, type VaultSyncState, emptyVaultState, wsUrlFor,
} from "./settings";
import { isInsideFolder, isSyncableFolderPath, planFolders } from "./sync/folderPlan";
import { isSyncableFilePath } from "./sync/filePlan";
import { contentTypeFor, FileSync, type FileVaultPort } from "./sync/fileSync";
import { actorDisplayNameFromAccessToken, displayNameFromClaims, pickUserColor } from "./sync/actorIdentity";
import { AuthentikAuthClient, TokenRefreshRejectedError, type StoredTokens } from "./sync/AuthentikAuthClient";
import { dedupeInFlight } from "./sync/dedupeInFlight";
import { awaitDesktopRedirectCode, DESKTOP_REDIRECT_URI, openAuthorizationUrlDesktop } from "./sync/desktopAuthRedirect";
import { awaitCatchupComplete, awaitConnected, connectForCatchup } from "./sync/catchupConnection";
import { bindEditorToText, unbindEditor } from "./sync/editorBinding";
import { planEditorBindings } from "./sync/editorBindingPlan";
import {
  handleMobileRedirectCallback, MOBILE_REDIRECT_ACTION, MOBILE_REDIRECT_URI,
  openAuthorizationUrlMobile, type PendingAuthCallback,
} from "./sync/mobileAuthRedirect";
import {
  MultiplexedTransport, VAULT_FOLDERS_CHANGED, VAULT_NOTE_CREATED, VAULT_NOTE_DELETED, VAULT_NOTE_RENAMED, VAULT_NOTE_UPDATED,
} from "./sync/multiplexedTransport";
import { type FileLimits, HttpError, NoteApiClient, type VaultSummary } from "./sync/NoteApiClient";
import {
  type ContentSyncPorts, type ContentSyncResult, conflictCopyPath, hasUnsyncedLocalEdits, prepareNoteDoc, resolveFirstContact,
  syncNoteContent, textOfState,
} from "./sync/noteContentSync";
import { NoteStateStore } from "./sync/NoteStateStore";
import { OperationJournal } from "./sync/OperationJournal";
import { isSyncablePath, planReconciliation, type ReconcileAction } from "./sync/reconcilePlan";
import { SyncActivity } from "./sync/SyncActivity";
import { SyncClient } from "./sync/SyncClient";
import { TicketClient } from "./sync/TicketClient";
import { CONNECT_ACTION, type ConnectLink, parseConnectLink } from "./sync/connectLink";
import { ConnectVaultModal } from "./ui/ConnectVaultModal";
import { DeletionConflictModal } from "./ui/DeletionConflictModal";
import { AiChangesModal } from "./ui/AiChangesModal";
import { InviteModal } from "./ui/InviteModal";
import { StoneIntelligenceSettingTab } from "./ui/SettingsTab";
import { presentStatus, type StatusPresentation } from "./ui/statusPresentation";
import { type Collaborator, type LiveNote, StatusView, VIEW_TYPE_STATUS } from "./ui/StatusView";

/**
 * Sicherheitsnetz-Takt fuer den Abgleich. Anlage/Umbenennung/Loeschung UND Inhaltsaenderungen
 * geschlossener Notizen kommen sofort per Vault-Ankuendigung; dieser Takt faengt nur ab, was
 * trotzdem durchrutscht (z. B. aeltere Server ohne Inhalts-Ankuendigung). Dank Server-Revisionen
 * kostet ein Durchlauf ohne Aenderungen genau eine Listen-Anfrage - frueher wurde alle 90s JEDE
 * Notiz einzeln gejoint.
 */
const RECONCILE_INTERVAL_MS = 30_000;
/**
 * Wartezeit nach einer Inhalts-Ankuendigung. Der Server kuendigt hoechstens einmal pro Sekunde
 * und Notiz an; laenger zu warten stellt sicher, dass auch das letzte Update eines Tipp-Schwalls
 * dabei ist - und buendelt den Schwall zu einem einzigen Abgleich.
 */
const REMOTE_CHANGE_DEBOUNCE_MS = 2_500;
/** Nach einer angekuendigten Neuanlage: der Inhalt folgt beim anlegenden Geraet kurz danach. */
const CREATED_FOLLOW_UP_MS = 3_000;
/**
 * Nach einer Ordner- oder Loesch-Ankuendigung kurz warten: die einzelnen Notiz-Loeschungen eines
 * geloeschten Ordners kommen nacheinander an, erst danach ist der Ordner hier leer.
 */
const FOLDER_PASS_DEBOUNCE_MS = 600;
/** Grosse Dateien schreibt ein Programm oft in mehreren Schritten - erst nach einer Pause hochladen. */
const FILE_CHANGE_DEBOUNCE_MS = 1_500;
/** Grenzen des Servers fuer Dateien nur gelegentlich neu abfragen. */
const FILE_LIMITS_TTL_MS = 60 * 60_000;
/**
 * Aeltere Server liefern keine Revisionen - dann liesse sich ohne Join nicht erkennen, ob sich
 * eine Notiz geaendert hat. Damit nicht alle 30s JEDE Notiz gejoint wird, laeuft der volle
 * Inhaltsabgleich in dem Fall nur in diesem (dem frueheren) Takt.
 */
const LEGACY_FULL_CONTENT_INTERVAL_MS = 90_000;
/**
 * Markierung in `blockedPaths`: anderswo geloescht, hier noch unuebertragene Aenderungen - bis
 * zur Entscheidung weder hochladen noch loeschen. Persistiert, damit die Frage einen Neustart
 * uebersteht.
 */
const DELETION_DECISION_PENDING = "deletion-decision-pending";
/** Frist fuer Verbindung + Catchup einer einzelnen Notiz im Hintergrund. */
const CONTENT_SYNC_TIMEOUT_MS = 10_000;
/** Wie lange eine geoeffnete Notiz auf den Server-Stand wartet, bevor sie offline weiterarbeitet. */
const LIVE_CATCHUP_WAIT_MS = 6_000;
/** Lokale Aenderungen (Tippen ohne Live-Bindung, externe Tools) werden gebuendelt uebertragen. */
const LOCAL_CHANGE_DEBOUNCE_MS = 2_000;
const STATE_SAVE_DEBOUNCE_MS = 1_500;
const SETTINGS_SAVE_DEBOUNCE_MS = 1_000;
/** Parallel abgeglichene Notizen je Durchlauf - genug fuer Tempo, wenig genug fuer Mobile. */
const PASS_CONCURRENCY = 3;
const BINDING_RETRY_DELAY_MS = 300;
const BINDING_RETRY_LIMIT = 20;

/**
 * Deterministischer djb2-Hash - reicht als Content-Fingerprint fuer das {@link OperationJournal}
 * (Fehlerklasse 1), muss kryptografisch nicht stark sein.
 */
function hashContent(content: string): string {
  let hash = 5381;
  for (let i = 0; i < content.length; i++) {
    hash = ((hash << 5) + hash + content.charCodeAt(i)) | 0;
  }
  return (hash >>> 0).toString(16);
}

function basename(path: string): string {
  return (path.split("/").pop() ?? path).replace(/\.md$/i, "");
}

function parentFolder(path: string): string {
  return path.split("/").slice(0, -1).join("/");
}

/** Eine im Editor geoeffnete Notiz: dauerhaft gejoint, zeichengenau per yCollab gebunden. */
interface LiveSession {
  path: string;
  noteId: string;
  client: SyncClient;
  view: EditorView;
  saveTimer: number | null;
  stopStateSaves: () => void;
}

async function runWithConcurrency<T>(items: T[], limit: number, task: (item: T) => Promise<void>): Promise<void> {
  let next = 0;
  const worker = async (): Promise<void> => {
    while (next < items.length) {
      const item = items[next++];
      await task(item);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
}

/**
 * StoneIntelligence-Sync fuer Obsidian.
 *
 * <p>Zwei Pfade teilen sich EINE WebSocket-Verbindung (Toms Vorgabe, s. `multiplexedTransport.ts`):
 * <ul>
 *   <li><b>Geoeffnete Notizen</b> sind dauerhaft gejoint und zeichengenau an ihren Editor gebunden
 *   (yCollab, inkl. Cursor anderer Personen).</li>
 *   <li><b>Alle anderen</b> gleicht ein Abgleich-Durchlauf ab ({@link planReconciliation}): eine
 *   Server-Liste mit Revisionen bestimmt, was sich geaendert hat; nur diese Notizen werden kurz
 *   gejoint. Jede Notiz hat einen lokal gespeicherten Yjs-Zustand ({@link NoteStateStore}) - damit
 *   lassen sich auch Aenderungen, die offline oder bei beendetem Obsidian passiert sind, sauber
 *   mergen statt "Server gewinnt".</li>
 * </ul>
 * Lokale Loeschungen/Umbenennungen landen zuerst in einer dauerhaften Warteschlange und werden
 * uebertragen, sobald der Server erreichbar ist - frueher gingen sie offline schlicht verloren und
 * der naechste Abgleich machte sie rueckgaengig.
 */
export default class StoneIntelligencePlugin extends Plugin {
  settings: StoneIntelligenceSettings = migrateSettings(null);
  readonly activity = new SyncActivity();
  private readonly journal = new OperationJournal();
  private stateStore!: NoteStateStore;
  private noteApiClient!: NoteApiClient;
  private authClient!: AuthentikAuthClient;
  private tokenEndpoint: string | null = null;
  /**
   * Dedupliziert gleichzeitige Refresh-Versuche: Authentik rotiert Refresh-Tokens, ein zweiter,
   * quasi zeitgleicher Versuch mit demselben Token scheitert garantiert mit HTTP 400 (live
   * beobachtet: WS blieb dauerhaft auf "verbindet").
   */
  private readonly dedupeTokenRefresh = dedupeInFlight<StoredTokens>();
  private transport: MultiplexedTransport | null = null;
  private pendingAuthCallback: PendingAuthCallback | null = null;

  private readonly live = new Map<string, LiveSession>();
  private readonly liveStarting = new Set<string>();
  private readonly liveBindingCompartment = new Compartment();
  private liveBindGeneration = 0;
  private editorViewPending = false;
  private bindingRetries = 0;
  private bindingRetryTimer: number | null = null;

  /**
   * Pfade, an denen gerade eine vom SERVER angestossene Aenderung ausgefuehrt wird. Obsidian feuert
   * dafuer dieselben Vault-Events wie fuer eine Nutzeraktion - ohne diesen Guard wuerde sie
   * postwendend als eigene Aenderung zurueckgemeldet.
   */
  private readonly serverDrivenPaths = new Set<string>();
  /** Pfade, deren Notiz gerade auf dem Server angelegt wird - deren eigene Anlage-Ankuendigung ist kein fremdes Ereignis. */
  private readonly uploadingPaths = new Set<string>();
  private readonly localChangeTimers = new Map<string, number>();
  private readonly remoteChangeTimers = new Map<string, number>();
  private readonly noteLocks = new Map<string, Promise<unknown>>();
  private lastLegacyContentPass = 0;
  private passRunning = false;
  private passRequested = false;
  private flushingOps = false;
  private folderPassRunning = false;
  private folderPassRequested = false;
  private folderPassTimer: number | null = null;
  /** Server ohne Ordner-Synchronisation (Endpunkt fehlt) - dann gar nicht erst versuchen. */
  private folderSyncUnsupported = false;
  /** Datei-Synchronisation (ADR 0009): Grenzen des Servers; `null` = Server kennt keine Dateien. */
  private fileLimits: FileLimits | null | undefined;
  private fileLimitsAt = 0;
  private readonly fileChangeTimers = new Map<string, number>();
  private readonly fileSync = new FileSync({
    vaultId: () => this.settings.vaultId,
    vault: this.fileVaultPort(),
    api: {
      createFile: (vaultId, path) => this.noteApiClient.createFile(vaultId, path),
      uploadFile: (vaultId, id, base, bytes, type) => this.noteApiClient.uploadFile(vaultId, id, base, bytes, type),
      downloadFile: (vaultId, id) => this.noteApiClient.downloadFile(vaultId, id),
      noteStatus: (vaultId, id) => this.noteApiClient.noteStatus(vaultId, id),
    },
    state: () => this.vaultState(),
    save: () => this.requestSettingsSave(),
    report: (path, message) => this.activity.reportProblem(path, message),
    clearProblem: (path) => this.activity.clearProblem(path),
    log: (kind, path, detail) => this.activity.log(kind, path, detail),
    now: () => new Date(),
    askDeletionDecision: (path) => {
      this.vaultState().blockedPaths[path] = DELETION_DECISION_PENDING;
      this.requestSettingsSave();
      this.askDeletionDecision(path);
    },
    contentType: contentTypeFor,
  });
  private settingsSaveTimer: number | null = null;
  /** Loeschkonflikte werden nacheinander gefragt, nie mehrere Dialoge uebereinander. */
  private decisionQueue: Promise<void> = Promise.resolve();
  /** Darf diese Person im aktuellen Vault Mitglieder verwalten (MANAGE)? Steuert, wo "Einladen" erscheint. */
  private manageAllowed = false;
  private readonly decisionsAsked = new Set<string>();

  private statusBarEl!: HTMLElement;
  private presenceBarEl!: HTMLElement;

  // ---------------------------------------------------------------------------------------------
  // Lebenszyklus

  async onload(): Promise<void> {
    this.settings = migrateSettings(await this.loadData());
    await this.saveData(this.settings);
    this.stateStore = new NoteStateStore(this.app.vault.adapter, `${this.manifest.dir ?? `${this.app.vault.configDir}/plugins/${this.manifest.id}`}/sync-state`);
    this.rebuildClients();
    this.refreshActivityFlags();

    this.registerObsidianProtocolHandler(MOBILE_REDIRECT_ACTION, (params) => {
      const pending = this.pendingAuthCallback;
      this.pendingAuthCallback = null;
      handleMobileRedirectCallback(pending, params as unknown as Record<string, string>);
    });
    this.registerObsidianProtocolHandler(CONNECT_ACTION, (params) => {
      const link = parseConnectLink(params as unknown as Record<string, string | undefined>);
      if (!link) {
        new Notice("StoneIntelligence: Dieser Verbinden-Link ist ungültig.");
        return;
      }
      this.handleConnectLink(link);
    });
    // Link in jeder KI-Quellnotiz: obsidian://stoneintelligence-ai-changes
    this.registerObsidianProtocolHandler("stoneintelligence-ai-changes", () => {
      if (!this.isReady()) {
        new Notice("StoneIntelligence: Erst anmelden und einen Vault verbinden.");
        return;
      }
      this.openAiChanges();
    });
    this.addSettingTab(new StoneIntelligenceSettingTab(this.app, this, this));
    this.registerView(VIEW_TYPE_STATUS, (leaf) => new StatusView(leaf, this));
    this.addRibbonIcon("refresh-cw", "StoneIntelligence-Sync", () => void this.activateStatusView());
    this.setupStatusBar();
    this.registerCommands();
    this.registerFileMenu();

    this.registerEditorExtension([this.liveBindingCompartment.of([])]);
    // `file-open` allein feuert zu frueh (Ziel-View existiert teils noch nicht), `active-leaf-change`
    // deckt Pane-/Tab-Wechsel ab, `layout-change` Oeffnen/Schliessen/Teilen - alle drei muenden in
    // denselben idempotenten Abgleich.
    this.registerEvent(this.app.workspace.on("file-open", () => void this.syncOpenEditorBindings()));
    this.registerEvent(this.app.workspace.on("active-leaf-change", () => {
      void this.syncOpenEditorBindings();
      this.updatePresenceBar();
    }));
    this.registerEvent(this.app.workspace.on("layout-change", () => void this.syncOpenEditorBindings()));

    this.registerEvent(this.app.vault.on("create", (file) => this.handleLocalCreate(file)));
    this.registerEvent(this.app.vault.on("modify", (file) => void this.handleLocalModify(file)));
    this.registerEvent(this.app.vault.on("delete", (file) => void this.handleLocalDelete(file)));
    this.registerEvent(this.app.vault.on("rename", (file, oldPath) => void this.handleLocalRename(file, oldPath)));

    this.registerInterval(window.setInterval(() => this.journal.evictOlderThan(5 * 60_000), 60_000));
    this.registerInterval(window.setInterval(() => this.requestPass(), RECONCILE_INTERVAL_MS));
    // Das Betriebssystem meldet, dass das Netz wieder da ist - nicht erst den Backoff abwarten.
    this.registerDomEvent(window, "online", () => this.transport?.reconnectNow());

    this.app.workspace.onLayoutReady(() => {
      if (this.isLoggedIn() && !this.settings.displayName) {
        void this.refreshDisplayName();
      }
      this.startSyncEngine();
    });
  }

  onunload(): void {
    window.clearTimeout(this.bindingRetryTimer ?? undefined);
    for (const timer of [...this.localChangeTimers.values(), ...this.remoteChangeTimers.values()]) {
      window.clearTimeout(timer);
    }
    this.stopSyncEngine();
    if (this.settingsSaveTimer !== null) {
      window.clearTimeout(this.settingsSaveTimer);
      void this.saveData(this.settings);
    }
  }

  private isReady(): boolean {
    return Boolean(this.settings.vaultId) && this.isLoggedIn() && !this.settings.paused;
  }

  private refreshActivityFlags(): void {
    this.activity.update({
      configured: Boolean(this.settings.vaultId),
      signedIn: this.isLoggedIn(),
      paused: this.settings.paused,
    });
  }

  /** Verbindung aufbauen und halten, ersten Abgleich anstossen, offene Notizen binden. */
  private startSyncEngine(): void {
    this.refreshActivityFlags();
    if (!this.isReady()) {
      return;
    }
    this.ensureTransport().start();
    this.askPendingDeletionDecisions();
    this.requestPass();
    void this.syncOpenEditorBindings();
    void this.refreshVaultName();
    void this.refreshPermissions();
  }

  private stopSyncEngine(): void {
    for (const path of [...this.live.keys()]) {
      this.detachLive(path);
    }
    this.transport?.destroy();
    this.transport = null;
    this.activity.update({ connection: "offline" });
    this.refreshActivityFlags();
  }

  private rebuildClients(): void {
    this.authClient = new AuthentikAuthClient({
      issuerUrl: this.settings.oidcIssuerUrl, clientId: this.settings.oidcClientId,
    });
    this.tokenEndpoint = null;
    this.noteApiClient = new NoteApiClient(this.settings.platformApiUrl.replace(/\/+$/, ""), this.getAccessToken);
  }

  private vaultState(): VaultSyncState {
    const vaultId = this.settings.vaultId;
    this.settings.vaults[vaultId] ??= emptyVaultState();
    return this.settings.vaults[vaultId];
  }

  async saveSettings(): Promise<void> {
    if (this.settingsSaveTimer !== null) {
      window.clearTimeout(this.settingsSaveTimer);
      this.settingsSaveTimer = null;
    }
    await this.saveData(this.settings);
  }

  /** Viele kleine Meta-Aenderungen in einem Durchlauf -> ein Schreibvorgang. */
  private requestSettingsSave(): void {
    if (this.settingsSaveTimer !== null) {
      return;
    }
    this.settingsSaveTimer = window.setTimeout(() => {
      this.settingsSaveTimer = null;
      void this.saveData(this.settings);
    }, SETTINGS_SAVE_DEBOUNCE_MS);
  }

  // ---------------------------------------------------------------------------------------------
  // Anmeldung

  /**
   * Liefert ein gueltiges Access-Token, refresht bei Bedarf still im Hintergrund. Arrow-Function,
   * damit `this` beim Durchreichen an NoteApiClient/TicketClient erhalten bleibt.
   */
  private getAccessToken = async (): Promise<string> => {
    const tokens = this.settings.tokens;
    if (!tokens) {
      throw new Error("Nicht angemeldet.");
    }
    if (Date.now() < tokens.expiresAt) {
      return tokens.accessToken;
    }
    const refreshed = await this.dedupeTokenRefresh(() => this.refreshTokens(tokens.refreshToken));
    return refreshed.accessToken;
  };

  /**
   * Loescht die Tokens NUR, wenn Authentik den Refresh-Token definitiv ablehnt
   * (`TokenRefreshRejectedError`). Netzwerkfehler beim Obsidian-Start (noch kein Netz) lassen die
   * Anmeldung unangetastet - sonst war man nach jedem Neustart ohne Netz abgemeldet.
   */
  private async refreshTokens(refreshToken: string): Promise<StoredTokens> {
    try {
      if (!this.tokenEndpoint) {
        this.tokenEndpoint = (await this.authClient.discover()).token_endpoint;
      }
      const refreshed = await this.authClient.refreshAccessToken(this.tokenEndpoint, refreshToken);
      this.settings.tokens = refreshed;
      await this.saveSettings();
      return refreshed;
    } catch (error) {
      if (error instanceof TokenRefreshRejectedError) {
        this.settings.tokens = null;
        await this.saveSettings();
        this.stopSyncEngine();
        // Bleibt stehen, bis sie weggeklickt wird - der Sync steht ab hier still.
        new Notice("StoneIntelligence: Anmeldung abgelaufen, Sync angehalten. Bitte erneut anmelden (Statusleiste anklicken).", 0);
      }
      throw error;
    }
  }

  isLoggedIn(): boolean {
    return this.settings.tokens !== null;
  }

  accountName(): string | null {
    if (!this.isLoggedIn()) {
      return null;
    }
    return this.settings.displayName ?? actorDisplayNameFromAccessToken(this.settings.tokens?.accessToken);
  }

  private actorDisplayName(): string {
    return this.settings.displayName ?? actorDisplayNameFromAccessToken(this.settings.tokens?.accessToken);
  }

  /** Voller Name aus Authentiks `userinfo`; scheitert bewusst leise (Fallback: Token-Claim). */
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
      await this.saveSettings();
      for (const session of this.live.values()) {
        session.client.awareness.setLocalStateField("user", { name, color: pickUserColor(name) });
      }
      this.activity.touch();
    } catch (error) {
      console.debug("StoneIntelligence: Anzeigename konnte nicht aufgeloest werden", error);
    }
  }

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
    await this.saveSettings();
    await this.refreshDisplayName();
    new Notice(this.settings.vaultId
      ? "StoneIntelligence: Angemeldet, Sync läuft."
      : "StoneIntelligence: Angemeldet. Wähle jetzt in den Einstellungen deinen Vault.");
    this.startSyncEngine();
  }

  async logout(): Promise<void> {
    this.settings.tokens = null;
    this.settings.displayName = null;
    await this.saveSettings();
    this.stopSyncEngine();
  }

  private awaitMobileRedirectCode(expectedState: string): Promise<string> {
    return new Promise((resolve, reject) => {
      this.pendingAuthCallback = { state: expectedState, resolve, reject };
    });
  }

  // ---------------------------------------------------------------------------------------------
  // Vault-Auswahl & Verbindungsdaten (fuer die Einstellungen)

  listVaults(): Promise<VaultSummary[]> {
    return this.noteApiClient.listVaults();
  }

  async selectVault(vault: VaultSummary): Promise<void> {
    if (vault.id === this.settings.vaultId) {
      return;
    }
    this.stopSyncEngine();
    this.settings.vaultId = vault.id;
    this.settings.vaultName = vault.name;
    this.manageAllowed = false;
    this.vaultState();
    await this.saveSettings();
    this.startSyncEngine();
  }

  async createVault(name: string): Promise<void> {
    const vaultId = await this.noteApiClient.createVault(name);
    await this.selectVault({ id: vaultId, name, createdAt: new Date().toISOString() });
  }

  private async refreshPermissions(): Promise<void> {
    try {
      const allowed = (await this.noteApiClient.permissions(this.settings.vaultId)).includes("MANAGE");
      if (allowed !== this.manageAllowed) {
        this.manageAllowed = allowed;
        this.activity.touch();
      }
    } catch {
      // Nur fuer die Anzeige des Einladen-Knopfs - der Server prueft ohnehin selbst.
    }
  }

  canInvite(): boolean {
    return this.isReady() && this.manageAllowed;
  }

  openInvite(): void {
    if (!this.settings.vaultId) {
      return;
    }
    new InviteModal(this.app, this.noteApiClient, this.settings.vaultId, this.vaultName() ?? "Vault").open();
  }

  openAiChanges(): void {
    if (!this.settings.vaultId) {
      return;
    }
    const active = this.app.workspace.getActiveFile();
    new AiChangesModal(this.app, this.noteApiClient, this.settings.vaultId, active?.path ?? null).open();
  }

  /**
   * Verbinden-Link der Einrichtungsseite: bestaetigen lassen, bei Bedarf anmelden, dann pruefen,
   * dass der Vault fuer dieses Konto wirklich zugaenglich ist, und ihn waehlen.
   */
  private handleConnectLink(link: ConnectLink): void {
    const displayName = link.vaultName ?? "gemeinsamer Vault";
    if (link.vaultId === this.settings.vaultId && this.isLoggedIn()) {
      new Notice(`StoneIntelligence: Dieser Obsidian-Vault ist bereits mit „${this.settings.vaultName || displayName}“ verbunden.`);
      void this.activateStatusView();
      return;
    }
    const knownState = this.settings.vaults[link.vaultId];
    const localNoteCount = this.app.vault.getMarkdownFiles()
      .filter((file) => isSyncablePath(file.path) && !isExcluded(file.path, this.settings.excludedFolders))
      .filter((file) => !knownState?.noteIds[file.path]).length;
    new ConnectVaultModal(this.app, {
      vaultName: displayName,
      localNoteCount,
      currentVaultName: this.settings.vaultId && this.settings.vaultId !== link.vaultId
        ? (this.settings.vaultName || "einem anderen Vault") : null,
      signedIn: this.isLoggedIn(),
    }, () => void this.connectToVault(link)).open();
  }

  private async connectToVault(link: ConnectLink): Promise<void> {
    try {
      if (!this.isLoggedIn()) {
        await this.login();
      }
      const vault = (await this.listVaults()).find((candidate) => candidate.id === link.vaultId);
      if (!vault) {
        new Notice("StoneIntelligence: Du hast (noch) keinen Zugriff auf diesen Vault. Nimm zuerst die Einladung an "
          + "oder bitte die Person, die den Vault verwaltet, dich hinzuzufügen.", 10_000);
        return;
      }
      if (this.settings.paused) {
        this.settings.paused = false;
      }
      await this.selectVault(vault);
      this.startSyncEngine();
      new Notice(`StoneIntelligence: Verbunden mit „${vault.name}“ – die Notizen werden jetzt synchronisiert.`);
      void this.activateStatusView();
    } catch (error) {
      new Notice(`StoneIntelligence: Verbinden fehlgeschlagen – ${(error as Error).message}`);
    }
  }

  private async refreshVaultName(): Promise<void> {
    try {
      const vault = (await this.listVaults()).find((candidate) => candidate.id === this.settings.vaultId);
      if (vault && vault.name !== this.settings.vaultName) {
        this.settings.vaultName = vault.name;
        await this.saveSettings();
        this.activity.touch();
      }
    } catch {
      // Nur Anzeige - der Sync haengt nicht davon ab.
    }
  }

  async saveConnectionSettings(): Promise<void> {
    await this.saveSettings();
    this.stopSyncEngine();
    this.rebuildClients();
    this.startSyncEngine();
  }

  setPaused(paused: boolean): void {
    this.settings.paused = paused;
    void this.saveSettings();
    if (paused) {
      this.stopSyncEngine();
    } else {
      this.startSyncEngine();
    }
  }

  isPaused(): boolean {
    return this.settings.paused;
  }

  /** Serialisiert die reine Verbindungskonfiguration (KEINE Tokens) als JSON. */
  connectionConfigJson(): string {
    const { platformApiUrl, platformWsUrl, vaultId, vaultName, oidcIssuerUrl, oidcClientId } = this.settings;
    return JSON.stringify({ platformApiUrl, platformWsUrl, vaultId, vaultName, oidcIssuerUrl, oidcClientId }, null, 2);
  }

  async applyConnectionConfig(json: string): Promise<void> {
    const parsed = JSON.parse(json) as Partial<StoneIntelligenceSettings>;
    for (const key of ["platformApiUrl", "platformWsUrl", "vaultId", "vaultName", "oidcIssuerUrl", "oidcClientId"] as const) {
      if (typeof parsed[key] === "string") {
        this.settings[key] = parsed[key] as string;
      }
    }
    if (this.settings.vaultId) {
      this.vaultState();
    }
    await this.saveConnectionSettings();
  }

  // ---------------------------------------------------------------------------------------------
  // UI: Statusleiste, Seitenleiste, Befehle

  private setupStatusBar(): void {
    this.statusBarEl = this.addStatusBarItem();
    this.statusBarEl.addClass("stoneintelligence-status-bar", "mod-clickable");
    this.statusBarEl.onclick = () => this.runStatusAction(presentStatus(this.activity, { pending: this.pendingCount() }).action);
    this.presenceBarEl = this.addStatusBarItem();
    this.presenceBarEl.addClass("stoneintelligence-presence-bar", "mod-clickable");
    this.presenceBarEl.onclick = () => void this.activateStatusView();
    this.register(this.activity.subscribe(() => this.updateStatusBar()));
    this.registerInterval(window.setInterval(() => this.updateStatusBar(), 30_000));
    this.updateStatusBar();
    this.updatePresenceBar();
  }

  private updateStatusBar(): void {
    const status = presentStatus(this.activity, { pending: this.pendingCount() });
    this.statusBarEl.empty();
    this.statusBarEl.className = `status-bar-item plugin-stoneintelligence stoneintelligence-status-bar mod-clickable is-${status.tone}`;
    const icon = this.statusBarEl.createSpan({ cls: "stoneintelligence-status-icon" });
    setIcon(icon, status.icon);
    icon.toggleClass("is-spinning", status.spinning);
    this.statusBarEl.createSpan({ text: status.label });
    this.statusBarEl.setAttr("aria-label", status.tooltip);
    this.statusBarEl.setAttr("data-tooltip-position", "top");
  }

  /** Wer gerade in der aktiven Notiz mitarbeitet - direkt sichtbar, ohne die Seitenleiste zu oeffnen. */
  private updatePresenceBar(): void {
    const path = this.app.workspace.getActiveFile()?.path;
    const session = path ? this.live.get(path) : undefined;
    const others = session ? this.collaboratorsOf(session) : [];
    this.presenceBarEl.empty();
    this.presenceBarEl.toggle(others.length > 0);
    if (others.length === 0) {
      return;
    }
    setIcon(this.presenceBarEl.createSpan({ cls: "stoneintelligence-status-icon" }), "users");
    this.presenceBarEl.createSpan({ text: others.length === 1 ? others[0].name : `${others.length} Personen` });
    this.presenceBarEl.setAttr("aria-label", `Gerade in dieser Notiz: ${others.map((person) => person.name).join(", ")}`);
    this.presenceBarEl.setAttr("data-tooltip-position", "top");
  }

  runStatusAction(action: StatusPresentation["action"]): void {
    if (action === "settings") {
      this.openSettings();
    } else if (action === "login") {
      this.login().catch((error: Error) => new Notice(`StoneIntelligence: Anmeldung fehlgeschlagen – ${error.message}`));
    } else {
      void this.activateStatusView();
    }
  }

  openSettings(): void {
    const setting = (this.app as unknown as { setting?: { open(): void; openTabById(id: string): void } }).setting;
    setting?.open();
    setting?.openTabById(this.manifest.id);
  }

  openFile(path: string): void {
    const file = this.app.vault.getAbstractFileByPath(path);
    if (file instanceof TFile) {
      void this.app.workspace.getLeaf(false).openFile(file);
    }
  }

  async activateStatusView(): Promise<void> {
    const { workspace } = this.app;
    let leaf = workspace.getLeavesOfType(VIEW_TYPE_STATUS)[0];
    if (!leaf) {
      leaf = workspace.getRightLeaf(false) ?? workspace.getLeaf(true);
      await leaf.setViewState({ type: VIEW_TYPE_STATUS, active: true });
    }
    void workspace.revealLeaf(leaf);
  }

  pendingCount(): number {
    return this.settings.vaultId ? this.vaultState().pendingOps.length : 0;
  }

  linkedCount(): number {
    return this.settings.vaultId ? Object.keys(this.vaultState().noteIds).length : 0;
  }

  vaultName(): string | null {
    return this.settings.vaultId ? (this.settings.vaultName || this.settings.vaultId) : null;
  }

  liveNotes(): LiveNote[] {
    return [...this.live.values()]
      .map((session) => ({ path: session.path, collaborators: this.collaboratorsOf(session) }))
      .sort((a, b) => a.path.localeCompare(b.path));
  }

  private collaboratorsOf(session: LiveSession): Collaborator[] {
    const people: Collaborator[] = [];
    const seen = new Set<string>();
    session.client.awareness.getStates().forEach((state, clientId) => {
      const user = (state as { user?: { name?: string; color?: string } }).user;
      if (clientId === session.client.doc.clientID || !user?.name || seen.has(user.name)) {
        return;
      }
      seen.add(user.name);
      people.push({ name: user.name, color: user.color ?? "var(--interactive-accent)" });
    });
    return people;
  }

  syncNow(): void {
    if (!this.isReady()) {
      this.runStatusAction(presentStatus(this.activity, { pending: this.pendingCount() }).action);
      return;
    }
    if (this.transport?.state !== "online") {
      this.transport?.reconnectNow();
    }
    this.requestPass();
  }

  private registerCommands(): void {
    // Befehls-IDs der Vorversion beibehalten - sonst verlieren Nutzer ihre Tastenkuerzel.
    this.addCommand({ id: "stoneintelligence-resync-all", name: "Jetzt synchronisieren", callback: () => this.syncNow() });
    this.addCommand({ id: "stoneintelligence-show-status", name: "Sync-Übersicht öffnen", callback: () => void this.activateStatusView() });
    this.addCommand({
      id: "stoneintelligence-login",
      name: "Anmelden",
      checkCallback: (checking) => {
        if (checking) {
          return !this.isLoggedIn();
        }
        this.runStatusAction("login");
        return true;
      },
    });
    this.addCommand({
      id: "stoneintelligence-logout",
      name: "Abmelden",
      checkCallback: (checking) => {
        if (checking) {
          return this.isLoggedIn();
        }
        void this.logout();
        return true;
      },
    });
    this.addCommand({
      id: "stoneintelligence-invite",
      name: "Mitbearbeiter einladen",
      checkCallback: (checking) => {
        if (checking) {
          return this.canInvite();
        }
        this.openInvite();
        return true;
      },
    });
    this.addCommand({
      id: "stoneintelligence-ai-changes",
      name: "KI-Änderungen anzeigen und rückgängig machen",
      checkCallback: (checking) => {
        if (checking) {
          return this.isReady();
        }
        this.openAiChanges();
        return true;
      },
    });
    this.addCommand({
      id: "stoneintelligence-toggle-pause",
      name: "Sync pausieren oder fortsetzen",
      callback: () => {
        this.setPaused(!this.settings.paused);
        new Notice(this.settings.paused ? "StoneIntelligence: Sync pausiert." : "StoneIntelligence: Sync läuft wieder.");
      },
    });
    this.addCommand({
      id: "stoneintelligence-resync-active",
      name: "Aktuelle Notiz neu verbinden",
      checkCallback: (checking) => {
        const file = this.app.workspace.getActiveFile();
        const available = file instanceof TFile && file.extension === "md" && this.isReady();
        if (checking || !file) {
          return available;
        }
        this.detachLive(file.path);
        void this.syncOpenEditorBindings();
        new Notice(`StoneIntelligence: „${basename(file.path)}“ wird neu verbunden.`);
        return true;
      },
    });
  }

  private registerFileMenu(): void {
    this.registerEvent(this.app.workspace.on("file-menu", (menu, file) => {
      if (!(file instanceof TFile) || file.extension !== "md" || !this.isReady()) {
        return;
      }
      const blocked = this.vaultState().blockedPaths[file.path];
      if (blocked === DELETION_DECISION_PENDING) {
        return;
      }
      menu.addItem((item) =>
        item.setTitle(blocked ? "StoneIntelligence: Erneut versuchen" : "StoneIntelligence: Jetzt synchronisieren")
          .setIcon("refresh-cw")
          .onClick(() => {
            delete this.vaultState().blockedPaths[file.path];
            this.activity.clearProblem(file.path);
            void this.runLocalChange(file.path);
          }),
      );
    }));
  }

  // ---------------------------------------------------------------------------------------------
  // Verbindung

  /**
   * Die EINE geteilte Sync-Verbindung. Holt bei JEDEM (Re-)Connect ein frisches Single-Use-Ticket
   * (ein Reconnect mit altem Ticket scheiterte garantiert mit 403). Nach jedem Connect wird ein
   * Abgleich angestossen - er holt nach, was an Vault-Ankuendigungen verpasst wurde.
   */
  private ensureTransport(): MultiplexedTransport {
    if (!this.transport) {
      this.transport = new MultiplexedTransport(() => this.issueFreshWsUrl(), (url) => new WebSocket(url), {
        onVaultEvent: (type, noteId, path) => void this.handleVaultEvent(type, noteId, path),
        onStateChange: (state) => {
          this.activity.update({ connection: state });
          if (state === "online") {
            // Notizen, die offline ohne gemeinsame Basis nicht gebunden werden konnten.
            void this.syncOpenEditorBindings();
          }
        },
        onConnected: () => this.requestPass(),
        subscribeContentUpdates: true,
        subscribeFolderEvents: true,
        subscribeFileEvents: true,
      });
    }
    return this.transport;
  }

  private async issueFreshWsUrl(): Promise<string> {
    const ticketClient = new TicketClient(this.settings.platformApiUrl.replace(/\/+$/, ""), this.getAccessToken);
    const ticket = await ticketClient.issueTicket(this.settings.vaultId);
    return `${wsUrlFor(this.settings)}/ws/sync?ticket=${encodeURIComponent(ticket.token)}`;
  }

  // ---------------------------------------------------------------------------------------------
  // Abgleich-Durchlauf

  /** Stoesst einen Durchlauf an; laeuft schon einer, folgt genau ein weiterer direkt danach. */
  requestPass(): void {
    if (!this.isReady()) {
      return;
    }
    if (this.passRunning) {
      this.passRequested = true;
      return;
    }
    void this.runPasses();
  }

  private async runPasses(): Promise<void> {
    this.passRunning = true;
    try {
      do {
        this.passRequested = false;
        await this.reconcileOnce();
      } while (this.passRequested && this.isReady());
    } finally {
      this.passRunning = false;
    }
  }

  private async reconcileOnce(): Promise<void> {
    const vaultId = this.settings.vaultId;
    const state = this.vaultState();
    await this.flushPendingOps();

    let serverNotes;
    let serverFiles: import("./sync/filePlan").ServerFile[] = [];
    const limits = await this.currentFileLimits();
    try {
      const entries = limits ? await this.noteApiClient.listAllEntries(vaultId) : await this.noteApiClient.listAllNotes(vaultId);
      serverNotes = entries.filter((entry) => entry.kind !== "FILE");
      serverFiles = entries.filter((entry) => entry.kind === "FILE")
        .map((entry) => ({ id: entry.id, path: entry.path, revision: entry.revision ?? 0, sha256: entry.sha256 ?? null }));
    } catch (error) {
      console.debug("StoneIntelligence: Notizliste nicht abrufbar", error);
      return;
    }
    if (vaultId !== this.settings.vaultId || !this.isReady()) {
      return;
    }

    const excluded = this.settings.excludedFolders;
    const fullPlan = planReconciliation({
      serverNotes: serverNotes.filter((note) => !isExcluded(note.path, excluded)),
      localFiles: this.app.vault.getMarkdownFiles()
        .filter((file) => !isExcluded(file.path, excluded))
        .map((file) => ({ path: file.path, mtime: file.stat.mtime, size: file.stat.size })),
      noteIds: Object.fromEntries(Object.entries(state.noteIds).filter(([path]) => !isExcluded(path, excluded))),
      meta: state.noteMeta,
      pendingNoteIds: new Set(state.pendingOps.filter(isNoteOp).map((op) => op.noteId)),
      livePaths: new Set([...this.live.keys(), ...this.liveStarting]),
      blockedPaths: state.blockedPaths,
    });
    const legacyServer = serverNotes.some((note) => note.revision === undefined);
    const legacyContentDue = Date.now() - this.lastLegacyContentPass >= LEGACY_FULL_CONTENT_INTERVAL_MS;
    const plan = legacyServer && !legacyContentDue
      ? fullPlan.filter((action) => !(action.kind === "sync" && action.serverRevision === null))
      : fullPlan;
    if (legacyServer && legacyContentDue) {
      this.lastLegacyContentPass = Date.now();
    }

    this.activity.beginPass(plan.length);
    try {
      await runWithConcurrency(plan, PASS_CONCURRENCY, async (action) => {
        if (vaultId === this.settings.vaultId && this.isReady()) {
          await this.executeAction(action);
        }
        this.activity.advancePass();
      });
    } finally {
      await this.saveSettings();
      this.activity.endPass(vaultId === this.settings.vaultId);
    }
    await this.reconcileFolders();
    if (limits && vaultId === this.settings.vaultId && this.isReady()) {
      await this.fileSync.reconcile(serverFiles.filter((file) => !isExcluded(file.path, this.settings.excludedFolders)),
        limits.maxFileBytes);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Dateien (PDFs, Bilder, Anhaenge - ADR 0009)

  /** Grenzen des Servers; `null`, wenn er keine Dateien kennt (dann bleibt alles wie vorher). */
  private async currentFileLimits(): Promise<FileLimits | null> {
    if (this.fileLimits !== undefined && Date.now() - this.fileLimitsAt < FILE_LIMITS_TTL_MS) {
      return this.fileLimits;
    }
    try {
      this.fileLimits = await this.noteApiClient.fileLimits();
      this.fileLimitsAt = Date.now();
    } catch (error) {
      console.debug("StoneIntelligence: Datei-Grenzen nicht abrufbar", error);
      return this.fileLimits ?? null;
    }
    return this.fileLimits;
  }

  private isTrackableFile(path: string): boolean {
    return this.isReady() && this.fileLimits !== null && isSyncableFilePath(path)
      && !isExcluded(path, this.settings.excludedFolders);
  }

  /** Lokal angelegt/geaendert: nach einer Pause hochladen (grosse Dateien entstehen schrittweise). */
  private scheduleLocalFileChange(path: string): void {
    const pending = this.fileChangeTimers.get(path);
    if (pending !== undefined) {
      window.clearTimeout(pending);
    }
    this.fileChangeTimers.set(path, window.setTimeout(() => {
      this.fileChangeTimers.delete(path);
      if (this.isTrackableFile(path) && !this.serverDrivenPaths.has(path)) {
        void this.currentFileLimits().then((limits) => limits && this.fileSync.localChanged(path, limits.maxFileBytes));
      }
    }, FILE_CHANGE_DEBOUNCE_MS));
  }

  /** Der Vault aus Sicht der Datei-Synchronisation; alles, was vom Server kommt, meldet sich nicht zurueck. */
  private fileVaultPort(): FileVaultPort {
    const fileAt = (path: string): TFile | null => {
      const file = this.app.vault.getAbstractFileByPath(path);
      return file instanceof TFile ? file : null;
    };
    const serverDriven = async (paths: string[], work: () => Promise<unknown>) => {
      paths.forEach((path) => this.serverDrivenPaths.add(path));
      try {
        await work();
      } finally {
        paths.forEach((path) => this.serverDrivenPaths.delete(path));
      }
    };
    return {
      list: () => this.app.vault.getFiles()
        .filter((file) => file.extension !== "md" && isSyncableFilePath(file.path) && !isExcluded(file.path, this.settings.excludedFolders))
        .map((file) => ({ path: file.path, mtime: file.stat.mtime, size: file.stat.size })),
      stat: (path) => {
        const file = fileAt(path);
        return file ? { mtime: file.stat.mtime, size: file.stat.size } : null;
      },
      read: async (path) => {
        const file = fileAt(path);
        if (!file) {
          throw new Error(`Datei ${path} fehlt`);
        }
        return this.app.vault.readBinary(file);
      },
      write: (path, bytes) => serverDriven([path], async () => {
        const existing = fileAt(path);
        if (existing) {
          await this.app.vault.modifyBinary(existing, bytes);
        } else {
          await this.ensureFolder(path);
          await this.app.vault.createBinary(path, bytes);
        }
      }),
      rename: (from, to) => serverDriven([from, to], async () => {
        const file = fileAt(from);
        if (file && !this.app.vault.getAbstractFileByPath(to)) {
          await this.ensureFolder(to);
          // Wie bei Notizen: ohne Links umzuschreiben und ohne Rueckfrage (s. applyRemoteRename).
          await this.app.vault.rename(file, to);
        }
      }),
      trash: (path) => serverDriven([path], async () => {
        const file = fileAt(path);
        if (file) {
          await this.app.fileManager.trashFile(file);
        }
      }),
    };
  }

  private async executeAction(action: ReconcileAction): Promise<void> {
    const path = action.kind === "renameLocal" ? action.from : action.path;
    try {
      switch (action.kind) {
        case "download":
          await this.downloadNote(action.noteId, action.path);
          break;
        case "adopt":
          this.mapNote(action.path, action.noteId);
          await this.syncContent(action.noteId, action.path, null, "adopt");
          break;
        case "upload":
          await this.uploadNote(action.path);
          break;
        case "sync":
          await this.syncContent(action.noteId, action.path, action.serverRevision, "sync");
          break;
        case "renameLocal":
          await this.applyRemoteRename(action.noteId, action.from, action.to);
          break;
        case "checkMissing":
          await this.resolveMissingNote(action.noteId, action.path);
          break;
      }
    } catch (error) {
      this.reportFailure(path, error);
    }
  }

  private reportFailure(path: string, error: unknown): void {
    if (error instanceof HttpError && (error.status === 401 || error.status === 403)) {
      this.activity.reportProblem(path, "Keine Berechtigung für diese Notiz.");
    } else if (error instanceof HttpError) {
      this.activity.reportProblem(path, `Server lehnte ab (HTTP ${error.status}).`);
    } else {
      // Netzwerkfehler: kein "Problem" der Notiz, der naechste Durchlauf versucht es erneut.
      console.debug(`StoneIntelligence: Abgleich von "${path}" fehlgeschlagen`, error);
    }
  }

  private mapNote(path: string, noteId: string): void {
    const state = this.vaultState();
    for (const [mappedPath, mappedId] of Object.entries(state.noteIds)) {
      if (mappedId === noteId && mappedPath !== path) {
        delete state.noteIds[mappedPath];
      }
    }
    state.noteIds[path] = noteId;
    this.requestSettingsSave();
  }

  private unmapNote(noteId: string): void {
    const state = this.vaultState();
    for (const [path, id] of Object.entries(state.noteIds)) {
      if (id === noteId) {
        delete state.noteIds[path];
      }
    }
    delete state.noteMeta[noteId];
    void this.stateStore.remove(this.settings.vaultId, noteId);
    this.requestSettingsSave();
  }

  private pathForNoteId(noteId: string): string | null {
    for (const [path, id] of Object.entries(this.vaultState().noteIds)) {
      if (id === noteId) {
        return path;
      }
    }
    return null;
  }

  private async ensureFolder(path: string): Promise<void> {
    const folder = parentFolder(path);
    if (folder && !this.app.vault.getAbstractFileByPath(folder)) {
      await this.createFolderFromServer(folder);
    }
  }

  /** Legt einen Ordner (samt Eltern) an, ohne dass der `create`-Event ihn zurueckmeldet. */
  private async createFolderFromServer(folder: string): Promise<void> {
    const chain = folder.split("/").map((_, index, parts) => parts.slice(0, index + 1).join("/"));
    chain.forEach((path) => this.serverDrivenPaths.add(path));
    try {
      await this.app.vault.createFolder(folder).catch(() => undefined);
    } finally {
      chain.forEach((path) => this.serverDrivenPaths.delete(path));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Ordner

  private isTrackableFolder(path: string): boolean {
    return this.isReady() && !this.folderSyncUnsupported && isSyncableFolderPath(path)
      && !isExcludedFolder(path, this.settings.excludedFolders);
  }

  /** Nach Ordner- oder Loesch-Ankuendigungen: gebuendelt einen Ordnerabgleich anstossen. */
  private scheduleFolderPass(): void {
    if (this.folderPassTimer !== null) {
      window.clearTimeout(this.folderPassTimer);
    }
    this.folderPassTimer = window.setTimeout(() => {
      this.folderPassTimer = null;
      void this.reconcileFolders();
    }, FOLDER_PASS_DEBOUNCE_MS);
  }

  private hasFilesInside(folder: TFolder): boolean {
    return folder.children.some((child) => child instanceof TFile || (child instanceof TFolder && this.hasFilesInside(child)));
  }

  private localFolders(): TFolder[] {
    return this.app.vault.getAllLoadedFiles()
      .filter((file): file is TFolder => file instanceof TFolder && this.isTrackableFolder(file.path));
  }

  /**
   * Ordner mit der Server-Liste abgleichen (s. {@link planFolders}): fehlende anlegen, hier neue
   * hochladen, anderswo geloeschte entfernen, sobald sie leer sind. Laeuft nie parallel zu sich selbst.
   */
  private async reconcileFolders(): Promise<void> {
    if (this.folderPassRunning) {
      this.folderPassRequested = true;
      return;
    }
    this.folderPassRunning = true;
    try {
      do {
        this.folderPassRequested = false;
        await this.reconcileFoldersOnce();
      } while (this.folderPassRequested && this.isReady());
    } finally {
      this.folderPassRunning = false;
    }
  }

  private async reconcileFoldersOnce(): Promise<void> {
    if (!this.isReady() || this.folderSyncUnsupported) {
      return;
    }
    const vaultId = this.settings.vaultId;
    await this.flushPendingOps();
    let serverFolders: string[] | null;
    try {
      serverFolders = await this.noteApiClient.listFolders(vaultId);
    } catch (error) {
      console.debug("StoneIntelligence: Ordnerliste nicht abrufbar", error);
      return;
    }
    if (serverFolders === null) {
      this.folderSyncUnsupported = true;
      return;
    }
    if (vaultId !== this.settings.vaultId || !this.isReady()) {
      return;
    }
    const state = this.vaultState();
    const plan = planFolders({
      serverFolders: serverFolders.filter((path) => this.isTrackableFolder(path)),
      knownFolders: state.knownFolders,
      localFolders: this.localFolders().map((folder) => ({ path: folder.path, hasFiles: this.hasFilesInside(folder) })),
      busyPaths: state.pendingOps.flatMap((op) => op.kind === "folderRename" ? [op.path, op.to]
        : op.kind === "folderCreate" || op.kind === "folderDelete" ? [op.path] : []),
    });

    for (const path of plan.createLocal) {
      if (!this.app.vault.getAbstractFileByPath(path)) {
        await this.createFolderFromServer(path);
      }
    }
    const failedUploads: string[] = [];
    for (const path of plan.upload) {
      try {
        await this.noteApiClient.createFolder(vaultId, path);
      } catch (error) {
        failedUploads.push(path);
        this.reportFailure(path, error);
      }
    }
    for (const path of plan.removeLocal) {
      const folder = this.app.vault.getAbstractFileByPath(path);
      // Zwischen Plan und jetzt kann etwas hineingekommen sein - dann bleibt der Ordner.
      if (!(folder instanceof TFolder) || this.hasFilesInside(folder)) {
        continue;
      }
      this.serverDrivenPaths.add(path);
      try {
        await this.app.fileManager.trashFile(folder);
        this.activity.log("deleted", path, "Ordner auf einem anderen Gerät gelöscht");
      } catch (error) {
        console.debug(`StoneIntelligence: Ordner "${path}" nicht entfernbar`, error);
      } finally {
        this.serverDrivenPaths.delete(path);
      }
    }
    // Nicht hochgeladene Ordner nicht als "bekannt" merken - sonst hielte der naechste Abgleich
    // sie fuer anderswo geloescht und raeumte sie weg.
    state.knownFolders = plan.known.filter((path) => !failedUploads.some((failed) => isInsideFolder(path, failed)));
    await this.saveSettings();
  }

  private queueLocalFolderOp(op: FolderOp): void {
    queueFolderOp(this.vaultState(), op);
    void this.saveSettings().then(() => this.flushPendingOps());
  }

  /** Auf dem Server vorhanden, lokal nicht: leere Datei anlegen, Inhalt abgleichen. */
  private async downloadNote(noteId: string, path: string): Promise<void> {
    if (this.app.vault.getAbstractFileByPath(path)) {
      return;
    }
    // Zuordnung VOR dem Anlegen: der `create`-Event sieht sie und laesst die Datei in Ruhe.
    this.mapNote(path, noteId);
    await this.ensureFolder(path);
    this.serverDrivenPaths.add(path);
    try {
      await this.app.vault.create(path, "");
    } finally {
      this.serverDrivenPaths.delete(path);
    }
    const result = await this.syncContent(noteId, path, null, "download");
    if (result && result.outcome !== "offline") {
      this.activity.log("downloaded", path);
    }
  }

  /**
   * Lokal vorhanden, auf dem Server nicht: anlegen und hochladen. Existiert der Pfad auf dem
   * Server doch schon (409, z. B. zeitgleich von einem anderen Geraet angelegt), wird DIESE Note
   * uebernommen statt eine zweite anzulegen.
   */
  private async uploadNote(path: string): Promise<string | null> {
    const state = this.vaultState();
    const existing = state.noteIds[path];
    if (existing) {
      return existing;
    }
    let noteId: string;
    let adopted = false;
    this.uploadingPaths.add(path);
    try {
      noteId = await this.noteApiClient.createNote(this.settings.vaultId, path, 1);
    } catch (error) {
      if (error instanceof HttpError && error.status === 409) {
        const match = (await this.noteApiClient.listAllNotes(this.settings.vaultId)).find((note) => note.path === path);
        if (!match) {
          throw error;
        }
        noteId = match.id;
        adopted = true;
      } else if (error instanceof HttpError && [400, 403].includes(error.status)) {
        const reason = error.status === 403 ? "Keine Berechtigung, hier Notizen anzulegen." : "Pfad vom Server abgelehnt.";
        state.blockedPaths[path] = reason;
        this.activity.reportProblem(path, reason);
        this.requestSettingsSave();
        return null;
      } else {
        throw error;
      }
    } finally {
      this.uploadingPaths.delete(path);
    }
    this.mapNote(path, noteId);
    const result = await this.syncContent(noteId, path, null, adopted ? "adopt" : "upload");
    if (!adopted && result && result.outcome !== "offline") {
      this.activity.log("uploaded", path);
    }
    return noteId;
  }

  /** Ein-Notiz-Abgleich im Hintergrund; offene Notizen erledigt die Live-Bindung. */
  private async syncContent(
    noteId: string,
    path: string,
    serverRevision: number | null,
    reason: "sync" | "adopt" | "download" | "upload" | "local",
  ): Promise<ContentSyncResult | null> {
    return this.withNoteLock(noteId, async () => {
      const liveSession = this.live.get(path);
      if (liveSession && liveSession.noteId !== noteId) {
        // Veraltete Live-Bindung (z. B. an eine anderswo geloeschte Notiz) - weg damit, sie darf den
        // Abgleich der aktuellen Zuordnung nicht verhindern. Die Neubindung folgt unten.
        this.detachLive(path);
      }
      if (this.live.has(path) || this.liveStarting.has(path) || this.pathForNoteId(noteId) !== path) {
        return null;
      }
      const result = await syncNoteContent(this.contentPorts(), noteId, path);
      if (result.outcome === "offline") {
        if (result.pendingLocalChanges) {
          this.markDirty(noteId, path);
        }
        return result;
      }
      this.recordMeta(noteId, path, serverRevision);
      this.activity.clearProblem(path);
      if (result.outcome === "conflict" && result.conflictPath) {
        this.reportConflict(path, result.conflictPath);
      } else if (reason === "sync" || reason === "local") {
        if (result.outcome === "pulled" || result.outcome === "merged" || result.outcome === "pushed") {
          this.activity.log(result.outcome, path);
        }
      }
      return result;
    });
  }

  private reportConflict(path: string, conflictPath: string): void {
    this.activity.log("conflict", path, `Lokale Fassung gesichert als „${basename(conflictPath)}“`);
    new Notice(
      `StoneIntelligence: „${basename(path)}“ war hier und auf dem Server unterschiedlich. `
        + `Die Server-Fassung ist jetzt aktiv, deine lokale liegt in „${basename(conflictPath)}“.`,
      10_000,
    );
  }

  /** Offline erfasste Aenderung merken - eine Loeschung von anderswo darf sie nicht wegraeumen. */
  private markDirty(noteId: string, path: string): void {
    const file = this.app.vault.getAbstractFileByPath(path);
    const state = this.vaultState();
    const meta = state.noteMeta[noteId];
    state.noteMeta[noteId] = meta
      ? { ...meta, dirty: true }
      : { revision: -1, mtime: file instanceof TFile ? file.stat.mtime : 0, size: file instanceof TFile ? file.stat.size : 0, dirty: true };
    this.requestSettingsSave();
  }

  private recordMeta(noteId: string, path: string, serverRevision: number | null): void {
    const file = this.app.vault.getAbstractFileByPath(path);
    if (!(file instanceof TFile)) {
      return;
    }
    // Ohne bekannte Revision (-1) gleicht der naechste Durchlauf einmal ab und merkt sie sich dann.
    this.vaultState().noteMeta[noteId] = { revision: serverRevision ?? -1, mtime: file.stat.mtime, size: file.stat.size };
    this.requestSettingsSave();
  }

  private withNoteLock<T>(noteId: string, task: () => Promise<T>): Promise<T> {
    const previous = this.noteLocks.get(noteId) ?? Promise.resolve();
    const run = previous.catch(() => undefined).then(task);
    const tail = run.catch(() => undefined);
    this.noteLocks.set(noteId, tail);
    void tail.then(() => {
      if (this.noteLocks.get(noteId) === tail) {
        this.noteLocks.delete(noteId);
      }
    });
    return run;
  }

  private contentPorts(): ContentSyncPorts {
    const vaultId = this.settings.vaultId;
    return {
      loadState: (noteId) => this.stateStore.load(vaultId, noteId),
      saveState: (noteId, state) => this.stateStore.save(vaultId, noteId, state),
      readFile: async (path) => {
        const file = this.app.vault.getAbstractFileByPath(path);
        if (!(file instanceof TFile)) {
          throw new Error(`Datei fehlt: ${path}`);
        }
        return this.app.vault.read(file);
      },
      writeFile: (path, content) => this.writeRemoteContent(path, content),
      writeConflictCopy: (path, content) => this.writeConflictCopy(path, content),
      connect: (noteId, doc) => this.connectBackground(noteId, doc),
    };
  }

  private async connectBackground(noteId: string, doc: Y.Doc): Promise<{ disconnect(): void } | null> {
    return this.transport ? connectForCatchup(this.transport, noteId, doc, CONTENT_SYNC_TIMEOUT_MS) : null;
  }

  /** Schreibt vom Server kommenden Inhalt - als eigene Aenderung markiert, damit sie nicht zurueckgemeldet wird. */
  private async writeRemoteContent(path: string, content: string): Promise<void> {
    const file = this.app.vault.getAbstractFileByPath(path);
    if (!(file instanceof TFile)) {
      return;
    }
    this.journal.registerSelfInitiated(crypto.randomUUID(), [`modify:${path}:${hashContent(content)}`]);
    await this.app.vault.modify(file, content);
  }

  private async writeConflictCopy(path: string, content: string): Promise<string> {
    const copyPath = conflictCopyPath(path, new Date(), (candidate) => this.app.vault.getAbstractFileByPath(candidate) !== null);
    // Die Kopie ist eine ganz normale neue Notiz - der `create`-Event laedt sie hoch.
    await this.app.vault.create(copyPath, content);
    return copyPath;
  }

  /** Auf einem anderen Geraet (oder waehrend dieses offline war) umbenannt: lokal nachziehen. */
  private async applyRemoteRename(noteId: string, from: string, to: string): Promise<void> {
    const file = this.app.vault.getAbstractFileByPath(from);
    if (!(file instanceof TFile) || this.app.vault.getAbstractFileByPath(to)) {
      return;
    }
    this.detachLive(from);
    await this.ensureFolder(to);
    this.serverDrivenPaths.add(from);
    this.serverDrivenPaths.add(to);
    try {
      // vault.rename statt fileManager.renameFile: das andere Geraet hat die Links schon angepasst (sie
      // kommen mit den Notizen). renameFile wuerde sie erneut umschreiben und dabei auf die Rueckfrage
      // "Links aktualisieren?" warten - der Abgleich hing dann, bis jemand den Dialog wegklickte.
      await this.app.vault.rename(file, to);
      this.mapNote(to, noteId);
    } finally {
      this.serverDrivenPaths.delete(from);
      this.serverDrivenPaths.delete(to);
    }
    this.activity.log("renamed", to, `Umbenannt von „${basename(from)}“`);
    void this.syncOpenEditorBindings();
  }

  /** Zugeordnete Notiz fehlt in der Server-Liste: geloescht oder nur nicht mehr sichtbar? */
  private async resolveMissingNote(noteId: string, path: string): Promise<void> {
    const status = await this.noteApiClient.noteStatus(this.settings.vaultId, noteId);
    if (status === "deleted") {
      await this.applyRemoteDeletion(noteId, path, await this.hasUnsyncedEdits(noteId, path));
    } else if (status === "forbidden") {
      this.detachLive(path);
      this.unmapNote(noteId);
      this.vaultState().blockedPaths[path] = "Kein Zugriff mehr auf diese Notiz.";
      this.activity.reportProblem(path, "Kein Zugriff mehr auf diese Notiz. Die lokale Datei bleibt unverändert.");
    }
  }

  /**
   * Anderswo geloescht: in den Papierkorb (Obsidians Einstellung "Geloeschte Dateien" gilt) -
   * aber NUR, wenn die Datei hier seit dem letzten Abgleich unveraendert ist. Sonst bleibt sie
   * und wird als neue Notiz wieder hochgeladen; eine Loeschung darf keine ungesicherte Arbeit fressen.
   */
  private async applyRemoteDeletion(noteId: string, path: string, locallyChanged: boolean): Promise<void> {
    this.detachLive(path);
    this.unmapNote(noteId);
    const file = this.app.vault.getAbstractFileByPath(path);
    if (!(file instanceof TFile)) {
      return;
    }
    if (locallyChanged) {
      this.vaultState().blockedPaths[path] = DELETION_DECISION_PENDING;
      this.requestSettingsSave();
      this.askDeletionDecision(path);
      return;
    }
    this.serverDrivenPaths.add(path);
    try {
      await this.app.fileManager.trashFile(file);
    } finally {
      this.serverDrivenPaths.delete(path);
    }
    this.activity.log("deleted", path, "Auf einem anderen Gerät gelöscht, im Papierkorb");
  }

  /** Offene Loeschentscheidungen (z. B. von vor einem Neustart) erneut stellen. */
  private askPendingDeletionDecisions(): void {
    for (const [path, reason] of Object.entries(this.vaultState().blockedPaths)) {
      if (reason === DELETION_DECISION_PENDING) {
        this.askDeletionDecision(path);
      }
    }
  }

  /**
   * Fragt per Dialog (15s-Countdown, Standard Loeschen) und bietet dieselbe Wahl in der
   * Seitenleiste an. Die zuerst getroffene Wahl gilt, jede weitere ist wirkungslos.
   */
  private askDeletionDecision(path: string): void {
    if (this.decisionsAsked.has(path)) {
      return;
    }
    this.decisionsAsked.add(path);
    let settled = false;
    const decide = (keep: boolean): void => {
      if (settled) {
        return;
      }
      settled = true;
      void this.resolveDeletionDecision(path, keep);
    };
    this.activity.reportProblem(path, "Anderswo gelöscht, hier aber noch nicht übertragene Änderungen.", [
      { label: "Behalten", run: () => decide(true) },
      { label: "Löschen", run: () => decide(false) },
    ]);
    this.decisionQueue = this.decisionQueue.then(() => new Promise<void>((done) => {
      if (settled || !(this.app.vault.getAbstractFileByPath(path) instanceof TFile)) {
        done();
        return;
      }
      const modal = new DeletionConflictModal(this.app, path, () => decide(true), () => decide(false));
      const originalOnClose = modal.onClose.bind(modal);
      modal.onClose = () => {
        originalOnClose();
        done();
      };
      modal.open();
    }));
  }

  private async resolveDeletionDecision(path: string, keep: boolean): Promise<void> {
    this.decisionsAsked.delete(path);
    this.activity.clearProblem(path);
    const state = this.vaultState();
    if (state.blockedPaths[path] === DELETION_DECISION_PENDING) {
      delete state.blockedPaths[path];
    }
    await this.saveSettings();
    const file = this.app.vault.getAbstractFileByPath(path);
    if (!(file instanceof TFile)) {
      return;
    }
    if (keep && file.extension !== "md") {
      this.activity.log("kept", path, "Behalten – wird als neue Datei hochgeladen");
      await this.fileSync.keepAfterDeletion(path);
      return;
    }
    if (keep) {
      this.activity.log("kept", path, "Behalten – wird als neue Notiz hochgeladen");
      // Eine noch an die geloeschte Notiz gebundene Editor-Sitzung zuerst loesen, sonst wird der
      // Inhalt nicht hochgeladen; danach den offenen Editor an die neue Notiz binden.
      this.detachLive(path);
      await this.runLocalChange(path);
      void this.syncOpenEditorBindings();
      return;
    }
    this.serverDrivenPaths.add(path);
    try {
      await this.app.fileManager.trashFile(file);
    } finally {
      this.serverDrivenPaths.delete(path);
    }
    this.activity.log("deleted", path, "Auf einem anderen Gerät gelöscht, im Papierkorb");
  }

  /**
   * Belegte, nie uebertragene lokale Aenderungen? (s. {@link hasUnsyncedLocalEdits}). Eine
   * geoeffnete, live gebundene Notiz ist per Definition synchron - jede Eingabe ging sofort raus.
   */
  private async hasUnsyncedEdits(noteId: string, path: string): Promise<boolean> {
    const file = this.app.vault.getAbstractFileByPath(path);
    if (!(file instanceof TFile) || (this.live.has(path) && this.transport?.state === "online")) {
      return false;
    }
    const meta = this.vaultState().noteMeta[noteId];
    return hasUnsyncedLocalEdits({
      dirty: meta?.dirty === true,
      statUnchanged: meta !== undefined && meta.mtime === file.stat.mtime && meta.size === file.stat.size,
      lastSyncedText: textOfState(await this.stateStore.load(this.settings.vaultId, noteId)),
      currentText: await this.app.vault.read(file),
    });
  }


  /**
   * Uebertraegt offline gemerkte Loeschungen/Umbenennungen in Reihenfolge. Netzwerkfehler: Rest
   * bleibt fuer den naechsten Versuch liegen. Endgueltige Ablehnung (4xx): verworfen und gemeldet;
   * der naechste Abgleich stellt dann den Server-Stand wieder her.
   */
  private async flushPendingOps(): Promise<void> {
    if (this.flushingOps || !this.isReady()) {
      return;
    }
    this.flushingOps = true;
    const vaultId = this.settings.vaultId;
    const state = this.vaultState();
    try {
      while (state.pendingOps.length > 0 && vaultId === this.settings.vaultId) {
        const op = state.pendingOps[0];
        try {
          if (op.kind === "delete") {
            await this.noteApiClient.deleteNote(vaultId, op.noteId, op.operationId);
          } else if (op.kind === "rename") {
            await this.noteApiClient.renameNote(vaultId, op.noteId, op.path);
          } else if (op.kind === "folderCreate") {
            await this.noteApiClient.createFolder(vaultId, op.path);
          } else if (op.kind === "folderRename") {
            await this.noteApiClient.renameFolder(vaultId, op.path, op.to);
          } else {
            await this.noteApiClient.deleteFolder(vaultId, op.path);
          }
        } catch (error) {
          const permanent = error instanceof HttpError && error.status >= 400 && error.status < 500
            && error.status !== 408 && error.status !== 429;
          if (!permanent) {
            break;
          }
          if (op.kind === "rename" && (error as HttpError).status === 404) {
            // Note inzwischen anderswo geloescht - der Abgleich erledigt den Rest.
          } else if (!isNoteOp(op)) {
            if ((error as HttpError).status === 404) {
              // Server ohne Ordner-Synchronisation.
              this.folderSyncUnsupported = true;
            } else {
              this.activity.reportProblem(op.path, "Ordneränderung vom Server abgelehnt – der Server-Stand wird wiederhergestellt.");
            }
          } else {
            this.activity.reportProblem(op.path, op.kind === "delete"
              ? "Löschen vom Server abgelehnt – die Notiz wird wiederhergestellt."
              : "Umbenennen vom Server abgelehnt – der Server-Name wird wiederhergestellt.");
          }
        }
        state.pendingOps.shift();
        await this.saveSettings();
        this.activity.touch();
      }
    } finally {
      this.flushingOps = false;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Vault-Ankuendigungen anderer Geraete (kommen ueber die Dauerverbindung sofort an)

  private async handleVaultEvent(messageType: number, noteId: string, path: string): Promise<void> {
    if (!this.isReady() || isExcluded(path, this.settings.excludedFolders)) {
      return;
    }
    if (messageType === VAULT_FOLDERS_CHANGED) {
      this.scheduleFolderPass();
      return;
    }
    const state = this.vaultState();
    if (state.pendingOps.some((op) => isNoteOp(op) && op.noteId === noteId)) {
      return;
    }
    // Dateien (ADR 0009) - an Id oder Endung erkennbar; nie als Notiz behandeln.
    if (this.fileSync.isFileId(noteId) || !path.toLowerCase().endsWith(".md")) {
      if (messageType === VAULT_NOTE_DELETED) {
        await this.fileSync.remoteDeleted(noteId);
        this.scheduleFolderPass();
      } else {
        // Anlage, neue Fassung, Umbenennung: der Abgleich holt genau das Noetige.
        this.requestPass();
      }
      return;
    }
    try {
      const localPath = this.pathForNoteId(noteId);
      if (messageType === VAULT_NOTE_UPDATED) {
        if (localPath && !this.live.has(localPath)) {
          this.scheduleRemoteChange(noteId);
        }
        return;
      }
      if (messageType === VAULT_NOTE_CREATED) {
        if (localPath || this.uploadingPaths.has(path)) {
          return;
        }
        const existing = this.app.vault.getAbstractFileByPath(path);
        if (existing instanceof TFile && !state.noteIds[path]) {
          this.mapNote(path, noteId);
          await this.syncContent(noteId, path, null, "adopt");
        } else if (!existing) {
          await this.downloadNote(noteId, path);
        }
        // Die Ankuendigung kommt, sobald die Notiz existiert - ihr Inhalt wird vom anlegenden
        // Geraet erst danach hochgeladen. Kurz darauf noch einmal abgleichen.
        window.setTimeout(() => this.requestPass(), CREATED_FOLLOW_UP_MS);
      } else if (messageType === VAULT_NOTE_RENAMED) {
        if (localPath && localPath !== path) {
          await this.applyRemoteRename(noteId, localPath, path);
        }
        // Der alte Ordner kann jetzt leer und anderswo schon geloescht sein.
        this.scheduleFolderPass();
      } else if (messageType === VAULT_NOTE_DELETED) {
        if (localPath) {
          await this.applyRemoteDeletion(noteId, localPath, await this.hasUnsyncedEdits(noteId, localPath));
        }
        this.scheduleFolderPass();
      }
      await this.saveSettings();
    } catch (error) {
      this.reportFailure(path, error);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Lokale Aenderungen

  /**
   * Ob ein Pfad gerade synchronisiert werden darf. Nicht, solange zu ihm eine Loeschentscheidung
   * offen ist - sonst legte z. B. die Live-Bindung eines offenen Editors schon vor der Entscheidung
   * eine neue Notiz an.
   */
  private isTrackable(path: string): boolean {
    return this.isReady() && isSyncablePath(path) && !isExcluded(path, this.settings.excludedFolders)
      && this.vaultState().blockedPaths[path] !== DELETION_DECISION_PENDING;
  }

  private handleLocalCreate(file: TAbstractFile): void {
    if (file instanceof TFolder) {
      // Beim Laden des Vaults meldet Obsidian jeden vorhandenen Ordner als "angelegt" - das ist
      // Sache des Ordnerabgleichs, der zwischen neu und anderswo geloescht unterscheiden kann.
      const known = this.vaultState().knownFolders ?? [];
      if (this.app.workspace.layoutReady && !this.serverDrivenPaths.has(file.path) && this.isTrackableFolder(file.path) && !known.includes(file.path)) {
        this.queueLocalFolderOp({ kind: "folderCreate", path: file.path });
      }
      return;
    }
    if (file instanceof TFile && file.extension !== "md") {
      if (this.app.workspace.layoutReady && !this.serverDrivenPaths.has(file.path) && this.isTrackableFile(file.path)) {
        this.scheduleLocalFileChange(file.path);
      }
      return;
    }
    if (!(file instanceof TFile) || file.extension !== "md" || this.serverDrivenPaths.has(file.path)) {
      return;
    }
    if (!this.isTrackable(file.path) || this.vaultState().noteIds[file.path]) {
      return;
    }
    this.scheduleLocalChange(file.path);
  }

  private async handleLocalModify(file: TAbstractFile): Promise<void> {
    if (file instanceof TFile && file.extension !== "md") {
      if (!this.serverDrivenPaths.has(file.path) && this.isTrackableFile(file.path)) {
        this.scheduleLocalFileChange(file.path);
      }
      return;
    }
    if (!(file instanceof TFile) || file.extension !== "md" || !this.isTrackable(file.path)) {
      return;
    }
    if (this.live.has(file.path)) {
      // yCollab hat die Tastatureingaben laengst im Y.Text - das ist nur Obsidians Autosave.
      return;
    }
    const content = await this.app.vault.cachedRead(file);
    if (this.journal.correlate(`modify:${file.path}:${hashContent(content)}`)) {
      return;
    }
    const state = this.vaultState();
    if (state.blockedPaths[file.path] === DELETION_DECISION_PENDING) {
      // Erst entscheiden, dann hochladen - weiteres Tippen aendert an der offenen Frage nichts.
      return;
    }
    if (state.blockedPaths[file.path]) {
      // Geaendert -> neuer Versuch (z. B. nachdem Rechte vergeben wurden).
      delete state.blockedPaths[file.path];
      this.activity.clearProblem(file.path);
    }
    this.scheduleLocalChange(file.path);
  }

  private scheduleLocalChange(path: string): void {
    window.clearTimeout(this.localChangeTimers.get(path));
    this.localChangeTimers.set(path, window.setTimeout(() => {
      this.localChangeTimers.delete(path);
      void this.runLocalChange(path);
    }, LOCAL_CHANGE_DEBOUNCE_MS));
  }

  private scheduleRemoteChange(noteId: string): void {
    window.clearTimeout(this.remoteChangeTimers.get(noteId));
    this.remoteChangeTimers.set(noteId, window.setTimeout(() => {
      this.remoteChangeTimers.delete(noteId);
      const path = this.pathForNoteId(noteId);
      if (path && this.isReady()) {
        void this.syncContent(noteId, path, null, "sync").catch((error) => this.reportFailure(path, error));
      }
    }, REMOTE_CHANGE_DEBOUNCE_MS));
  }

  private async runLocalChange(path: string): Promise<void> {
    if (!this.isTrackable(path) || !(this.app.vault.getAbstractFileByPath(path) instanceof TFile)) {
      return;
    }
    try {
      const noteId = this.vaultState().noteIds[path];
      if (noteId) {
        await this.syncContent(noteId, path, null, "local");
      } else {
        await this.uploadNote(path);
      }
    } catch (error) {
      this.reportFailure(path, error);
    }
  }

  private async handleLocalDelete(file: TAbstractFile): Promise<void> {
    if (!this.settings.vaultId || this.serverDrivenPaths.has(file.path)) {
      return;
    }
    const state = this.vaultState();
    // War das der letzte Inhalt eines anderswo geloeschten Ordners (z. B. ein Anhang), kann der
    // Ordner jetzt weg.
    if (!(file instanceof TFolder) && parentFolder(file.path)
      && state.knownFolders?.includes(parentFolder(file.path))) {
      this.scheduleFolderPass();
    }
    if (state.blockedPaths[file.path] === DELETION_DECISION_PENDING) {
      // Die offene Frage hat sich erledigt - die Person hat selbst geloescht.
      delete state.blockedPaths[file.path];
      this.decisionsAsked.delete(file.path);
      this.activity.clearProblem(file.path);
      this.requestSettingsSave();
    }
    const affected = file instanceof TFolder
      ? Object.keys(state.noteIds).filter((path) => path.startsWith(`${file.path}/`))
      : [file.path];
    let changed = false;
    for (const path of affected) {
      const noteId = state.noteIds[path];
      if (!noteId) {
        continue;
      }
      this.detachLive(path);
      delete state.noteIds[path];
      delete state.noteMeta[noteId];
      void this.stateStore.remove(this.settings.vaultId, noteId);
      queueDelete(state, noteId, path, crypto.randomUUID());
      this.activity.log("deleted", path, "Gelöscht");
      changed = true;
    }
    // Dateien (ADR 0009): dieselbe Loeschung auf dem Server, eigene Zuordnung hier.
    const affectedFiles = file instanceof TFolder
      ? Object.keys(state.fileIds).filter((path) => path.startsWith(`${file.path}/`))
      : [file.path];
    for (const path of affectedFiles) {
      const fileId = this.fileSync.localDeleted(path);
      if (fileId) {
        queueDelete(state, fileId, path, crypto.randomUUID());
        this.activity.log("deleted", path, "Gelöscht");
        changed = true;
      }
    }
    if (file instanceof TFolder && this.isTrackableFolder(file.path)) {
      // Nach den Notiz-Loeschungen: der Server entfernt den Ordner samt allem darunter, die anderen
      // Geraete raeumen ihn weg, sobald ihre Notizen darin geloescht sind.
      queueFolderOp(state, { kind: "folderDelete", path: file.path });
      changed = true;
    }
    if (changed) {
      await this.saveSettings();
      void this.flushPendingOps();
    }
  }

  /**
   * Umbenennen/Verschieben: NoteId bleibt stabil (Fehlerklasse 5), nur die Zuordnung wandert mit.
   * Ordner-Umbenennungen tragen alle enthaltenen Notizen mit; kommen zusaetzlich Einzel-Events fuer
   * die Kinder, finden sie ihre Zuordnung schon am neuen Ort vor und tun nichts.
   */
  private async handleLocalRename(file: TAbstractFile, oldPath: string): Promise<void> {
    if (!this.settings.vaultId || this.serverDrivenPaths.has(oldPath) || this.serverDrivenPaths.has(file.path)) {
      return;
    }
    const state = this.vaultState();
    let folderChanged = false;
    if (file instanceof TFolder) {
      const from = this.isTrackableFolder(oldPath);
      const to = this.isTrackableFolder(file.path);
      if (from || to) {
        queueFolderOp(state, from && to ? { kind: "folderRename", path: oldPath, to: file.path }
          : to ? { kind: "folderCreate", path: file.path } : { kind: "folderDelete", path: oldPath });
        folderChanged = true;
      }
    }
    const moves: Array<[string, string]> = file instanceof TFolder
      ? Object.keys(state.noteIds)
        .filter((path) => path.startsWith(`${oldPath}/`))
        .map((path) => [path, `${file.path}/${path.slice(oldPath.length + 1)}`])
      : [[oldPath, file.path]];

    let changed = false;
    for (const [from, to] of moves) {
      const noteId = state.noteIds[from];
      if (!noteId) {
        if (file instanceof TFile && !state.noteIds[to] && this.isTrackable(to)) {
          this.scheduleLocalChange(to);
        }
        continue;
      }
      delete state.noteIds[from];
      state.noteIds[to] = noteId;
      queueRename(state, noteId, to);
      const session = this.live.get(from);
      if (session) {
        this.live.delete(from);
        session.path = to;
        this.live.set(to, session);
      }
      changed = true;
    }
    const fileMoves: Array<[string, string]> = file instanceof TFolder
      ? Object.keys(state.fileIds)
        .filter((path) => path.startsWith(`${oldPath}/`))
        .map((path) => [path, `${file.path}/${path.slice(oldPath.length + 1)}`])
      : file instanceof TFile && file.extension !== "md" ? [[oldPath, file.path]] : [];
    for (const [from, to] of fileMoves) {
      const fileId = this.fileSync.localRenamed(from, to);
      if (fileId) {
        queueRename(state, fileId, to);
        changed = true;
      } else if (this.isTrackableFile(to)) {
        this.scheduleLocalFileChange(to);
      }
    }
    if (changed || folderChanged) {
      await this.saveSettings();
      void this.flushPendingOps();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Live-Bindung geoeffneter Notizen

  /**
   * Alle offenen Markdown-Panes samt CM6-`EditorView` (`editor.cm` ist der in der Community
   * etablierte, inoffizielle Zugriffspfad). Nicht nur "das aktive": `activeEditor` zeigt beim
   * `file-open` oft noch auf die vorige Datei - daran scheiterten frueher die Live-Cursor.
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
      // Nach dem Loeschen meldet das Leaf kurz noch eine Stale-TFile - nie binden (sonst schreibt
      // der Catchup den alten Inhalt zurueck und die geloeschte Datei ist wieder da).
      if (!(this.app.vault.getAbstractFileByPath(file.path) instanceof TFile)) {
        continue;
      }
      const view = (markdownView.editor as unknown as { cm?: EditorView }).cm;
      if (!view) {
        this.editorViewPending = true;
        continue;
      }
      result.push({ file, view });
    }
    return result;
  }

  private async syncOpenEditorBindings(): Promise<void> {
    const generation = ++this.liveBindGeneration;
    this.editorViewPending = false;
    const open = this.isReady()
      ? this.openMarkdownEditors().filter((entry) => this.isTrackable(entry.file.path))
      : [];
    if (this.editorViewPending) {
      this.scheduleBindingRetry();
    } else {
      this.bindingRetries = 0;
    }
    const bound = new Map([...this.live].map(([path, session]) => [path, session.view]));
    const plan = planEditorBindings(open.map((entry) => ({ path: entry.file.path, view: entry.view })), bound);

    for (const { path } of plan.unbind) {
      this.detachLive(path);
    }
    for (const { path } of plan.bind) {
      if (generation !== this.liveBindGeneration) {
        return;
      }
      const entry = open.find((candidate) => candidate.file.path === path);
      if (entry && !this.liveStarting.has(path)) {
        await this.startLive(entry.file, entry.view);
      }
    }
    this.updatePresenceBar();
    this.activity.touch();
  }

  private scheduleBindingRetry(): void {
    if (this.bindingRetries >= BINDING_RETRY_LIMIT) {
      return;
    }
    this.bindingRetries++;
    window.clearTimeout(this.bindingRetryTimer ?? undefined);
    this.bindingRetryTimer = window.setTimeout(() => void this.syncOpenEditorBindings(), BINDING_RETRY_DELAY_MS);
  }

  /**
   * Oeffnet die Live-Session einer Notiz: lokaler Zustand + seit dem letzten Abgleich geaenderte
   * Datei -> joinen -> Server-Catchup abwarten -> Editor angleichen -> binden.
   *
   * <p>Die Datei wird hier NIE per `vault.modify` beschrieben: der Editor bekommt den Stand per
   * Diff, Obsidians Autosave schreibt ihn. Ein paralleles Neuladen der offenen Datei von der
   * Platte haette sonst mit yCollab konkurriert.
   */
  private async startLive(file: TFile, view: EditorView): Promise<void> {
    const path = file.path;
    let remappedMeanwhile = false;
    this.liveStarting.add(path);
    try {
      const noteId = this.vaultState().noteIds[path] ?? (await this.uploadNote(path));
      if (!noteId || !this.transport) {
        return;
      }
      await this.withNoteLock(noteId, async () => {
        const transport = this.transport;
        if (!transport || this.live.has(path)) {
          return;
        }
        const ports = this.contentPorts();
        const { doc, hadBase, localContent } = await prepareNoteDoc(ports, noteId, path);
        const text = doc.getText("content");
        const client = new SyncClient("", () => transport.createVirtualSocket(noteId, { priority: true }), doc);
        const name = this.actorDisplayName();
        client.awareness.setLocalStateField("user", { name, color: pickUserColor(name) });
        client.connect();
        const caughtUp = (await awaitConnected(client, LIVE_CATCHUP_WAIT_MS))
          && (await awaitCatchupComplete(client, LIVE_CATCHUP_WAIT_MS));

        if (!hadBase) {
          if (!caughtUp) {
            // Noch nie abgeglichen UND Server-Stand unbekannt: nicht binden (kein sicherer Merge
            // moeglich). Sobald die Verbindung steht, wird es erneut versucht.
            client.disconnect();
            doc.destroy();
            return;
          }
          const result = await resolveFirstContact(
            { writeFile: async () => undefined, writeConflictCopy: (p, c) => this.writeConflictCopy(p, c) },
            text, path, localContent,
          );
          if (result.outcome === "conflict" && result.conflictPath) {
            this.reportConflict(path, result.conflictPath);
          }
        }

        // Waehrend der Awaits kann das Pane geschlossen oder auf eine andere Datei umgestellt
        // worden sein - dann zeigt diese View diesen Pfad nicht mehr und darf nicht gebunden werden.
        const stillShown = this.openMarkdownEditors().some((candidate) => candidate.file.path === path && candidate.view === view);
        // Ebenso kann die Notiz waehrend der Awaits anderswo geloescht und hier neu zugeordnet
        // worden sein. Eine Bindung an die alte Id liesse den Editor ins Leere schreiben - und
        // blockierte den Upload unter der neuen Id (behaltene Notiz kam bei anderen leer an).
        const stillMapped = this.vaultState().noteIds[path] === noteId && this.isTrackable(path);
        remappedMeanwhile = stillShown && !stillMapped;
        if (!stillShown || !stillMapped || this.live.has(path)) {
          client.disconnect();
          doc.destroy();
          return;
        }
        await this.stateStore.save(this.settings.vaultId, noteId, Y.encodeStateAsUpdate(doc));
        bindEditorToText(view, this.liveBindingCompartment, text, client.awareness, localContent);

        const session: LiveSession = { path, noteId, client, view, saveTimer: null, stopStateSaves: () => undefined };
        const onUpdate = (): void => this.scheduleLiveStateSave(session);
        doc.on("update", onUpdate);
        const onAwareness = (): void => {
          this.updatePresenceBar();
          this.activity.touch();
        };
        client.awareness.on("change", onAwareness);
        session.stopStateSaves = () => {
          doc.off("update", onUpdate);
          client.awareness.off("change", onAwareness);
        };
        client.onNoteDeleted = () => {
          // Der Notiz-Raum ist zu; Datei/Zuordnung erledigt die vault-weite Loeschmeldung.
          this.detachLive(session.path);
        };
        this.live.set(path, session);
        this.activity.clearProblem(path);
      });
    } catch (error) {
      this.reportFailure(path, error);
    } finally {
      this.liveStarting.delete(path);
      // Waehrend dieses Versuchs wurde die Notiz neu zugeordnet (z. B. nach "Behalten" in einem
      // Loeschkonflikt). Ein dabei angestossener Bindungsversuch wurde uebersprungen, weil dieser
      // hier noch lief - also jetzt neu binden, sonst bliebe der offene Editor ungebunden.
      if (remappedMeanwhile) {
        void this.syncOpenEditorBindings();
      }
    }
  }

  private scheduleLiveStateSave(session: LiveSession): void {
    if (session.saveTimer !== null) {
      return;
    }
    session.saveTimer = window.setTimeout(() => {
      session.saveTimer = null;
      void this.stateStore.save(this.settings.vaultId, session.noteId, Y.encodeStateAsUpdate(session.client.doc));
    }, STATE_SAVE_DEBOUNCE_MS);
  }

  /** Loest die Bindung, sichert den Zustand; die Notiz faellt zurueck in den Abgleich-Durchlauf. */
  private detachLive(path: string): void {
    const session = this.live.get(path);
    if (!session) {
      return;
    }
    this.live.delete(path);
    try {
      unbindEditor(session.view, this.liveBindingCompartment);
    } catch (error) {
      // View kann mit ihrem geschlossenen Leaf bereits zerstoert sein - harmlos.
      console.debug("StoneIntelligence: Editor-Bindung konnte nicht geloest werden", path, error);
    }
    window.clearTimeout(session.saveTimer ?? undefined);
    session.stopStateSaves();
    const state = Y.encodeStateAsUpdate(session.client.doc);
    session.client.disconnect();
    const vaultId = this.settings.vaultId;
    const vaultState = this.settings.vaults[vaultId];
    if (vaultState?.noteIds[session.path] === session.noteId) {
      void this.stateStore.save(vaultId, session.noteId, state);
      // Autosave hat die Datei beschrieben, ohne dass der Abgleich es mitbekam: einmal nachziehen.
      const meta = vaultState.noteMeta[session.noteId];
      if (meta) {
        meta.revision = -1;
      }
    }
    this.updatePresenceBar();
    this.activity.touch();
  }
}
