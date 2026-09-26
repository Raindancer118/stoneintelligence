package de.tstieh.stoneintelligence.platform.ai;

import java.util.Locale;
import java.util.Optional;

/** Art einer Verknuepfung (Anforderungen.md: typisierte Beziehungen), wie die KI sie in Stufe 3 benennt. */
public enum LinkRelation {
    USES, REQUIRES, PART_OF, RELATED_TO, DESCRIBED_BY, EXAMPLE_OF, CONTRASTS_WITH;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Unbekanntes oder Fehlendes wird zu "verwandt" - eine Beziehung ist immer da, nur nicht immer genauer bestimmt. */
    public static LinkRelation of(String code) {
        return Optional.ofNullable(code).map(value -> value.strip().toUpperCase(Locale.ROOT).replace('-', '_'))
            .flatMap(value -> java.util.Arrays.stream(values()).filter(relation -> relation.name().equals(value)).findFirst())
            .orElse(RELATED_TO);
    }
}
