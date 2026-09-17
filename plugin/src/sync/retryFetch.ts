export interface RetryOptions {
  maxRetries?: number;
  initialDelayMs?: number;
  sleep?: (ms: number) => Promise<void>;
}

const defaultSleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Umhuellt einen fetch-Impl mit Exponential-Backoff-Retry bei HTTP 429 (Server-seitiger
 * Token-Bucket-Rate-Limiter, s. platform-api RateLimiter). Ohne das: ein Vault mit mehr Notizen,
 * als der Bucket Kapazitaet hat, feuert beim initialen syncAllNotes() alle Requests ungebremst
 * hintereinander ab - jede Notiz nach Bucket-Erschoepfung schlaegt dann dauerhaft mit einem nie
 * abgefangenen 429 fehl (live beobachtet).
 */
export function withRateLimitRetry(fetchImpl: typeof fetch, options: RetryOptions = {}): typeof fetch {
  const maxRetries = options.maxRetries ?? 5;
  const initialDelayMs = options.initialDelayMs ?? 500;
  const sleep = options.sleep ?? defaultSleep;

  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    for (let attempt = 0; ; attempt++) {
      const response = await fetchImpl(input, init);
      if (response.status !== 429 || attempt >= maxRetries) {
        return response;
      }
      await sleep(initialDelayMs * 2 ** attempt);
    }
  }) as typeof fetch;
}
