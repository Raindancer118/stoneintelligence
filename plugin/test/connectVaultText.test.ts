import { describe, expect, it } from "vitest";
import { recommendNewVault, serverSwitchWarning, uploadWarning } from "../src/ui/connectText";

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

describe("serverSwitchWarning", () => {
  // Wer den Link untergeschoben bekommt, muss sehen, wohin er sich gleich anmeldet.
  it("names the new server and where the sign-in happens", () => {
    expect(serverSwitchWarning(
      { platformApiUrl: "https://notes.example.org/", oidcIssuerUrl: "https://sso.example.org/application/o/si/" },
      { platformApiUrl: "https://stoneintelligence.tstieh.de" },
    )).toBe("Dieser Link gehört zum Server notes.example.org (Anmeldung über sso.example.org), bisher ist stoneintelligence.tstieh.de eingestellt. "
      + "Verbinde nur, wenn du dieser Einrichtungsseite vertraust – danach meldest du dich dort neu an.");
  });

  it("names only the server when sign-in runs on the same host", () => {
    expect(serverSwitchWarning(
      { platformApiUrl: "https://notes.example.org", oidcIssuerUrl: "https://notes.example.org/auth/" },
      { platformApiUrl: "https://stoneintelligence.tstieh.de" },
    )).toContain("zum Server notes.example.org, bisher");
  });
});
