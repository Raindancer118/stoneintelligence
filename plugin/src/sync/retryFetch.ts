export interface RetryOptions {
  maxRetries?: number;
  initialDelayMs?: number;
  sleep?: (ms: number) => Promise<void>;
}

const defaultSleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Ermittelt die Wartezeit vor dem naechsten Versuch: der Server kennt den exakten Bucket-Fuellstand
 * und schickt bei 429 einen `Retry-After`-Header (Sekunden, s. platform-api RateLimitInterceptor) -
 * der ist praeziser als jede geratene Backoff-Kurve und wird deshalb bevorzugt. Nur wenn der Header
 * fehlt oder nicht parsbar ist (z. B. ein fetch-Impl ohne Header-Unterstuetzung), faellt der Client
 * auf Exponential-Backoff zurueck.
 */
function delayFor(response: Response, attempt: number, initialDelayMs: number): number {
  const retryAfterSeconds = Number(response.headers?.get?.("Retry-After"));
  if (Number.isFinite(retryAfterSeconds) && retryAfterSeconds > 0) {
    return retryAfterSeconds * 1000;
  }
  return initialDelayMs * 2 ** attempt;
}

/**
 * Umhuellt einen fetch-Impl mit Retry bei HTTP 429 (Server-seitiger Token-Bucket-Rate-Limiter, s.
 * platform-api RateLimiter). Ohne das: ein Vault mit mehr Notizen, als der Bucket Kapazitaet hat,
 * feuert beim initialen syncAllNotes() alle Requests ungebremst hintereinander ab - jede Notiz
 * nach Bucket-Erschoepfung schlaegt dann dauerhaft mit einem nie abgefangenen 429 fehl (live
 * beobachtet). `maxRetries` ist bewusst grosszuegig: dank `Retry-After` weiss der Client genau,
 * wie lange eine Wartezeit dauert, statt raten zu muessen - lange Vault-weite Backlogs (Hunderte
 * Notizen bei 60 Bucket-Kapazitaet + 1 Token/s Refill) brauchen viele, aber kurze Wartezyklen.
 */
export function withRateLimitRetry(fetchImpl: typeof fetch, options: RetryOptions = {}): typeof fetch {
  const maxRetries = options.maxRetries ?? 30;
  const initialDelayMs = options.initialDelayMs ?? 500;
  const sleep = options.sleep ?? defaultSleep;

  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    for (let attempt = 0; ; attempt++) {
      const response = await fetchImpl(input, init);
      if (response.status !== 429 || attempt >= maxRetries) {
        return response;
      }
      await sleep(delayFor(response, attempt, initialDelayMs));
    }
  }) as typeof fetch;
}
