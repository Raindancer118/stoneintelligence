import type { NoteListItem } from "./NoteApiClient";

/** Eine Seite des Aenderungs-Feeds (ADR 0013), wie sie `GET /vaults/{v}/changes` liefert. */
export interface FeedChanges {
  cursor: string;
  more: boolean;
  entries: NoteListItem[];
  /** Geloescht oder fuer mich nicht mehr lesbar. */
  removed: string[];
  folders: { changed: string[]; removed: string[] };
  /** Ordner ("" = alles), in denen sich meine Sicht geaendert hat - dort neu laden. */
  relist: string[];
}

/**
 * Was der Server hat, im Speicher des Geraets (ADR 0013): einmal voll geladen, danach nur noch
 * ueber den Aenderungs-Feed nachgefuehrt. Der Abgleichsplan arbeitet darauf wie auf einer frisch
 * geholten vollstaendigen Liste. Lebt nur im Speicher - nach einem Neustart wird einmal voll geladen.
 */
export class ServerMirror {
  cursor: string | null = null;
  private vaultId: string | null = null;
  private kinds: string | null = null;
  private relistNeeded = false;
  private readonly byId = new Map<string, NoteListItem>();
  private folderSet: Set<string> | null = null;

  isReadyFor(vaultId: string, kinds: string): boolean {
    return this.cursor !== null && this.vaultId === vaultId && this.kinds === kinds && !this.relistNeeded;
  }

  /** `folders === null`: der Server kennt keine Ordner. */
  load(vaultId: string, kinds: string, cursor: string, entries: NoteListItem[], folders: string[] | null): void {
    this.vaultId = vaultId;
    this.kinds = kinds;
    this.cursor = cursor;
    this.relistNeeded = false;
    this.byId.clear();
    for (const item of entries) {
      this.byId.set(item.id, item);
    }
    this.folderSet = folders === null ? null : new Set(folders);
  }

  apply(changes: FeedChanges): void {
    for (const item of changes.entries) {
      this.byId.set(item.id, item);
    }
    for (const id of changes.removed) {
      this.byId.delete(id);
    }
    if (this.folderSet) {
      changes.folders.removed.forEach((path) => this.folderSet?.delete(path));
      changes.folders.changed.forEach((path) => this.folderSet?.add(path));
    }
    if (changes.relist.length > 0) {
      this.relistNeeded = true;
    }
    this.cursor = changes.cursor;
  }

  reset(): void {
    this.cursor = null;
    this.vaultId = null;
    this.byId.clear();
    this.folderSet = null;
  }

  entries(): NoteListItem[] {
    return [...this.byId.values()];
  }

  folders(): string[] | null {
    return this.folderSet === null ? null : [...this.folderSet].sort();
  }

  byPath(path: string): NoteListItem | undefined {
    for (const item of this.byId.values()) {
      if (item.path === path) {
        return item;
      }
    }
    return undefined;
  }
}
