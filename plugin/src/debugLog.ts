/** Diagnostics stay silent unless `localStorage.stoneintelligenceDebug = "1"`; only real errors belong in the console. */
export function debugLog(...args: unknown[]): void {
  if (typeof localStorage !== "undefined" && localStorage.getItem("stoneintelligenceDebug") === "1") {
    console.error(...args);
  }
}
