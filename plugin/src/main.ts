import { Compartment } from "@codemirror/state";
import type { EditorView } from "@codemirror/view";
import { App, Notice, Plugin, PluginSettingTab, Setting, TFile } from "obsidian";
import { yCollab } from "y-codemirror.next";
import * as Y from "yjs";
import { NoteApiClient } from "./sync/NoteApiClient";
import { OperationJournal } from "./sync/OperationJournal";
import { SyncClient } from "./sync/SyncClient";
import { TicketClient } from "./sync/TicketClient";

interface StoneIntelligenceSettings {
  platformApiUrl: string;
  platformWsUrl: string;
  vaultId: string;
  actor: string;
  noteIds: Record<string, string>;
}

const DEFAULT_SETTINGS: StoneIntelligenceSettings = {
  platformApiUrl: "http://localhost:8080",
  platformWsUrl: "ws://localhost:8080",
  vaultId: "",
  actor: "",
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
  private readonly liveBindingCompartment = new Compartment();
  private liveBoundPath: string | null = null;

  async onload(): Promise<void> {
    await this.loadSettings();
    this.noteApiClient = new NoteApiClient(this.settings.platformApiUrl, this.settings.actor);
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

    this.app.workspace.onLayoutReady(() => {
      void this.syncAllNotes();
    });
  }

  onunload(): void {
    for (const path of [...this.sessions.keys()]) {
      this.stopSync(path);
    }
  }

  private async syncAllNotes(): Promise<void> {
    if (!this.settings.vaultId) {
      return;
    }
    for (const file of this.app.vault.getMarkdownFiles()) {
      await this.startSync(file);
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
    const ticketClient = new TicketClient(this.settings.platformApiUrl, this.settings.actor);
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
    if (text.toString() !== initialContent) {
      doc.transact(() => {
        text.delete(0, text.length);
        text.insert(0, initialContent);
      });
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
    // ensures NoteApiClient und ggf. spaetere Reconnects den aktuellen Actor/BaseUrl nutzen.
    this.noteApiClient = new NoteApiClient(this.settings.platformApiUrl, this.settings.actor);
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

    new Setting(containerEl)
      .setName("Actor")
      .setDesc("Provisorisch bis OIDC (Phase 3) - dein Anzeigename gegenueber dem Server.")
      .addText((text) =>
        text.setValue(this.plugin.settings.actor).onChange(async (value) => {
          this.plugin.settings.actor = value;
          await this.plugin.saveSettings();
        }),
      );
  }
}
