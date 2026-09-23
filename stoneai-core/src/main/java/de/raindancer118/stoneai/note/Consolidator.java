package de.raindancer118.stoneai.note;

import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.extract.ExtractedConcept;
import de.raindancer118.stoneai.extract.LlmAnswer;
import de.raindancer118.stoneai.extract.LlmClient;
import de.raindancer118.stoneai.extract.Tier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the per-chunk concepts into the notes that will actually be written: the same idea found
 * on three pages becomes one note citing three pages, not three near-identical notes.
 *
 * <p>Grouping is deterministic — normalised titles, alias overlap and a tunable similarity
 * threshold — so the same document always groups the same way. The model is only asked to do the
 * one thing it is genuinely better at: writing a single coherent text out of several partial
 * ones. A unique concept therefore costs nothing extra, and a failed merge falls back to keeping
 * every source text rather than dropping any of them.
 */
public final class Consolidator {

    private final StoneAiConfig config;
    private final LlmClient llm;

    public Consolidator(StoneAiConfig config, LlmClient llm) {
        this.config = config;
        this.llm = llm;
    }

    public List<DraftNote> consolidate(List<ExtractedConcept> concepts) {
        List<List<ExtractedConcept>> groups = group(concepts);
        List<DraftNote> notes = new ArrayList<>();
        for (List<ExtractedConcept> group : groups) {
            notes.add(group.size() == 1 ? toNote(group.get(0)) : merge(group));
        }
        return capped(notes);
    }

    /** Buckets concepts that describe the same thing, by title, alias or close similarity. */
    private List<List<ExtractedConcept>> group(List<ExtractedConcept> concepts) {
        List<List<ExtractedConcept>> groups = new ArrayList<>();
        List<Set<String>> keys = new ArrayList<>();
        double threshold = config.notes().similarityThreshold();

        for (ExtractedConcept concept : concepts) {
            Set<String> conceptKeys = keysOf(concept);
            int target = -1;
            for (int i = 0; i < groups.size() && target < 0; i++) {
                if (!java.util.Collections.disjoint(keys.get(i), conceptKeys)) {
                    target = i;
                    continue;
                }
                for (String existing : keys.get(i)) {
                    if (TextSimilarity.similarity(existing, concept.title()) >= threshold) {
                        target = i;
                        break;
                    }
                }
            }
            if (target < 0) {
                groups.add(new ArrayList<>(List.of(concept)));
                keys.add(new LinkedHashSet<>(conceptKeys));
            } else {
                groups.get(target).add(concept);
                keys.get(target).addAll(conceptKeys);
            }
        }
        return groups;
    }

    private static Set<String> keysOf(ExtractedConcept concept) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(TextSimilarity.normalise(concept.title()));
        concept.aliases().forEach(alias -> keys.add(TextSimilarity.normalise(alias)));
        return keys;
    }

    private DraftNote merge(List<ExtractedConcept> group) {
        ExtractedConcept best = group.stream()
                .max(Comparator.comparingDouble(ExtractedConcept::confidence))
                .orElseThrow();

        String body = mergedBody(group).orElseGet(() -> concatenate(group));

        return new DraftNote(best.title(), union(group, ExtractedConcept::aliases),
                best.definition(), body, union(group, ExtractedConcept::tags),
                mergedEntities(group), union(group, ExtractedConcept::related),
                best.confidence(), group.stream().map(ExtractedConcept::provenance).toList());
    }

    /** Asks the model to fuse the texts; empty when it could not or would not. */
    private java.util.Optional<String> mergedBody(List<ExtractedConcept> group) {
        StringBuilder parts = new StringBuilder();
        for (ExtractedConcept concept : group) {
            parts.append("\n\n--- ").append(concept.provenance().label()).append(" ---\n")
                    .append(concept.body());
        }
        String user = """
                Begriff: %s

                Unten stehen mehrere Fassungen desselben Begriffs aus einem Dokument.
                Schreibe daraus EINEN zusammenhängenden Text in Markdown: ohne Wiederholungen,
                ohne Überschrift, ohne Frontmatter, ohne Quellenmarker. Nimm nichts hinzu, was
                nicht in den Fassungen steht. Antworte nur mit dem Text.
                %s""".formatted(group.get(0).title(), parts);

        try {
            LlmAnswer answer = llm.complete(Tier.SMART,
                    "Du führst Textfassungen zusammen, ohne Inhalt zu erfinden oder zu verlieren.", user);
            String text = answer.text() == null ? "" : answer.text().strip();
            return text.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(text);
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    private static String concatenate(List<ExtractedConcept> group) {
        StringBuilder body = new StringBuilder();
        for (ExtractedConcept concept : group) {
            if (body.length() > 0) {
                body.append("\n\n");
            }
            body.append(concept.body());
        }
        return body.toString();
    }

    private static DraftNote toNote(ExtractedConcept concept) {
        return new DraftNote(concept.title(), concept.aliases(), concept.definition(), concept.body(),
                concept.tags(), concept.entities(), concept.related(), concept.confidence(),
                List.of(concept.provenance()));
    }

    private static List<String> union(List<ExtractedConcept> group,
                                      java.util.function.Function<ExtractedConcept, List<String>> field) {
        Set<String> merged = new LinkedHashSet<>();
        group.forEach(concept -> merged.addAll(field.apply(concept)));
        return List.copyOf(merged);
    }

    private static Map<String, List<String>> mergedEntities(List<ExtractedConcept> group) {
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        for (ExtractedConcept concept : group) {
            concept.entities().forEach((category, values) ->
                    merged.computeIfAbsent(category, key -> new LinkedHashSet<>()).addAll(values));
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        merged.forEach((category, values) -> result.put(category, List.copyOf(values)));
        return result;
    }

    /**
     * Enforces {@code notes.maxNotesPerDocument}. When a document yields more ideas than the
     * limit, the most confident ones survive — an arbitrary truncation would drop whatever
     * happened to come last.
     */
    private List<DraftNote> capped(List<DraftNote> notes) {
        int limit = config.notes().maxNotesPerDocument();
        if (notes.size() <= limit) {
            return notes;
        }
        return notes.stream()
                .sorted(Comparator.comparingDouble(DraftNote::confidence).reversed())
                .limit(limit)
                .toList();
    }
}
