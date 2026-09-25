package de.raindancer118.stoneai.source;

import de.raindancer118.stoneai.config.StoneAiConfig;
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

    public PdfLoader(StoneAiConfig config, OcrService ocr) {
        this.config = config;
        this.ocr = ocr;
    }

    @Override
    public boolean supports(Path file) {
        return Documents.extension(file).equals(".pdf");
    }

    @Override
    public SourceDocument load(Path file) throws IOException {
        List<Page> pages = new ArrayList<>();
        List<Integer> skipped = new ArrayList<>();
        List<Integer> unreadable = new ArrayList<>();
        RuntimeException lastOcrFailure = null;
        boolean truncated;
        String title;

        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            int available = document.getNumberOfPages();
            int limit = Math.min(available, config.ingest().maxPages());
            truncated = limit < available;
            title = metadataTitle(document).orElse(Documents.titleFromFileName(file));

            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);

            for (int number = 1; number <= limit; number++) {
                stripper.setStartPage(number);
                stripper.setEndPage(number);
                String text = normalise(stripper.getText(document));

                boolean hasText = countVisible(text) >= TEXT_LAYER_THRESHOLD;
                if (hasText || !containsImage(document.getPage(number - 1))) {
                    if (text.isBlank()) {
                        skipped.add(number);
                    } else {
                        pages.add(new Page(number, text, false));
                    }
                    continue;
                }
                if (!config.llm().visionEnabled()) {
                    skipped.add(number);
                    continue;
                }
                String transcribed;
                try {
                    transcribed = normalise(ocr.read(renderPng(renderer, number), number));
                } catch (de.raindancer118.stoneai.extract.LlmCapacityException outOfCapacity) {
                    // Every further page would fail alike - the run waits for capacity instead.
                    throw outOfCapacity;
                } catch (RuntimeException unavailable) {
                    // One unreadable diagram must not cost the rest of the document; it is named instead.
                    unreadable.add(number);
                    lastOcrFailure = unavailable;
                    continue;
                }
                if (transcribed.isBlank()) {
                    skipped.add(number);
                } else {
                    pages.add(new Page(number, transcribed, true));
                }
            }
        }

        if (pages.isEmpty() && lastOcrFailure != null) {
            // Nothing readable at all - a later attempt may reach the provider.
            throw lastOcrFailure;
        }
        if (pages.isEmpty() && skipped.isEmpty()) {
            throw new EmptyDocumentException(file);
        }
        return new SourceDocument(file, title, DocumentKind.PDF, pages, skipped, truncated,
                Documents.sha256(file), unreadable);
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
