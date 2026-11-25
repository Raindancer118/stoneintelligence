import { describe, expect, it } from "vitest";
import * as Y from "yjs";
import { parseCanvas, serializeCanvas, type CanvasData } from "../src/sync/canvasDoc";
import { type CanvasLiveHost, CanvasLiveManager } from "../src/sync/canvasLiveManager";
import { MultiplexedTransport } from "../src/sync/multiplexedTransport";
import { SyncClient } from "../src/sync/SyncClient";
import { RelayServer } from "./fakeRelay";

const NOTE = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
const PATH = "Board.canvas";
const card = (id: string, x: number, text: string) => ({ id, type: "text", x, y: 0, width: 200, height: 60, text });
const json = (nodes: object[]) => serializeCanvas({ nodes, edges: [] } as CanvasData);
const settle = (ms = 40) => new Promise((resolve) => setTimeout(resolve, ms));

/** Ein Geraet mit einem Canvas-Pane: `view` ist der Inhalt der Ansicht, `file` der gespeicherte. */
class Device implements CanvasLiveHost {
  file: string;
  view: string | null;
  open = true;
  noteId: string | undefined = NOTE;
  readonly states = new Map<string, Uint8Array>();
  readonly detached: string[] = [];
  readonly transport: MultiplexedTransport;
  readonly manager: CanvasLiveManager;

  constructor(server: RelayServer, content: string) {
    this.file = content;
    this.view = content;
    this.transport = new MultiplexedTransport(async () => "wss://relay.test", () => server.connect(), {
      joinStaggerMs: 0, reconnectDelayMs: 5, maxReconnectDelayMs: 20,
    });
    this.transport.start();
    this.manager = new CanvasLiveManager(this, { pollMs: 10, remoteDebounceMs: 5 });
  }

  openCanvases = () => (this.open ? [{ path: PATH, readView: () => this.view }] : []);
  noteIdOf = () => this.noteId;
  ensureNoteId = async () => (this.noteId = NOTE);
  isTrackable = () => true;
  readFile = async () => this.file;
  writeFile = async (_path: string, content: string) => {
    this.file = content;
    this.view = content;
  };
  loadState = async (id: string) => this.states.get(id) ?? null;
  saveState = async (id: string, state: Uint8Array) => void this.states.set(id, state);
  createClient = (id: string, doc: Y.Doc) => new SyncClient("", () => this.transport.createVirtualSocket(id, { priority: true }), doc);
  onDetached = (path: string) => void this.detached.push(path);
}

const ids = (source: string | null) => parseCanvas(source ?? "")!.nodes.map((n) => n.id).sort();

describe("CanvasLiveManager", () => {
  it("should_carryEditsOfTheOpenViewToTheOtherDevice_withoutWaitingForASave", async () => {
    const server = new RelayServer();
    const one = new Device(server, json([card("a", 0, "A")]));
    const two = new Device(server, "");
    await one.manager.refresh();
    await two.manager.refresh();
    one.manager.startPolling();
    two.manager.startPolling();
    await settle(80);
    expect(ids(two.view)).toEqual(["a"]);

    one.view = json([card("a", 0, "A"), card("b", 300, "B")]);
    await settle(120);

    expect(ids(two.view)).toEqual(["a", "b"]);
    expect(one.file).not.toContain("\"b\"");
    await one.manager.detachAll();
    await two.manager.detachAll();
  });

  it("should_letGoOfACanvas_thatWasClosed_andKeepItsState", async () => {
    const server = new RelayServer();
    const one = new Device(server, json([card("a", 0, "A")]));
    await one.manager.refresh();
    expect(one.manager.has(PATH)).toBe(true);

    one.open = false;
    await one.manager.refresh();

    expect(one.manager.has(PATH)).toBe(false);
    expect(one.detached).toEqual([PATH]);
    expect(one.states.get(NOTE)).toBeDefined();
    await one.manager.detachAll();
  });

  it("should_createTheCanvasOnTheServer_whenItHasNoIdYet", async () => {
    const server = new RelayServer();
    const one = new Device(server, json([card("a", 0, "A")]));
    one.noteId = undefined;

    await one.manager.refresh();

    expect(one.noteId).toBe(NOTE);
    expect(one.manager.has(PATH)).toBe(true);
    await one.manager.detachAll();
  });

  it("should_followARename_withoutRejoining", async () => {
    const server = new RelayServer();
    const one = new Device(server, json([card("a", 0, "A")]));
    await one.manager.refresh();

    one.manager.rename(PATH, "Neu.canvas");

    expect(one.manager.has("Neu.canvas")).toBe(true);
    expect(one.manager.has(PATH)).toBe(false);
    await one.manager.detachAll();
  });

  it("should_notBind_whenNothingIsTrackable", async () => {
    const server = new RelayServer();
    const one = new Device(server, json([card("a", 0, "A")]));
    one.isTrackable = () => false;

    await one.manager.refresh();

    expect(one.manager.paths()).toEqual([]);
  });
});
