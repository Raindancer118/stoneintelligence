package de.tstieh.stoneintelligence.worker.platform;

import java.util.Set;

/** Verlinkung des Vaults aus Sicht des Workers: Modus und wer der KI-Pruefung eigener Notizen zugestimmt hat. */
public record LinkingSettings(String mode, Set<String> aiConsents) {

    public LinkingSettings {
        aiConsents = aiConsents == null ? Set.of() : Set.copyOf(aiConsents);
    }

    /** Darf ein Auszug dieser Notiz an den KI-Anbieter? Notizen der KI selbst immer, andere nur mit Einwilligung. */
    public boolean mayGoToAi(ListedNote note) {
        return note.writtenByAi() || aiConsents.contains(note.createdBy());
    }
}
