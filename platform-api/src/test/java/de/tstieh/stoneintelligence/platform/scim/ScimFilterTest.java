package de.tstieh.stoneintelligence.platform.scim;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimFilterTest {

    private static final Map<String, Object> BEN = Map.of(
        "id", "2819c223", "userName", "Ben.Becker@example.com", "externalId", "oid-1",
        "displayName", "Ben Becker", "active", true,
        "emails", List.of(Map.of("type", "work", "value", "ben@example.com")));

    private static boolean matches(String filter) {
        return ScimFilter.parse(filter).matches(BEN::get);
    }

    // Die Filter, die Entra ID, Okta und Authentik beim Abgleich wirklich schicken.
    @Test
    void should_matchTheFiltersIdentityProvidersSend() {
        assertThat(matches("userName eq \"ben.becker@example.com\"")).as("userName ist nicht caseExact").isTrue();
        assertThat(matches("externalId eq \"oid-1\"")).isTrue();
        assertThat(matches("externalId eq \"OID-1\"")).as("externalId ist caseExact").isFalse();
        assertThat(matches("displayName eq \"Anna\"")).isFalse();
    }

    @Test
    void should_combineWithAndOrNotAndParentheses_respectingPrecedence() {
        assertThat(matches("userName sw \"ben\" and active eq true")).isTrue();
        assertThat(matches("displayName eq \"x\" or userName co \"becker\" and active eq true")).isTrue();
        assertThat(matches("(displayName eq \"x\" or userName co \"becker\") and active eq false")).isFalse();
        assertThat(matches("not (active eq false)")).isTrue();
        assertThat(matches("userName ew \"@EXAMPLE.com\" AND displayName pr")).isTrue();
        assertThat(matches("title pr")).isFalse();
    }

    @Test
    void should_filterMultiValuedAttributes_withValuePaths_andSchemaPrefixes() {
        assertThat(matches("emails[type eq \"work\" and value co \"@example.com\"]")).isTrue();
        assertThat(matches("emails[type eq \"home\"]")).isFalse();
        assertThat(matches("urn:ietf:params:scim:schemas:core:2.0:User:userName eq \"Ben.Becker@example.com\"")).isTrue();
    }

    @Test
    void should_handleEscapedQuotes_andNullComparisons() {
        var filter = ScimFilter.parse("displayName eq \"Ben \\\"The Rock\\\" Becker\"");
        assertThat(filter.matches(Map.<String, Object>of("displayName", "Ben \"The Rock\" Becker")::get)).isTrue();
        assertThat(matches("title eq null")).isTrue();
    }

    @Test
    void should_exposeASimpleEquality_forIndexedLookups() {
        assertThat(ScimFilter.parse("userName eq \"ben\"").expression())
            .isEqualTo(new ScimFilter.Compare("userName", ScimFilter.Op.EQ, "ben"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "userName", "userName eq", "userName xx \"a\"", "userName eq \"a", "(userName eq \"a\"",
        "userName eq \"a\" and", "emails[type eq \"a\"", "userName eq \"a\" garbage"})
    void should_rejectMalformedFilters_asInvalidFilter(String filter) {
        assertThatThrownBy(() -> ScimFilter.parse(filter))
            .isInstanceOfSatisfying(ScimException.class, error -> assertThat(error.scimType()).isEqualTo("invalidFilter"));
    }
}
