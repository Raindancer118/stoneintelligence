package de.tstieh.stoneintelligence.platform.ai;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;

/**
 * Ein angebundener KI-Dienst (Anbieter + Modell), wie ihn der Betrieb konfiguriert. Je Dienst ist
 * festgelegt, welche Note-Levels er verarbeiten darf - z. B. ein externer Anbieter nur Level 1,
 * ein selbst gehostetes Modell auch interne Levels (ADR 0008).
 *
 * <p>Level 100 (keine Synchronisation) und 101 (E2EE) sind nie erlaubt: deren Inhalt kennt der
 * Server gar nicht bzw. nur als Ciphertext - das laesst sich nicht wegkonfigurieren.
 */
public record AiService(String id, String name, Set<Integer> allowedLevels) {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    public AiService {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("AI service id must match " + ID + ", was " + id);
        }
        if (name == null || name.isBlank() || name.length() > 60 || !name.equals(name.strip())) {
            throw new IllegalArgumentException("AI service name must be 1..60 visible characters");
        }
        allowedLevels = Set.copyOf(Objects.requireNonNull(allowedLevels, "allowedLevels must not be null"));
        for (var level : allowedLevels) {
            if (level < NoteLevel.MIN || level >= NoteLevel.NO_SYNC) {
                throw new IllegalArgumentException("AI service " + id + " may only process levels "
                    + NoteLevel.MIN + ".." + (NoteLevel.NO_SYNC - 1) + ", not " + level);
            }
        }
    }

    /** Die Identitaet, unter der der Dienst im Vault schreibt (Audit, createdBy). */
    public String agent() {
        return "ki:" + name;
    }

    public boolean mayProcess(NoteLevel level) {
        return allowedLevels.contains(level.value());
    }
}
