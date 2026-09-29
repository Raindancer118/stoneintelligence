package de.tstieh.stoneintelligence.platform.vault.feed;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedCursorTest {

    @Test
    void should_roundTripACompleteCursor_andAContinuation() {
        var complete = FeedCursor.parse("866");
        assertThat(complete.since()).isEqualTo(866);
        assertThat(complete.continuation()).isNull();
        assertThat(complete.format()).isEqualTo("866");

        var id = UUID.randomUUID();
        var page = new FeedCursor(866, new FeedCursor.Continuation(900, 870, id));
        assertThat(FeedCursor.parse(page.format())).isEqualTo(page);
    }

    @Test
    void should_refuseAnythingElse() {
        for (var bad : new String[] {"", "abc", "-1", "1:2", "1:2:3:no-uuid", "1;DROP TABLE", "99999999999999999999999"}) {
            assertThatThrownBy(() -> FeedCursor.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
