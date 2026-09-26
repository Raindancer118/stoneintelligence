package de.tstieh.stoneintelligence.worker.ingest;

import java.util.List;
import java.util.Locale;
import de.tstieh.stoneintelligence.worker.embed.Embedder;

/**
 * Fuer Tests: ein Vektor je Text aus seinen Woertern (jedes Wort eine Richtung) - Texte mit gleichen
 * Woertern liegen nah beieinander, ganz ohne Modell. Das echte Modell testet LocalEmbedderTest.
 */
final class WordEmbedder implements Embedder {

    static final int SIZE = 384;
    int calls;

    @Override
    public float[][] embed(List<String> texts, Kind kind) {
        calls += texts.size();
        var vectors = new float[texts.size()][];
        for (var i = 0; i < texts.size(); i++) {
            var vector = new float[SIZE];
            for (var word : texts.get(i).toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
                if (word.length() > 3) {
                    vector[Math.floorMod(word.hashCode(), SIZE)] += 1;
                }
            }
            var length = 0.0;
            for (var value : vector) {
                length += value * value;
            }
            for (var d = 0; d < SIZE && length > 0; d++) {
                vector[d] /= (float) Math.sqrt(length);
            }
            vectors[i] = vector;
        }
        return vectors;
    }

    @Override
    public String model() {
        return "woerter@1";
    }

    @Override
    public int dimensions() {
        return SIZE;
    }
}
