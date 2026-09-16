interface PendingOperation {
  operationId: string;
  fingerprints: Set<string>;
  registeredAt: number;
}

/**
 * Fix fuer Fehlerklasse 1 (Plan.md Abschnitt 3): unterscheidet selbstinitiierte
 * (server-getriebene) Vault-Aenderungen von echten Nutzeraenderungen. Korrelation laeuft
 * ausschliesslich ueber exakte Fingerprints (Pfad + Content-Hash bzw. Operationsart), NIE ueber
 * ein Zeitfenster - Obsidian buendelt Filesystem-Events asynchron und in wechselnder
 * Reihenfolge, ein Zeitfenster kann daher am falschen Event landen.
 *
 * Eine registrierte Operation kann mehrere Fingerprints umfassen (z. B. eine Umbenennung, die
 * das Dateisystem als getrenntes delete+create meldet) - sie gilt erst als vollstaendig
 * abgearbeitet, wenn alle erwarteten Fingerprints korreliert wurden.
 */
export class OperationJournal {
  private readonly pending = new Map<string, PendingOperation>();
  private readonly now: () => number;

  constructor(now: () => number = () => Date.now()) {
    this.now = now;
  }

  registerSelfInitiated(operationId: string, fingerprints: string[]): void {
    this.pending.set(operationId, {
      operationId,
      fingerprints: new Set(fingerprints),
      registeredAt: this.now(),
    });
  }

  /**
   * Prueft ein eingehendes Vault-Event gegen offene selbstinitiierte Operationen. Bei Treffer
   * wird der Fingerprint konsumiert (einmalig) und die zugehoerige operationId zurueckgegeben -
   * der Aufrufer weiss dann, dass dieses Event NICHT an den Server propagiert werden darf.
   * `null` bedeutet: eine echte, extern ausgeloeste Aenderung.
   */
  correlate(fingerprint: string): string | null {
    for (const operation of this.pending.values()) {
      if (operation.fingerprints.delete(fingerprint)) {
        if (operation.fingerprints.size === 0) {
          this.pending.delete(operation.operationId);
        }
        return operation.operationId;
      }
    }
    return null;
  }

  /** Reine Hygiene-Massnahme gegen unbegrenztes Wachstum - niemals Grundlage der Korrelation selbst. */
  evictOlderThan(maxAgeMs: number): void {
    const cutoff = this.now() - maxAgeMs;
    for (const [operationId, operation] of this.pending) {
      if (operation.registeredAt < cutoff) {
        this.pending.delete(operationId);
      }
    }
  }
}
