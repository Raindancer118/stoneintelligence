/** Hinweis, dass bereits vorhandene Notizen fuer alle Mitglieder sichtbar werden - grammatisch passend zur Anzahl. */
export function uploadWarning(count: number): string {
  return count === 1
    ? "Die Notiz, die schon hier liegt, wird ebenfalls hochgeladen und ist dann für alle Mitglieder sichtbar."
    : `Die ${count} Notizen, die schon hier liegen, werden ebenfalls hochgeladen und sind dann für alle Mitglieder sichtbar.`;
}

/**
 * Neuer Obsidian-Vault oder dieser hier? Nur ein leerer, noch mit keinem gemeinsamen Vault
 * verbundener Vault ist so gut wie ein neuer - sonst wuerden private Notizen hochgeladen oder eine
 * bestehende Verbindung verdraengt.
 */
export function recommendNewVault(current: { localNoteCount: number; connectedElsewhere: boolean }): boolean {
  return current.localNoteCount > 0 || current.connectedElsewhere;
}
