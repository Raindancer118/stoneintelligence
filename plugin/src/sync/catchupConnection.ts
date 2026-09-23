import type * as Y from "yjs";
import type { MultiplexedTransport } from "./multiplexedTransport";
import { SyncClient } from "./SyncClient";

/**
 * Wartet, bis DIESE Notiz tatsaechlich gejoint ist. `false` nach Ablauf der Frist - der Aufrufer
 * muss das als "noch nicht verbunden" behandeln, NIE als Erfolg.
 */
export function awaitConnected(client: SyncClient, timeoutMs: number): Promise<boolean> {
  if (client.status === "connected") {
    return Promise.resolve(true);
  }
  return new Promise((resolve) => {
    const previous = client.onStatusChange;
    const timeout = setTimeout(() => {
      client.onStatusChange = previous;
      resolve(false);
    }, timeoutMs);
    client.onStatusChange = (status) => {
      previous?.(status);
      if (status === "connected") {
        clearTimeout(timeout);
        client.onStatusChange = previous;
        resolve(true);
      }
    };
  });
}

/**
 * Wartet auf das explizite Catchup-Signal des Servers. Ein Timeout ist NIE "Server ist leer" -
 * genau dieser Fehlschluss hat frueher Notizinhalte verdreifacht.
 */
export function awaitCatchupComplete(client: SyncClient, timeoutMs: number): Promise<boolean> {
  return new Promise((resolve) => {
    const previous = client.onCatchupComplete;
    const timeout = setTimeout(() => {
      client.onCatchupComplete = previous;
      resolve(false);
    }, timeoutMs);
    client.onCatchupComplete = () => {
      previous?.();
      clearTimeout(timeout);
      client.onCatchupComplete = previous;
      resolve(true);
    };
  });
}

/**
 * Joint `doc` auf der geteilten Verbindung und kehrt nach abgeschlossenem Catchup zurueck (lokale
 * Aenderungen sind dann per Reparatur-Resend bereits unterwegs). Ohne Anwesenheit: ein
 * Hintergrundabgleich ist keine Person in der Notiz. `null`, wenn die Frist verstreicht.
 */
export async function connectForCatchup(
  transport: MultiplexedTransport,
  noteId: string,
  doc: Y.Doc,
  timeoutMs: number,
): Promise<{ disconnect(): void } | null> {
  const client = new SyncClient("", () => transport.createVirtualSocket(noteId, { priority: false }), doc);
  client.awareness.setLocalState(null);
  client.connect();
  const caughtUp = (await awaitConnected(client, timeoutMs)) && (await awaitCatchupComplete(client, timeoutMs));
  if (!caughtUp) {
    client.disconnect();
    return null;
  }
  return { disconnect: () => client.disconnect() };
}
