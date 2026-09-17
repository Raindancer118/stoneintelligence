import { requestUrl } from "obsidian";

/**
 * Fetch-kompatibler Adapter ueber Obsidians `requestUrl()`. Noetig, weil natives `fetch()` im
 * Electron-Renderer weiterhin normale Browser-CORS-Durchsetzung unterliegt - Obsidians eigener
 * Origin (ein internes `app://`/`capacitor://`-Schema) laesst sich bei keinem externen Server
 * sinnvoll als erlaubte CORS-Origin registrieren (live beobachtet: `discover()` schlug mit
 * "Failed to fetch" fehl, obwohl derselbe Issuer fuer die registrierten Origins der Webapp
 * einwandfrei antwortet). `requestUrl()` macht den Request auf Node/Electron-`net`-Ebene ausserhalb
 * des Browser-Sicherheitsmodells, dort greift CORS gar nicht erst - der von Obsidian selbst
 * dokumentierte Weg fuer Plugins, die beliebige externe APIs ansprechen muessen.
 *
 * <p>Deckt bewusst nur die Response-Oberflaeche ab, die dieses Plugin tatsaechlich nutzt
 * (`.ok`, `.status`, `.json()`) - kein vollstaendiges `fetch()`-Polyfill.
 */
export const obsidianFetch: typeof fetch = async (input, init) => {
  const url = typeof input === "string" ? input : input.toString();
  const method = (init?.method ?? "GET").toUpperCase();
  const headers = init?.headers as Record<string, string> | undefined;
  const body = typeof init?.body === "string" ? init.body : undefined;

  const response = await requestUrl({ url, method, headers, body, throw: false });

  return {
    ok: response.status >= 200 && response.status < 300,
    status: response.status,
    json: async () => response.json,
    text: async () => response.text,
  } as Response;
};
