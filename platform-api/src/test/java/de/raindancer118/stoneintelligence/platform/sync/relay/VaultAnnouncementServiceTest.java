package de.raindancer118.stoneintelligence.platform.sync.relay;

import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vault-weite Bestandsankuendigungen: seit nur noch GEOEFFNETE Notizen einem Notiz-Raum
 * beitreten (s. Plugin-Architekturentscheidung 2026-09-18), erreicht eine notenskopierte
 * Nachricht niemanden mehr, der die Notiz gerade nicht offen hat. Anlage, Loeschung und
 * Umbenennung muessen aber JEDES verbundene Geraet des Vaults sofort erreichen.
 */
class VaultAnnouncementServiceTest {

    private final VaultAnnouncementService announcements = new VaultAnnouncementService();
    private final VaultId vaultId = VaultId.newId();
    private final NoteId noteId = NoteId.newId();

    @Nested
    class Broadcast {

        @Test
        void should_reachEverySubscriberOfTheVault_when_aNoteIsCreated() {
            var first = new RecordingVaultSubscriber("first");
            var second = new RecordingVaultSubscriber("second");
            announcements.subscribe(vaultId, first);
            announcements.subscribe(vaultId, second);

            announcements.announceNoteCreated(vaultId, noteId, "Ordner/Notiz.md");

            var expected = new RecordingVaultSubscriber.Received(
                    SyncFrame.TYPE_VAULT_NOTE_CREATED, noteId, "Ordner/Notiz.md");
            assertThat(first.received).containsExactly(expected);
            assertThat(second.received).containsExactly(expected);
        }

        @Test
        void should_notLeakToOtherVaults_when_announcing() {
            var mine = new RecordingVaultSubscriber("mine");
            var stranger = new RecordingVaultSubscriber("stranger");
            announcements.subscribe(vaultId, mine);
            announcements.subscribe(VaultId.newId(), stranger);

            announcements.announceNoteDeleted(vaultId, noteId, "Geheim.md");

            assertThat(mine.received).hasSize(1);
            assertThat(stranger.received).isEmpty();
        }

        @Test
        void should_useDistinctMessageTypes_forCreateDeleteAndRename() {
            var subscriber = new RecordingVaultSubscriber("s");
            announcements.subscribe(vaultId, subscriber);

            announcements.announceNoteCreated(vaultId, noteId, "a.md");
            announcements.announceNoteRenamed(vaultId, noteId, "b.md");
            announcements.announceNoteDeleted(vaultId, noteId, "b.md");

            assertThat(subscriber.received).extracting(RecordingVaultSubscriber.Received::messageType)
                    .containsExactly(
                            SyncFrame.TYPE_VAULT_NOTE_CREATED,
                            SyncFrame.TYPE_VAULT_NOTE_RENAMED,
                            SyncFrame.TYPE_VAULT_NOTE_DELETED);
        }

        @Test
        void should_stopDelivering_after_unsubscribe() {
            var subscriber = new RecordingVaultSubscriber("s");
            announcements.subscribe(vaultId, subscriber);
            announcements.unsubscribe(vaultId, subscriber);

            announcements.announceNoteCreated(vaultId, noteId, "a.md");

            assertThat(subscriber.received).isEmpty();
        }
    }

    @Nested
    class Authorization {

        @Test
        void should_notRevealANotePathToSomeoneWithoutReadPermission() {
            // Sonst waere die Ankuendigung selbst ein Informationsleck: wer den Ordner gar nicht
            // sehen darf, erfuehre ueber sie Existenz und vollen Pfad fremder Notizen.
            var allowed = RecordingVaultSubscriber.readingOnly("allowed", "Oeffentlich/Notiz.md");
            var denied = RecordingVaultSubscriber.readingOnly("denied", "Anderer/Pfad.md");
            announcements.subscribe(vaultId, allowed);
            announcements.subscribe(vaultId, denied);

            announcements.announceNoteCreated(vaultId, noteId, "Oeffentlich/Notiz.md");

            assertThat(allowed.received).hasSize(1);
            assertThat(denied.received).isEmpty();
        }
    }

    @Nested
    class FailureIsolation {

        @Test
        void should_keepDeliveringToHealthySubscribers_when_oneConnectionIsDead() {
            // Gleiche Lehre wie beim Room-Broadcast: ein toter Empfaenger darf die Zustellung an
            // alle nachfolgenden, gesunden Empfaenger nicht abbrechen.
            var dead = RecordingVaultSubscriber.failing("dead");
            var healthy = new RecordingVaultSubscriber("healthy");
            announcements.subscribe(vaultId, dead);
            announcements.subscribe(vaultId, healthy);

            announcements.announceNoteCreated(vaultId, noteId, "a.md");

            assertThat(healthy.received).hasSize(1);
        }

        @Test
        void should_dropADeadSubscriber_insteadOfRetryingItForever() {
            var dead = RecordingVaultSubscriber.failing("dead");
            announcements.subscribe(vaultId, dead);

            announcements.announceNoteCreated(vaultId, noteId, "a.md");

            assertThat(announcements.subscriberCount(vaultId)).isZero();
        }
    }

    /**
     * Inhaltsaenderungen: Geraete, die die Notiz NICHT offen haben, erfahren davon sofort statt
     * erst beim naechsten periodischen Abgleich. Gedrosselt, weil jeder Tastendruck ein Update ist.
     */
    @Nested
    class ContentUpdates {

        private long now = 0;
        private final VaultAnnouncementService throttled = new VaultAnnouncementService(() -> now);

        @Test
        void should_announceUpdates_toSubscribersThatHaveNotJoinedTheNote() {
            var closed = new RecordingVaultSubscriber("closed");
            var open = new RecordingVaultSubscriber("open");
            open.joined.add(noteId);
            throttled.subscribe(vaultId, closed);
            throttled.subscribe(vaultId, open);

            throttled.announceNoteUpdated(vaultId, noteId, () -> java.util.Optional.of("a.md"));

            assertThat(closed.received).containsExactly(
                new RecordingVaultSubscriber.Received(SyncFrame.TYPE_VAULT_NOTE_UPDATED, noteId, "a.md"));
            // Wer gejoint hat, bekommt das Update selbst - eine zusaetzliche Ankuendigung wuerde
            // aeltere Plugins, die Typ 9 nicht kennen, als vermeintliches Yjs-Update verwirren.
            assertThat(open.received).isEmpty();
        }

        @Test
        void should_announceAtMostOncePerSecond_perNote() {
            var subscriber = new RecordingVaultSubscriber("s");
            throttled.subscribe(vaultId, subscriber);
            var lookups = new java.util.concurrent.atomic.AtomicInteger();
            java.util.function.Supplier<java.util.Optional<String>> path = () -> {
                lookups.incrementAndGet();
                return java.util.Optional.of("a.md");
            };

            throttled.announceNoteUpdated(vaultId, noteId, path);
            now += 400_000_000L;
            throttled.announceNoteUpdated(vaultId, noteId, path);
            now += 700_000_000L;
            throttled.announceNoteUpdated(vaultId, noteId, path);

            assertThat(subscriber.received).hasSize(2);
            assertThat(lookups).as("Pfad-Lookup (DB) nur, wenn wirklich angekuendigt wird").hasValue(2);
        }

        @Test
        void should_notAnnounceUpdates_toConnectionsThatDidNotOptIn() {
            var legacyPlugin = new RecordingVaultSubscriber("legacy");
            legacyPlugin.contentUpdates = false;
            throttled.subscribe(vaultId, legacyPlugin);

            throttled.announceNoteUpdated(vaultId, noteId, () -> java.util.Optional.of("a.md"));

            assertThat(legacyPlugin.received).isEmpty();
        }

        @Test
        void should_respectReadPermissions_forUpdates() {
            var reader = RecordingVaultSubscriber.readingOnly("reader", "sichtbar.md");
            throttled.subscribe(vaultId, reader);

            throttled.announceNoteUpdated(vaultId, noteId, () -> java.util.Optional.of("geheim.md"));

            assertThat(reader.received).isEmpty();
        }
    }
}
