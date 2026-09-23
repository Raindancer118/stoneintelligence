import * as Y from "yjs";
import { applyTextChange } from "./textDiff";

/**
 * Alles, was der Inhaltsabgleich einer Notiz von aussen braucht - in main.ts gegen Obsidians
 * Vault/Adapter und den geteilten Sync-Transport verdrahtet, in Tests gegen eine Fake-Welt.
 */
export interface ContentSyncPorts {
  /** Zuletzt gespeicherter Yjs-Zustand dieser Notiz auf DIESEM Geraet (null = noch nie abgeglichen). */
  loadState(noteId: string): Promise<Uint8Array | null>;
  saveState(noteId: string, state: Uint8Array): Promise<void>;
  readFile(path: string): Promise<string>;
  writeFile(path: string, content: string): Promise<void>;
  /** Legt eine Konfliktkopie neben `path` an und liefert deren Pfad. */
  writeConflictCopy(path: string, content: string): Promise<string>;
  /**
   * Verbindet `doc` mit dem Notiz-Raum und kehrt erst nach abgeschlossenem Server-Catchup
   * zurueck (danach sind lokale Aenderungen bereits per Reparatur-Resend unterwegs). `null`, wenn
   * das nicht innerhalb der Frist gelingt - das ist "unbekannt", NIE "Server ist leer".
   */
  connect(noteId: string, doc: Y.Doc): Promise<{ disconnect(): void } | null>;
}

export type ContentSyncOutcome = "unchanged" | "pushed" | "pulled" | "merged" | "conflict" | "offline";

export interface ContentSyncResult {
  outcome: ContentSyncOutcome;
  conflictPath?: string;
}

/**
 * Laedt den lokal gespeicherten Yjs-Zustand und nimmt Aenderungen, die seit dem letzten Abgleich
 * AUSSERHALB des Syncs an der Datei passiert sind (offline, bei beendetem Obsidian, durch ein
 * anderes Plugin/Programm), als echte CRDT-Operationen auf. Damit gibt es fuer jede bereits einmal
 * abgeglichene Notiz eine gemeinsame Basis - und einen korrekten automatischen Merge statt der
 * frueheren Heuristik "Server gewinnt, sobald er Inhalt hat".
 */
export async function prepareNoteDoc(
  ports: Pick<ContentSyncPorts, "loadState" | "readFile">,
  noteId: string,
  path: string,
): Promise<{ doc: Y.Doc; hadBase: boolean; localContent: string; localChanged: boolean }> {
  const doc = new Y.Doc();
  const state = await ports.loadState(noteId);
  if (state) {
    Y.applyUpdate(doc, state);
  }
  const text = doc.getText("content");
  const localContent = await ports.readFile(path);
  const localChanged = state !== null && text.toString() !== localContent;
  if (localChanged) {
    applyTextChange(text, localContent);
  }
  return { doc, hadBase: state !== null, localContent, localChanged };
}

/**
 * Erstkontakt ohne gemeinsame Basis (noch nie auf diesem Geraet abgeglichen): zwei unabhaengig
 * entstandene Texte lassen sich per CRDT nicht sinnvoll mergen - Yjs wuerde beide hintereinander
 * haengen. Deshalb explizit: gleich -> fertig, eine Seite leer -> die andere gewinnt, sonst
 * Server-Fassung behalten und die lokale als Konfliktkopie daneben legen. Es geht nie etwas verloren.
 */
export async function resolveFirstContact(
  ports: Pick<ContentSyncPorts, "writeFile" | "writeConflictCopy">,
  text: Y.Text,
  path: string,
  localContent: string,
): Promise<ContentSyncResult> {
  const serverContent = text.toString();
  if (serverContent === localContent) {
    return { outcome: "unchanged" };
  }
  if (serverContent.length === 0) {
    applyTextChange(text, localContent);
    return { outcome: "pushed" };
  }
  if (localContent.length === 0) {
    await ports.writeFile(path, serverContent);
    return { outcome: "pulled" };
  }
  const conflictPath = await ports.writeConflictCopy(path, localContent);
  await ports.writeFile(path, serverContent);
  return { outcome: "conflict", conflictPath };
}

/** Einmaliger vollstaendiger Abgleich einer (nicht im Editor geoeffneten) Notiz. */
export async function syncNoteContent(ports: ContentSyncPorts, noteId: string, path: string): Promise<ContentSyncResult> {
  const { doc, hadBase, localContent, localChanged } = await prepareNoteDoc(ports, noteId, path);
  const text = doc.getText("content");
  try {
    const connection = await ports.connect(noteId, doc);
    if (!connection) {
      if (localChanged) {
        // Offline erfasste Aenderung sichern - sie geht beim naechsten Verbindungsaufbau per
        // Reparatur-Resend raus, auch wenn die Datei bis dahin wieder anders aussieht.
        await ports.saveState(noteId, Y.encodeStateAsUpdate(doc));
      }
      return { outcome: "offline" };
    }
    try {
      let result: ContentSyncResult;
      if (!hadBase) {
        result = await resolveFirstContact(ports, text, path, localContent);
      } else {
        const merged = text.toString();
        if (merged !== localContent) {
          await ports.writeFile(path, merged);
          result = { outcome: localChanged ? "merged" : "pulled" };
        } else {
          result = { outcome: localChanged ? "pushed" : "unchanged" };
        }
      }
      await ports.saveState(noteId, Y.encodeStateAsUpdate(doc));
      return result;
    } finally {
      connection.disconnect();
    }
  } finally {
    doc.destroy();
  }
}

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

/** "Ordner/Notiz.md" -> "Ordner/Notiz (Konflikt 2026-09-23 11-42).md", bei Kollision mit Zaehler. */
export function conflictCopyPath(path: string, date: Date, exists: (candidate: string) => boolean): string {
  const base = path.replace(/\.md$/i, "");
  const stamp = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}-${pad(date.getMinutes())}`;
  let candidate = `${base} (Konflikt ${stamp}).md`;
  for (let counter = 2; exists(candidate); counter++) {
    candidate = `${base} (Konflikt ${stamp}) ${counter}.md`;
  }
  return candidate;
}
