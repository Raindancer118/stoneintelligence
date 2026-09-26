package de.tstieh.stoneintelligence.platform.identity;

/** Eine themen-/tag-basierte Sichteinschraenkungsregel (Plan.md Abschnitt 4.4a, eigenes Regelwerk). */
public record TopicRule(String topic, RuleScope scope, RuleEffect effect) {
}
