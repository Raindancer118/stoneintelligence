package de.tstieh.stoneintelligence.platform.vault;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import de.tstieh.stoneintelligence.platform.sync.relay.UpdateInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoteVersionsTest {

    private static final Instant T0 = Instant.parse("2026-09-29T08:00:00Z");

    private final List<UpdateInfo> log = new ArrayList<>();

    private void edit(String actor, int minute) {
        log.add(new UpdateInfo(log.size() + 1L, actor, T0.plusSeconds(minute * 60L), false));
    }

    @Test
    void should_joinOneSittingOfOnePersonIntoOneVersion() {
        edit("tom", 0);
        edit("tom", 1);
        edit("tom", 7);

        assertThat(NoteVersions.group(log)).singleElement().satisfies(version -> {
            assertThat(version.revision()).isEqualTo(3);
            assertThat(version.firstRevision()).isEqualTo(1);
            assertThat(version.actor()).isEqualTo("tom");
            assertThat(version.startedAt()).isEqualTo(T0);
            assertThat(version.endedAt()).isEqualTo(T0.plusSeconds(7 * 60));
            assertThat(version.updates()).isEqualTo(3);
        });
    }

    @Test
    void should_startANewVersion_whenSomeoneElseWrites_orAfterAPause_newestFirst() {
        edit("tom", 0);
        edit("anna", 1);
        edit("anna", 2);
        edit("anna", 20);

        assertThat(NoteVersions.group(log)).extracting(NoteVersion::revision, NoteVersion::actor)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(4L, "anna"),
                org.assertj.core.groups.Tuple.tuple(3L, "anna"),
                org.assertj.core.groups.Tuple.tuple(1L, "tom"));
    }

    // Wer zwei Stunden am Stueck schreibt, soll trotzdem zu einem Stand von vor einer Stunde zurueck koennen.
    @Test
    void should_cutLongSittingsIntoHourlyVersions() {
        for (var minute = 0; minute <= 120; minute += 5) {
            edit("tom", minute);
        }

        assertThat(NoteVersions.group(log)).extracting(NoteVersion::startedAt)
            .containsExactly(T0.plusSeconds(120 * 60), T0.plusSeconds(60 * 60), T0);
    }

    @Test
    void should_treatUnknownAuthorsAsOnePerson() {
        edit(null, 0);
        edit(null, 1);

        assertThat(NoteVersions.group(log)).singleElement().extracting(NoteVersion::actor).isNull();
        assertThat(NoteVersions.group(List.of())).isEmpty();
    }
}
