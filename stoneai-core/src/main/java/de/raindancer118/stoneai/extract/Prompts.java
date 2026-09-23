package de.raindancer118.stoneai.extract;

import de.raindancer118.stoneai.chunk.Chunk;

import java.util.List;
import java.util.stream.Collectors;

/** The prompts the pipeline uses. Kept in one place so they can be reviewed as a whole. */
final class Prompts {

    private Prompts() {
    }

    /** The planner's prompt - the word "Themenplan" marks it, tests route on it. */
    static String planSystem(String language) {
        return """
                Du erstellst den Themenplan für ein Dokument, das in eine persönliche
                Wissensdatenbank (Obsidian) aufgenommen wird: Zu welchen Themen soll es je eine
                Notiz geben?

                Regeln:
                - Ein Thema ist etwas, das jemand später gezielt aufschlägt: das Ereignis oder der
                  Vorgang, um den es geht (ein Unfall, ein Vertrag, ein Antrag, ein Projekt),
                  wichtige Beteiligte mit eigener Rolle (Personen, Organisationen), bei Geschichten
                  die Figuren, Orte und Ereignisse der Handlung, bei Lehrtexten die zentralen
                  Begriffe, Verfahren und Zusammenhänge.
                - Das Hauptthema des Dokuments steht immer an erster Stelle.
                - KEIN eigenes Thema für Einzelheiten: Nummern (Schaden-, Versicherungs-,
                  Aktenzeichen), Adressen, Telefonnummern, Daten, Beträge, Abbildungen, einzelne
                  Handlungsschritte, Gegenstände, Körperteile, Symptome, Allerweltsbegriffe. Sie
                  stehen später in der Notiz des Themas, zu dem sie gehören.
                - Ein Fachbegriff bekommt nur dann ein eigenes Thema, wenn das Dokument ihn wirklich
                  erklärt und er über dieses Dokument hinaus nützlich ist.
                - Wenige, tragfähige Themen: ein kurzes Dokument (1 bis 3 Seiten) hat meist 1 bis 4,
                  ein langes selten mehr als eines je zwei bis drei Seiten.
                - Gehört ein Thema zu einer schon vorhandenen Notiz (derselbe Vorgang, dieselbe
                  Person, dieselbe Figur), übernimm deren Titel exakt - das Dokument ergänzt dann
                  diese Notiz.
                - Titel sind eindeutig und auch ohne das Dokument verständlich:
                  "Verkehrsunfall am 05.06.2026" statt "Unfall", Personen mit vollem Namen. Kein
                  Satz, keine Frage, keine Dateiendung.
                - "scope" sagt in einem Satz, was in die Notiz gehört.
                - "kind" ist eines von: ereignis, vorgang, person, organisation, ort, figur,
                  begriff, thema.
                - Sprache der Titel: %s
                - Antworte ausschließlich mit JSON in genau dieser Form:

                {"topics": [{"title": "…", "kind": "…", "scope": "…", "aliases": ["…"]}]}""".formatted(language);
    }

    static String planUser(String documentTitle, List<String> existingTitles, String text) {
        String existing = existingTitles.isEmpty() ? "(keine)"
                : existingTitles.stream().map(title -> "- " + title).collect(Collectors.joining("\n"));
        return """
                Dokument: %s

                Schon vorhandene Notizen:
                %s

                Text:
                ---
                %s
                ---""".formatted(documentTitle, existing, text);
    }

    static String extractionSystem(String language, boolean mayAddTopics) {
        String newTopics = mayAddTopics
                ? """
                - Enthält der Abschnitt ein wesentliches Thema, das in der Liste fehlt und über
                  mehrere Absätze trägt, darfst du dafür eine weitere Notiz anlegen. Einzelheiten
                  sind nie ein solches Thema."""
                : """
                - Lege KEINE Notizen zu Themen an, die nicht in der Liste stehen.""";
        return """
                Du schreibst Notizen für eine persönliche Wissensdatenbank (Obsidian). Welche
                Notizen es gibt, ist vorgegeben: die Liste der Themen in der Anfrage.

                Regeln:
                - Schreibe zu jedem Thema der Liste, zu dem dieser Abschnitt etwas enthält, genau
                  EINE Notiz und übernimm den Titel exakt. Themen, zu denen der Abschnitt nichts
                  enthält, lässt du weg.%s
                - Alle Einzelheiten gehören in die Notiz ihres Themas: Nummern, Aktenzeichen,
                  Adressen, Telefonnummern, Daten, Beträge, Nebenpersonen, Gegenstände,
                  Abbildungen. Nichts davon wird eine eigene Notiz.
                - Extrahiere nur, was im Text steht. Ergänze kein Wissen von außen und rate nicht.
                - "body" ist gut gegliedertes Markdown: kurze Absätze, Stichpunkte für Fakten
                  ("- **Schadennummer:** …"), bei Bedarf Zwischenüberschriften ab ###. Keine
                  Überschrift der Ebene 1 oder 2, kein Frontmatter.
                - Auf ein anderes Thema der Liste darfst du im body mit [[Titel]] verweisen,
                  auf nichts anderes.
                - "related" nennt die Titel der anderen Themen der Liste, die mit dieser Notiz
                  zusammenhängen.
                - "entities" enthält strukturierte Fakten nach Kategorie (person, ort, datum,
                  betrag, begriff, quelle). Nur wörtlich Belegtes, keine Umschreibungen.
                - "tags" sind thematische Schlagworte AUS DEM TEXT. Übernimm niemals die
                  Platzhalter aus dem Beispielschema unten.
                - "confidence" schätzt, wie klar der Text das Thema hergibt (0.0 bis 1.0).
                - Findet sich zu keinem Thema etwas, gib eine leere Liste zurück.
                - Antworte in der Sprache mit dem Code: %s
                - Antworte ausschließlich mit JSON in genau dieser Form, ohne Fließtext davor
                  oder danach:

                %s""".formatted(newTopics, language, ConceptJson.CONTRACT);
    }

    /** Without a plan (the CLI's direct use): the user prompt says so, the model picks sparingly. */
    static String extractionSystem(String language) {
        return extractionSystem(language, true);
    }

    static String extractionUser(Chunk chunk, TopicPlan plan) {
        String topics = plan.isEmpty()
                ? "(keine Vorgabe - wähle wenige, tragfähige Themen: Vorgänge, Beteiligte, zentrale Begriffe)"
                : plan.topics().stream()
                        .map(topic -> "- " + topic.title() + " (" + topic.kind() + ")"
                                + (topic.scope().isBlank() ? "" : ": " + topic.scope()))
                        .collect(Collectors.joining("\n"));
        return """
                Themen:
                %s

                Quelle: %s

                Text:
                ---
                %s
                ---""".formatted(topics, chunk.provenance().label(), chunk.text());
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
