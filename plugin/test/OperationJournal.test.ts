import { describe, expect, it } from "vitest";
import { OperationJournal } from "../src/sync/OperationJournal";

/**
 * Fehlerklasse 1 (Plan.md Abschnitt 3): server-getriebene Aenderungen (z. B. app.vault.modify()
 * durch das Plugin selbst) duerfen nicht denselben Vault-Event-Handler ausloesen wie eine echte
 * Nutzeraenderung - sonst propagiert die eigene Aenderung faelschlich zurueck zum Server. Ein
 * Zeitfenster reicht nicht (asynchrone, umsortierte Obsidian-Events); die Korrelation laeuft
 * stattdessen rein ueber exakte Fingerprints, nie ueber Zeit.
 */
describe("OperationJournal", () => {
  describe("correlate", () => {
    it("should_recognizeSelfInitiatedEvent_when_fingerprintMatchesRegisteredOperation", () => {
      const journal = new OperationJournal();
      journal.registerSelfInitiated("op-1", ["modify:note.md:abc123"]);

      const matched = journal.correlate("modify:note.md:abc123");

      expect(matched).toBe("op-1");
    });

    it("should_returnNull_when_fingerprintWasNeverRegistered", () => {
      const journal = new OperationJournal();

      expect(journal.correlate("modify:note.md:abc123")).toBeNull();
    });

    it("should_returnNullForGenuineUserEdit_when_contentHashDiffersFromRegisteredOperation", () => {
      const journal = new OperationJournal();
      journal.registerSelfInitiated("op-1", ["modify:note.md:abc123"]);

      // Nutzer hat die Datei zwischen Server-Schreibvorgang und Event-Ankunft selbst editiert -
      // anderer Hash, also KEIN Match, auch wenn derselbe Pfad involviert ist.
      const matched = journal.correlate("modify:note.md:different-hash");

      expect(matched).toBeNull();
    });

    it("should_consumeFingerprintOnce_when_correlatedTwice", () => {
      const journal = new OperationJournal();
      journal.registerSelfInitiated("op-1", ["modify:note.md:abc123"]);

      journal.correlate("modify:note.md:abc123");
      const second = journal.correlate("modify:note.md:abc123");

      expect(second).toBeNull();
    });

    it("should_keepOperationPending_when_atomicOperationHasMultipleFingerprintsAndOnlySomeArrived", () => {
      // Eine Umbenennung wird von Obsidian teils als zwei Rohereignisse gemeldet - beide
      // gehoeren zu EINER fachlichen Operation und werden beide als self-initiated erkannt.
      const journal = new OperationJournal();
      journal.registerSelfInitiated("op-rename", ["delete:old.md", "create:new.md"]);

      const first = journal.correlate("delete:old.md");
      const second = journal.correlate("create:new.md");

      expect(first).toBe("op-rename");
      expect(second).toBe("op-rename");
    });

    it("should_keepOtherOperationsPending_when_oneOperationIsFullyResolved", () => {
      const journal = new OperationJournal();
      journal.registerSelfInitiated("op-1", ["modify:a.md:hash-a"]);
      journal.registerSelfInitiated("op-2", ["modify:b.md:hash-b"]);

      journal.correlate("modify:a.md:hash-a");

      expect(journal.correlate("modify:b.md:hash-b")).toBe("op-2");
    });
  });

  describe("evictOlderThan", () => {
    it("should_removeStaleUnresolvedOperations_when_olderThanGivenAge", () => {
      const clock = { now: 1_000 };
      const journal = new OperationJournal(() => clock.now);
      journal.registerSelfInitiated("op-1", ["modify:note.md:abc123"]);

      clock.now = 1_000 + 60_000;
      journal.evictOlderThan(30_000);

      // Nach der Eviction ist der Fingerprint nicht mehr als self-initiated erkennbar - das ist
      // eine reine Hygiene-Massnahme gegen unbegrenztes Wachstum, KEIN Ersatz fuer die
      // fingerprint-basierte Korrelation selbst (die bleibt exakt, nie zeitbasiert).
      expect(journal.correlate("modify:note.md:abc123")).toBeNull();
    });

    it("should_keepRecentUnresolvedOperations_when_youngerThanGivenAge", () => {
      const clock = { now: 1_000 };
      const journal = new OperationJournal(() => clock.now);
      journal.registerSelfInitiated("op-1", ["modify:note.md:abc123"]);

      clock.now = 1_000 + 5_000;
      journal.evictOlderThan(30_000);

      expect(journal.correlate("modify:note.md:abc123")).toBe("op-1");
    });
  });
});
