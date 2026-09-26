package de.tstieh.stoneintelligence.stoneai.extract;

import java.util.List;

/**
 * The topics a document gets notes about, decided once for the whole document before any note is
 * written. The first topic is the document's main subject: whatever a chunk says that belongs to
 * no other topic ends up there instead of in a note of its own.
 */
/**
 * @param complete whether the planner read the whole document - then nothing outside the plan may
 *                 become a note; otherwise (a long document read in excerpts) a chunk may add one
 */
public record TopicPlan(List<Topic> topics, boolean complete) {

    /**
     * @param kind  what sort of thing it is (ereignis, person, figur, begriff …) - guides the model
     * @param scope one sentence on what belongs into the note
     */
    public record Topic(String title, String kind, String scope, List<String> aliases) {

        public Topic {
            aliases = List.copyOf(aliases);
        }
    }

    public TopicPlan {
        topics = List.copyOf(topics);
    }

    /** No plan: every chunk decides its own notes (the behaviour before planning existed). */
    public static TopicPlan none() {
        return new TopicPlan(List.of(), false);
    }

    /** The plan when the planner gave nothing usable: one note about the document itself. */
    public static TopicPlan single(String title) {
        return new TopicPlan(List.of(new Topic(title, "dokument", "Inhalt des Dokuments", List.of())), true);
    }

    /** The kinds a topic can have - models like to append them to titles, which is then undone. */
    public static final List<String> KINDS = List.of("ereignis", "vorgang", "person", "organisation", "ort", "figur",
            "begriff", "thema", "dokument");

    private static final java.util.regex.Pattern KIND_SUFFIX = java.util.regex.Pattern.compile(
            "\\s*\\((?i:" + String.join("|", KINDS) + ")\\)\\s*$");

    /** {@code "Tom Stieh (person)"} → {@code "Tom Stieh"}; other parentheses stay. */
    public static String withoutKind(String title) {
        String stripped = title;
        String previous;
        do {
            previous = stripped;
            stripped = KIND_SUFFIX.matcher(stripped).replaceFirst("");
        } while (!stripped.equals(previous));
        return stripped.isBlank() ? title : stripped;
    }

    public boolean isEmpty() {
        return topics.isEmpty();
    }

    public Topic main() {
        return topics.get(0);
    }
}
