package de.tstieh.stoneintelligence.platform.ai;

/**
 * Der Job laeuft nicht (mehr) - abgebrochen, beendet oder von einem anderen Versuch uebernommen.
 * Eigene Ausnahme (HTTP 410), damit der Worker das von einer fachlichen Ablehnung unterscheiden
 * und seine Verarbeitung sofort beenden kann.
 */
public class AiJobGoneException extends AiWriteRefusedException {

    public AiJobGoneException() {
        super("Dieser Job läuft nicht (mehr)");
    }
}
