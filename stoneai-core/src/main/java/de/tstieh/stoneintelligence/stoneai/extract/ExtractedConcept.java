package de.tstieh.stoneintelligence.stoneai.extract;

import de.tstieh.stoneintelligence.stoneai.chunk.Provenance;

import java.util.List;
import java.util.Map;

/**
 * One idea found in one chunk: the raw material a note is later built from. Still tied to the
 * chunk it came from — merging duplicates across a document happens afterwards.
 *
 * @param entities structured facts by category (person, datum, betrag, begriff …), the part that
 *                 becomes queryable frontmatter rather than prose
 */
public record ExtractedConcept(String title,
                               List<String> aliases,
                               String definition,
                               String body,
                               List<String> tags,
                               Map<String, List<String>> entities,
                               List<String> related,
                               double confidence,
                               Provenance provenance) {

    public ExtractedConcept {
        aliases = List.copyOf(aliases);
        tags = List.copyOf(tags);
        related = List.copyOf(related);
        entities = Map.copyOf(entities);
    }
}
