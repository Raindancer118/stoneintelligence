import { describe, expect, it } from "vitest";
import { recommendNewVault, uploadWarning } from "../src/ui/connectText";

describe("uploadWarning", () => {
  it("uses singular grammar for one note", () => {
    expect(uploadWarning(1)).toBe("Die Notiz, die schon hier liegt, wird ebenfalls hochgeladen und ist dann für alle Mitglieder sichtbar.");
  });

  it("uses plural grammar for several notes", () => {
    expect(uploadWarning(3)).toBe("Die 3 Notizen, die schon hier liegen, werden ebenfalls hochgeladen und sind dann für alle Mitglieder sichtbar.");
  });
});

describe("recommendNewVault", () => {
  it("should_recommendANewVault_when_thisOneAlreadyHasNotes", () => {
    expect(recommendNewVault({ localNoteCount: 4, connectedElsewhere: false })).toBe(true);
  });

  it("should_recommendANewVault_when_thisOneIsConnectedToAnotherSharedVault", () => {
    expect(recommendNewVault({ localNoteCount: 0, connectedElsewhere: true })).toBe(true);
  });

  // Ein leerer, unverbundener Vault ist genau das, was ein neuer Vault waere.
  it("should_recommendThisVault_when_itIsEmptyAndUnused", () => {
    expect(recommendNewVault({ localNoteCount: 0, connectedElsewhere: false })).toBe(false);
  });
});
