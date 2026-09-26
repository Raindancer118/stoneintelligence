package de.tstieh.stoneintelligence.worker.ingest;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import de.tstieh.stoneintelligence.worker.embed.Embedder;
import de.tstieh.stoneintelligence.worker.embed.LocalEmbedder;
import de.tstieh.stoneintelligence.worker.embed.NoteChunker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Haelt die Standard-Schwelle der Stufe 2 (ADR 0012) am echten Modell fest: eng verwandte Notizen
 * werden verlinkt, Notizen fremder Themen bleiben mit Abstand darunter. Aendert sich Modell oder
 * Abschnittsbildung, faellt es hier auf statt als stilles Falsch-Verlinken im Vault.
 */
class LinkingCalibrationTest {

    private static final Map<String, String> BIOLOGY = Map.of(
        "Photosynthese", "# Photosynthese\n\nPflanzen wandeln in den Chloroplasten Lichtenergie in chemische Energie um. Aus Kohlendioxid und Wasser entstehen Glukose und Sauerstoff.\n",
        "Chlorophyll", "# Chlorophyll\n\nChlorophyll ist der grüne Farbstoff der Pflanzen. Es absorbiert rotes und blaues Licht und ermöglicht so die Lichtreaktion der Photosynthese.\n",
        "Calvin-Zyklus", "# Calvin-Zyklus\n\nIm Stroma der Chloroplasten wird CO2 mithilfe von ATP und NADPH zu Zucker fixiert. Das Enzym RuBisCO katalysiert den ersten Schritt.\n");
    private static final Map<String, String> ACCOUNTING = Map.of(
        "Bilanz", "# Bilanz\n\nDie Bilanz stellt Vermögen und Kapital eines Unternehmens zum Stichtag gegenüber. Aktiva und Passiva müssen gleich groß sein.\n",
        "Abschreibung", "# Abschreibung\n\nAbschreibungen verteilen die Anschaffungskosten eines Anlageguts über die Nutzungsdauer und mindern den Gewinn.\n",
        "Umsatzsteuer", "# Umsatzsteuer\n\nUnternehmen stellen Umsatzsteuer in Rechnung und ziehen gezahlte Vorsteuer ab; die Differenz geht ans Finanzamt.\n");

    @Test
    void should_linkCloselyRelatedNotes_andKeepOtherTopicsWellBelowTheThreshold() {
        var dir = LocalEmbedder.modelDir(System.getenv());
        assumeTrue(Files.exists(dir.resolve(LocalEmbedder.MODEL_FILE)), "Embedding-Modell fehlt unter " + dir);
        var threshold = LinkingRun.Thresholds.from(Map.of()).link();
        try (var embedder = LocalEmbedder.load(dir)) {
            var biology = vectors(embedder, BIOLOGY);
            var accounting = vectors(embedder, ACCOUNTING);

            assertThat(similarity(biology.get("Photosynthese"), biology.get("Chlorophyll"))).isGreaterThanOrEqualTo(threshold);
            assertThat(similarity(biology.get("Photosynthese"), biology.get("Calvin-Zyklus"))).isGreaterThanOrEqualTo(threshold);
            for (var plant : biology.values()) {
                for (var money : accounting.values()) {
                    assertThat(similarity(plant, money)).isLessThan(threshold - 0.04);
                }
            }
        }
    }

    private static Map<String, float[]> vectors(Embedder embedder, Map<String, String> notes) {
        var titles = List.copyOf(notes.keySet());
        var texts = titles.stream().map(title -> NoteChunker.chunks(title, notes.get(title)).getFirst().text()).toList();
        var vectors = embedder.embed(texts, Embedder.Kind.PASSAGE);
        var result = new java.util.HashMap<String, float[]>();
        for (var i = 0; i < titles.size(); i++) {
            result.put(titles.get(i), vectors[i]);
        }
        return result;
    }

    private static double similarity(float[] a, float[] b) {
        var sum = 0.0;
        for (var i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
