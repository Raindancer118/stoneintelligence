package de.raindancer118.stoneai.note;

import de.raindancer118.stoneai.chunk.Provenance;

import java.util.List;
import java.util.Map;

/**
 * A note ready to be written: one concept, its final text, and every place in the source it was
 * read from. Still independent of the vault — resolving it against existing notes happens next.
 */
public record DraftNote(String title,
                        List<String> aliases,
                        String definition,
                        String body,
                        List<String> tags,
                        Map<String, List<String>> entities,
                        List<String> related,
                        double confidence,
                        List<Provenance> sources) {

    public DraftNote {
        aliases = List.copyOf(aliases);
        tags = List.copyOf(tags);
        related = List.copyOf(related);
        entities = Map.copyOf(entities);
        sources = List.copyOf(sources);
    }

    /** The comparison key used for deduplication against the vault. */
    public String key() {
        return TextSimilarity.normalise(title);
    }
}
