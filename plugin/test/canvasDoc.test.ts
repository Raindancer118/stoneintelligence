import { describe, expect, it } from "vitest";
import * as Y from "yjs";
import {
  applyCanvasChange, canvasEquals, canvasOfDoc, parseCanvas, serializeCanvas, type CanvasData,
} from "../src/sync/canvasDoc";

const text = (id: string, x: number, y: number, body: string) => ({ id, type: "text", x, y, width: 200, height: 60, text: body });
const canvas = (nodes: object[], edges: object[] = []): CanvasData => ({ nodes, edges } as CanvasData);
const edge = (id: string, from: string, to: string) => ({ id, fromNode: from, fromSide: "right", toNode: to, toSide: "left" });

/** Zwei Docs, die ihre Updates austauschen (wie ueber den Relay, nur synchron). */
function sync(a: Y.Doc, b: Y.Doc): void {
  Y.applyUpdate(b, Y.encodeStateAsUpdate(a, Y.encodeStateVector(b)));
  Y.applyUpdate(a, Y.encodeStateAsUpdate(b, Y.encodeStateVector(a)));
}

function seeded(data: CanvasData): Y.Doc {
  const doc = new Y.Doc();
  applyCanvasChange(doc, canvas([]), data);
  return doc;
}

describe("parseCanvas / serializeCanvas", () => {
  it("should_treatEmptyFilesAsEmptyCanvas", () => {
    expect(parseCanvas("")).toEqual({ nodes: [], edges: [] });
    expect(parseCanvas("  \n")).toEqual({ nodes: [], edges: [] });
    expect(parseCanvas("{}")).toEqual({ nodes: [], edges: [] });
  });

  it("should_returnNull_forBrokenJson_orNodesWithoutId", () => {
    expect(parseCanvas("{\"nodes\":[")).toBeNull();
    expect(parseCanvas("[]")).toBeNull();
    expect(parseCanvas(JSON.stringify({ nodes: [{ type: "text" }], edges: [] }))).toBeNull();
  });

  it("should_roundtripThroughSerialize_asValidJsonCanvas", () => {
    const data = canvas([text("a", 0, 0, "Hallo"), { id: "f", type: "file", file: "Notiz.md", x: 1, y: 2, width: 3, height: 4 }],
      [edge("e1", "a", "f")]);
    const json = serializeCanvas(data);
    expect(canvasEquals(parseCanvas(json)!, data)).toBe(true);
    expect(JSON.parse(json).nodes).toHaveLength(2);
  });

  it("should_keepUnknownTopLevelKeys", () => {
    const data = { nodes: [], edges: [], metadata: { version: "1.0" } } as CanvasData;
    expect(parseCanvas(serializeCanvas(data))).toEqual(data);
  });
});

describe("canvasOfDoc / applyCanvasChange", () => {
  it("should_reproduceTheCanvas_afterApplyingItToAnEmptyDoc", () => {
    const data = canvas([text("a", 0, 0, "Hallo"), text("b", 300, 0, "Welt")], [edge("e", "a", "b")]);
    const doc = seeded(data);
    expect(canvasEquals(canvasOfDoc(doc), data)).toBe(true);
  });

  it("should_keepTheArrayOrder_ofNodes", () => {
    const data = canvas([text("z", 0, 0, "1"), text("a", 0, 0, "2"), text("m", 0, 0, "3")]);
    expect(canvasOfDoc(seeded(data)).nodes.map((n) => n.id)).toEqual(["z", "a", "m"]);
  });

  it("should_applyOnlyTheDifference_betweenBeforeAndAfter", () => {
    const before = canvas([text("a", 0, 0, "x"), text("b", 10, 10, "y")]);
    const doc = seeded(before);
    const other = new Y.Doc();
    Y.applyUpdate(other, Y.encodeStateAsUpdate(doc));
    // Ein Peer verschiebt b, lokal wird nur a umgeschrieben - beides bleibt erhalten.
    applyCanvasChange(other, before, canvas([text("a", 0, 0, "x"), text("b", 99, 10, "y")]));
    applyCanvasChange(doc, before, canvas([text("a", 0, 0, "x2"), text("b", 10, 10, "y")]));
    sync(doc, other);
    const merged = canvasOfDoc(doc);
    expect(merged).toEqual(canvasOfDoc(other));
    expect(merged.nodes.find((n) => n.id === "a")!.text).toBe("x2");
    expect(merged.nodes.find((n) => n.id === "b")!.x).toBe(99);
  });

  it("should_mergeMovesOfDifferentFields_ofTheSameNode", () => {
    const before = canvas([text("a", 0, 0, "x")]);
    const one = seeded(before);
    const two = new Y.Doc();
    Y.applyUpdate(two, Y.encodeStateAsUpdate(one));
    applyCanvasChange(one, before, canvas([text("a", 50, 0, "x")]));
    applyCanvasChange(two, before, canvas([text("a", 0, 70, "x")]));
    sync(one, two);
    const node = canvasOfDoc(one).nodes[0];
    expect([node.x, node.y]).toEqual([50, 70]);
    expect(canvasOfDoc(two)).toEqual(canvasOfDoc(one));
  });

  it("should_mergeConcurrentTextEdits_ofTheSameNode", () => {
    const before = canvas([text("a", 0, 0, "Hallo Welt")]);
    const one = seeded(before);
    const two = new Y.Doc();
    Y.applyUpdate(two, Y.encodeStateAsUpdate(one));
    applyCanvasChange(one, before, canvas([text("a", 0, 0, "Hallo schoene Welt")]));
    applyCanvasChange(two, before, canvas([text("a", 0, 0, "Hallo Welt!")]));
    sync(one, two);
    expect(canvasOfDoc(one).nodes[0].text).toBe("Hallo schoene Welt!");
    expect(canvasOfDoc(two)).toEqual(canvasOfDoc(one));
  });

  it("should_mergeNodesAndEdgesAddedConcurrently", () => {
    const before = canvas([text("a", 0, 0, "x")]);
    const one = seeded(before);
    const two = new Y.Doc();
    Y.applyUpdate(two, Y.encodeStateAsUpdate(one));
    applyCanvasChange(one, before, canvas([text("a", 0, 0, "x"), text("b", 5, 5, "von eins")], [edge("e1", "a", "b")]));
    applyCanvasChange(two, before, canvas([text("a", 0, 0, "x"), text("c", 9, 9, "von zwei")]));
    sync(one, two);
    const merged = canvasOfDoc(one);
    expect(merged.nodes.map((n) => n.id).sort()).toEqual(["a", "b", "c"]);
    expect(merged.edges).toHaveLength(1);
    expect(canvasOfDoc(two)).toEqual(merged);
  });

  it("should_letADeletionWin_overAConcurrentEditOfTheSameNode_withoutBreakingTheOtherSide", () => {
    const before = canvas([text("a", 0, 0, "x"), text("b", 5, 5, "y")]);
    const one = seeded(before);
    const two = new Y.Doc();
    Y.applyUpdate(two, Y.encodeStateAsUpdate(one));
    applyCanvasChange(one, before, canvas([text("b", 5, 5, "y")]));
    applyCanvasChange(two, before, canvas([text("a", 77, 0, "x"), text("b", 5, 5, "y")]));
    sync(one, two);
    expect(canvasOfDoc(one).nodes.map((n) => n.id)).toEqual(["b"]);
    expect(canvasOfDoc(two)).toEqual(canvasOfDoc(one));
  });

  it("should_dropEdges_whoseNodeWasDeletedByAPeer", () => {
    const before = canvas([text("a", 0, 0, "x"), text("b", 5, 5, "y")]);
    const one = seeded(before);
    const two = new Y.Doc();
    Y.applyUpdate(two, Y.encodeStateAsUpdate(one));
    applyCanvasChange(one, before, canvas([text("a", 0, 0, "x")]));
    applyCanvasChange(two, before, canvas([text("a", 0, 0, "x"), text("b", 5, 5, "y")], [edge("e", "a", "b")]));
    sync(one, two);
    expect(canvasOfDoc(one).edges).toEqual([]);
    expect(canvasOfDoc(two)).toEqual(canvasOfDoc(one));
  });

  it("should_notTouchTheDoc_whenNothingChanged", () => {
    const data = canvas([text("a", 0, 0, "x")]);
    const doc = seeded(data);
    let updates = 0;
    doc.on("update", () => updates++);
    applyCanvasChange(doc, data, canvas([text("a", 0, 0, "x")]));
    expect(updates).toBe(0);
  });

  it("should_removeAFieldTheUserCleared", () => {
    const before = canvas([{ ...text("a", 0, 0, "x"), color: "3" }]);
    const doc = seeded(before);
    applyCanvasChange(doc, before, canvas([text("a", 0, 0, "x")]));
    expect(canvasOfDoc(doc).nodes[0]).not.toHaveProperty("color");
  });

  it("should_reorderNodes_whenTheUserBringsOneToFront", () => {
    const before = canvas([text("a", 0, 0, "1"), text("b", 0, 0, "2"), text("c", 0, 0, "3")]);
    const doc = seeded(before);
    applyCanvasChange(doc, before, canvas([text("b", 0, 0, "2"), text("c", 0, 0, "3"), text("a", 0, 0, "1")]));
    expect(canvasOfDoc(doc).nodes.map((n) => n.id)).toEqual(["b", "c", "a"]);
  });

  it("should_putANewNodeWhereTheUserPutIt", () => {
    const before = canvas([text("a", 0, 0, "1"), text("c", 0, 0, "3")]);
    const doc = seeded(before);
    applyCanvasChange(doc, before, canvas([text("a", 0, 0, "1"), text("b", 0, 0, "2"), text("c", 0, 0, "3")]));
    expect(canvasOfDoc(doc).nodes.map((n) => n.id)).toEqual(["a", "b", "c"]);
  });

  it("should_onlyAddMissingEntries_onFirstContact_andNeverOverwriteOrDeleteTheServerSide", () => {
    const server = seeded(canvas([text("a", 0, 0, "Server"), text("s", 1, 1, "nur Server")]));
    applyCanvasChange(server, null, canvas([text("a", 0, 0, "lokal"), text("l", 2, 2, "nur lokal")]));
    const merged = canvasOfDoc(server);
    expect(merged.nodes.map((n) => n.id).sort()).toEqual(["a", "l", "s"]);
    expect(merged.nodes.find((n) => n.id === "a")!.text).toBe("Server");
  });

  it("should_keepUnknownTopLevelKeys_inTheDoc", () => {
    const withMeta = { nodes: [], edges: [], metadata: { v: 1 } } as CanvasData;
    const doc = new Y.Doc();
    applyCanvasChange(doc, canvas([]), withMeta);
    expect(canvasOfDoc(doc)).toEqual(withMeta);
  });

  it("should_notEchoLocalApplies_whenTheyCarryTheGivenOrigin", () => {
    const doc = new Y.Doc();
    const origins: unknown[] = [];
    doc.on("update", (_u: Uint8Array, origin: unknown) => origins.push(origin));
    applyCanvasChange(doc, canvas([]), canvas([text("a", 0, 0, "x")]), "origin-x");
    expect(origins).toEqual(["origin-x"]);
  });
});
