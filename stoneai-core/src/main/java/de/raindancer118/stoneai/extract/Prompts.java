package de.raindancer118.stoneai.extract;

import de.raindancer118.stoneai.chunk.Chunk;

/** The prompts the pipeline uses. Kept in one place so they can be reviewed as a whole. */
final class Prompts {

    private Prompts() {
    }

    static String extractionSystem(String language) {
        return """
                Du zerlegst Fachtexte in atomare Wissens-Notizen nach dem Zettelkasten-Prinzip.

                Regeln:
                - Ein Konzept = eine Notiz. Trenne Themen, statt sie zu bündeln.
                - Extrahiere nur, was im Text steht. Ergänze kein Wissen von außen und rate nicht.
                - Nimm nur auf, was jemand später eigenständig nachschlagen würde: Begriffe,
                  Definitionen, Verfahren, Zusammenhänge. KEINE Verwaltungsangaben eines
                  Dokuments (Dateistand, Versionsnummer, Gültigkeitsbereich, Ansprechpartner,
                  Teilnehmerzahl, Sprache, Semesterlage) und keine reinen Formalia.
                - Lieber fünf tragfähige Notizen als dreißig, die nur Zeilen des Dokuments
                  wiederholen.
                - Der Titel ist der Begriff selbst, nicht ein Satz und keine Frage.
                - "body" ist Markdown ohne Überschrift und ohne Frontmatter.
                - "entities" enthält strukturierte Fakten nach Kategorie (person, ort, datum,
                  betrag, begriff, quelle). Nur wörtlich Belegtes, keine Umschreibungen.
                - "tags" sind thematische Schlagworte AUS DEM TEXT. Übernimm niemals die
                  Platzhalter aus dem Beispielschema unten.
                - "confidence" schätzt, wie klar der Text das Konzept hergibt (0.0 bis 1.0).
                - Findet sich nichts Eigenständiges, gib eine leere Liste zurück.
                - Antworte in der Sprache mit dem Code: %s
                - Antworte ausschließlich mit JSON in genau dieser Form, ohne Fließtext davor
                  oder danach:

                %s""".formatted(language, ConceptJson.CONTRACT);
    }

    static String extractionUser(Chunk chunk) {
        return """
                Quelle: %s

                Text:
                ---
                %s
                ---""".formatted(chunk.provenance().label(), chunk.text());
    }

    static String repairUser(String previousAnswer, String problem) {
        return """
                Deine letzte Antwort war unbrauchbar: %s

                Das war sie:
                ---
                %s
                ---

                Gib denselben Inhalt erneut aus, diesmal als reines, gültiges JSON im vereinbarten
                Format. Kein Fließtext, keine Code-Fence, keine Kommentare.""".formatted(problem, previousAnswer);
    }

    static String ocr(int pageNumber, String language) {
        return """
                Gib den gesamten Text dieser Seite (Seite %d) wörtlich wieder.
                Behalte Absätze, Listen und Formeln bei; Formeln als LaTeX.
                Beschreibe nichts, kommentiere nichts, ergänze nichts.
                Ist die Seite leer oder unlesbar, antworte mit einer leeren Zeile.
                Sprache des Dokuments: %s""".formatted(pageNumber, language);
    }
}
