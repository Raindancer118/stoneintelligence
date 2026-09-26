package de.tstieh.stoneintelligence.domain.notelevel;

/**
 * Geworfen, wenn für einen {@link NoteLevel} noch kein {@link NotePolicyProfile} registriert ist.
 * Level 2..99 sind laut Plan.md Abschnitt 8 bewusst noch offen (Phase 8, Note-Level-Feinschliff) -
 * der Resolver darf hier niemals eine Policy erraten.
 */
public final class UnresolvedNoteLevelException extends RuntimeException {

    public UnresolvedNoteLevelException(NoteLevel level) {
        super("no NotePolicyProfile registered for NoteLevel " + level.value()
            + " - not yet defined (see Plan.md Abschnitt 8, Phase 8)");
    }
}
