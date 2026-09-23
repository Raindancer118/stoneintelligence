import { describe, expect, it } from "vitest";
import { uploadWarning } from "../src/ui/connectText";

describe("uploadWarning", () => {
  it("uses singular grammar for one note", () => {
    expect(uploadWarning(1)).toBe("Die Notiz, die schon hier liegt, wird ebenfalls hochgeladen und ist dann für alle Mitglieder sichtbar.");
  });

  it("uses plural grammar for several notes", () => {
    expect(uploadWarning(3)).toBe("Die 3 Notizen, die schon hier liegen, werden ebenfalls hochgeladen und sind dann für alle Mitglieder sichtbar.");
  });
});
