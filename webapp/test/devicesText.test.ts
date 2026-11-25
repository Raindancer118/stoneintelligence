import { describe, expect, it } from "vitest";
import { deviceStatusText, deviceTone, formatAllowedLines, parseAllowedLines } from "../src/lib/devicesText";

describe("devicesText", () => {
  it("parses one plugin per line with optional pinned versions and comments", () => {
    expect(parseAllowedLines("calendar@1.5.10\n\n# Notizen\ndataview\n")).toEqual({
      rules: [{ id: "calendar", version: "1.5.10" }, { id: "dataview", version: null }], error: null,
    });
  });

  it("names the offending line for invalid input and duplicates", () => {
    expect(parseAllowedLines("ok\n../etc/passwd").error).toContain("../etc/passwd");
    expect(parseAllowedLines("a@1@2").error).toContain("a@1@2");
    expect(parseAllowedLines("a\na@1").error).toContain("mehrfach");
  });

  it("round-trips", () => {
    const rules = [{ id: "calendar", version: "1.5.10" }, { id: "dataview", version: null }];
    expect(parseAllowedLines(formatAllowedLines(rules)).rules).toEqual(rules);
  });

  it("words the status and tone of a device", () => {
    expect(deviceStatusText({ status: "NON_COMPLIANT", offending: ["kanban 1"], wipedAt: null })).toBe("Verstößt gegen die Richtlinie: kanban 1");
    expect(deviceStatusText({ status: "REVOKED", offending: [], wipedAt: "x" })).toBe("Gesperrt, Daten gelöscht");
    expect(deviceStatusText({ status: "WIPE_PENDING", offending: [], wipedAt: null })).toContain("beim nächsten Verbinden");
    expect(deviceTone({ status: "COMPLIANT" })).toBe("ok");
    expect(deviceTone({ status: "NON_COMPLIANT" })).toBe("warn");
    expect(deviceTone({ status: "UNKNOWN" })).toBe("bad");
  });
});
