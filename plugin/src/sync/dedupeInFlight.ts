/**
 * Dedupliziert gleichzeitige Aufrufe: solange ein Aufruf noch laeuft, bekommen alle weiteren
 * Aufrufer DIESELBE Promise zurueck statt eine eigene neue auszuloesen. Sobald sie sich
 * entschieden hat (erfuellt ODER abgelehnt), erlaubt der naechste Aufruf einen frischen Versuch.
 *
 * <p>Wurde noetig, weil {@code getAccessToken()} ohne das bei gleichzeitigen Aufrufern (z. B.
 * Reconciliation + Ticket-Ausstellung fast zeitgleich) denselben, noch gueltig aussehenden
 * Refresh-Token mehrfach parallel einreichte - Authentik rotiert Refresh-Tokens (macht den alten
 * nach erfolgreicher Einloesung ungueltig), der zweite, quasi gleichzeitige Versuch scheiterte
 * dadurch garantiert mit HTTP 400 und legte den gespeicherten Token-Zustand nie neu an - live
 * beobachtet als endlose Kette fehlschlagender Refreshes, nachdem der Multiplex-Transport viele
 * Operationen parallel anstiess.
 */
export function dedupeInFlight<T>(): (fn: () => Promise<T>) => Promise<T> {
  let inFlight: Promise<T> | null = null;
  return (fn: () => Promise<T>): Promise<T> => {
    if (!inFlight) {
      inFlight = fn().finally(() => {
        inFlight = null;
      });
    }
    return inFlight;
  };
}
