package de.raindancer118.stoneai.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Remembers which documents have already been turned into notes, keyed by file hash. It is what
 * keeps the watch daemon from re-processing a file every time it is touched, and what lets a
 * re-run be a no-op instead of a second bill.
 *
 * <p>Plain JSON on purpose: a person can read it, diff it and delete a line from it. A corrupt
 * ledger degrades to "nothing processed yet" rather than aborting a run — the worst case is
 * doing work twice, which the managed blocks make harmless.
 */
public final class ProcessingLedger {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Map<String, LedgerEntry> entries = new LinkedHashMap<>();

    private ProcessingLedger(Path file) {
        this.file = file;
    }

    /** {@code ~/.local/share/stoneai/ledger.json}, honouring {@code XDG_DATA_HOME}. */
    public static Path defaultLocation() {
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = (xdg == null || xdg.isBlank())
                ? Path.of(System.getProperty("user.home"), ".local", "share")
                : Path.of(xdg);
        return base.resolve("stoneai").resolve("ledger.json");
    }

    public static ProcessingLedger load(Path file) throws IOException {
        ProcessingLedger ledger = new ProcessingLedger(file);
        if (!Files.exists(file)) {
            return ledger;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return ledger;
        }
        JsonNode documents = root.path("documents");
        if (!documents.isArray()) {
            return ledger;
        }
        for (JsonNode node : documents) {
            LedgerEntry entry = new LedgerEntry(
                    node.path("sha256").asText(""),
                    node.path("file").asText(""),
                    parseInstant(node.path("processedAt").asText("")),
                    strings(node.path("notes")),
                    strings(node.path("providers")),
                    node.path("tokensUsed").asInt(0));
            if (!entry.sha256().isEmpty()) {
                ledger.entries.put(entry.sha256(), entry);
            }
        }
        return ledger;
    }

    public void record(LedgerEntry entry) {
        entries.put(entry.sha256(), entry);
    }

    public boolean isProcessed(String sha256) {
        return entries.containsKey(sha256);
    }

    public Optional<LedgerEntry> find(String sha256) {
        return Optional.ofNullable(entries.get(sha256));
    }

    public int size() {
        return entries.size();
    }

    public List<LedgerEntry> all() {
        return List.copyOf(entries.values());
    }

    public void save() throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 1);
        ArrayNode documents = root.putArray("documents");
        for (LedgerEntry entry : entries.values()) {
            ObjectNode node = documents.addObject();
            node.put("sha256", entry.sha256());
            node.put("file", entry.sourceFile());
            node.put("processedAt", entry.processedAt().toString());
            node.put("tokensUsed", entry.tokensUsed());
            ArrayNode notes = node.putArray("notes");
            entry.noteTitles().forEach(notes::add);
            ArrayNode providers = node.putArray("providers");
            entry.providers().forEach(providers::add);
        }

        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".stoneai-ledger", ".tmp");
        try {
            Files.writeString(temp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> values.add(item.asText("")));
        }
        return values;
    }

    private static Instant parseInstant(String value) {
        try {
            return value.isEmpty() ? Instant.EPOCH : Instant.parse(value);
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }
}
