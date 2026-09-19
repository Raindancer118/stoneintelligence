import { User, UserManager } from "oidc-client-ts";

/**
 * Browser-OIDC (Authorization Code + PKCE) gegen denselben Authentik-Public-Client wie das
 * Obsidian-Plugin (identischer Issuer/Client-ID, andere Redirect-URI - `/callback` statt des
 * Plugin-Loopback-Servers). `oidc-client-ts` uebernimmt PKCE-Erzeugung, Token-Refresh (silent
 * renew via Iframe) und Session-Storage - kein Grund, das selbst nachzubauen (s. senior-dev).
 */
const userManager = new UserManager({
  authority: import.meta.env.VITE_OIDC_ISSUER_URL,
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID,
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: window.location.origin,
  response_type: "code",
  scope: "openid profile email offline_access",
  // Bewusst aus: monitorSession/automaticSilentRenew bauen eine Check-Session-Iframe- bzw.
  // Silent-Renew-Infrastruktur auf, die schon beim Laden der Seite (noch ohne eingeloggten
  // Nutzer) Metadaten nachlaedt - ein einzelner Nutzer, der sich bei Bedarf neu einloggt, ist
  // hier einfacher und robuster als stille Hintergrund-Renews ueber ein Iframe.
  monitorSession: false,
  automaticSilentRenew: false,
});

export async function login(): Promise<void> {
  await userManager.signinRedirect();
}

export async function completeLogin(): Promise<User> {
  return userManager.signinRedirectCallback();
}

/** Faellt bewusst auf "nicht angemeldet" zurueck statt die ganze App mit einem Fehler zu blockieren. */
export async function getUser(): Promise<User | null> {
  try {
    const user = await userManager.getUser();
    return user && !user.expired ? user : null;
  } catch {
    return null;
  }
}

export async function logout(): Promise<void> {
  await userManager.signoutRedirect();
}

export async function getAccessToken(): Promise<string> {
  const user = await userManager.getUser();
  if (!user || user.expired) {
    throw new Error("nicht angemeldet");
  }
  return user.access_token;
}

export function preferredUsername(user: User): string {
  return (user.profile.preferred_username as string | undefined) ?? user.profile.sub;
}
