/**
 * Test-only Ersatz fuer das "obsidian"-npm-Paket: es liefert nur Typdeklarationen
 * (`main: ""` in seiner package.json), Vite/Vitest kann den Import sonst gar nicht erst
 * aufloesen - unabhaengig davon, ob ein einzelner Test `vi.mock("obsidian", ...)` nutzt. Per
 * `resolve.alias` in vitest.config.ts eingebunden; einzelne Tests koennen `requestUrl` trotzdem
 * per `vi.mock("obsidian", ...)` ueberschreiben.
 */
export function requestUrl(): never {
  throw new Error("requestUrl() stub called without vi.mock override in this test");
}
