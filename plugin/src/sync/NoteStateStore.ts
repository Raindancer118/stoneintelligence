import * as Y from "yjs";

/** Der Teil von Obsidians `DataAdapter`, den der Store braucht (gleiche Methodennamen). */
export interface BinaryAdapter {
  exists(path: string): Promise<boolean>;
  readBinary(path: string): Promise<ArrayBuffer>;
  writeBinary(path: string, data: ArrayBuffer): Promise<void>;
  remove(path: string): Promise<void>;
  mkdir(path: string): Promise<void>;
}

/**
 * Dauerhafter Yjs-Zustand je Notiz auf DIESEM Geraet (Vorbild y-indexeddb, hier im Plugin-Ordner,
 * weil IndexedDB auf Mobile nicht zuverlaessig erhalten bleibt). Er ist die gemeinsame Basis fuer
 * den Merge: ohne ihn liess sich eine offline oder bei beendetem Obsidian geaenderte Notiz nicht
 * mit Aenderungen anderer Geraete zusammenfuehren - der Abgleich musste raten, wer gewinnt.
 *
 * Nur ein Cache im Sinne von "jederzeit neu aufbaubar": geht er verloren, greifen die
 * Erstkontakt-Regeln (s. `resolveFirstContact`), es geht kein Inhalt verloren.
 */
export class NoteStateStore {
  constructor(
    private readonly adapter: BinaryAdapter,
    private readonly baseDir: string,
  ) {}

  private dir(vaultId: string): string {
    return `${this.baseDir}/${vaultId}`;
  }

  private file(vaultId: string, noteId: string): string {
    return `${this.dir(vaultId)}/${noteId}.yjs`;
  }

  async load(vaultId: string, noteId: string): Promise<Uint8Array | null> {
    const path = this.file(vaultId, noteId);
    try {
      if (!(await this.adapter.exists(path))) {
        return null;
      }
      const state = new Uint8Array(await this.adapter.readBinary(path));
      // Probeweise anwenden: ein halb geschriebener Zustand (Absturz waehrend des Schreibens)
      // wirft hier, statt spaeter einen Merge zu verderben.
      Y.applyUpdate(new Y.Doc(), state);
      return state;
    } catch (error) {
      console.warn(`StoneIntelligence: lokaler Sync-Zustand fuer ${noteId} unlesbar, wird neu aufgebaut`, error);
      return null;
    }
  }

  async save(vaultId: string, noteId: string, state: Uint8Array): Promise<void> {
    const dir = this.dir(vaultId);
    if (!(await this.adapter.exists(dir))) {
      await this.adapter.mkdir(dir);
    }
    await this.adapter.writeBinary(this.file(vaultId, noteId), state.slice().buffer);
  }

  async remove(vaultId: string, noteId: string): Promise<void> {
    const path = this.file(vaultId, noteId);
    if (await this.adapter.exists(path)) {
      await this.adapter.remove(path);
    }
  }
}
