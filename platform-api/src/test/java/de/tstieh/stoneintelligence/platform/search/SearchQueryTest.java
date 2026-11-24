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
}
