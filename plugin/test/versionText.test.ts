import { describe, expect, it } from "vitest";
import { describeVersion, explainVersionError, lineDiff, type NoteVersion } from "../src/sync/versionText";

const version = (over: Partial<NoteVersion> = {}): NoteVersion => ({
  revision: 12, firstRevision: 3, actor: "tom", updates: 10,
  startedAt: "2026-09-29T07:00:00Z", endedAt: "2026-09-29T07:20:00Z", ...over,
});

describe("describeVersion", () => {
  it("names who wrote it and when the sitting ended", () => {
    expect(describeVersion(version(), "Europe/Berlin")).toEqual({ who: "tom", when: "29. September 2026, 09:20" });
  });

  it("names the AI and admits unknown authors from before the history existed", () => {
    expect(describeVersion(version({ actor: "ki:Gemini" }), "Europe/Berlin").who).toBe("KI „Gemini“");
    expect(describeVersion(version({ actor: null }), "Europe/Berlin").who).toBe("unbekannt");
  });
});

describe("lineDiff", () => {
  it("shows removed and added lines between unchanged ones", () => {
    expect(lineDiff("a\nb\nc", "a\nB\nc")).toEqual([
      { kind: "same", text: "a" },
      { kind: "removed", text: "b" },
      { kind: "added", text: "B" },
      { kind: "same", text: "c" },
    ]);
  });

  it("folds long unchanged stretches, keeping three lines of context", () => {
    const before = Array.from({ length: 20 }, (_, i) => `z${i}`);
    const after = [...before];
    after[10] = "neu";

    const rows = lineDiff(before.join("\n"), after.join("\n"));

    expect(rows[0]).toEqual({ kind: "gap", text: "7 unveränderte Zeilen" });
    expect(rows.slice(1, 4).map((row) => row.text)).toEqual(["z7", "z8", "z9"]);
    expect(rows.slice(4, 6)).toEqual([{ kind: "removed", text: "z10" }, { kind: "added", text: "neu" }]);
    expect(rows.at(-1)).toEqual({ kind: "gap", text: "6 unveränderte Zeilen" });
  });

  it("says so when nothing differs", () => {
    expect(lineDiff("gleich\n", "gleich\n")).toEqual([]);
  });

  it("does not split lines that differ only in the middle", () => {
    expect(lineDiff("Hallo Welt", "Hallo schöne Welt")).toEqual([
      { kind: "removed", text: "Hallo Welt" },
      { kind: "added", text: "Hallo schöne Welt" },
    ]);
  });

  it("copes with many distinct lines", () => {
    const before = Array.from({ length: 70000 }, (_, i) => `zeile ${i}`).join("\n");
    const after = `${before}\nende`;

    expect(lineDiff(before, after).filter((row) => row.kind === "added")).toEqual([{ kind: "added", text: "ende" }]);
  });
});

describe("explainVersionError", () => {
  it("explains refusals in terms of versions", () => {
    expect(explainVersionError(403)).toContain("Schreibrecht");
    expect(explainVersionError(409)).toContain("gleich noch einmal");
    expect(explainVersionError(500)).toBe("Der Server lehnte ab (HTTP 500).");
  });
});
