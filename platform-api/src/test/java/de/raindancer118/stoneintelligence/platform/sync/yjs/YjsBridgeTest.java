package de.raindancer118.stoneintelligence.platform.sync.yjs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die echte Yjs-Bibliothek, eingebettet ueber GraalJS (ADR 0008). Dieselbe Diff-Logik wie das
 * Plugin; die Interop mit Node-/Obsidian-Yjs deckt yjs-bridge/test ab.
 */
class YjsBridgeTest {

    private static YjsBridge bridge;

    @BeforeAll
    static void load() {
        bridge = YjsBridge.load();
    }

    @AfterAll
    static void close() {
        bridge.close();
    }

    @Test
    void should_readEmptyText_fromAnEmptyHistory() {
        assertThat(bridge.textOf(List.of())).isEmpty();
    }

    @Test
    void should_writeAndReadBack_includingUmlautsAndEmoji() {
        var first = bridge.change(List.of(), "# Grüße\n\nMit Emoji 😀\n").orElseThrow();
        var second = bridge.change(List.of(first), "# Grüße\n\nMit Emoji 😀 und mehr\n").orElseThrow();

        assertThat(bridge.textOf(List.of(first, second))).isEqualTo("# Grüße\n\nMit Emoji 😀 und mehr\n");
    }

    @Test
    void should_produceNoUpdate_whenTheTextIsUnchanged() {
        var first = bridge.change(List.of(), "gleich").orElseThrow();

        assertThat(bridge.change(List.of(first), "gleich")).isEmpty();
    }

    // Ein GraalJS-Context ist nicht threadsicher - parallele Anfragen duerfen sich nicht stoeren.
    @Test
    void should_handleParallelCallers() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                var text = "Notiz " + i;
                results.add(executor.submit(() -> bridge.textOf(List.of(bridge.change(List.of(), text).orElseThrow()))));
            }
            for (int i = 0; i < 40; i++) {
                assertThat(results.get(i).get()).isEqualTo("Notiz " + i);
            }
        }
    }
}
