package de.raindancer118.stoneai.vault;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedBlockTest {

    private static final String HANDWRITTEN = """
            # Meine Notiz

            Das hier habe ich selbst geschrieben und es darf sich nicht ändern.

            - Punkt eins
            - Punkt zwei
            """;

    @Nested
    @DisplayName("Adding a block")
    class Adding {

        @Test
        @DisplayName("should append the block and leave every other byte untouched")
        void should_leaveExistingContentIntact_when_appending() {
            String result = ManagedBlock.apply(HANDWRITTEN, "abc123", "Von StoneAI ergänzt.");

            assertThat(result).startsWith(HANDWRITTEN);
            assertThat(result).contains("Von StoneAI ergänzt.");
            assertThat(ManagedBlock.strip(result)).isEqualTo(HANDWRITTEN);
        }

        @Test
        @DisplayName("should mark the block so a human can see where it starts and ends")
        void should_surroundWithMarkers_when_appending() {
            String result = ManagedBlock.apply("", "abc123", "Inhalt");

            assertThat(result)
                    .contains("<!-- stoneai:begin id=abc123 -->")
                    .contains("<!-- stoneai:end id=abc123 -->");
        }

        @Test
        @DisplayName("should keep several blocks with different ids side by side")
        void should_keepBothBlocks_when_idsDiffer() {
            String once = ManagedBlock.apply(HANDWRITTEN, "aaa", "Erster Block");
            String twice = ManagedBlock.apply(once, "bbb", "Zweiter Block");

            assertThat(twice).contains("Erster Block").contains("Zweiter Block");
            assertThat(ManagedBlock.ids(twice)).containsExactly("aaa", "bbb");
        }
    }

    @Nested
    @DisplayName("Updating a block")
    class Updating {

        @Test
        @DisplayName("should replace only the block with the same id")
        void should_replaceInPlace_when_idAlreadyExists() {
            String once = ManagedBlock.apply(HANDWRITTEN, "aaa", "Alter Inhalt");
            String twice = ManagedBlock.apply(once, "bbb", "Anderer Block");

            String updated = ManagedBlock.apply(twice, "aaa", "Neuer Inhalt");

            assertThat(updated).doesNotContain("Alter Inhalt");
            assertThat(updated).contains("Neuer Inhalt").contains("Anderer Block");
            assertThat(ManagedBlock.strip(updated)).isEqualTo(HANDWRITTEN);
        }

        @Test
        @DisplayName("should be idempotent when nothing changed")
        void should_produceIdenticalText_when_appliedTwiceWithSameContent() {
            String once = ManagedBlock.apply(HANDWRITTEN, "aaa", "Inhalt");
            String twice = ManagedBlock.apply(once, "aaa", "Inhalt");

            assertThat(twice).isEqualTo(once);
        }

        @Test
        @DisplayName("should keep handwritten text that sits between two blocks")
        void should_preserveTextBetweenBlocks_when_updating() {
            String withBlocks = ManagedBlock.apply(HANDWRITTEN, "aaa", "Erster")
                    + "\nHandschriftlich dazwischen.\n"
                    + "";
            String full = ManagedBlock.apply(withBlocks, "bbb", "Zweiter");

            String updated = ManagedBlock.apply(full, "aaa", "Erster, überarbeitet");

            assertThat(updated).contains("Handschriftlich dazwischen.");
            assertThat(updated).contains("Zweiter");
        }
    }

    @Nested
    @DisplayName("Reading blocks")
    class Reading {

        @Test
        @DisplayName("should report that a note carries no managed block")
        void should_returnEmpty_when_noBlockIsPresent() {
            assertThat(ManagedBlock.ids(HANDWRITTEN)).isEmpty();
            assertThat(ManagedBlock.contains(HANDWRITTEN, "aaa")).isFalse();
        }

        @Test
        @DisplayName("should not be confused by a marker-looking line inside a code block")
        void should_ignoreMarkers_when_theyAreInsideCode() {
            String note = """
                    # Notiz

                    ```markdown
                    <!-- stoneai:begin id=fake -->
                    nicht echt
                    <!-- stoneai:end id=fake -->
                    ```
                    """;

            assertThat(ManagedBlock.ids(note)).isEmpty();
        }
    }
}
