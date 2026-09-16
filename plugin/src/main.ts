import { Plugin, TFile } from "obsidian";
import * as Y from "yjs";
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

interface ActiveSync {
  path: string;
  noteId: string;
  client: SyncClient;
  unbindDocObserver: () => void;
}

/**
 * MVP-Scope (Plan.md Abschnitt 6, Phase 2): synchronisiert die aktuell GEOEFFNETE Notiz
 * volltext-basiert (ganzer Dateiinhalt in einem Y.Text, kein CodeMirror-6-Live-Binding auf
 * Zeichenebene). Deckt NICHT die Anforderung "das ganze Vault soll aktuell gehalten werden,
 * nicht erst beim Oeffnen" ab - das ist ein dokumentierter Folgeschritt (Hintergrund-Sync aller
 * Notes), kein Teil dieses vertikalen Slices.
 */
export default class StoneIntelligencePlugin extends Plugin {
  settings: StoneIntelligenceSettings = DEFAULT_SETTINGS;
  private readonly journal = new OperationJournal();
  private activeSync: ActiveSync | null = null;

  async onload(): Promise<void> {
    await this.loadSettings();

    this.registerEvent(
      this.app.workspace.on("file-open", (file) => {
        if (file instanceof TFile && file.extension === "md") {
          void this.syncActiveFile(file);
        } else {
          this.teardownActiveSync();
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

    this.registerInterval(
      window.setInterval(() => this.journal.evictOlderThan(5 * 60_000), 60_000) as unknown as number,
    );
  }

  onunload(): void {
    this.teardownActiveSync();
  }

  private async ensureNoteId(file: TFile): Promise<string> {
    const existing = this.settings.noteIds[file.path];
    if (existing) {
      return existing;
    }

    const response = await fetch(`${this.settings.platformApiUrl}/api/v1/vaults/${this.settings.vaultId}/notes`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Actor": this.settings.actor },
      body: JSON.stringify({ path: file.path, noteLevel: 1 }),
    });
    if (!response.ok) {
      throw new Error(`failed to register note with platform-api: HTTP ${response.status}`);
    }
    const created = (await response.json()) as { id: string };
    this.settings.noteIds[file.path] = created.id;
    await this.saveSettings();
    return created.id;
  }

  private async syncActiveFile(file: TFile): Promise<void> {
    this.teardownActiveSync();

    const noteId = await this.ensureNoteId(file);
    const ticketClient = new TicketClient(this.settings.platformApiUrl, this.settings.actor);
    const ticket = await ticketClient.issueTicket(this.settings.vaultId, noteId);

    const doc = new Y.Doc();
    const text = doc.getText("content");
    const initialContent = await this.app.vault.read(file);
    text.insert(0, initialContent);

    const wsUrl = `${this.settings.platformWsUrl}/ws/sync?ticket=${encodeURIComponent(ticket.token)}`;
    const client = new SyncClient(wsUrl, (url) => new WebSocket(url), doc);
    client.connect();

    const observer = (): void => {
      void this.applyRemoteContentToFile(file, text.toString());
    };
    text.observe(observer);

    this.activeSync = { path: file.path, noteId, client, unbindDocObserver: () => text.unobserve(observer) };
  }

  private teardownActiveSync(): void {
    if (this.activeSync) {
      this.activeSync.unbindDocObserver();
      this.activeSync.client.disconnect();
      this.activeSync = null;
    }
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
    if (!this.activeSync || this.activeSync.path !== file.path) {
      return;
    }

    const content = await this.app.vault.read(file);
    const fingerprint = `modify:${file.path}:${hashContent(content)}`;
    if (this.journal.correlate(fingerprint)) {
      return;
    }

    const text = this.activeSync.client.doc.getText("content");
    if (text.toString() === content) {
      return;
    }
    this.activeSync.client.doc.transact(() => {
      text.delete(0, text.length);
      text.insert(0, content);
    });
  }

  async loadSettings(): Promise<void> {
    this.settings = Object.assign({}, DEFAULT_SETTINGS, await this.loadData());
  }

  async saveSettings(): Promise<void> {
    await this.saveData(this.settings);
  }
}
