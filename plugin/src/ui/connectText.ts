/** Hinweis, dass bereits vorhandene Notizen fuer alle Mitglieder sichtbar werden - grammatisch passend zur Anzahl. */
export function uploadWarning(count: number): string {
  return count === 1
    ? "Die Notiz, die schon hier liegt, wird ebenfalls hochgeladen und ist dann für alle Mitglieder sichtbar."
    : `Die ${count} Notizen, die schon hier liegen, werden ebenfalls hochgeladen und sind dann für alle Mitglieder sichtbar.`;
}
