import { Compartment } from "@codemirror/state";
import type { EditorView } from "@codemirror/view";
import { App, Notice, Platform, Plugin, PluginSettingTab, Setting, TFile } from "obsidian";
import { yCollab } from "y-codemirror.next";
import * as Y from "yjs";
import { type SessionSnapshot, StatusView, VIEW_TYPE_STATUS } from "./StatusView";
import { AuthentikAuthClient, type StoredTokens } from "./sync/AuthentikAuthClient";
import { awaitDesktopRedirectCode, DESKTOP_REDIRECT_URI, openAuthorizationUrlDesktop } from "./sync/desktopAuthRedirect";
import {
  handleMobileRedirectCallback, MOBILE_REDIRECT_ACTION, MOBILE_REDIRECT_URI,
  openAuthorizationUrlMobile, type PendingAuthCallback,
} from "./sync/mobileAuthRedirect";
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
  private noteApiClient!: NoteApiClient;
  private authClient!: AuthentikAuthClient;
  private tokenEndpoint: string | null = null;
  private readonly liveBindingCompartment = new Compartment();
  private liveBoundPath: string | null = null;
  private statusBarItem!: HTMLElement;
  private pendingAuthCallback: PendingAuthCallback | null = null;

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
    if (!this.tokenEndpoint) {
      this.tokenEndpoint = (await this.authClient.discover()).token_endpoint;
    }
    const refreshed = await this.authClient.refreshAccessToken(this.tokenEndpoint, tokens.refreshToken);
    this.settings.tokens = refreshed;
    await this.saveData(this.settings);
    return refreshed.accessToken;
  };

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
        if (file instanceof TFile && file.extension === "md") {
          void this.startSync(file);
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
          void this.startSync(file);
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
   * iterierte. Legt fuer jede fehlende Notiz eine leere lokale Platzhalterdatei an (der
   * `create`-Vault-Event startet darueber automatisch `startSync`, das dann per Late-Joiner-
   * Catchup den echten Inhalt vom Server einspielt, s. `startSync`).
   */
  private async reconcileMissingNotesFromServer(): Promise<void> {
    const localPaths = new Set(this.app.vault.getMarkdownFiles().map((f) => f.path));
    const serverNotes = await this.noteApiClient.listAllNotes(this.settings.vaultId);

    for (const note of serverNotes) {
      if (localPaths.has(note.path)) {
        continue;
      }
      // NoteId VOR dem Anlegen der Datei eintragen: der `create`-Event-Handler ruft `startSync`
      // auf, dessen `ensureNoteId` sonst eine ZWEITE Note fuer denselben Pfad anlegen wuerde
      // (Fehlerklasse 5) statt die bereits vorhandene Server-Note zu uebernehmen.
      this.settings.noteIds[note.path] = note.id;
      await this.saveSettings();

      const folderPath = note.path.split("/").slice(0, -1).join("/");
      if (folderPath && !this.app.vault.getAbstractFileByPath(folderPath)) {
        await this.app.vault.createFolder(folderPath).catch(() => {
          // Race mit einer anderen gerade heruntergeladenen Notiz im selben Ordner - harmlos.
        });
      }
      await this.app.vault.create(note.path, "");
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

  private async startSync(file: TFile): Promise<void> {
    if (this.sessions.has(file.path) || !this.settings.vaultId) {
      return;
    }

    const noteId = await this.ensureNoteId(file);
    const ticketClient = new TicketClient(this.settings.platformApiUrl, this.getAccessToken);
    const ticket = await ticketClient.issueTicket(this.settings.vaultId, noteId);

    const doc = new Y.Doc();
    const text = doc.getText("content");

    // WICHTIG: Client zuerst verbinden, DANACH erst lokalen Inhalt einspielen. SyncClients
    // Update-Listener wird im Konstruktor registriert - wuerde man den Inhalt vorher einfuegen,
    // wuerde das erzeugte Yjs-Update verpuffen (kein Listener vorhanden), und die Notiz wuerde
    // nie zum Server uebertragen werden.
    const wsUrl = `${this.settings.platformWsUrl}/ws/sync?ticket=${encodeURIComponent(ticket.token)}`;
    const client = new SyncClient(wsUrl, (url) => new WebSocket(url), doc);
    client.onNoteDeleted = () => this.handleRemoteNoteDeleted(file.path);
    client.connect();

    // Kurze Gnadenfrist, damit ein eventueller Late-Joiner-Catchup (die Notiz hat bereits
    // Server-Historie von einem anderen Client) eintreffen kann, BEVOR wir den lokalen
    // Dateiinhalt einspielen - sonst wuerden zwei unabhaengige volle Texte additiv im selben
    // Y.Text landen (CRDT-Merge, kein "letzter gewinnt"). Eine echte "Catchup abgeschlossen"-
    // Markierung gibt es im WS-Protokoll noch nicht (dokumentierte Grenze).
    await new Promise((resolve) => setTimeout(resolve, 300));

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
  }

  /**
   * Bindet (oder loest) das echte CodeMirror-6-Live-Binding fuer die gerade geoeffnete Notiz.
   * Hintergrund-Notes bleiben beim Volltext-Sync - nur der aktive Editor bekommt yCollab.
   */
  private async updateLiveEditorBinding(file: TFile | null): Promise<void> {
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

    await this.startSync(file);
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
      // startSync liest den aktuellen Inhalt bereits ein, kein separater Merge noetig.
      void this.startSync(file);
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
