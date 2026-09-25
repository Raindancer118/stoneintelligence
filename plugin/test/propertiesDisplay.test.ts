import { describe, expect, it } from "vitest";
import { propertiesDefault } from "../src/ui/propertiesDisplay";

describe("propertiesDefault", () => {
  // KI-Notizen sollen sich lesen lassen: die Eigenschaften oben stoeren dabei (Issue #1).
  it("should_hideProperties_onTheFirstStart_when_obsidianStillShowsThem", () => {
    expect(propertiesDefault(false, "visible")).toBe("hidden");
    expect(propertiesDefault(false, undefined)).toBe("hidden");
  });

  // Wer "Quelltext" gewaehlt hat oder sie schon ausblendet, hat sich bewusst entschieden.
  it("should_keepADeliberateChoice", () => {
    expect(propertiesDefault(false, "source")).toBeNull();
    expect(propertiesDefault(false, "hidden")).toBeNull();
  });

  // Nur einmal: blendet jemand sie danach wieder ein, bleibt das so.
  it("should_neverChangeItAgain_afterTheFirstStart", () => {
    expect(propertiesDefault(true, "visible")).toBeNull();
  });
});
