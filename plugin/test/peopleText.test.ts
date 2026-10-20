import { describe, expect, it } from "vitest";
import { idpLinkText, personLabel } from "../src/ui/peopleText";

describe("peopleText", () => {
  // Mit sub/oid als Principal-Claim sind Subjects UUIDs - angezeigt wird der Name.
  it("should_showTheDisplayName_andTheSubjectOnlyWhenItSaysSomethingElse", () => {
    expect(personLabel("5f2c1a7e-0d3b-4c2a-9f1e-2b7c8d9e0a11", "Ben Becker")).toBe("Ben Becker");
    expect(personLabel("ben", "Ben Becker")).toBe("Ben Becker (ben)");
    expect(personLabel("ben", null)).toBe("ben");
    expect(personLabel("ben", "ben")).toBe("ben");
  });

  it("should_describeTheIdpLink_withTheNumberOfPeople", () => {
    expect(idpLinkText({ idpGroup: null, idpMemberCount: null })).toBeNull();
    expect(idpLinkText({ idpGroup: "sales", idpMemberCount: 0 })).toBe("Verknüpft mit „sales“ beim Identitätsanbieter – noch niemand angemeldet");
    expect(idpLinkText({ idpGroup: "sales", idpMemberCount: 1 })).toBe("Verknüpft mit „sales“ beim Identitätsanbieter – 1 Person");
    expect(idpLinkText({ idpGroup: "all", idpMemberCount: 12034 })).toBe("Verknüpft mit „all“ beim Identitätsanbieter – 12.034 Personen");
  });
});
