package de.tstieh.stoneintelligence.worker.embed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Laedt das lokale Modell erst beim ersten Verlinkungslauf (Einlese-Jobs brauchen es nie) und haelt es
 * dann. Fehlt das Modell (aeltere Images), gibt es {@code null} - der Lauf verlinkt dann nur woertlich.
 */
public final class LazyEmbedder implements Supplier<Embedder> {

    private static final Logger LOG = LoggerFactory.getLogger(LazyEmbedder.class);

    private final Path dir;
    private LocalEmbedder loaded;
    private boolean missing;

    public LazyEmbedder(Path dir) {
        this.dir = dir;
    }

    @Override
    public synchronized Embedder get() {
        if (loaded != null || missing) {
            return loaded;
        }
        if (!Files.exists(dir.resolve(LocalEmbedder.MODEL_FILE)) || !Files.exists(dir.resolve(LocalEmbedder.TOKENIZER_FILE))) {
            LOG.warn("Kein Embedding-Modell unter {} - Verlinkung nur woertlich, ohne 'Aehnliche Notizen'", dir);
            missing = true;
            return null;
        }
        loaded = LocalEmbedder.load(dir);
        LOG.info("Embedding-Modell {} geladen", loaded.model());
        return loaded;
    }
}
