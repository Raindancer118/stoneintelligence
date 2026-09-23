package de.raindancer118.stoneai.extract;

import java.util.List;

/**
 * The topics a document gets notes about, decided once for the whole document before any note is
 * written. The first topic is the document's main subject: whatever a chunk says that belongs to
 * no other topic ends up there instead of in a note of its own.
 */
public record TopicPlan(List<Topic> topics) {

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
        return new TopicPlan(List.of());
    }

    /** The plan when the planner gave nothing usable: one note about the document itself. */
    public static TopicPlan single(String title) {
        return new TopicPlan(List.of(new Topic(title, "dokument", "Inhalt des Dokuments", List.of())));
    }

    public boolean isEmpty() {
        return topics.isEmpty();
    }

    public Topic main() {
        return topics.get(0);
    }
}
