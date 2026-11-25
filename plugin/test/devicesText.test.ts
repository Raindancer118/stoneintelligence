import { describe, expect, it } from "vitest";
import { deviceStatusText, formatAllowedLines, parseAllowedLines } from "../src/sync/devicesText";

describe("devicesText", () => {
  it("parses one plugin per line, with optional pinned versions and comments", () => {
    expect(parseAllowedLines("calendar@1.5.10\n\n# Notizen\ndataview\n")).toEqual({
      rules: [{ id: "calendar", version: "1.5.10" }, { id: "dataview", version: null }], error: null,
    });
  });

  it("names the offending line for invalid input and duplicates", () => {
    expect(parseAllowedLines("ok\n../etc/passwd").error).toContain("../etc/passwd");
    expect(parseAllowedLines("a@1@2").error).toContain("a@1@2");
    expect(parseAllowedLines("a\na@1").error).toContain("mehrfach");
  });

  it("round-trips the list", () => {
    const rules = [{ id: "calendar", version: "1.5.10" }, { id: "dataview", version: null }];

    expect(parseAllowedLines(formatAllowedLines(rules)).rules).toEqual(rules);
  });

  it("explains a device's status, naming the offending plugins", () => {
    expect(deviceStatusText({ status: "NON_COMPLIANT", offending: ["kanban 2.0.51"], wipedAt: null })).toBe("Verstößt gegen die Richtlinie: kanban 2.0.51");
    expect(deviceStatusText({ status: "REVOKED", offending: [], wipedAt: "2026-11-25T09:00:00Z" })).toBe("Gesperrt, Daten gelöscht");
    expect(deviceStatusText({ status: "COMPLIANT", offending: [], wipedAt: null })).toBe("Entspricht der Richtlinie");
  });
});
