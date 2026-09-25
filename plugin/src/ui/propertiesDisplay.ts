/**
 * Obsidians Einstellung "Eigenschaften im Dokument" (`propertiesInDocument`: visible | hidden |
 * source), gilt fuer den ganzen Vault. StoneIntelligence blendet sie beim ersten Start aus - die
 * Eigenschaften oben in jeder Notiz stoerten beim Lesen (Issue #1). Sie bleiben in der
 * Seitenleiste "Dateieigenschaften" erreichbar.
 */
export type PropertiesInDocument = "visible" | "hidden" | "source";

/** Was beim Start zu setzen ist, oder null: nur beim ersten Start, und nie gegen eine bewusste Wahl. */
export function propertiesDefault(alreadyApplied: boolean, current: string | undefined): PropertiesInDocument | null {
  if (alreadyApplied) {
    return null;
  }
  return current === undefined || current === "visible" ? "hidden" : null;
}
