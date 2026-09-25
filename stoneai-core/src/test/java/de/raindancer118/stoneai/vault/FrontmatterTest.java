package de.raindancer118.stoneai.vault;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FrontmatterTest {

    @Nested
    @DisplayName("Reading")
    class Reading {

        @Test
        @DisplayName("should read scalars, inline lists and block lists")
        void should_readAllSupportedShapes_when_parsing() {
            Frontmatter frontmatter = Frontmatter.of("""
                    ---
                    title: Äquivalenzrelation
                    aliases: [Äquivalenzrelationen, ÄR]
                    tags:
                      - StoneAI
                      - mathematik/relationen
                    confidence: 0.9
                    ---

                    Körper der Notiz.
                    """).frontmatter();

            assertThat(frontmatter.scalar("title")).isEqualTo("Äquivalenzrelation");
            assertThat(frontmatter.list("aliases")).containsExactly("Äquivalenzrelationen", "ÄR");
            assertThat(frontmatter.list("tags")).containsExactly("StoneAI", "mathematik/relationen");
            assertThat(frontmatter.scalar("confidence")).isEqualTo("0.9");
        }

        @Test
        @DisplayName("should separate the body from the frontmatter")
        void should_returnBody_when_frontmatterIsPresent() {
            assertThat(Frontmatter.of("---\ntitle: X\n---\n\nInhalt\n").body()).isEqualTo("Inhalt\n");
        }

        @Test
        @DisplayName("should treat a note without frontmatter as all body")
        void should_returnWholeContent_when_frontmatterIsMissing() {
            Frontmatter.Document document = Frontmatter.of("# Nur Text\n");

            assertThat(document.frontmatter().keys()).isEmpty();
            assertThat(document.body()).isEqualTo("# Nur Text\n");
        }

        @Test
        @DisplayName("should leave an unterminated frontmatter block alone instead of eating the note")
        void should_notConsumeContent_when_frontmatterIsUnterminated() {
            String content = "---\ntitle: X\n\nkein Ende\n";

            assertThat(Frontmatter.of(content).body()).isEqualTo(content);
        }

        @Test
        @DisplayName("should read a nested mapping of lists, as used for entities")
        void should_readNestedLists_when_valueIsAMapping() {
            Frontmatter frontmatter = Frontmatter.of("""
                    ---
                    entities:
                      begriff:
                        - Reflexivität
                        - Symmetrie
                      person:
                        - Cantor
                    ---
                    """).frontmatter();

            assertThat(frontmatter.nested("entities"))
                    .containsEntry("begriff", List.of("Reflexivität", "Symmetrie"))
                    .containsEntry("person", List.of("Cantor"));
        }
    }

    @Nested
    @DisplayName("Writing")
    class Writing {

        @Test
        @DisplayName("should render a block that reads back identically")
        void should_roundTrip_when_rendered() {
            Frontmatter original = Frontmatter.empty()
                    .withScalar("title", "Gruppe: Definition")
                    .withList("tags", List.of("StoneAI", "algebra"))
                    .withNested("entities", Map.of("begriff", List.of("Assoziativität")));

            Frontmatter reparsed = Frontmatter.of(original.render() + "\nInhalt\n").frontmatter();

            assertThat(reparsed.scalar("title")).isEqualTo("Gruppe: Definition");
            assertThat(reparsed.list("tags")).containsExactly("StoneAI", "algebra");
            assertThat(reparsed.nested("entities")).containsEntry("begriff", List.of("Assoziativität"));
        }

        @Test
        @DisplayName("should drop the given keys and keep the order of the rest")
        void should_removeKeys_when_askedToDropThem() {
            Frontmatter frontmatter = Frontmatter.empty()
                    .withScalar("title", "Gruppe")
                    .withList("tags", List.of("StoneAI"))
                    .withScalar("confidence", "0.9")
                    .withScalar("created", "2026-09-01");

            assertThat(frontmatter.without(java.util.Set.of("title", "confidence", "fehlt")).keys())
                    .containsExactly("tags", "created");
        }

        @Test
        @DisplayName("should quote values that would otherwise break the YAML")
        void should_quote_when_valueContainsColonOrHash() {
            String rendered = Frontmatter.empty().withScalar("title", "A: B #1").render();

            assertThat(rendered).contains("\"A: B #1\"");
        }

        @Test
        @DisplayName("should keep keys in the order they were added")
        void should_preserveKeyOrder_when_rendering() {
            String rendered = Frontmatter.empty()
                    .withScalar("title", "T")
                    .withList("aliases", List.of("A"))
                    .withScalar("created", "2026-09-01")
                    .render();

            assertThat(rendered.indexOf("title")).isLessThan(rendered.indexOf("aliases"));
            assertThat(rendered.indexOf("aliases")).isLessThan(rendered.indexOf("created"));
        }
    }

    @Nested
    @DisplayName("Merging into an existing note")
    class Merging {

        @Test
        @DisplayName("should never replace a value the user already set")
        void should_keepExistingScalar_when_merging() {
            Frontmatter existing = Frontmatter.empty().withScalar("title", "Von Hand");
            Frontmatter incoming = Frontmatter.empty().withScalar("title", "Von der KI");

            assertThat(existing.mergeAdditively(incoming).scalar("title")).isEqualTo("Von Hand");
        }

        @Test
        @DisplayName("should add a key the note did not have yet")
        void should_addMissingKey_when_merging() {
            Frontmatter merged = Frontmatter.empty().withScalar("title", "T")
                    .mergeAdditively(Frontmatter.empty().withScalar("source", "[[Quelle]]"));

            assertThat(merged.scalar("source")).isEqualTo("[[Quelle]]");
        }

        @Test
        @DisplayName("should union list values without duplicating them")
        void should_unionLists_when_merging() {
            Frontmatter existing = Frontmatter.empty().withList("tags", List.of("uni", "StoneAI"));
            Frontmatter incoming = Frontmatter.empty().withList("tags", List.of("StoneAI", "mathe"));

            assertThat(existing.mergeAdditively(incoming).list("tags"))
                    .containsExactly("uni", "StoneAI", "mathe");
        }
    }
}
