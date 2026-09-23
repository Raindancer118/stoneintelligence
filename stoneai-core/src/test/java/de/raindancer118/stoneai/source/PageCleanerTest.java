package de.raindancer118.stoneai.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lecture slides repeat the same header and footer on every page, number every page and show a
 * slide once per animation step. None of that is content - it costs tokens and buries the rest.
 */
class PageCleanerTest {

    private static final String FOOTER = "Dr. Brzezinski/Dr. Ullmann (NAK)\nRelationen und Algebraische Strukturen\n3. Quartal 2024";

    private static SourceDocument deck(String... pages) {
        List<Page> list = new ArrayList<>();
        for (int i = 0; i < pages.length; i++) {
            list.add(new Page(i + 1, pages[i], false));
        }
        return new SourceDocument(Path.of("/tmp/Folien.pdf"), "Folien", DocumentKind.PDF, list, List.of(), false, "hash");
    }

    private static List<String> texts(SourceDocument document) {
        return document.pages().stream().map(Page::text).toList();
    }

    @Test
    @DisplayName("should remove the header and footer every slide repeats, and the page numbers")
    void should_removeRepeatedLinesAndPageNumbers() {
        SourceDocument cleaned = PageCleaner.clean(deck(
                "Relationen\nDefinition Relation\n" + FOOTER + "\n1 / 244",
                "Relationen\nReflexivität\n" + FOOTER + "\n2 / 244",
                "Ordnungen\nHalbordnung\n" + FOOTER + "\n3 / 244",
                "Ordnungen\nTotalordnung\n" + FOOTER + "\n4 / 244"));

        assertThat(texts(cleaned)).containsExactly("Relationen\nDefinition Relation", "Relationen\nReflexivität",
                "Ordnungen\nHalbordnung", "Ordnungen\nTotalordnung");
    }

    // PDF text extraction glues the page number onto the footer: "95Dipl. Betriebswirt Fred Ludolph",
    // "... 3. Quartal 2024 36 / 244" - a different line on every page, the same footer nonetheless.
    @Test
    @DisplayName("should recognise a footer that carries the page number")
    void should_removeFootersWithPageNumbers() {
        SourceDocument cleaned = PageCleaner.clean(deck(
                "Kapitel A\nInhalt A\n95Dipl. Betriebswirt Fred Ludolph",
                "Kapitel B\nInhalt B\n96Dipl. Betriebswirt Fred Ludolph",
                "Kapitel C\nInhalt C\nDr. X (NAK) Relationen 3. Quartal 2024 36 / 244\n97Dipl. Betriebswirt Fred Ludolph",
                "Kapitel D\nInhalt D\n98Dipl. Betriebswirt Fred Ludolph"));

        assertThat(texts(cleaned).getFirst()).isEqualTo("Kapitel A\nInhalt A");
        assertThat(texts(cleaned).get(2)).contains("Dr. X (NAK)");
    }

    // Beamer shows a slide once per revealed bullet: only the complete version is worth reading.
    @Test
    @DisplayName("should keep only the last step of a slide that builds up bullet by bullet")
    void should_collapseAnimationSteps() {
        SourceDocument cleaned = PageCleaner.clean(deck(
                "Äquivalenz\n- reflexiv",
                "Äquivalenz\n- reflexiv\n- symmetrisch",
                "Äquivalenz\n- reflexiv\n- symmetrisch\n- transitiv",
                "Ordnung\n- antisymmetrisch"));

        assertThat(cleaned.pages()).extracting(Page::number).containsExactly(3, 4);
    }

    @Test
    @DisplayName("should keep one of two identical consecutive slides")
    void should_collapseRepeatedSlides() {
        SourceDocument cleaned = PageCleaner.clean(deck("Überblick\n1 Relationen\n2 Ordnungen", "Überblick\n1 Relationen\n2 Ordnungen", "Relationen\nText"));

        assertThat(cleaned.pages()).extracting(Page::number).containsExactly(2, 3);
    }

    @Test
    @DisplayName("should drop a page that held nothing but boilerplate")
    void should_dropPagesLeftEmpty() {
        SourceDocument cleaned = PageCleaner.clean(deck(
                "Kapitel 1\nText eins\nVorlesung Controlling\n7",
                "Vorlesung Controlling\n8",
                "Kapitel 2\nText zwei\nVorlesung Controlling\n9",
                "Kapitel 3\nText drei\nVorlesung Controlling\n10"));

        assertThat(cleaned.pages()).extracting(Page::number).containsExactly(1, 3, 4);
    }

    // A letter with its address block on both pages loses nothing - too few pages to tell
    // boilerplate from content.
    @Test
    @DisplayName("should leave short documents alone")
    void should_notTouchShortDocuments() {
        SourceDocument letter = deck("Tom Stieh\nBrief Seite eins", "Tom Stieh\nBrief Seite zwei");

        assertThat(texts(PageCleaner.clean(letter))).containsExactly("Tom Stieh\nBrief Seite eins", "Tom Stieh\nBrief Seite zwei");
    }

    @Test
    @DisplayName("should keep a line that repeats on only some slides")
    void should_keepLinesThatAreNotBoilerplate() {
        SourceDocument cleaned = PageCleaner.clean(deck(
                "Beispiel\nEins", "Beispiel\nZwei", "Satz\nDrei", "Satz\nVier", "Satz\nFünf", "Definition\nSechs"));

        assertThat(texts(cleaned).getFirst()).isEqualTo("Beispiel\nEins");
    }

    @Test
    @DisplayName("should list each slide's title for an outline of the whole deck")
    void should_describeTheOutline() {
        SourceDocument cleaned = PageCleaner.clean(deck(
                "Relationen\nDefinition\nText", "Relationen\nDefinition\nMehr Text",
                "Relationen\nReflexivität\nText", "Ordnungen\nHalbordnung\nText"));

        assertThat(PageCleaner.outline(cleaned)).containsExactly(
                "S. 1: Relationen › Definition", "S. 3: Relationen › Reflexivität", "S. 4: Ordnungen › Halbordnung");
    }
}
