// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { EditorState } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { Compartment } from "@codemirror/state";
import { yCollab } from "y-codemirror.next";
import { SyncClient, type WebSocketLike } from "../src/sync/SyncClient";
import { pickUserColor } from "../src/sync/actorIdentity";

/**
 * Das eigentliche Versprechen des Plugins (Anforderungen.md: "Ueber Websocket-Verbindungen
 * sollen die Cursor anderer Nutzer live sichtbar sein") - nachgebaut wie im Vorgaengerprojekt
 * `stonesync` (plugin/src/editor/remoteCursors.test.ts), aber gegen UNSEREN echten
 * {@link SyncClient} inkl. seines Byte-Framings statt gegen ein in-process Awareness-Objekt:
 * ein Remote-Cursor ueberlebt (oder ueberlebt eben nicht) genau diesen Kodier-Rundlauf.
 */
class FakeSocket implements WebSocketLike {
  binaryType = "arraybuffer";
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  peer: FakeSocket | null = null;

  send(data: ArrayBuffer): void {
    this.peer?.onmessage?.({ data } as MessageEvent);
  }

  close(): void {}
}

interface Peer {
  client: SyncClient;
  socket: FakeSocket;
  view: EditorView;
}

function createPeer(name: string): Peer {
  const socket = new FakeSocket();
  const client = new SyncClient("", () => socket);
  client.connect();
  socket.onopen?.({} as Event);
  client.awareness.setLocalStateField("user", { name, color: pickUserColor(name) });

  const compartment = new Compartment();
  const parent = document.createElement("div");
  document.body.appendChild(parent);
  const view = new EditorView({ state: EditorState.create({ extensions: [compartment.of([])] }), parent });
  view.dispatch({
    effects: compartment.reconfigure(yCollab(client.doc.getText("content"), client.awareness)),
  });
  return { client, socket, view };
}

describe("Remote-Cursor im Editor", () => {
  it("zeigt den Cursor der anderen Person, in ihrer Farbe und mit ihrem Namen", () => {
    const alice = createPeer("Alice");
    const bob = createPeer("Bob");
    alice.socket.peer = bob.socket;
    bob.socket.peer = alice.socket;

    alice.view.dispatch({ changes: { from: 0, insert: "ein geteilter Absatz" } });
    expect(bob.view.state.doc.toString()).toBe("ein geteilter Absatz");

    // Cursor-Praesenz setzt einen fokussierten Editor voraus - daran knuepft
    // y-codemirror.next das lokale Awareness-`cursor`-Feld.
    alice.view.focus();
    alice.view.dispatch({ selection: { anchor: 8, head: 8 } });

    const caret = bob.view.dom.querySelector(".cm-ySelectionCaret");
    expect(caret, "Bob muss Alices Cursor sehen").not.toBeNull();
    expect(caret?.getAttribute("style") ?? "").toContain(pickUserColor("Alice"));
    expect(caret?.textContent).toContain("Alice");

    alice.view.destroy();
    bob.view.destroy();
  });

  it("bindet beim Notizwechsel im selben Pane sauber auf den neuen Y.Text um", () => {
    // Obsidian verwendet beim Oeffnen einer anderen Datei DIESELBE EditorView weiter - die
    // Bindung muss also umkonfiguriert werden koennen, ohne dass die alte Notiz weiter in diesen
    // Editor hineinschreibt (sonst landet Notiz As Inhalt in der angezeigten Notiz B).
    const socketA = new FakeSocket();
    const socketB = new FakeSocket();
    const clientA = new SyncClient("", () => socketA);
    const clientB = new SyncClient("", () => socketB);
    clientA.connect();
    clientB.connect();

    const compartment = new Compartment();
    const parent = document.createElement("div");
    document.body.appendChild(parent);
    const view = new EditorView({ state: EditorState.create({ extensions: [compartment.of([])] }), parent });

    view.dispatch({ effects: compartment.reconfigure(yCollab(clientA.doc.getText("content"), clientA.awareness)) });
    view.dispatch({ changes: { from: 0, insert: "Notiz A" } });
    expect(clientA.doc.getText("content").toString()).toBe("Notiz A");

    clientB.doc.getText("content").insert(0, "Notiz B");
    // Erst loesen, DANN binden - in einer einzigen Reconfigure-Transaktion behielte das
    // ySync-ViewPlugin (ein modulweites Singleton) seinen alten Y.Text samt Observer.
    view.dispatch({ effects: compartment.reconfigure([]) });
    view.dispatch({ effects: compartment.reconfigure(yCollab(clientB.doc.getText("content"), clientB.awareness)) });

    // Der Kern: die alte Notiz darf den jetzt anders gebundenen Editor nicht mehr anfassen.
    const shown = view.state.doc.toString();
    clientA.doc.getText("content").insert(0, "spaeter Nachzuegler aus A: ");
    expect(view.state.doc.toString()).toBe(shown);
    expect(view.state.doc.toString()).not.toContain("Nachzuegler");

    view.destroy();
  });
});
