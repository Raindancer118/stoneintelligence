/** Exakt dasselbe JSON-Format wie das Obsidian-Plugins "Verbindungsdaten einfuegen" erwartet. */
export function connectionConfigJson(vaultId: string): string {
  return JSON.stringify(
    {
      platformApiUrl: import.meta.env.VITE_PLATFORM_API_URL,
      platformWsUrl: import.meta.env.VITE_PLATFORM_WS_URL,
      vaultId,
      oidcIssuerUrl: import.meta.env.VITE_OIDC_ISSUER_URL,
      oidcClientId: import.meta.env.VITE_OIDC_CLIENT_ID,
    },
    null,
    2,
  );
}

/**
 * Verbinden-Knopf der Einrichtungsseite. Nennt den Server DIESER Instanz, damit das Plugin auch mit
 * einer selbst gehosteten verbindet - es fragt nach, bevor es auf einen anderen Server wechselt.
 * encodeURIComponent statt URLSearchParams: Obsidian dekodiert kein "+" als Leerzeichen. "stoneVault"
 * statt "vault": den liest Obsidian selbst als Namen des zu oeffnenden Obsidian-Vaults.
 */
export function obsidianConnectLink(vault: { id: string; name: string }): string {
  const params: Array<[string, string]> = [
    ["stoneVault", vault.id],
    ["name", vault.name],
    ["server", import.meta.env.VITE_PLATFORM_API_URL ?? ""],
    ["ws", import.meta.env.VITE_PLATFORM_WS_URL ?? ""],
    ["issuer", import.meta.env.VITE_OIDC_ISSUER_URL ?? ""],
    ["client", import.meta.env.VITE_OIDC_CLIENT_ID ?? ""],
  ];
  const query = params.filter(([, value]) => value).map(([key, value]) => `${key}=${encodeURIComponent(value)}`).join("&");
  return `obsidian://stoneintelligence-connect?${query}`;
}

export function serverHost(): string {
  try {
    return new URL(import.meta.env.VITE_PLATFORM_API_URL).host;
  } catch {
    return import.meta.env.VITE_PLATFORM_API_URL ?? "";
  }
}
