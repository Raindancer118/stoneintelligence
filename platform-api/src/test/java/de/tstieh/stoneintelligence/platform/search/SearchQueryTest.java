package de.tstieh.stoneintelligence.platform.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchQueryTest {

    @Test
    void should_matchEveryWord_asPrefix() {
        assertThat(SearchQuery.tsquery("Grenz werte")).contains("grenz:* & werte:*");
    }

    @Test
    void should_keepUmlautsAndDigits() {
        assertThat(SearchQuery.tsquery("Größe 2026")).contains("größe:* & 2026:*");
    }

    // Operatoren der tsquery-Syntax duerfen nie durchrutschen - sonst Syntaxfehler (500) oder fremde Logik.
    @Test
    void should_dropQuerySyntax_fromUserInput() {
        assertThat(SearchQuery.tsquery("a&b | !c:* (d) <-> 'e'")).contains("a:* & b:* & c:* & d:* & e:*");
    }

    @Test
    void should_giveNothing_whenNoWordIsLeft() {
        assertThat(SearchQuery.tsquery("&& !! ::")).isEmpty();
        assertThat(SearchQuery.tsquery("   ")).isEmpty();
    }

    @Test
    void should_limitTheNumberOfWords() {
        var many = "w ".repeat(50);
        assertThat(SearchQuery.tsquery(many).orElseThrow().split("&")).hasSize(SearchQuery.MAX_WORDS);
    }

    // Fragen sind Saetze: jedes inhaltstragende Wort darf genuegen, Fuellwoerter zaehlen nicht.
    @Test
    void should_matchAnyContentWord_ofAQuestion_ignoringStopWordsAndShortWords() {
        assertThat(SearchQuery.anyOf("Wo läuft die Lichtreaktion der Photosynthese ab?"))
            .contains("läuft:* | lichtreaktion:* | photosynthese:*");
        assertThat(SearchQuery.anyOf("What is the Calvin cycle?")).contains("calvin:* | cycle:*");
    }

    @Test
    void should_giveNothingForAQuestion_withoutContentWords_andLimitItsWords() {
        assertThat(SearchQuery.anyOf("Was ist das? Wo?")).isEmpty();
        var many = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "wort" + i).collect(java.util.stream.Collectors.joining(" "));
        assertThat(SearchQuery.anyOf(many).orElseThrow().split("\\|")).hasSize(SearchQuery.MAX_QUESTION_WORDS);
    }

    @Test
    void should_dropQuerySyntax_fromAQuestion() {
        assertThat(SearchQuery.anyOf("a&b | !café:* (dach) <-> 'eule'")).contains("café:* | dach:* | eule:*");
    }
}
