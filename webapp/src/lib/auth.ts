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

/** @param returnTo Pfad, auf den die App nach der Anmeldung zurueckkehrt (z. B. eine Einladung). */
export async function login(returnTo?: string): Promise<void> {
  await userManager.signinRedirect(returnTo ? { state: { returnTo } } : undefined);
}

export async function completeLogin(): Promise<User> {
  return userManager.signinRedirectCallback();
}

/** Rechtzeitig vor Ablauf erneuern, damit keine Anfrage mit einem gerade sterbenden Token rausgeht. */
const RENEW_BEFORE_SECONDS = 60;
let renewal: Promise<User | null> | null = null;

/**
 * Erneuert das Access-Token mit dem Refresh-Token (`signinSilent` nutzt bei vorhandenem
 * Refresh-Token den refresh_token-Grant, kein Iframe). Parallele Aufrufe teilen sich eine
 * Erneuerung - Authentik rotiert Refresh-Tokens, ein zweiter gleichzeitiger Versuch wuerde am
 * bereits verbrauchten Token scheitern.
 */
function renew(): Promise<User | null> {
  renewal ??= userManager.signinSilent()
    .then((user) => (user && !user.expired ? user : null), () => null)
    .finally(() => { renewal = null; });
  return renewal;
}

/**
 * Gueltige Sitzung oder null. Ein abgelaufenes (oder gleich ablaufendes) Access-Token wird mit dem
 * Refresh-Token still erneuert - vorher endete jede Sitzung nach der Lebensdauer des Access-Tokens.
 * Faellt bewusst auf "nicht angemeldet" zurueck statt die ganze App mit einem Fehler zu blockieren.
 */
export async function getUser(): Promise<User | null> {
  try {
    const user = await userManager.getUser();
    if (!user) {
      return null;
    }
    const expiringSoon = user.expired || (user.expires_in !== undefined && user.expires_in < RENEW_BEFORE_SECONDS);
    if (!expiringSoon) {
      return user;
    }
    return user.refresh_token ? await renew() : (user.expired ? null : user);
  } catch {
    return null;
  }
}

export async function logout(): Promise<void> {
  await userManager.signoutRedirect();
}

export async function getAccessToken(): Promise<string> {
  const user = await getUser();
  if (!user) {
    throw new Error("nicht angemeldet");
  }
  return user.access_token;
}

export function preferredUsername(user: User): string {
  return (user.profile.preferred_username as string | undefined) ?? user.profile.sub;
}
