import { describe, expect, it } from "vitest";
import { initialTheme, nextTheme, THEME_KEY } from "../src/lib/theme";

describe("theme", () => {
  it("follows the system when nothing is stored", () => {
    expect(initialTheme(null, true)).toBe("dark");
    expect(initialTheme(null, false)).toBe("light");
  });

  it("keeps a stored choice over the system", () => {
    expect(initialTheme("light", true)).toBe("light");
    expect(initialTheme("dark", false)).toBe("dark");
  });

  it("ignores garbage in storage", () => {
    expect(initialTheme("purple", true)).toBe("dark");
  });

  it("toggles between the two", () => {
    expect(nextTheme("dark")).toBe("light");
    expect(nextTheme("light")).toBe("dark");
    expect(THEME_KEY).toBe("stone.theme");
  });
});
