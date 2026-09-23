package de.raindancer118.stoneintelligence.worker.platform;

/** Eine bestehende Notiz, die die KI kennen darf. */
public record ListedNote(String noteId, String path, int level, String createdBy) {

    public boolean writtenByAi() {
        return createdBy != null && createdBy.startsWith("ki:");
    }
}
