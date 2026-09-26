package de.tstieh.stoneintelligence.platform.vault;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoteActivityTrackerTest {

    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final VaultId vaultId = VaultId.newId();
    private Instant now = Instant.parse("2026-09-26T10:00:00Z");
    private final NoteActivityTracker tracker = new NoteActivityTracker(notes, new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    });

    @Test
    void should_writeAtMostOncePerMinute_perNoteAndPerson() {
        var note = notes.create(vaultId, "plan.md", NoteLevel.of(1), "tom");

        tracker.edited(vaultId, note.id(), "anna");
        var first = now;
        now = now.plusSeconds(30);
        tracker.edited(vaultId, note.id(), "anna");
        tracker.opened(vaultId, note.id(), "ben");

        assertThat(notes.activity(vaultId, note.id())).get().satisfies(activity -> {
            assertThat(activity.lastEditedAt()).isEqualTo(first);
            assertThat(activity.lastOpenedBy()).isEqualTo("ben");
        });

        now = now.plus(Duration.ofMinutes(1));
        tracker.edited(vaultId, note.id(), "anna");
        assertThat(notes.activity(vaultId, note.id())).get().extracting(NoteActivity::lastEditedAt).isEqualTo(now);
    }
}
