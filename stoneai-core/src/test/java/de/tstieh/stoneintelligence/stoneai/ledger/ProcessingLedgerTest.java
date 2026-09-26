package de.tstieh.stoneintelligence.stoneai.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingLedgerTest {

    @TempDir
    Path dir;

    private LedgerEntry entry(String hash) {
        return new LedgerEntry(hash, "/tmp/Skript.pdf", Instant.parse("2026-09-01T10:00:00Z"),
                List.of("Gruppe", "Ring"), List.of("groq"), 1234);
    }

    @Nested
    @DisplayName("Remembering documents")
    class Remembering {

        @Test
        @DisplayName("should recognise a document it has already processed")
        void should_reportProcessed_when_hashIsKnown() throws IOException {
            Path file = dir.resolve("ledger.json");
            ProcessingLedger ledger = ProcessingLedger.load(file);

            ledger.record(entry("abc"));
            ledger.save();

            assertThat(ProcessingLedger.load(file).isProcessed("abc")).isTrue();
            assertThat(ProcessingLedger.load(file).isProcessed("xyz")).isFalse();
        }

        @Test
        @DisplayName("should survive a round trip with every field intact")
        void should_roundTrip_when_savedAndLoaded() throws IOException {
            Path file = dir.resolve("ledger.json");
            ProcessingLedger ledger = ProcessingLedger.load(file);
            ledger.record(entry("abc"));
            ledger.save();

            LedgerEntry loaded = ProcessingLedger.load(file).find("abc").orElseThrow();

            assertThat(loaded.sourceFile()).isEqualTo("/tmp/Skript.pdf");
            assertThat(loaded.noteTitles()).containsExactly("Gruppe", "Ring");
            assertThat(loaded.providers()).containsExactly("groq");
            assertThat(loaded.tokensUsed()).isEqualTo(1234);
            assertThat(loaded.processedAt()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
        }

        @Test
        @DisplayName("should replace an earlier entry when the same document is processed again")
        void should_replaceEntry_when_recordedTwice() throws IOException {
            ProcessingLedger ledger = ProcessingLedger.load(dir.resolve("ledger.json"));

            ledger.record(entry("abc"));
            ledger.record(new LedgerEntry("abc", "/tmp/Skript.pdf", Instant.parse("2026-09-02T10:00:00Z"),
                    List.of("Gruppe"), List.of("gemini"), 10));

            assertThat(ledger.size()).isEqualTo(1);
            assertThat(ledger.find("abc").orElseThrow().providers()).containsExactly("gemini");
        }
    }

    @Nested
    @DisplayName("Robustness")
    class Robustness {

        @Test
        @DisplayName("should start empty when there is no ledger yet")
        void should_beEmpty_when_fileIsMissing() throws IOException {
            assertThat(ProcessingLedger.load(dir.resolve("absent.json")).size()).isZero();
        }

        @Test
        @DisplayName("should start over rather than crash on a corrupted ledger")
        void should_recover_when_fileIsCorrupt() throws IOException {
            Path file = dir.resolve("ledger.json");
            Files.writeString(file, "{ das ist kein JSON");

            assertThat(ProcessingLedger.load(file).size()).isZero();
        }

        @Test
        @DisplayName("should leave no temporary files behind when saving")
        void should_writeAtomically_when_saving() throws IOException {
            Path file = dir.resolve("nested/ledger.json");
            ProcessingLedger ledger = ProcessingLedger.load(file);
            ledger.record(entry("abc"));

            ledger.save();

            try (var files = Files.list(file.getParent())) {
                assertThat(files).containsExactly(file);
            }
        }
    }
}
