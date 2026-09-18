package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression fuer den P1-Befund "Catchup-complete kann ein paralleles Live-Update ueberholen"
 * (docs/sync-comparison-review-2026-09-18.md): {@code onJoin} (Registrierung + Historie-Catchup +
 * Abschlusssignal) und {@code onUpdate} (Persistieren + Broadcast) fuer dieselbe Notiz waren nicht
 * gegeneinander synchronisiert. Deterministisch erzwungenes Interleaving statt eines zeitbasierten
 * Zufalls-Race, s. {@link InterceptingSnapshotStore}.
 */
class SyncRelayServiceJoinUpdateRaceTest {

    @Test
    void should_neverDuplicateOrMisorder_when_updateHappensWhileAJoinIsMidCatchup() throws InterruptedException {
        var noteId = NoteId.newId();
        var blockingStore = new InterceptingSnapshotStore(new FakeSnapshotStore());
        var registry = new SyncRoomRegistry();
        var relay = new SyncRelayService(blockingStore, registry);

        var lateJoiner = new RecordingSyncSession("late-joiner");
        var updater = new RecordingSyncSession("updater");
        var concurrentPayload = "concurrent-update".getBytes(StandardCharsets.UTF_8);

        var joinThread = new Thread(() -> relay.onJoin(noteId, lateJoiner), "join-thread");
        joinThread.start();
        blockingStore.awaitEnteredListSince();

        // Der Join haengt jetzt MITTEN in seinem Historie-Catchup (registry.join() ist bereits
        // gelaufen, sonst wuerde er den Broadcast unten gar nicht erst sehen koennen). Ein echtes
        // Update fuer dieselbe Notiz trifft genau in diesem Fenster ein.
        var updateThread = new Thread(
            () -> relay.onUpdate(noteId, updater, concurrentPayload, false), "update-thread");
        updateThread.start();
        // Kurzes Zeitfenster geben, damit das Update - OHNE Fix - tatsaechlich die Chance hat,
        // fertig zu laufen, waehrend der Join noch blockiert ist (mit Fix kann es das nicht: es
        // wartet auf denselben Notiz-Lock).
        Thread.sleep(200);

        blockingStore.releaseListSince();
        joinThread.join(5000);
        updateThread.join(5000);

        assertThat(joinThread.isAlive()).isFalse();
        assertThat(updateThread.isAlive()).isFalse();

        // Kernaussage: das gleichzeitige Update darf beim Late-Joiner NICHT doppelt ankommen
        // (einmal via Live-Broadcast waehrend des Joins, einmal nochmal via dessen eigener,
        // danach doch noch denselben Stand lesender Historie).
        var occurrences = lateJoiner.receivedDocUpdates.stream()
            .filter(payload -> new String(payload, StandardCharsets.UTF_8).equals("concurrent-update"))
            .count();
        assertThat(occurrences).as("concurrent update must reach the late joiner exactly once, not duplicated").isEqualTo(1);

        // Und wenn es als LIVE-Broadcast ankommt (statt Teil der Historie zu sein), muss es NACH
        // dem Catchup-Abschlusssignal ankommen, nie davor - sonst koennte der Client es faelschlich
        // schon fuer eine (unvollstaendige) Historie halten.
        var catchupIndex = lateJoiner.orderedEvents.indexOf("catchup-complete");
        var updateIndex = lateJoiner.orderedEvents.indexOf("doc-update:concurrent-update");
        if (updateIndex >= 0 && updateIndex < catchupIndex) {
            org.assertj.core.api.Assertions.fail(
                "concurrent update arrived as %s before catchup-complete at %s: %s"
                    .formatted(updateIndex, catchupIndex, lateJoiner.orderedEvents));
        }
    }
}
