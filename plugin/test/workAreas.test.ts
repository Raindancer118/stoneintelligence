import { describe, expect, it } from "vitest";
import { effectiveScope, inScope, isAreasMode, MAX_SCOPE, NOTHING, outOfScope, scopeKey } from "../src/sync/workAreas";

describe("work areas", () => {
  it("follow the vault's default until the device decides", () => {
    expect(isAreasMode({}, false)).toBe(false);
    expect(isAreasMode({}, true)).toBe(true);
    expect(isAreasMode({ areasMode: false }, true)).toBe(false);
  });

  it("mean the whole vault when off, and nothing when on without any area", () => {
    expect(effectiveScope({ areas: ["Team"] }, false)).toBeNull();
    expect(effectiveScope({ areasMode: true }, false)).toEqual([NOTHING]);
    expect(inScope("Team/x.md", [NOTHING])).toBe(false);
  });

  it("combine areas and pinned notes outside them, outermost only", () => {
    const scope = effectiveScope({ areasMode: true, areas: ["Team", "Team/Sub", "Kunden"], pinned: ["Team/x.md", "Wiki/Start.md"] }, false);

    expect(scope).toEqual(["Kunden", "Team", "Wiki/Start.md"]);
    expect(inScope("Team/Sub/a.md", scope)).toBe(true);
    expect(inScope("Wiki/Start.md", scope)).toBe(true);
    expect(inScope("Wiki/Other.md", scope)).toBe(false);
    expect(inScope("Teamraum/a.md", scope)).toBe(false);
  });

  it("fold many pinned notes into their folders so requests stay small", () => {
    const pinned = Array.from({ length: 400 }, (_, i) => `Wiki/Seite${i % 20}/Notiz${i}.md`);

    const scope = effectiveScope({ areasMode: true, areas: ["Team"], pinned }, false)!;

    expect(scope.length).toBeLessThanOrEqual(MAX_SCOPE);
    expect(pinned.every((path) => inScope(path, scope))).toBe(true);
    expect(inScope("Team/x.md", scope)).toBe(true);
  });

  it("names local notes that fall outside", () => {
    expect(outOfScope(["Team/a.md", "Rest/b.md"], ["Team"])).toEqual(["Rest/b.md"]);
    expect(outOfScope(["Rest/b.md"], null)).toEqual([]);
  });

  it("has a stable key per scope", () => {
    expect(scopeKey(null)).toBe("*");
    expect(scopeKey(["B", "A"])).toBe(scopeKey(["A", "B"]));
  });
});
