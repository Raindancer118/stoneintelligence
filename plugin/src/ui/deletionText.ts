/** Text des Loeschdialogs - "Notiz" nur fuer Markdown, sonst "Datei" (ADR 0009). */
export function deletionConflictText(path: string): string {
  const what = path.toLowerCase().endsWith(".md") ? "Notiz" : "Datei";
  return `Hier gibt es an dieser ${what} noch Änderungen, die nie übertragen wurden. `
    + "Ohne Auswahl wird sie gelöscht (in den Papierkorb, dort wiederherstellbar).";
}
