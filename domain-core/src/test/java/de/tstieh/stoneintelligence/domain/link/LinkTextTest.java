package de.tstieh.stoneintelligence.domain.link;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinkTextTest {

    @Nested
    class FindingMentions {

        @Test
        void should_findTheFirstWholeWordMention_ignoringCase() {
            var text = "Die Photosynthesen laufen. Photosynthese braucht Licht. Und photosynthese nochmal.";

            assertThat(LinkText.findMention(text, "Photosynthese")).hasValue(text.indexOf("Photosynthese braucht"));
            assertThat(LinkText.findMention("PHOTOSYNTHESE!", "Photosynthese")).hasValue(0);
            assertThat(LinkText.findMention("Chlorophyll", "Photosynthese")).isEmpty();
        }

        @Test
        void should_skipPlacesWhereALinkWouldBreakSomething() {
            var text = """
                ---
                title: Licht
                aliases: [Licht]
                ---
                # Licht und Schatten
                `Licht` im Code, [[Licht]] schon verlinkt, [Licht](https://x.de/Licht), #Licht als Tag,
                $Licht$ als Formel, %%Licht%% als Kommentar, <!-- Licht --> und https://licht.de/Licht.
                ```
                Licht im Codeblock
                ```
                Hier endlich Licht.
                """;

            assertThat(LinkText.findMention(text, "Licht")).hasValue(text.indexOf("Licht.", text.indexOf("endlich")));
        }

        @Test
        void should_notMatchAcrossWordBoundariesOrLines() {
            assertThat(LinkText.findMention("Lichtjahr und Rotlicht", "Licht")).isEmpty();
            assertThat(LinkText.findMention("Grüne\nPflanze", "Grüne Pflanze")).isEmpty();
            assertThat(LinkText.findMention("Die Grüne Pflanze wächst", "grüne pflanze")).hasValue(4);
        }
    }

    @Nested
    class Scanning {

        @Test
        void should_tellForAnyRangeWhetherALinkMayGoThere_computingTheMaskOnce() {
            var text = "Code `Licht` und Licht.";
            var scanner = LinkText.scanner(text);

            assertThat(scanner.linkable(text.indexOf("Licht"), text.indexOf("Licht") + 5)).isFalse();
            assertThat(scanner.linkable(text.lastIndexOf("Licht"), text.lastIndexOf("Licht") + 5)).isTrue();
            assertThat(scanner.linkable(0, text.length() + 3)).isFalse();
        }
    }

    @Nested
    class Inserting {

        @Test
        void should_linkTheMentionInPlace_keepingTheWordingAsWritten() {
            var result = LinkText.insert("Wir betrachten die photosynthese genauer.", "Photosynthese", "Photosynthese", false);

            assertThat(result).get().satisfies(insertion -> {
                assertThat(insertion.text()).isEqualTo("Wir betrachten die [[Photosynthese|photosynthese]] genauer.");
                assertThat(insertion.placement()).isEqualTo(LinkText.Placement.INLINE);
                assertThat(insertion.markup()).isEqualTo("[[Photosynthese|photosynthese]]");
            });
        }

        @Test
        void should_useTheShortForm_whenTheWordEqualsTheTarget() {
            assertThat(LinkText.insert("Mehr über Licht.", "Licht", "Licht", false)).get()
                .extracting(LinkText.Insertion::text).isEqualTo("Mehr über [[Licht]].");
            assertThat(LinkText.insert("Mehr über Licht.", "Physik/Licht", "Licht", false)).get()
                .extracting(LinkText.Insertion::text).isEqualTo("Mehr über [[Physik/Licht|Licht]].");
        }

        @Test
        void should_onlyEverInsert_neverRemoveOrChangeText() {
            var before = "# Titel\n\nText mit Chlorophyll und mehr.\n";
            var after = LinkText.insert(before, "Chlorophyll", "Chlorophyll", false).orElseThrow().text();

            assertThat(after.replace("[[", "").replace("]]", "")).isEqualTo(before);
        }

        @Test
        void should_addARelatedSection_whenThereIsNoPlaceInTheText_andAllowed() {
            var result = LinkText.insert("Nur Text.\n", "Chlorophyll", "Blattgrün", true).orElseThrow();

            assertThat(result.text()).isEqualTo("Nur Text.\n\n## Verwandt\n\n- [[Chlorophyll]]\n");
            assertThat(result.placement()).isEqualTo(LinkText.Placement.RELATED);
            assertThat(result.createdSection()).isTrue();
            assertThat(LinkText.insert("Nur Text.\n", "Chlorophyll", "Blattgrün", false)).isEmpty();
        }

        @Test
        void should_appendToAnExistingRelatedSection() {
            var text = "Text.\n\n## Verwandt\n\n- [[Licht]]\n\n## Quellen\n\n- Buch\n";

            var result = LinkText.insert(text, "Chlorophyll", "Blattgrün", true).orElseThrow();

            assertThat(result.text()).isEqualTo("Text.\n\n## Verwandt\n\n- [[Licht]]\n- [[Chlorophyll]]\n\n## Quellen\n\n- Buch\n");
            assertThat(result.createdSection()).isFalse();
        }

        @Test
        void should_fillAnEmptyRelatedSection_andIgnoreOneInsideCode() {
            assertThat(LinkText.insert("Text.\n\n## Verwandt\n", "Licht", "Photon", true).orElseThrow().text())
                .isEqualTo("Text.\n\n## Verwandt\n- [[Licht]]\n");
            var inCode = "```\n## Verwandt\n```\n";
            assertThat(LinkText.insert(inCode, "Licht", "Photon", true).orElseThrow().text())
                .isEqualTo(inCode + "\n## Verwandt\n\n- [[Licht]]\n");
        }

        @Test
        void should_doNothing_whenTheTargetIsAlreadyLinked() {
            assertThat(LinkText.insert("Siehe [[licht|das Licht]]. Licht ist hell.", "Licht", "Licht", true)).isEmpty();
            assertThat(LinkText.insert("Siehe [[Physik/Licht#Farben]]. Licht.", "Physik/Licht", "Licht", true)).isEmpty();
        }
    }

    @Nested
    class Removing {

        @Test
        void should_takeOutExactlyTheInsertedMarkup_evenAfterOthersKeptWriting() {
            var inline = LinkText.insert("Die photosynthese braucht Licht.", "Photosynthese", "Photosynthese", false).orElseThrow();
            var related = LinkText.insert(inline.text(), "Chlorophyll", "Blattgrün", true).orElseThrow();
            var edited = "Neuer Absatz davor.\n\n" + related.text();

            var reverted = LinkText.remove(edited, List.of(inline, related));

            assertThat(reverted).isEqualTo("Neuer Absatz davor.\n\nDie photosynthese braucht Licht.");
        }

        @Test
        void should_keepTheRelatedHeading_whenSomeoneWroteUnderIt() {
            var related = LinkText.insert("Text.\n", "Chlorophyll", "Blattgrün", true).orElseThrow();
            var edited = related.text() + "- [[Licht]] von Hand\n";

            assertThat(LinkText.remove(edited, List.of(related))).isEqualTo("Text.\n\n## Verwandt\n\n- [[Licht]] von Hand\n");
        }

        @Test
        void should_leaveALinkAlone_thatSomeoneChangedMeanwhile() {
            var inline = LinkText.insert("Die photosynthese.", "Photosynthese", "Photosynthese", false).orElseThrow();

            assertThat(LinkText.remove("Die [[Photosynthese|Fotosynthese]].", List.of(inline))).isEqualTo("Die [[Photosynthese|Fotosynthese]].");
        }
    }
}
