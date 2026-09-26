import { describe, expect, it } from "vitest";
import {
  badgeFor, describeScope, describeSource, explainAccessError, isReadOnly, permissionsLabel, PRESETS,
  permissionsFor, presetOf, type Grant,
} from "../src/sync/accessPlan";

const grant = (overrides: Partial<Grant>): Grant => ({
  id: "g1", target: { kind: "folder", path: "Team", noteId: null }, scopeType: "USER", subject: "ben",
  groupName: null, permissions: ["READ"], inheritsVault: false, ...overrides,
});

describe("accessPlan", () => {
  describe("presets", () => {
    it("should_mapEveryPreset_toItsPermissions_andBack", () => {
      for (const preset of PRESETS) {
        expect(presetOf(permissionsFor(preset.id))).toBe(preset.id);
      }
    });

    it("should_callAnythingElse_custom_andIgnoreOrder", () => {
      expect(presetOf(["WRITE", "READ"])).toBe("custom");
      expect(presetOf(["DELETE", "CREATE", "WRITE", "READ"])).toBe("edit");
      expect(presetOf(null)).toBe("inherit");
      expect(presetOf([])).toBe("none");
    });
  });

  it("should_describeRightsInPlainWords", () => {
    expect(permissionsLabel([])).toBe("Kein Zugriff");
    expect(permissionsLabel(["READ"])).toBe("Lesen");
    expect(permissionsLabel(["MANAGE", "READ", "WRITE"])).toBe("Lesen, Bearbeiten, Verwalten");
    expect(permissionsLabel(null)).toBe("Wie im Vault");
  });

  it("should_sayWhereRightsComeFrom", () => {
    expect(describeSource(null)).toBe("aus der Vault-Rolle");
    expect(describeSource(grant({}))).toBe("Freigabe für Ordner „Team“");
    expect(describeSource(grant({ target: { kind: "folder", path: "", noteId: null } }))).toBe("Freigabe für den ganzen Vault");
    expect(describeSource(grant({ target: { kind: "entry", path: "Team/plan.md", noteId: "n1" } }))).toBe("Freigabe für diese Datei");
  });

  it("should_nameWhoAGrantIsFor", () => {
    expect(describeScope(grant({}))).toBe("ben");
    expect(describeScope(grant({ scopeType: "GROUP", subject: "id", groupName: "Lektorat" }))).toBe("Gruppe „Lektorat“");
    expect(describeScope(grant({ scopeType: "EVERYONE", subject: null }))).toBe("Alle Mitglieder");
  });

  describe("badges", () => {
    it("should_markReadOnlyEntries_andSharedOnesForManagers", () => {
      expect(badgeFor({ permissions: ["READ"], shared: null })).toBe("readonly");
      expect(badgeFor({ permissions: ["READ", "WRITE", "MANAGE"], shared: true })).toBe("shared");
      expect(badgeFor({ permissions: ["READ", "WRITE"], shared: null })).toBeNull();
    });

    it("should_notJudgeEntriesOfOlderServers", () => {
      expect(badgeFor({})).toBeNull();
      expect(isReadOnly(undefined)).toBe(false);
      expect(isReadOnly(["READ"])).toBe(true);
    });
  });

  it("should_explainRefusalsInPlainWords", () => {
    expect(explainAccessError(403)).toContain("keine Berechtigung");
    expect(explainAccessError(409)).toContain("niemand mehr");
    expect(explainAccessError(400)).toContain("Mitglied");
    expect(explainAccessError(404)).toContain("nicht mehr");
    expect(explainAccessError(500)).toContain("HTTP 500");
  });
});
