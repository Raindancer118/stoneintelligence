import { describe, expect, it } from "vitest";
import { actorDisplayNameFromAccessToken, decodeJwtClaims, pickUserColor } from "../src/sync/actorIdentity";

/** Baut ein syntaktisch gueltiges (unsigniertes) JWT fuer Tests - Payload beliebig. */
function fakeJwt(payload: Record<string, unknown>): string {
  const base64url = (obj: Record<string, unknown>) =>
    btoa(JSON.stringify(obj)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${base64url({ alg: "none" })}.${base64url(payload)}.`;
}

describe("pickUserColor", () => {
  it("should_returnTheSameColor_forTheSameNameEveryTime", () => {
    expect(pickUserColor("tom")).toBe(pickUserColor("tom"));
  });

  it("should_returnAColorFromTheFixedPalette", () => {
    const color = pickUserColor("anyone");
    expect(color).toMatch(/^#[0-9a-f]{6}$/);
  });

  it("should_tendToReturnDifferentColors_forDifferentNames", () => {
    const colors = new Set(["alice", "bob", "carol", "dave", "erin"].map(pickUserColor));
    expect(colors.size).toBeGreaterThan(1);
  });
});

describe("decodeJwtClaims", () => {
  it("should_decodeThePayloadClaims_ofAWellFormedJwt", () => {
    const token = fakeJwt({ preferred_username: "tom", sub: "abc123" });

    expect(decodeJwtClaims(token)).toEqual({ preferred_username: "tom", sub: "abc123" });
  });

  it("should_returnNull_forAMalformedToken", () => {
    expect(decodeJwtClaims("not-a-jwt")).toBeNull();
    expect(decodeJwtClaims("")).toBeNull();
  });

  it("should_returnNull_whenThePayloadSegmentIsNotValidBase64Json", () => {
    expect(decodeJwtClaims("header.%%%notbase64%%%.signature")).toBeNull();
  });
});

describe("actorDisplayNameFromAccessToken", () => {
  it("should_returnThePreferredUsernameClaim_whenPresent", () => {
    const token = fakeJwt({ preferred_username: "reader-actor" });

    expect(actorDisplayNameFromAccessToken(token)).toBe("reader-actor");
  });

  it("should_returnAFallback_whenTheTokenIsMissing", () => {
    expect(actorDisplayNameFromAccessToken(null)).toBe("Unbekannt");
    expect(actorDisplayNameFromAccessToken(undefined)).toBe("Unbekannt");
  });

  it("should_returnAFallback_whenTheClaimIsAbsentOrMalformed", () => {
    const token = fakeJwt({ sub: "abc123" });

    expect(actorDisplayNameFromAccessToken(token)).toBe("Unbekannt");
    expect(actorDisplayNameFromAccessToken("garbage")).toBe("Unbekannt");
  });
});
