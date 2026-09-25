package de.raindancer118.stoneai.source;

import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.pipeline.BoundedParallel;
import de.raindancer118.stoneai.pipeline.ProgressSink;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads PDFs page by page. Pages that carry a text layer are extracted directly; pages that come
 * out (near-)empty are treated as scans and rendered to an image for the vision model — which is
 * the only way to get anything out of a photographed or scanned document.
 */
public final class PdfLoader implements DocumentLoader {

    /**
     * A page counts as scanned only when it is both (near-)empty of text <em>and</em> carries an
     * image. Text alone is not a reliable signal: title pages, section dividers and sparse slides
     * legitimately hold a handful of words, and sending those through a vision model would be
     * both wasteful and worse than just reading them.
     */
    private static final int TEXT_LAYER_THRESHOLD = 12;
    private static final int RENDER_DPI = 200;

    /** Titles PDF exporters write when there is no real one. */
    private static final java.util.Set<String> PLACEHOLDER_TITLES = java.util.Set.of(
            "(anonymous)", "anonymous", "untitled", "unbenannt", "ohne titel",
            "document", "dokument", "pdf document", "none", "n/a", "-");

    private final StoneAiConfig config;
    private final OcrService ocr;
    private final ProgressSink progress;

    public PdfLoader(StoneAiConfig config, OcrService ocr) {
        this(config, ocr, ProgressSink.NONE);
    }

    public PdfLoader(StoneAiConfig config, OcrService ocr, ProgressSink progress) {
        this.config = config;
        this.ocr = ocr;
        this.progress = progress;
    }

    @Override
    public boolean supports(Path file) {
        return Documents.extension(file).equals(".pdf");
    }

    /**
     * Reads the text layer page by page first, then the image pages - up to
     * {@code llm.parallelCalls} at once, since a scanned book is hundreds of vision calls. PDFBox
     * renders one page at a time; only the model calls overlap.
     */
    @Override
    public SourceDocument load(Path file) throws IOException {
        List<Integer> skipped = new ArrayList<>();
        boolean truncated;
        String title;
        String[] texts;
        List<Integer> scans = new ArrayList<>();
        List<OcrResult> read;

        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            int available = document.getNumberOfPages();
            int limit = Math.min(available, config.ingest().maxPages());
            truncated = limit < available;
            title = metadataTitle(document).orElse(Documents.titleFromFileName(file));
            texts = new String[limit + 1];

            PDFTextStripper stripper = new PDFTextStripper();
            for (int number = 1; number <= limit; number++) {
                progress.report("Seite " + number + "/" + limit + " wird gelesen", number * 10 / limit);
                stripper.setStartPage(number);
                stripper.setEndPage(number);
                String text = normalise(stripper.getText(document));

                boolean hasText = countVisible(text) >= TEXT_LAYER_THRESHOLD;
                if (hasText || !containsImage(document.getPage(number - 1))) {
                    texts[number] = text;
                } else if (config.llm().visionEnabled()) {
                    scans.add(number);
                } else {
                    texts[number] = "";
                }
            }

            PDFRenderer renderer = new PDFRenderer(document);
            int[] done = {0};
            read = BoundedParallel.run(scans.size(), config.llm().parallelCalls(),
                    index -> transcribe(renderer, scans.get(index)),
                    index -> true,
                    (index, result) -> {
                        done[0]++;
                        progress.report("Bildseite " + done[0] + "/" + scans.size() + " gelesen (S. " + scans.get(index) + ")",
                                10 + done[0] * 90 / scans.size());
                    });
        }

        List<Integer> unreadable = new ArrayList<>();
        boolean[] fromOcr = new boolean[texts.length];
        RuntimeException lastOcrFailure = null;
        for (int index = 0; index < scans.size(); index++) {
            OcrResult result = read.get(index);
            if (result.failure() != null) {
                // One unreadable diagram must not cost the rest of the document; it is named instead.
                unreadable.add(scans.get(index));
                lastOcrFailure = result.failure();
            } else {
                texts[scans.get(index)] = result.text();
                fromOcr[scans.get(index)] = true;
            }
        }
        List<Page> pages = new ArrayList<>();
        for (int number = 1; number < texts.length; number++) {
            if (texts[number] == null) {
                continue;
            }
            if (texts[number].isBlank()) {
                skipped.add(number);
            } else {
                pages.add(new Page(number, texts[number], fromOcr[number]));
            }
        }
        progress.report("Dokument gelesen", 100);

        if (pages.isEmpty() && lastOcrFailure != null) {
            // Nothing readable at all - a later attempt may reach the provider.
            throw lastOcrFailure;
        }
        if (pages.isEmpty() && skipped.isEmpty()) {
            throw new EmptyDocumentException(file);
        }
        if (pages.isEmpty()) {
            // Every page empty, or an image the text recognition returned nothing for: without a
            // word of text the run could only write an empty source note and call that "done".
            throw new EmptyDocumentException(file, "weder Text noch Bildtext gefunden ("
                    + (skipped.size() == 1 ? "Seite " : "Seiten ")
                    + String.join(", ", skipped.stream().map(String::valueOf).toList()) + ")");
        }
        return new SourceDocument(file, title, DocumentKind.PDF, pages, skipped, truncated,
                Documents.sha256(file), unreadable);
    }

    private record OcrResult(String text, RuntimeException failure) {
    }

    /** One image page through the vision model; an exhausted quota ends the load, anything else names the page. */
    private OcrResult transcribe(PDFRenderer renderer, int number) {
        byte[] png;
        try {
            synchronized (renderer) {
                png = renderPng(renderer, number);
            }
        } catch (IOException e) {
            return new OcrResult(null, new java.io.UncheckedIOException(e));
        }
        try {
            return new OcrResult(normalise(ocr.read(png, number)), null);
        } catch (de.raindancer118.stoneai.extract.LlmCapacityException outOfCapacity) {
            // Every further page would fail alike - the run waits for capacity instead.
            throw outOfCapacity;
        } catch (RuntimeException unavailable) {
            return new OcrResult(null, unavailable);
        }
    }

    /** Whether a page draws at least one image — the other half of the "is this a scan?" test. */
    private static boolean containsImage(PDPage page) {
        PDResources resources = page.getResources();
        if (resources == null) {
            return false;
        }
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xObject = resources.getXObject(name);
                if (xObject instanceof PDImageXObject) {
                    return true;
                }
            } catch (IOException e) {
                // A resource we cannot open tells us nothing; keep looking at the rest.
            }
        }
        return false;
    }

    /**
     * The PDF's own title, but only when it is worth anything. Exporters routinely write
     * placeholders — {@code (anonymous)}, {@code untitled}, the source file name with a
     * {@code Microsoft Word - } prefix — and using those makes every note cite a source called
     * "(anonymous)". The file name is the better fallback.
     */
    private static java.util.Optional<String> metadataTitle(PDDocument document) {
        var info = document.getDocumentInformation();
        if (info == null || info.getTitle() == null) {
            return java.util.Optional.empty();
        }
        String title = info.getTitle().trim();
        if (title.startsWith("Microsoft Word - ")) {
            title = title.substring("Microsoft Word - ".length()).trim();
        }
        title = title.replaceAll("(?i)\\.(pdf|docx?|odt|tex)$", "").trim();

        String comparable = title.toLowerCase(java.util.Locale.ROOT);
        boolean useless = title.length() < 3
                || PLACEHOLDER_TITLES.contains(comparable)
                || comparable.chars().noneMatch(Character::isLetter);
        return useless ? java.util.Optional.empty() : java.util.Optional.of(title);
    }

    private static byte[] renderPng(PDFRenderer renderer, int pageNumber) throws IOException {
        BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, RENDER_DPI, ImageType.RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static String normalise(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static int countVisible(String text) {
        return (int) text.chars().filter(c -> !Character.isWhitespace(c)).count();
    }
}
