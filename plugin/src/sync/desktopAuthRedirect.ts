import { AuthentikAuthClient } from "./AuthentikAuthClient";

export const OIDC_REDIRECT_PORT = 42813;
export const DESKTOP_REDIRECT_URI = `http://127.0.0.1:${OIDC_REDIRECT_PORT}/callback`;

const LOGIN_TIMEOUT_MS = 5 * 60_000;
let pending: { cancel: () => void } | null = null;

/** Oeffnet die Authentik-Login-Seite im Systembrowser (Electron, nur Desktop). */
export function openAuthorizationUrlDesktop(url: string): void {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const { shell } = require("electron");
  void shell.openExternal(url);
}

/**
 * Faengt den OAuth-Redirect ueber einen lokalen Loopback-HTTP-Server ab (nur Desktop - Node-`http`
 * ist auf Mobile/Capacitor nicht verfuegbar, s. main.ts fuer den Mobile-Pfad ueber Obsidians
 * eigenes `obsidian://`-URI-Schema).
 */
export function awaitDesktopRedirectCode(expectedState: string, timeoutMs = LOGIN_TIMEOUT_MS): Promise<string> {
  cancelDesktopRedirect();
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const http = require("http") as typeof import("http");
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (outcome: () => void): void => {
      if (settled) {
        return;
      }
      settled = true;
      window.clearTimeout(timer);
      pending = null;
      server.close();
      outcome();
    };
    const server = http.createServer((req, res) => {
      const requestUrl = new URL(req.url ?? "", DESKTOP_REDIRECT_URI);
      if (requestUrl.pathname !== "/callback") {
        res.writeHead(404).end();
        return;
      }
      const code = requestUrl.searchParams.get("code");
      const state = requestUrl.searchParams.get("state");
      const error = requestUrl.searchParams.get("error");

      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", Connection: "close" /* sonst haelt Keep-Alive den abgeschlossenen Server am Leben */ });
      res.end(error
        ? `<html><body>Login fehlgeschlagen: ${AuthentikAuthClient.escapeHtml(error)}. Dieses Fenster kann geschlossen werden.</body></html>`
        : "<html><body>Login erfolgreich - dieses Fenster kann geschlossen werden.</body></html>");

      if (error) {
        finish(() => reject(new Error(`authorization failed: ${error}`)));
      } else if (state !== expectedState) {
        finish(() => reject(new Error("state mismatch - moeglicher CSRF-Versuch")));
      } else if (!code) {
        finish(() => reject(new Error("no authorization code in callback")));
      } else {
        finish(() => resolve(code));
      }
    });
    // Ohne Handler waere ein belegter Port (EADDRINUSE) ein unbehandelter Fehler und der Login hinge ewig.
    server.on("error", (error) => finish(() => reject(new Error(
      `Der lokale Login-Port ${OIDC_REDIRECT_PORT} ist nicht verfuegbar: ${error.message}`))));
    const timer = window.setTimeout(() => finish(() => reject(new Error("login timed out"))), timeoutMs);
    pending = { cancel: () => finish(() => reject(new Error("login cancelled"))) };
    server.listen(OIDC_REDIRECT_PORT, "127.0.0.1");
  });
}

/** Beendet einen noch wartenden Login und gibt den Port frei (neuer Login, Plugin-Entladen). */
export function cancelDesktopRedirect(): void {
  pending?.cancel();
}
