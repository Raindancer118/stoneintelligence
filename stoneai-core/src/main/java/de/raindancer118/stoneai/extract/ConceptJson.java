package de.raindancer118.stoneai.extract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.raindancer118.stoneai.chunk.Provenance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses and validates the JSON contract the extraction prompt asks for. Models routinely wrap
 * their answer in prose or a code fence and occasionally drop a required field, so the parser
 * is forgiving about packaging and strict about content: anything that would produce a note
 * without a title or without a body is rejected, which is what triggers the repair round.
 */
final class ConceptJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double DEFAULT_CONFIDENCE = 0.6;

    private ConceptJson() {
    }

    /** The shape the model is asked to produce, embedded verbatim in the prompt. */
    static final String CONTRACT = """
            {
              "concepts": [
                {
                  "title": "kurzer, eindeutiger Begriff — der Notiztitel",
                  "aliases": ["alternative Schreibweisen"],
                  "definition": "ein bis zwei Sätze, die den Begriff definieren",
                  "body": "Markdown: Erklärung, Beispiele, Formeln. Keine Überschrift, kein Frontmatter.",
                  "tags": ["oberthema/unterthema — durch echte Schlagworte ersetzen"],
                  "entities": {"begriff": ["..."], "person": ["..."], "datum": ["..."]},
                  "related": ["Titel anderer Konzepte aus demselben Text"],
                  "confidence": 0.0
                }
              ]
            }""";

    static List<ExtractedConcept> parse(String answer, Provenance provenance) {
        JsonNode root = readTree(unwrap(answer));
        JsonNode concepts = root.get("concepts");
        if (concepts == null || !concepts.isArray()) {
            throw new ExtractionException("the answer has no \"concepts\" array");
        }

        List<ExtractedConcept> parsed = new ArrayList<>();
        for (JsonNode node : concepts) {
            parsed.add(toConcept(node, provenance));
        }
        return parsed;
    }

    private static ExtractedConcept toConcept(JsonNode node, Provenance provenance) {
        String title = text(node, "title");
        if (title.isBlank()) {
            throw new ExtractionException("a concept has no non-empty \"title\"");
        }
        String body = text(node, "body");
        if (body.isBlank()) {
            throw new ExtractionException("concept \"" + title + "\" has no non-empty \"body\"");
        }
        double confidence = node.hasNonNull("confidence")
                ? clamp(node.get("confidence").asDouble(DEFAULT_CONFIDENCE))
                : DEFAULT_CONFIDENCE;

        return new ExtractedConcept(title.strip(), strings(node, "aliases"),
                text(node, "definition").strip(), body.strip(), strings(node, "tags"),
                entities(node), strings(node, "related"), confidence, provenance);
    }

    /**
     * Pulls the JSON object out of whatever the model wrapped it in — a {@code ```json} fence,
     * a sentence of preamble, or both.
     */
    private static String unwrap(String answer) {
        String text = answer.strip();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                text = text.substring(start + 1, end).strip();
            }
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            throw new ExtractionException("the answer contains no JSON object");
        }
        return text.substring(firstBrace, lastBrace + 1);
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ExtractionException("the answer is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode value = node.get(field);
        List<String> items = new ArrayList<>();
        if (value == null || value.isNull()) {
            return items;
        }
        if (value.isTextual()) {
            addIfPresent(items, value.asText());
            return items;
        }
        if (value.isArray()) {
            for (JsonNode element : value) {
                addIfPresent(items, element.asText(""));
            }
        }
        return items;
    }

    private static Map<String, List<String>> entities(JsonNode node) {
        JsonNode value = node.get("entities");
        Map<String, List<String>> entities = new LinkedHashMap<>();
        if (value == null || !value.isObject()) {
            return entities;
        }
        value.fields().forEachRemaining(entry -> {
            List<String> values = strings(value, entry.getKey());
            if (!values.isEmpty()) {
                entities.put(entry.getKey().strip(), values);
            }
        });
        return entities;
    }

    private static void addIfPresent(List<String> items, String candidate) {
        String trimmed = candidate == null ? "" : candidate.strip();
        if (!trimmed.isEmpty() && !items.contains(trimmed)) {
            items.add(trimmed);
        }
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
