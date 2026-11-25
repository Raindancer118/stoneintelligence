package de.tstieh.stoneintelligence.worker.platform;

import java.util.List;

/** @param local der KI-Dienst laeuft auf eigener Infrastruktur; {@code omittedForConsent}: Notizen, die ein externer Dienst nicht bekam */
public record AskPassages(List<AskPassage> passages, boolean local, int omittedForConsent) {

    public AskPassages {
        passages = passages == null ? List.of() : List.copyOf(passages);
    }
}
