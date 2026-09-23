import { describe, expect, it } from "vitest";
import { deletionConflictText } from "../src/ui/deletionText";

describe("deletionConflictText", () => {
  it("speaks of a note for Markdown", () => {
    expect(deletionConflictText("Ordner/Plan.md")).toMatch(/^Hier gibt es an dieser Notiz noch Änderungen/);
  });

  // Dateien (ADR 0009): ein PDF ist keine Notiz.
  it("speaks of a file for everything else", () => {
    expect(deletionConflictText("Anhänge/Skript.pdf")).toMatch(/^Hier gibt es an dieser Datei noch Änderungen/);
    expect(deletionConflictText("Anhänge/Skript.pdf")).toContain("Ohne Auswahl wird sie gelöscht");
  });
});
