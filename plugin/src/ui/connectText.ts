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

function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

/** Der Link zeigt auf einen anderen Server: sagen, wohin es geht und wo die Anmeldung laeuft. */
export function serverSwitchWarning(
  target: { platformApiUrl: string; oidcIssuerUrl: string },
  current: { platformApiUrl: string },
): string {
  const server = hostOf(target.platformApiUrl);
  const issuer = hostOf(target.oidcIssuerUrl);
  const login = issuer === server ? "" : ` (Anmeldung über ${issuer})`;
  return `Dieser Link gehört zum Server ${server}${login}, bisher ist ${hostOf(current.platformApiUrl)} eingestellt. `
    + "Verbinde nur, wenn du dieser Einrichtungsseite vertraust – danach meldest du dich dort neu an.";
}
