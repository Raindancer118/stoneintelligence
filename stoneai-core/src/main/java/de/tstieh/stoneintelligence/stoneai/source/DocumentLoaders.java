package de.tstieh.stoneintelligence.stoneai.source;

import de.tstieh.stoneintelligence.stoneai.config.StoneAiConfig;
import de.tstieh.stoneintelligence.stoneai.pipeline.ProgressSink;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Picks the right loader for a file and fails loudly when there is none. */
public final class DocumentLoaders implements DocumentLoader {

    private final List<DocumentLoader> loaders;

    public DocumentLoaders(List<DocumentLoader> loaders) {
        this.loaders = List.copyOf(loaders);
    }

    public static DocumentLoaders forConfig(StoneAiConfig config, OcrService ocr) {
        return forConfig(config, ocr, ProgressSink.NONE);
    }

    /** Loaders that report how far a long PDF has been read - page by page, image pages included. */
    public static DocumentLoaders forConfig(StoneAiConfig config, OcrService ocr, ProgressSink progress) {
        return new DocumentLoaders(List.of(new PdfLoader(config, ocr, progress), new TextLoader()));
    }

    @Override
    public boolean supports(Path file) {
        return loaders.stream().anyMatch(loader -> loader.supports(file));
    }

    @Override
    public SourceDocument load(Path file) throws IOException {
        for (DocumentLoader loader : loaders) {
            if (loader.supports(file)) {
                return loader.load(file);
            }
        }
        throw new UnsupportedDocumentException(file);
    }
}
