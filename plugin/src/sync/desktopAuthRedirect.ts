import { AuthentikAuthClient } from "./AuthentikAuthClient";

export const OIDC_REDIRECT_PORT = 42813;
export const DESKTOP_REDIRECT_URI = `http://127.0.0.1:${OIDC_REDIRECT_PORT}/callback`;

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
export function awaitDesktopRedirectCode(expectedState: string): Promise<string> {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const http = require("http") as typeof import("http");
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const requestUrl = new URL(req.url ?? "", DESKTOP_REDIRECT_URI);
      if (requestUrl.pathname !== "/callback") {
        res.writeHead(404).end();
        return;
      }
      const code = requestUrl.searchParams.get("code");
      const state = requestUrl.searchParams.get("state");
      const error = requestUrl.searchParams.get("error");

      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      res.end(error
        ? `<html><body>Login fehlgeschlagen: ${AuthentikAuthClient.escapeHtml(error)}. Dieses Fenster kann geschlossen werden.</body></html>`
        : "<html><body>Login erfolgreich - dieses Fenster kann geschlossen werden.</body></html>");
      server.close();

      if (error) {
        reject(new Error(`authorization failed: ${error}`));
      } else if (state !== expectedState) {
        reject(new Error("state mismatch - moeglicher CSRF-Versuch"));
      } else if (!code) {
        reject(new Error("no authorization code in callback"));
      } else {
        resolve(code);
      }
    });
    server.listen(OIDC_REDIRECT_PORT, "127.0.0.1");
  });
}
