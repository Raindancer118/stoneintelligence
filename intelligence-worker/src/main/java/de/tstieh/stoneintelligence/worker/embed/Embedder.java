package de.tstieh.stoneintelligence.worker.embed;

import java.util.List;

/** Texte als Vektoren (ADR 0012, Stufe 2) - normiert, sodass das Skalarprodukt die Kosinus-Aehnlichkeit ist. */
public interface Embedder {

    /** e5-Modelle unterscheiden, ob ein Text gespeichert (PASSAGE) oder gesucht (QUERY) wird. */
    enum Kind { PASSAGE, QUERY }

    float[][] embed(List<String> texts, Kind kind);

    /** Name samt Fassung - aendert er sich, muessen alle Vektoren neu berechnet werden. */
    String model();

    int dimensions();
}
