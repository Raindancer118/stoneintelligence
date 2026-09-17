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
