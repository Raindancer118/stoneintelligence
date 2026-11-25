import { describe, expect, it } from "vitest";
import * as Y from "yjs";
import { base64ToBytes, bytesToBase64, change, merge, textOf } from "../src/bridge";

function docFrom(updates: string[]): Y.Doc {
  const doc = new Y.Doc();
  for (const update of updates) Y.applyUpdate(doc, base64ToBytes(update));
  return doc;
}

describe("yjs bridge", () => {
  it("creates the first content of an empty note", () => {
    const update = change([], "# Titel\n\nText\n");

    expect(update).not.toBeNull();
    expect(textOf([update as string])).toBe("# Titel\n\nText\n");
  });

  it("returns only the missing change, as a minimal diff on top of the history", () => {
    const first = change([], "Zeile eins\nZeile zwei\n") as string;
    const second = change([first], "Zeile eins, ergänzt 😀\nZeile zwei\n") as string;

    expect(textOf([first, second])).toBe("Zeile eins, ergänzt 😀\nZeile zwei\n");
  });

  it("reads a canvas document (ADR 0018) as empty text instead of failing", () => {
    const canvas = new Y.Doc();
    const node = new Y.Map<unknown>();
    node.set("id", "a");
    node.set("text", new Y.Text("Karte"));
    canvas.getMap("nodes").set("a", node);
    const update = bytesToBase64(Y.encodeStateAsUpdate(canvas));

    expect(textOf([update])).toBe("");
    expect(merge([update, update])).toBeTruthy();
  });

  it("returns null when nothing changes", () => {
    const first = change([], "gleich") as string;

    expect(change([first], "gleich")).toBeNull();
  });

  // Kern: Eine KI-Aenderung darf eine gleichzeitige Aenderung eines Menschen an anderer Stelle
  // nicht ueberschreiben - beide muessen im CRDT erhalten bleiben.
  it("merges with a concurrent human edit elsewhere in the note", () => {
    const base = change([], "Absatz A\n\nAbsatz B\n") as string;
    const human = new Y.Doc();
    Y.applyUpdate(human, base64ToBytes(base));
    human.getText("content").insert(0, "Mensch: ");
    const humanUpdate = bytesToBase64(Y.encodeStateAsUpdate(human, Y.encodeStateVector(docFrom([base]))));
    const ai = change([base], "Absatz A\n\nAbsatz B, von der KI ergänzt\n") as string;

    expect(docFrom([base, humanUpdate, ai]).getText("content").toString()).toBe("Mensch: Absatz A\n\nAbsatz B, von der KI ergänzt\n");
  });

  it("round-trips base64 for all byte values", () => {
    const bytes = Uint8Array.from({ length: 256 }, (_, i) => i);
    for (const length of [0, 1, 2, 3, 255, 256]) {
      expect(Array.from(base64ToBytes(bytesToBase64(bytes.slice(0, length))))).toEqual(Array.from(bytes.slice(0, length)));
    }
    expect(bytesToBase64(new Uint8Array([104, 105]))).toBe("aGk=");
  });

  it("merges a history into one update that joins like the full history", () => {
    const history: string[] = [];
    for (const text of ["a", "ab", "abc", "ac", "Ende: ac 😀"]) {
      history.push(change(history, text) as string);
    }

    const state = merge(history);

    expect(textOf([state])).toBe("Ende: ac 😀");
    // A client that already had part of the history converges with one that gets only the state.
    const partial = docFrom(history.slice(0, 2));
    Y.applyUpdate(partial, base64ToBytes(state));
    expect(partial.getText("content").toString()).toBe("Ende: ac 😀");
    // An edit made offline against the old history still applies on top of the state.
    const offline = docFrom(history.slice(0, 3));
    const before = Y.encodeStateVector(offline);
    offline.getText("content").insert(0, "X");
    const late = bytesToBase64(Y.encodeStateAsUpdate(offline, before));
    expect(textOf([state, late])).toBe(textOf([...history, late]));
  });

  it("merges an empty history into an update of an empty note", () => {
    expect(textOf([merge([])])).toBe("");
  });
});
