package de.tstieh.stoneintelligence.worker.link;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MentionFinderTest {

    private final MentionFinder finder = new MentionFinder(Map.of(
        "licht", List.of("Licht", "Photonen"),
        "pflanze", List.of("Grüne Pflanze"),
        "ki", List.of("KI"),
        "self", List.of("Pflanzen")));

    @Test
    void should_findTheFirstLinkableMentionOfEachOtherNote_inTextOrder() {
        var mentions = finder.find("self", "`Licht` im Code. Eine grüne  pflanze braucht Photonen und Licht. Pflanzen!");

        assertThat(mentions).extracting(MentionFinder.Mention::targetNoteId, MentionFinder.Mention::anchor)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("pflanze", "grüne  pflanze"),
                org.assertj.core.groups.Tuple.tuple("licht", "Photonen"));
    }

    @Test
    void should_ignoreNamesTooShortToBeMeaningful_andMatchWholeWordsOnly() {
        assertThat(finder.find("x", "Die KI sieht Lichter und Rotlicht.")).isEmpty();
    }

    @Test
    void should_skipNamesThatShrinkToSomethingTooShort_orHaveNoLetters() {
        var odd = new MentionFinder(Map.of("cpp", List.of("C++"), "jahr", List.of("2024")));

        assertThat(odd.find("x", "C ist nicht C++, und 2024 ist ein Jahr.")).isEmpty();
    }

    @Test
    void should_notMatchAcrossLines() {
        assertThat(finder.find("x", "grüne\npflanze")).isEmpty();
    }
}
