import { describe, expect, it } from "vitest";
import * as Y from "yjs";
import { connectForCatchup } from "../src/sync/catchupConnection";
import { canvasOfDoc, parseCanvas, serializeCanvas, type CanvasData } from "../src/sync/canvasDoc";
import { CanvasLiveBinding, revertReadOnlyCanvasEdit, syncCanvasContent } from "../src/sync/canvasSync";
import { MultiplexedTransport } from "../src/sync/multiplexedTransport";
import type { ContentSyncPorts } from "../src/sync/noteContentSync";
import { SyncClient } from "../src/sync/SyncClient";
import { RelayServer } from "./fakeRelay";

const NOTE = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
const PATH = "Board.canvas";
const card = (id: string, x: number, y: number, text: string) => ({ id, type: "text", x, y, width: 200, height: 60, text });
const json = (nodes: object[], edges: object[] = []) => serializeCanvas({ nodes, edges } as CanvasData);
const parsed = (source: string | undefined) => parseCanvas(source ?? "")!;
const ids = (source: string | undefined) => parsed(source).nodes.map((n) => n.id).sort();

async function settle(ms = 30): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

class Device implements ContentSyncPorts {
  readonly files = new Map<string, string>();
  readonly states = new Map<string, Uint8Array>();
  readonly transport: MultiplexedTransport;

  constructor(readonly server: RelayServer) {
    this.transport = new MultiplexedTransport(async () => "wss://relay.test", () => server.connect(), {
      joinStaggerMs: 0, reconnectDelayMs: 5, maxReconnectDelayMs: 20,
    });
    this.transport.start();
  }

  loadState = async (id: string) => this.states.get(id) ?? null;
  saveState = async (id: string, state: Uint8Array) => void this.states.set(id, state);
  readFile = async (path: string) => this.files.get(path) ?? "";
  writeFile = async (path: string, content: string) => void this.files.set(path, content);
  writeConflictCopy = async (path: string, content: string) => {
    this.files.set(`${path}.copy`, content);
    return `${path}.copy`;
  };
  connect = (id: string, doc: Y.Doc) => connectForCatchup(this.transport, id, doc, 500);
  sync = () => syncCanvasContent(this, NOTE, PATH);
}

describe("syncCanvasContent (Hintergrundabgleich)", () => {
  it("should_pushANewCanvas_andPullItOnAnotherDevice", async () => {
    const server = new RelayServer();
    const laptop = new Device(server);
    const phone = new Device(server);
    laptop.files.set(PATH, json([card("a", 0, 0, "Idee")]));

    expect((await laptop.sync()).outcome).toBe("pushed");
    await settle();
    expect((await phone.sync()).outcome).toBe("pulled");

    expect(parsed(phone.files.get(PATH)).nodes).toHaveLength(1);
    expect(canvasOfDoc(server.doc(NOTE)).nodes[0].text).toBe("Idee");
  });

  it("should_mergeOfflineEditsOfBothDevices_withoutLosingEither", async () => {
    const server = new RelayServer();
    const laptop = new Device(server);
    const phone = new Device(server);
    laptop.files.set(PATH, json([card("a", 0, 0, "Eins"), card("b", 300, 0, "Zwei")]));
    await laptop.sync();
    await settle();
    await phone.sync();
    await settle();

    server.goOffline();
    phone.files.set(PATH, json([card("a", 0, 0, "Eins"), card("b", 300, 90, "Zwei"), card("p", 0, 200, "vom Handy")]));
    laptop.files.set(PATH, json([card("a", 40, 0, "Eins erweitert"), card("b", 300, 0, "Zwei"), card("l", 0, 300, "vom Laptop")]));
    expect((await phone.sync()).outcome).toBe("offline");
    expect((await laptop.sync()).outcome).toBe("offline");
    server.online = true;
    phone.transport.reconnectNow();
    laptop.transport.reconnectNow();

    await phone.sync();
    await settle();
    await laptop.sync();
    await settle();
    await phone.sync();
    await laptop.sync();

    for (const device of [phone, laptop]) {
      const canvas = parsed(device.files.get(PATH));
      expect(ids(device.files.get(PATH))).toEqual(["a", "b", "l", "p"]);
      const a = canvas.nodes.find((n) => n.id === "a")!;
      expect([a.x, a.text]).toEqual([40, "Eins erweitert"]);
      expect(canvas.nodes.find((n) => n.id === "b")!.y).toBe(90);
    }
    expect(canvasOfDoc(server.doc(NOTE)).nodes.map((n) => n.id).sort()).toEqual(["a", "b", "l", "p"]);
  });

  it("should_notDuplicateAnything_acrossRepeatedSyncs", async () => {
    const server = new RelayServer();
    const laptop = new Device(server);
    const phone = new Device(server);
    laptop.files.set(PATH, json([card("a", 0, 0, "Einmal")]));
    for (let round = 0; round < 4; round++) {
      await laptop.sync();
      await settle();
      await phone.sync();
      await settle();
    }
    expect(canvasOfDoc(server.doc(NOTE)).nodes).toHaveLength(1);
    expect(parsed(phone.files.get(PATH)).nodes).toHaveLength(1);
  });

  it("should_unionTwoIndependentCanvases_onFirstContact_neverLosingCards", async () => {
    const server = new RelayServer();
    const laptop = new Device(server);
    const phone = new Device(server);
    laptop.files.set(PATH, json([card("a", 0, 0, "Laptop")]));
    await laptop.sync();
    await settle();
    phone.files.set(PATH, json([card("b", 0, 0, "Handy")]));

    expect((await phone.sync()).outcome).toBe("merged");
    expect(ids(phone.files.get(PATH))).toEqual(["a", "b"]);
  });

  it("should_leaveAnInvalidFileAlone", async () => {
    const server = new RelayServer();
    const laptop = new Device(server);
    laptop.files.set(PATH, "{\"nodes\":[");
    expect((await laptop.sync()).outcome).toBe("invalid");
    expect(laptop.files.get(PATH)).toBe("{\"nodes\":[");
    expect(server.updates.get(NOTE)).toBeUndefined();
  });

  it("should_treatAnEmptyNewFileAsEmptyCanvas_andFillItFromTheServer", async () => {
    const server = new RelayServer();
    const laptop = new Device(server);
    const phone = new Device(server);
    laptop.files.set(PATH, json([card("a", 0, 0, "x")]));
    await laptop.sync();
    await settle();
    phone.files.set(PATH, "");
    expect((await phone.sync()).outcome).toBe("pulled");
    expect(parsed(phone.files.get(PATH)).nodes).toHaveLength(1);
  });
});

describe("revertReadOnlyCanvasEdit", () => {
  it("should_keepTheEditAsACopy_andRestoreTheLastSyncedCanvas", async () => {
    const server = new RelayServer();
    const device = new Device(server);
    device.files.set(PATH, json([card("a", 0, 0, "Original")]));
    await device.sync();

    const edited = json([card("a", 0, 0, "Original"), card("x", 1, 1, "Unbefugt")]);
    device.files.set(PATH, edited);
    const copy = await revertReadOnlyCanvasEdit(device, NOTE, PATH);

    expect(copy).toBe(`${PATH}.copy`);
    expect(device.files.get(copy!)).toBe(edited);
    expect(ids(device.files.get(PATH))).toEqual(["a"]);
    expect(await revertReadOnlyCanvasEdit(device, NOTE, PATH)).toBeNull();
  });
});

/** Ein offenes Canvas: eigene Datei, persistente Verbindung, Bindung wie in Obsidian. */
class OpenCanvas {
  file: string;
  readonly doc = new Y.Doc();
  readonly binding: CanvasLiveBinding;
  readonly client: SyncClient;
  writes = 0;
  /** `false`: Obsidian laedt die offene Ansicht nach dem Schreiben nicht neu - sie zeigt weiter den alten Stand. */
  reloads = true;

  constructor(server: RelayServer, initial: string) {
    this.file = initial;
    const transport = new MultiplexedTransport(async () => "wss://relay.test", () => server.connect(), {
      joinStaggerMs: 0, reconnectDelayMs: 5, maxReconnectDelayMs: 20,
    });
    transport.start();
    this.binding = new CanvasLiveBinding(this.doc, {
      readLocal: async () => this.file,
      writeLocal: async (content) => {
        this.writes++;
        if (this.reloads) {
          this.file = content;
        }
      },
      saveState: async () => undefined,
    }, { remoteDebounceMs: 5 });
    this.client = new SyncClient("", () => transport.createVirtualSocket(NOTE, { priority: true }), this.doc);
    this.client.awareness.setLocalState(null);
  }

  async open(): Promise<void> {
    await this.binding.attach(null);
    this.client.connect();
    await settle(60);
    // Die Ansicht meldet sich regelmaessig - hier einmal nach dem ersten Einspielen.
    await this.binding.localChanged();
  }
}

describe("CanvasLiveBinding (geoeffnetes Canvas)", () => {
  it("should_showEachOthersMovesEditsNewCardsAndEdges_live", async () => {
    const server = new RelayServer();
    const one = new OpenCanvas(server, json([card("a", 0, 0, "Start")]));
    const two = new OpenCanvas(server, "");
    await one.open();
    await two.open();
    expect(parsed(two.file).nodes).toHaveLength(1);

    one.file = json([card("a", 120, 0, "Start"), card("b", 300, 0, "Neu")],
      [{ id: "e", fromNode: "a", fromSide: "right", toNode: "b", toSide: "left" }]);
    await one.binding.localChanged();
    await settle();

    const seen = parsed(two.file);
    expect(seen.nodes.map((n) => n.id)).toEqual(["a", "b"]);
    expect(seen.nodes[0].x).toBe(120);
    expect(seen.edges).toHaveLength(1);
  });

  it("should_mergeSimultaneousEditsOfBothSides_intoBothFiles", async () => {
    const server = new RelayServer();
    const one = new OpenCanvas(server, json([card("a", 0, 0, "Hallo Welt"), card("b", 0, 100, "B")]));
    const two = new OpenCanvas(server, "");
    await one.open();
    await two.open();

    one.file = json([card("a", 0, 0, "Hallo schoene Welt"), card("b", 0, 100, "B")]);
    two.file = json([card("a", 0, 0, "Hallo Welt!"), card("b", 55, 100, "B")]);
    await Promise.all([one.binding.localChanged(), two.binding.localChanged()]);
    await settle(80);

    for (const side of [one, two]) {
      const merged = parsed(side.file);
      expect(merged.nodes.find((n) => n.id === "a")!.text).toBe("Hallo schoene Welt!");
      expect(merged.nodes.find((n) => n.id === "b")!.x).toBe(55);
    }
  });

  it("should_keepUnsavedLocalEdits_whenARemoteChangeArrivesFirst", async () => {
    const server = new RelayServer();
    const one = new OpenCanvas(server, json([card("a", 0, 0, "A")]));
    const two = new OpenCanvas(server, "");
    await one.open();
    await two.open();

    two.file = json([card("a", 0, 0, "A"), card("z", 9, 9, "ungespeichert")]);
    one.file = json([card("a", 0, 0, "A"), card("o", 5, 5, "von eins")]);
    await one.binding.localChanged();
    await settle(80);

    expect(ids(two.file)).toEqual(["a", "o", "z"]);
    expect(ids(one.file)).toEqual(["a", "o", "z"]);
  });

  it("should_notTurnAStaleView_intoDeletionsOfRemoteChanges", async () => {
    const server = new RelayServer();
    const one = new OpenCanvas(server, json([card("a", 0, 0, "A")]));
    const two = new OpenCanvas(server, "");
    await one.open();
    await two.open();
    two.reloads = false;

    one.file = json([card("a", 0, 0, "A"), card("o", 5, 5, "von eins")]);
    await one.binding.localChanged();
    await settle(80);
    // Zwei zeigt noch den alten Stand und bekommt eine eigene Aenderung - die von Eins darf nicht verschwinden.
    two.file = json([card("a", 0, 0, "A"), card("z", 9, 9, "von zwei")]);
    await two.binding.localChanged();
    await settle(80);

    expect(canvasOfDoc(two.doc).nodes.map((n) => n.id).sort()).toEqual(["a", "o", "z"]);
    expect(canvasOfDoc(one.doc).nodes.map((n) => n.id).sort()).toEqual(["a", "o", "z"]);
  });

  it("should_notRewriteTheFile_forItsOwnChanges", async () => {
    const server = new RelayServer();
    const one = new OpenCanvas(server, json([card("a", 0, 0, "A")]));
    await one.open();
    one.file = json([card("a", 7, 0, "A")]);
    await one.binding.localChanged();
    await settle();
    expect(one.writes).toBe(0);
  });

  it("should_ignoreABrokenFile_untilItIsValidAgain", async () => {
    const server = new RelayServer();
    const one = new OpenCanvas(server, json([card("a", 0, 0, "A")]));
    await one.open();
    one.file = "{\"nodes\":[{";
    await one.binding.localChanged();
    expect(canvasOfDoc(one.doc).nodes).toHaveLength(1);
    one.file = json([card("a", 0, 0, "A"), card("b", 1, 1, "B")]);
    await one.binding.localChanged();
    expect(canvasOfDoc(one.doc).nodes).toHaveLength(2);
  });
});
