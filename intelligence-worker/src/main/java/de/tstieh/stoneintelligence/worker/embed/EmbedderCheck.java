package de.tstieh.stoneintelligence.worker.embed;

import java.util.List;

/**
 * Betriebsprobe: laedt das lokale Modell im fertigen Image und vergleicht zwei verwandte und einen
 * fremden Satz. So laesst sich nach einem Rollout pruefen, dass Modell und native Bibliotheken im
 * Container laufen, ohne einen Verlinkungslauf anzustossen:
 * {@code java -Dloader.main=de.tstieh.stoneintelligence.worker.embed.EmbedderCheck -cp app.jar
 * org.springframework.boot.loader.launch.PropertiesLauncher}
 */
public final class EmbedderCheck {

    private EmbedderCheck() {
    }

    public static void main(String[] args) {
        var dir = LocalEmbedder.modelDir(System.getenv());
        try (var embedder = LocalEmbedder.load(dir)) {
            var v = embedder.embed(List.of(
                "Pflanzen nutzen Sonnenlicht, um Zucker herzustellen.",
                "Photosynthese: Pflanzen erzeugen mit Licht Glukose.",
                "Die Bilanz eines Unternehmens zeigt Aktiva und Passiva."), Embedder.Kind.PASSAGE);
            var related = dot(v[0], v[1]);
            var unrelated = dot(v[0], v[2]);
            System.out.printf("Modell %s aus %s: verwandt %.3f, fremd %.3f - %s%n", embedder.model(), dir, related, unrelated,
                related > unrelated ? "OK" : "FEHLER");
            if (related <= unrelated) {
                System.exit(1);
            }
        }
    }

    private static double dot(float[] a, float[] b) {
        var sum = 0.0;
        for (var i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
