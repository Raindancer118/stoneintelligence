import { describe, expect, it } from "vitest";
import { inlineAsset } from "../inlineAssets";

describe("asset inlining", () => {
  // Die CSP erlaubt Schriften nur von 'self' - als data:-URL eingebettete Schriften blockt der Browser.
  it("never inlines fonts", () => {
    expect(inlineAsset("/x/plus-jakarta-sans-vietnamese-400-normal.woff2")).toBe(false);
    expect(inlineAsset("/x/jetbrains-mono-greek-400-normal.woff")).toBe(false);
  });

  it("leaves everything else to Vite's size limit", () => {
    expect(inlineAsset("/x/logo.png")).toBeUndefined();
  });
});
