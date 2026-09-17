package de.raindancer118.stoneintelligence.platform.identity;

/** Eine ordnerbasierte Sichteinschraenkungsregel (Plan.md Abschnitt 3, aus stonesync uebernommen). */
public record PathRule(String pathPrefix, RuleScope scope, RuleEffect effect) {
}
