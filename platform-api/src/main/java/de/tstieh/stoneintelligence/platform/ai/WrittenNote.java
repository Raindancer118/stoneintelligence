package de.tstieh.stoneintelligence.platform.ai;

import de.tstieh.stoneintelligence.domain.id.NoteId;

public record WrittenNote(NoteId noteId, String path) {
}
