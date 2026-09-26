package de.tstieh.stoneintelligence.worker.ingest;

import java.util.List;
import de.tstieh.stoneintelligence.stoneai.extract.LlmClient;
import de.tstieh.stoneintelligence.worker.link.LinkJudge;

/**
 * Betriebsprobe fuer Stufe 3 (ADR 0012): fragt das echte Sprachmodell eines Diensts nach einem
 * erfundenen Beispiel (keine Vault-Inhalte) - ein passender und ein unpassender Kandidat. Aufruf im
 * Worker-Container mit worker.env: {@code java -Dloader.main=de.tstieh.stoneintelligence.worker.ingest.LinkJudgeCheck
 * -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher <dienst>}
 */
public final class LinkJudgeCheck {

    static final String SOURCE_TITLE = "Chlorophyll";
    static final String SOURCE = "Chlorophyll ist der grüne Farbstoff der Pflanzen. Es absorbiert rotes und blaues Licht "
        + "und treibt so die Lichtreaktion der Photosynthese an.";
    static final List<LinkJudge.Candidate> CANDIDATES = List.of(
        new LinkJudge.Candidate("passend", "Photosynthese",
            "Pflanzen wandeln in den Chloroplasten Lichtenergie in chemische Energie um; aus CO2 und Wasser entstehen Glukose und Sauerstoff."),
        new LinkJudge.Candidate("unpassend", "Umsatzsteuer",
            "Unternehmen stellen Umsatzsteuer in Rechnung und ziehen gezahlte Vorsteuer ab; die Differenz geht ans Finanzamt."));

    private LinkJudgeCheck() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Aufruf: LinkJudgeCheck <dienst>");
            System.exit(2);
        }
        var model = ServiceModels.from(System.getenv()).forService(args[0]);
        var ok = check(new GatewayLlmFactory().forService(model));
        System.exit(ok ? 0 : 1);
    }

    /** Gibt die Urteile aus und sagt, ob das Modell den passenden verlinkt und den unpassenden nicht. */
    static boolean check(LlmClient llm) {
        var verdicts = new LinkJudge(llm).judge(SOURCE_TITLE, SOURCE, CANDIDATES);
        verdicts.forEach(verdict -> System.out.printf("%s: sinnvoll=%s, Beziehung=%s, Anker=%s%n",
            verdict.targetNoteId(), verdict.useful(), verdict.relation(), verdict.anchor()));
        var linked = verdicts.stream().anyMatch(v -> v.targetNoteId().equals("passend") && v.useful());
        var refused = verdicts.stream().anyMatch(v -> v.targetNoteId().equals("unpassend") && !v.useful());
        System.out.println(linked && refused ? "OK" : "FEHLER: das Modell urteilt nicht wie erwartet");
        return linked && refused;
    }
}
