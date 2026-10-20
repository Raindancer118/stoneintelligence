package de.tstieh.stoneintelligence.platform.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlockedSubjectsTest {

    private final AtomicReference<Set<String>> directory = new AtomicReference<>(Set.of("left-2025"));
    private final List<Object> events = new ArrayList<>();
    private final BlockedSubjects blocked = new BlockedSubjects(directory::get, events::add);

    @Test
    void should_loadOnFirstUse_withoutAnnouncingPeopleBlockedLongAgo() {
        assertThat(blocked.isBlocked("left-2025")).isTrue();
        assertThat(blocked.isBlocked("ben")).isFalse();
        assertThat(events).isEmpty();
    }

    @Test
    void should_announceNewlyBlocked_once_andUnblock() {
        blocked.isBlocked("ben");
        directory.set(Set.of("left-2025", "ben"));

        blocked.changed();
        blocked.refresh();

        assertThat(blocked.isBlocked("ben")).isTrue();
        assertThat(events).containsExactly(new SubjectBlocked("ben"));

        directory.set(Set.of());
        blocked.refresh();
        assertThat(blocked.isBlocked("ben")).isFalse();
    }
}
