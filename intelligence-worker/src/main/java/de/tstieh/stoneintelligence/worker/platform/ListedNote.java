package de.tstieh.stoneintelligence.worker.platform;

/** Eine bestehende Notiz, die die KI kennen darf. */
public record ListedNote(String noteId, String path, int level, String createdBy, String kind) {

    /** Ein aelterer Server liefert keine Art - dann ist es eine Notiz. */
    public boolean isFile() {
        return "FILE".equals(kind);
    }

    public boolean writtenByAi() {
        return createdBy != null && createdBy.startsWith("ki:");
    }
}
