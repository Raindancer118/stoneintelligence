import { describe, expect, it } from "vitest";
import * as Y from "yjs";
import { applyTextChange, textChanges } from "../src/sync/textDiff";

function syncedPair(initial: string): [Y.Doc, Y.Doc] {
  const a = new Y.Doc();
  a.getText("content").insert(0, initial);
  const b = new Y.Doc();
  Y.applyUpdate(b, Y.encodeStateAsUpdate(a));
  return [a, b];
}

function exchange(a: Y.Doc, b: Y.Doc): void {
  const toB = Y.encodeStateAsUpdate(a, Y.encodeStateVector(b));
  const toA = Y.encodeStateAsUpdate(b, Y.encodeStateVector(a));
  Y.applyUpdate(b, toB);
  Y.applyUpdate(a, toA);
}

describe("applyTextChange", () => {
  it("should_produceTheTargetText", () => {
    const doc = new Y.Doc();
    const text = doc.getText("content");
    text.insert(0, "Hallo Welt\nZeile zwei\n");

    applyTextChange(text, "Hallo schöne Welt\nZeile 2\nneu\n");

    expect(text.toString()).toBe("Hallo schöne Welt\nZeile 2\nneu\n");
  });

  it("should_beANoOp_when_textIsUnchanged", () => {
    const doc = new Y.Doc();
    const text = doc.getText("content");
    text.insert(0, "gleich");
    let updates = 0;
    doc.on("update", () => updates++);

    applyTextChange(text, "gleich");

    expect(updates).toBe(0);
  });

  // Der eigentliche Grund fuer ein Diff statt "alles loeschen, alles neu einfuegen": zwei Geraete
  // aendern verschiedene Stellen derselben Notiz. Ein Komplett-Ersatz wuerde beim Mergen einen
  // der beiden Staende verdoppeln oder die Aenderung des anderen verwerfen.
  it("should_keepBothEdits_when_twoDevicesChangeDifferentParts", () => {
    const [a, b] = syncedPair("# Titel\n\nAbsatz eins.\n\nAbsatz zwei.\n");

    applyTextChange(a.getText("content"), "# Neuer Titel\n\nAbsatz eins.\n\nAbsatz zwei.\n");
    applyTextChange(b.getText("content"), "# Titel\n\nAbsatz eins.\n\nAbsatz zwei, ergaenzt.\n");
    exchange(a, b);

    expect(a.getText("content").toString()).toBe("# Neuer Titel\n\nAbsatz eins.\n\nAbsatz zwei, ergaenzt.\n");
    expect(b.getText("content").toString()).toBe(a.getText("content").toString());
  });

  it("should_handleSurrogatePairs_withoutSplittingThem", () => {
    const doc = new Y.Doc();
    const text = doc.getText("content");
    text.insert(0, "Emoji 😀 hier");

    applyTextChange(text, "Emoji 😃 hier");

    expect(text.toString()).toBe("Emoji 😃 hier");
  });
});

describe("textChanges", () => {
  it("should_describeMinimalEditorChanges_inOriginalCoordinates", () => {
    const changes = textChanges("abc def ghi", "abc XYZ ghi!");

    let result = "abc def ghi";
    for (const change of [...changes].reverse()) {
      result = result.slice(0, change.from) + change.insert + result.slice(change.to);
    }
    expect(result).toBe("abc XYZ ghi!");
    expect(changes.every((change) => change.to - change.from < "abc def ghi".length)).toBe(true);
  });

  it("should_returnNoChanges_forIdenticalText", () => {
    expect(textChanges("same", "same")).toEqual([]);
  });
});
