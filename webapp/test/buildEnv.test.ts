import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { missingBuildEnv } from "../buildEnv";

const complete = {
  VITE_PLATFORM_API_URL: "https://api.example",
  VITE_PLATFORM_WS_URL: "wss://api.example",
  VITE_OIDC_ISSUER_URL: "https://idp.example/application/o/x/",
  VITE_OIDC_CLIENT_ID: "abc",
};

describe("build environment", () => {
  it("accepts a complete configuration", () => {
    expect(missingBuildEnv(complete)).toEqual([]);
  });

  // Ohne Issuer baute Vite ein Bundle, das beim Login nur "No authority or metadataUrl configured" zeigte.
  it("names every missing or blank value", () => {
    expect(missingBuildEnv({ ...complete, VITE_OIDC_ISSUER_URL: " ", VITE_OIDC_CLIENT_ID: undefined }))
      .toEqual(["VITE_OIDC_ISSUER_URL", "VITE_OIDC_CLIENT_ID"]);
  });

  it("keeps .env in the Docker build context, since Vite reads it at build time", () => {
    const dockerignore = readFileSync(".dockerignore", "utf8");
    const rules = dockerignore.split("\n").map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
    const excluded = rules.reduce((out, rule) => {
      if (rule === "!.env") return false;
      return out || rule === ".env" || rule === ".env*";
    }, false);
    expect(excluded).toBe(false);
  });
});
