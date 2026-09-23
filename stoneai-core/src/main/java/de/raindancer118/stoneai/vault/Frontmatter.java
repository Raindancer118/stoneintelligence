package de.raindancer118.stoneai.vault;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The YAML block at the top of an Obsidian note — the part that makes facts queryable with
 * Dataview.
 *
 * <p>Deliberately a small hand-written subset (scalars, inline and block lists, one level of
 * nested lists) rather than a general YAML library: StoneAI has to <em>merge into</em> notes a
 * person edits by hand, and a full round-trip through a YAML parser rewrites formatting,
 * reorders keys and drops comments. Anything more exotic than the supported shapes is passed
 * through untouched instead of being reformatted.
 */
public final class Frontmatter {

    private static final String DELIMITER = "---";

    private final Map<String, Object> values;

    private Frontmatter(Map<String, Object> values) {
        this.values = values;
    }

    public static Frontmatter empty() {
        return new Frontmatter(new LinkedHashMap<>());
    }

    /** Splits a note into its frontmatter and its body. */
    public static Document of(String content) {
        String normalised = content.replace("\r\n", "\n");
        if (!normalised.startsWith(DELIMITER + "\n")) {
            return new Document(empty(), normalised);
        }
        int end = normalised.indexOf("\n" + DELIMITER, DELIMITER.length());
        if (end < 0) {
            // No closing delimiter: this is not frontmatter, and guessing would eat the note.
            return new Document(empty(), normalised);
        }
        String block = normalised.substring(DELIMITER.length() + 1, end);
        String body = normalised.substring(Math.min(normalised.length(), end + DELIMITER.length() + 2));
        return new Document(parse(block), body.startsWith("\n") ? body.substring(1) : body);
    }

    public Set<String> keys() {
        return new LinkedHashSet<>(values.keySet());
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public String scalar(String key) {
        Object value = values.get(key);
        return value instanceof String text ? text : null;
    }

    @SuppressWarnings("unchecked")
    public List<String> list(String key) {
        Object value = values.get(key);
        return value instanceof List<?> list ? List.copyOf((List<String>) list) : List.of();
    }

    @SuppressWarnings("unchecked")
    public Map<String, List<String>> nested(String key) {
        Object value = values.get(key);
        return value instanceof Map<?, ?> map ? Map.copyOf((Map<String, List<String>>) map) : Map.of();
    }

    public Frontmatter withScalar(String key, String value) {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.put(key, value);
        return new Frontmatter(copy);
    }

    public Frontmatter withList(String key, List<String> value) {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.put(key, List.copyOf(value));
        return new Frontmatter(copy);
    }

    public Frontmatter withNested(String key, Map<String, List<String>> value) {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.put(key, new LinkedHashMap<>(value));
        return new Frontmatter(copy);
    }

    /**
     * Merges {@code incoming} in without ever replacing something that is already there: missing
     * keys are added, list values are unioned, existing scalars win. Anything a person typed into
     * their own note survives a StoneAI run unchanged.
     */
    @SuppressWarnings("unchecked")
    public Frontmatter mergeAdditively(Frontmatter incoming) {
        Map<String, Object> merged = new LinkedHashMap<>(values);
        for (Map.Entry<String, Object> entry : incoming.values.entrySet()) {
            Object mine = merged.get(entry.getKey());
            if (mine == null) {
                merged.put(entry.getKey(), entry.getValue());
                continue;
            }
            if (mine instanceof List<?> mineList && entry.getValue() instanceof List<?> theirs) {
                Set<String> union = new LinkedHashSet<>((List<String>) mineList);
                union.addAll((List<String>) theirs);
                merged.put(entry.getKey(), List.copyOf(union));
                continue;
            }
            if (mine instanceof Map<?, ?> mineMap && entry.getValue() instanceof Map<?, ?> theirsMap) {
                merged.put(entry.getKey(), mergeNested(
                        (Map<String, List<String>>) mineMap, (Map<String, List<String>>) theirsMap));
            }
            // Scalars: what is already in the note wins.
        }
        return new Frontmatter(merged);
    }

    private static Map<String, List<String>> mergeNested(Map<String, List<String>> mine,
                                                         Map<String, List<String>> theirs) {
        Map<String, List<String>> merged = new LinkedHashMap<>(mine);
        theirs.forEach((key, values) -> {
            Set<String> union = new LinkedHashSet<>(merged.getOrDefault(key, List.of()));
            union.addAll(values);
            merged.put(key, List.copyOf(union));
        });
        return merged;
    }

    /** The frontmatter block including both delimiters, ending with a newline. */
    public String render() {
        StringBuilder out = new StringBuilder(DELIMITER).append('\n');
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof List<?> list) {
                out.append(entry.getKey()).append(':');
                if (list.isEmpty()) {
                    out.append(" []\n");
                } else {
                    out.append('\n');
                    list.forEach(item -> out.append("  - ").append(quote(String.valueOf(item))).append('\n'));
                }
            } else if (value instanceof Map<?, ?> map) {
                out.append(entry.getKey()).append(":\n");
                map.forEach((key, items) -> {
                    out.append("  ").append(key).append(":\n");
                    ((List<?>) items).forEach(item ->
                            out.append("    - ").append(quote(String.valueOf(item))).append('\n'));
                });
            } else {
                out.append(entry.getKey()).append(": ").append(quote(String.valueOf(value))).append('\n');
            }
        }
        return out.append(DELIMITER).append('\n').toString();
    }

    /** Quotes only where YAML needs it, so simple values stay readable. */
    private static String quote(String value) {
        boolean needsQuotes = value.isEmpty()
                || value.matches(".*[:#\\[\\]{},&*!|>%@`\"'].*")
                || value.startsWith(" ") || value.endsWith(" ")
                || value.matches("(?i)(true|false|null|yes|no|on|off)");
        if (!needsQuotes) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Frontmatter parse(String block) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> lines = List.of(block.split("\n", -1));

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.startsWith(" ") || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String rest = line.substring(colon + 1).strip();

            if (!rest.isEmpty()) {
                values.put(key, rest.startsWith("[") ? inlineList(rest) : unquote(rest));
                continue;
            }
            Block parsed = readIndented(lines, i + 1);
            i = parsed.nextIndex() - 1;
            values.put(key, parsed.value());
        }
        return new Frontmatter(values);
    }

    /** Reads the indented lines after a bare {@code key:} — either a list or a mapping of lists. */
    private static Block readIndented(List<String> lines, int start) {
        List<String> items = new ArrayList<>();
        Map<String, List<String>> nested = new LinkedHashMap<>();
        String currentKey = null;
        int index = start;

        while (index < lines.size()) {
            String line = lines.get(index);
            if (line.isBlank()) {
                index++;
                continue;
            }
            if (!line.startsWith(" ")) {
                break;
            }
            String trimmed = line.strip();
            int indent = line.length() - line.stripLeading().length();

            if (trimmed.startsWith("- ")) {
                String item = unquote(trimmed.substring(2).strip());
                if (currentKey == null || indent <= 2) {
                    items.add(item);
                } else {
                    nested.computeIfAbsent(currentKey, key -> new ArrayList<>()).add(item);
                }
            } else if (trimmed.endsWith(":")) {
                currentKey = trimmed.substring(0, trimmed.length() - 1).strip();
                nested.computeIfAbsent(currentKey, key -> new ArrayList<>());
            }
            index++;
        }
        Object value = nested.isEmpty() ? List.copyOf(items) : new LinkedHashMap<String, List<String>>(nested);
        return new Block(value, index);
    }

    private static List<String> inlineList(String value) {
        String inner = value.substring(1, value.endsWith("]") ? value.length() - 1 : value.length()).strip();
        if (inner.isEmpty()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String item : inner.split(",")) {
            String cleaned = unquote(item.strip());
            if (!cleaned.isEmpty()) {
                items.add(cleaned);
            }
        }
        return List.copyOf(items);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    /** A note split into its frontmatter and its body. */
    public record Document(Frontmatter frontmatter, String body) {
    }

    private record Block(Object value, int nextIndex) {
    }
}
