import { describe, expect, it } from "vitest";
import * as Y from "yjs";
import { conflictCopyPath, type ContentSyncPorts, hasUnsyncedLocalEdits, revertReadOnlyEdit, syncNoteContent } from "../src/sync/noteContentSync";

/**
 * Simuliert Server + Datei-System eines Geraets. `connect` verhaelt sich wie der echte Ablauf
 * ueber SyncClient: Catchup (Server-Historie in das lokale Doc), danach Reparatur-Resend des
 * vollen lokalen Stands, danach werden weitere lokale Updates live gesendet.
 */
class FakeWorld implements ContentSyncPorts {
  readonly server = new Y.Doc();
  readonly files = new Map<string, string>();
  readonly states = new Map<string, Uint8Array>();
  online = true;

  constructor(serverText = "") {
    if (serverText) {
      this.server.getText("content").insert(0, serverText);
    }
  }

  async loadState(noteId: string): Promise<Uint8Array | null> {
    return this.states.get(noteId) ?? null;
  }

  async saveState(noteId: string, state: Uint8Array): Promise<void> {
    this.states.set(noteId, state);
  }

  async readFile(path: string): Promise<string> {
    return this.files.get(path) ?? "";
  }

  async writeFile(path: string, content: string): Promise<void> {
    this.files.set(path, content);
  }

  async writeConflictCopy(path: string, content: string): Promise<string> {
    const copy = path.replace(/\.md$/, " (Konflikt).md");
    this.files.set(copy, content);
    return copy;
  }

  async connect(_noteId: string, doc: Y.Doc): Promise<{ disconnect(): void } | null> {
    if (!this.online) {
      return null;
    }
    Y.applyUpdate(doc, Y.encodeStateAsUpdate(this.server), "server");
    Y.applyUpdate(this.server, Y.encodeStateAsUpdate(doc));
    const forward = (update: Uint8Array, origin: unknown): void => {
      if (origin !== "server") {
        Y.applyUpdate(this.server, update);
      }
    };
    doc.on("update", forward);
    return { disconnect: () => doc.off("update", forward) };
  }

  serverText(): string {
    return this.server.getText("content").toString();
  }
}

describe("syncNoteContent", () => {
  it("should_uploadLocalContent_when_theServerNoteIsEmpty", async () => {
    const world = new FakeWorld();
    world.files.set("a.md", "lokal");

    const result = await syncNoteContent(world, "n1", "a.md");

    expect(result.outcome).toBe("pushed");
    expect(world.serverText()).toBe("lokal");
    expect(world.states.has("n1")).toBe(true);
  });

  it("should_downloadServerContent_into_anEmptyLocalFile", async () => {
    const world = new FakeWorld("vom Server");
    world.files.set("a.md", "");

    const result = await syncNoteContent(world, "n1", "a.md");

    expect(result.outcome).toBe("pulled");
    expect(world.files.get("a.md")).toBe("vom Server");
  });

  it("should_reportUnchanged_when_bothSidesAlreadyAgree", async () => {
    const world = new FakeWorld("gleich");
    world.files.set("a.md", "gleich");

    expect((await syncNoteContent(world, "n1", "a.md")).outcome).toBe("unchanged");
    expect(world.serverText()).toBe("gleich");
  });

  // Erstkontakt ohne gemeinsame Basis und mit unterschiedlichem Inhalt: es gibt keinen korrekten
  // automatischen Merge. Frueher gewann stillschweigend der Server und die lokale Fassung war weg.
  it("should_keepBothVersions_asConflictCopy_when_firstContactContentDiffers", async () => {
    const world = new FakeWorld("Server-Fassung");
    world.files.set("a.md", "Lokale Fassung");

    const result = await syncNoteContent(world, "n1", "a.md");

    expect(result.outcome).toBe("conflict");
    expect(world.files.get("a.md")).toBe("Server-Fassung");
    expect(world.files.get(result.conflictPath as string)).toBe("Lokale Fassung");
    expect(world.serverText()).toBe("Server-Fassung");
  });

  // Der Kern des Fixes: eine geschlossene Notiz wurde offline (oder bei beendetem Obsidian)
  // lokal bearbeitet, parallel auf einem anderen Geraet auch. Beide Aenderungen muessen bleiben.
  it("should_mergeOfflineLocalEdits_withConcurrentRemoteEdits", async () => {
    const world = new FakeWorld();
    world.files.set("a.md", "Zeile eins\nZeile zwei\n");
    await syncNoteContent(world, "n1", "a.md");

    world.files.set("a.md", "Zeile eins, lokal ergaenzt\nZeile zwei\n");
    const otherDevice = new Y.Doc();
    Y.applyUpdate(otherDevice, Y.encodeStateAsUpdate(world.server));
    const otherText = otherDevice.getText("content");
    otherText.insert(otherText.length, "Zeile drei von woanders\n");
    Y.applyUpdate(world.server, Y.encodeStateAsUpdate(otherDevice));

    const result = await syncNoteContent(world, "n1", "a.md");

    const expected = "Zeile eins, lokal ergaenzt\nZeile zwei\nZeile drei von woanders\n";
    expect(result.outcome).toBe("merged");
    expect(world.files.get("a.md")).toBe(expected);
    expect(world.serverText()).toBe(expected);
  });

  it("should_pullRemoteEdits_withoutTouchingTheServer_when_theLocalFileIsUnchanged", async () => {
    const world = new FakeWorld();
    world.files.set("a.md", "alt");
    await syncNoteContent(world, "n1", "a.md");
    const remote = new Y.Doc();
    Y.applyUpdate(remote, Y.encodeStateAsUpdate(world.server));
    remote.getText("content").insert(3, " und neu");
    Y.applyUpdate(world.server, Y.encodeStateAsUpdate(remote));

    const result = await syncNoteContent(world, "n1", "a.md");

    expect(result.outcome).toBe("pulled");
    expect(world.files.get("a.md")).toBe("alt und neu");
  });

  it("should_keepCapturedOfflineEdits_inLocalState_whileTheServerIsUnreachable", async () => {
    const world = new FakeWorld();
    world.files.set("a.md", "Basis");
    await syncNoteContent(world, "n1", "a.md");

    world.online = false;
    world.files.set("a.md", "Basis offline geaendert");
    expect((await syncNoteContent(world, "n1", "a.md")).outcome).toBe("offline");
    expect(world.serverText()).toBe("Basis");

    world.online = true;
    await syncNoteContent(world, "n1", "a.md");
    expect(world.serverText()).toBe("Basis offline geaendert");
  });

  it("should_notUploadAnything_when_offlineOnFirstContact", async () => {
    const world = new FakeWorld();
    world.online = false;
    world.files.set("a.md", "lokal");

    expect((await syncNoteContent(world, "n1", "a.md")).outcome).toBe("offline");
    expect(world.states.has("n1")).toBe(false);
  });
});

describe("conflictCopyPath", () => {
  const date = new Date(2026, 8, 23, 11, 42);

  it("should_nameTheCopyAfterTheOriginal_withDateAndTime", () => {
    expect(conflictCopyPath("Ordner/Notiz.md", date, () => false)).toBe("Ordner/Notiz (Konflikt 2026-09-23 11-42).md");
  });

  it("should_countUp_when_theNameIsTaken", () => {
    const taken = new Set(["Notiz (Konflikt 2026-09-23 11-42).md"]);
    expect(conflictCopyPath("Notiz.md", date, (path) => taken.has(path))).toBe("Notiz (Konflikt 2026-09-23 11-42) 2.md");
  });

  // Dateien (ADR 0009): die Endung bleibt, sonst oeffnet sich die Kopie nicht mehr.
  it("should_keepTheExtension_ofOtherFiles", () => {
    expect(conflictCopyPath("Anhänge/Skript.v2.pdf", date, () => false)).toBe("Anhänge/Skript.v2 (Konflikt 2026-09-23 11-42).pdf");
    expect(conflictCopyPath("Ordner.x/ohne-endung", date, () => false)).toBe("Ordner.x/ohne-endung (Konflikt 2026-09-23 11-42)");
  });
});

describe("hasUnsyncedLocalEdits", () => {
  // Der Fall aus der Praxis: Notiz aus der Zeit vor dem lokalen Sync-Zustand, anderswo geloescht.
  // Ohne jeden Beleg fuer eine lokale Aenderung muss die Loeschung gewinnen.
  it("should_letADeletionWin_when_thereIsNoEvidenceOfLocalEdits", () => {
    expect(hasUnsyncedLocalEdits({ dirty: false, statUnchanged: false, lastSyncedText: null, currentText: "x" })).toBe(false);
  });

  it("should_ignoreTimestampDrift_when_theContentMatchesTheLastSyncedState", () => {
    expect(hasUnsyncedLocalEdits({ dirty: false, statUnchanged: false, lastSyncedText: "gleich", currentText: "gleich" })).toBe(false);
  });

  it("should_reportEdits_when_theContentDiffersFromTheLastSyncedState", () => {
    expect(hasUnsyncedLocalEdits({ dirty: false, statUnchanged: false, lastSyncedText: "alt", currentText: "neu" })).toBe(true);
  });

  it("should_reportEdits_thatWereCapturedOfflineButNeverReachedTheServer", () => {
    expect(hasUnsyncedLocalEdits({ dirty: true, statUnchanged: true, lastSyncedText: "neu", currentText: "neu" })).toBe(true);
  });

  it("should_trustAnUnchangedFile", () => {
    expect(hasUnsyncedLocalEdits({ dirty: false, statUnchanged: true, lastSyncedText: "alt", currentText: "neu" })).toBe(false);
  });
});

describe("offline outcome", () => {
  it("should_tellTheCaller_whetherLocalEditsAreWaiting", async () => {
    const world = new FakeWorld();
    world.files.set("a.md", "Basis");
    await syncNoteContent(world, "n1", "a.md");
    world.online = false;

    world.files.set("a.md", "Basis geaendert");
    expect(await syncNoteContent(world, "n1", "a.md")).toEqual({ outcome: "offline", pendingLocalChanges: true });
  });
});

describe("revertReadOnlyEdit (ADR 0011)", () => {
  function syncedWorld(text: string): FakeWorld {
    const world = new FakeWorld(text);
    world.files.set("a.md", text);
    world.states.set("n1", Y.encodeStateAsUpdate(world.server));
    return world;
  }

  it("should_keepAnEditOfAReadOnlyNote_asACopy_andRestoreTheNote", async () => {
    const world = syncedWorld("Original");
    world.files.set("a.md", "Original, von mir geändert");

    const copy = await revertReadOnlyEdit(world, "n1", "a.md");

    expect(copy).toBe("a (Konflikt).md");
    expect(world.files.get("a (Konflikt).md")).toBe("Original, von mir geändert");
    expect(world.files.get("a.md")).toBe("Original");
  });

  it("should_leaveAnUnchangedNote_andOneWithoutKnownState_alone", async () => {
    const world = syncedWorld("Original");
    expect(await revertReadOnlyEdit(world, "n1", "a.md")).toBeNull();

    world.files.set("b.md", "irgendwas");
    expect(await revertReadOnlyEdit(world, "unbekannt", "b.md")).toBeNull();
    expect(world.files.get("b.md")).toBe("irgendwas");
  });
});
