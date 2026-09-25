package de.raindancer118.stoneintelligence.worker.platform;

/**
 * Der Job laeuft nicht mehr (HTTP 410) - meist, weil ihn jemand abgebrochen hat. Der Worker hoert
 * dann sofort auf, statt weiterzurechnen oder einen Fehler zu melden.
 */
public class JobGoneException extends PlatformRefusedException {

    public JobGoneException(String message) {
        super(message);
    }
}
