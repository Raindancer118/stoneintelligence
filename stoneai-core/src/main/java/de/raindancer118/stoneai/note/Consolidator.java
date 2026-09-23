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
                for (ExtractedConcept member : groups.get(i)) {
                    if (TextSimilarity.sameConcept(member.title(), concept.title(), threshold)) {
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

    /** Input per merge call - small enough that the merged text always fits into one answer. */
    static final int MERGE_INPUT_LIMIT = 12_000;
    /** A merge shorter than this share of its input was most likely cut off, not condensed. */
    private static final double MIN_MERGE_SHARE = 0.25;

    private DraftNote merge(List<ExtractedConcept> group) {
        ExtractedConcept best = group.stream()
                .max(Comparator.comparingDouble(ExtractedConcept::confidence))
                .orElseThrow();

        List<Part> parts = group.stream().map(concept -> new Part(concept.provenance().label(), concept.body())).toList();
        String body = mergedBody(group.get(0).title(), parts);

        return new DraftNote(best.title(), union(group, ExtractedConcept::aliases),
                best.definition(), body, union(group, ExtractedConcept::tags),
                mergedEntities(group), union(group, ExtractedConcept::related),
                best.confidence(), group.stream().map(ExtractedConcept::provenance).toList());
    }

    private record Part(String label, String text) {
    }

    /**
     * Fuses the parts in rounds: batches that fit into one call are merged, then the results
     * again, until one text is left. A batch the model cannot or will not merge - or whose merge
     * comes back suspiciously short - keeps its parts side by side: nothing is ever dropped.
     */
    private String mergedBody(String title, List<Part> parts) {
        List<Part> current = parts;
        while (current.size() > 1) {
            List<List<Part>> batches = batches(current);
            if (batches.size() == current.size()) {
                return concatenate(current);
            }
            List<Part> next = new ArrayList<>();
            for (List<Part> batch : batches) {
                String label = batch.size() == 1 ? batch.get(0).label() : "zusammengeführt";
                next.add(new Part(label, batch.size() == 1 ? batch.get(0).text() : mergeBatch(title, batch)));
            }
            current = next;
        }
        return current.get(0).text();
    }

    private static List<List<Part>> batches(List<Part> parts) {
        List<List<Part>> batches = new ArrayList<>();
        List<Part> batch = new ArrayList<>();
        int size = 0;
        for (Part part : parts) {
            int length = part.text().length() + part.label().length() + 10;
            if (!batch.isEmpty() && size + length > MERGE_INPUT_LIMIT) {
                batches.add(batch);
                batch = new ArrayList<>();
                size = 0;
            }
            batch.add(part);
            size += length;
        }
        if (!batch.isEmpty()) {
            batches.add(batch);
        }
        return batches;
    }

    private String mergeBatch(String title, List<Part> batch) {
        StringBuilder texts = new StringBuilder();
        for (Part part : batch) {
            texts.append("\n\n--- ").append(part.label()).append(" ---\n").append(part.text());
        }
        String user = """
                Thema: %s

                Unten stehen mehrere Teile derselben Notiz aus einem Dokument.
                Schreibe daraus EINEN gut gegliederten Text in Markdown: ohne Wiederholungen,
                Fakten als Stichpunkte, bei Bedarf Zwischenüberschriften ab ###, keine Überschrift
                der Ebene 1 oder 2, kein Frontmatter, keine Quellenmarker. Behalte jeden Fakt und
                jeden [[Verweis]]. Nimm nichts hinzu, was nicht in den Teilen steht. Antworte nur
                mit dem Text.
                %s""".formatted(title, texts);
        int input = batch.stream().mapToInt(part -> part.text().length()).sum();
        try {
            LlmAnswer answer = llm.complete(Tier.SMART,
                    "Du führst Textfassungen zusammen, ohne Inhalt zu erfinden oder zu verlieren.", user);
            String text = answer.text() == null ? "" : answer.text().strip();
            return text.length() < input * MIN_MERGE_SHARE ? concatenate(batch) : text;
        } catch (RuntimeException e) {
            return concatenate(batch);
        }
    }

    private static String concatenate(List<Part> parts) {
        StringBuilder body = new StringBuilder();
        for (Part part : parts) {
            if (body.length() > 0) {
                body.append("\n\n");
            }
            body.append(part.text());
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
