export const MOBILE_REDIRECT_ACTION = "stoneintelligence-auth";
export const MOBILE_REDIRECT_URI = `obsidian://${MOBILE_REDIRECT_ACTION}`;

/**
 * Oeffnet die Authentik-Login-Seite im System-/In-App-Browser auf Mobile (Capacitor) - dort gibt
 * es kein `electron.shell.openExternal`. `window.open` wird von Obsidian Mobile fuer externe
 * URLs an den System-Browser durchgereicht.
 */
export function openAuthorizationUrlMobile(url: string): void {
  window.open(url, "_system");
}

export interface PendingAuthCallback {
  state: string;
  resolve: (code: string) => void;
  reject: (error: Error) => void;
}

/**
 * Wartet auf den Redirect, gibt aber nach `timeoutMs` auf und verdraengt einen frueheren, nie
 * beantworteten Login - sonst bliebe "Warte auf Anmeldung..." nach abgebrochenem Browser ewig stehen.
 */
export function awaitMobileRedirect(
  state: string,
  current: { pending: PendingAuthCallback | null },
  timeoutMs = 5 * 60_000,
): Promise<string> {
  current.pending?.reject(new Error("login cancelled"));
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      if (current.pending === entry) {
        current.pending = null;
      }
      reject(new Error("login timed out"));
    }, timeoutMs);
    const entry: PendingAuthCallback = {
      state,
      resolve: (code) => { clearTimeout(timer); resolve(code); },
      reject: (error) => { clearTimeout(timer); reject(error); },
    };
    current.pending = entry;
  });
}

/**
 * Loest die auf den `obsidian://`-Redirect wartende Promise auf (oder lehnt sie ab) - reine
 * Funktion, getrennt von `registerObsidianProtocolHandler`, damit sie ohne Plugin-/Obsidian-
 * Laufzeit testbar ist. `pending === null` heisst: kein Login laeuft gerade, der Aufruf wird
 * ignoriert (z. B. ein zweiter/verspaeteter Redirect).
 */
export function handleMobileRedirectCallback(
  pending: PendingAuthCallback | null,
  params: Record<string, string>,
): void {
  if (!pending) {
    return;
  }
  if (params.error) {
    pending.reject(new Error(`authorization failed: ${params.error}`));
  } else if (params.state !== pending.state) {
    pending.reject(new Error("state mismatch - moeglicher CSRF-Versuch"));
  } else if (!params.code) {
    pending.reject(new Error("no authorization code in callback"));
  } else {
    pending.resolve(params.code);
  }
}
