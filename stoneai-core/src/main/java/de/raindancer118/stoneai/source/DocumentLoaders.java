package de.raindancer118.stoneai.source;

import de.raindancer118.stoneai.config.StoneAiConfig;

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
        return new DocumentLoaders(List.of(new PdfLoader(config, ocr), new TextLoader()));
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
