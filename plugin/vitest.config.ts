import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "jsdom",
    include: ["test/**/*.test.ts"],
  },
  resolve: {
    alias: {
      // Das echte "obsidian"-npm-Paket liefert nur Typdeklarationen (main: "" in seiner
      // package.json) - ohne diesen Alias kann Vite den Import gar nicht erst aufloesen,
      // unabhaengig davon, ob ein Test vi.mock("obsidian", ...) nutzt. S. test/obsidian-stub.ts.
      obsidian: fileURLToPath(new URL("./test/obsidian-stub.ts", import.meta.url)),
    },
  },
});
