package de.raindancer118.stoneai.protection;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Reads a PDF's metadata to see whether it carries the protection tag. This is the escape hatch
 * for PDFs: a file can be protected without renaming it, by putting {@code NoStoneAI} into its
 * keywords, subject or title — something every PDF viewer can edit.
 */
final class PdfMetadataProtection {

    private PdfMetadataProtection() {
    }

    static ProtectionDecision inspect(Path pdf, String tag) {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDDocumentInformation info = document.getDocumentInformation();
            if (info == null) {
                return ProtectionDecision.allowed();
            }
            String needle = tag.toLowerCase(Locale.ROOT);
            boolean tagged = Stream.of(info.getKeywords(), info.getSubject(), info.getTitle())
                    .filter(java.util.Objects::nonNull)
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .anyMatch(value -> containsWord(value, needle));
            return tagged
                    ? ProtectionDecision.protectedBy("PDF-Metadaten enthalten das Schutz-Tag " + tag)
                    : ProtectionDecision.allowed();
        } catch (IOException e) {
            // An unreadable PDF is not a protected one; the loader will report the real problem.
            return ProtectionDecision.allowed();
        }
    }

    private static boolean containsWord(String haystack, String needle) {
        for (String token : haystack.split("[\\s,;]+")) {
            String cleaned = token.startsWith("#") ? token.substring(1) : token;
            if (cleaned.equals(needle)) {
                return true;
            }
        }
        return false;
    }
}
