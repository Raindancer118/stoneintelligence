package de.tstieh.stoneintelligence.worker.embed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Gegen das echte Modell (ADR 0012) - es liegt im Worker-Image, lokal unter
 * {@code ~/.cache/stoneintelligence/multilingual-e5-small} bzw. {@code STONEAI_EMBEDDING_MODEL_DIR};
 * CI laedt es vorher herunter. Ohne Modell wird der Test uebersprungen, nicht gruen gemeldet.
 */
class LocalEmbedderTest {

    private static LocalEmbedder embedder;

    @BeforeAll
    static void load() {
        var dir = LocalEmbedder.modelDir(System.getenv());
        assumeTrue(Files.exists(dir.resolve(LocalEmbedder.MODEL_FILE)), "Embedding-Modell fehlt unter " + dir);
        embedder = LocalEmbedder.load(dir);
    }

    @AfterAll
    static void close() {
        if (embedder != null) {
            embedder.close();
        }
    }

    private static double cosine(float[] a, float[] b) {
        var sum = 0.0;
        for (var i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    @Test
    void should_embedIntoNormalisedVectorsOfTheModelsSize() {
        var vectors = embedder.embed(List.of("Photosynthese wandelt Licht in chemische Energie um.", ""), Embedder.Kind.PASSAGE);

        assertThat(vectors.length).isEqualTo(2);
        assertThat(vectors[0]).hasSize(LocalEmbedder.DIMENSIONS);
        assertThat(cosine(vectors[0], vectors[0])).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    // e5 drueckt alle Werte in einen engen Bereich (gemessen: Umschreibung 0,91, Fremdes 0,83) -
    // entscheidend ist der Abstand, nicht die Hoehe. Sprachuebergreifend ist er kleiner, aber da.
    @Test
    void should_placeRelatedMeaningCloser_evenInOtherWordsOrLanguages() {
        var vectors = embedder.embed(List.of(
            "Pflanzen nutzen Sonnenlicht, um Zucker herzustellen.",
            "Photosynthese: Pflanzen erzeugen mit Licht Glukose.",
            "Photosynthesis converts light energy into chemical energy in plants.",
            "Die Bilanz eines Unternehmens zeigt Aktiva und Passiva."), Embedder.Kind.PASSAGE);

        var unrelated = cosine(vectors[0], vectors[3]);
        assertThat(cosine(vectors[0], vectors[1])).isGreaterThan(unrelated + 0.05);
        assertThat(cosine(vectors[0], vectors[2])).isGreaterThan(unrelated);
    }

    @Test
    void should_handleTextsLongerThanTheModelWindow() {
        var longText = "Licht ".repeat(3_000);

        assertThat(embedder.embed(List.of(longText), Embedder.Kind.PASSAGE)[0]).hasSize(LocalEmbedder.DIMENSIONS);
        assertThat(Path.of(LocalEmbedder.MODEL_ID)).isNotNull();
    }
}
