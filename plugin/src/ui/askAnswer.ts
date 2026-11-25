/**
 * Die Antwort des Modells, bevor Obsidian sie als Markdown rendert (ADR 0017): Bilder, Einbettungen, rohes HTML und
 * Links nach aussen fallen weg. Ein Modell, das von Notiztext umgelenkt wurde, kann so nichts nachladen und keine
 * Daten ueber eine Adresse nach draussen tragen. Zitate `[n]` und Verweise auf Notizen `[[Titel]]` bleiben.
 */
export function answerForDisplay(markdown: string): string {
  return markdown
    .replace(/!\[\[[^\]]*]]/g, "")
    .replace(/!\[[^\]]*]\([^)]*\)/g, "")
    .replace(/<\/?[a-zA-Z][^>]*>/g, "")
    .replace(/\[([^\]]+)]\((?:https?|mailto|obsidian|javascript|data|file):[^)]*\)/gi, "$1");
}
